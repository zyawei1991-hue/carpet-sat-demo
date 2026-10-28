package com.carpet.satdemo.app

import com.carpet.satdemo.core.diagnostic.PassthroughLog
import com.carpet.satdemo.core.modem.Fix
import com.carpet.satdemo.core.modem.ModemBytePipe
import com.carpet.satdemo.core.modem.ModemGate
import com.carpet.satdemo.core.modem.MonoClock
import com.carpet.satdemo.core.modem.RawLine
import com.carpet.satdemo.core.modem.SatFailReason
import com.carpet.satdemo.core.modem.SatLock
import com.carpet.satdemo.core.modem.SatPath
import com.carpet.satdemo.core.modem.SatResult
import com.carpet.satdemo.core.modem.SatSend
import com.carpet.satdemo.core.modem.SatelliteModem
import com.carpet.satdemo.core.modem.SentenceModem
import com.carpet.satdemo.core.protocol.AccessService
import com.carpet.satdemo.core.protocol.CommunicationBits
import com.carpet.satdemo.core.protocol.GeodeticPosition
import com.carpet.satdemo.core.protocol.LengthUnit
import com.carpet.satdemo.core.protocol.ModuleCaps
import com.carpet.satdemo.core.protocol.ModuleModel
import com.carpet.satdemo.core.protocol.MsgLengthLevel
import com.carpet.satdemo.core.protocol.SfiFailReason
import com.carpet.satdemo.core.protocol.SfiState
import com.carpet.satdemo.core.protocol.hexToBytes
import com.geesatcom.protocollibrary.Parse.entity.EntityDII
import com.geesatcom.protocollibrary.Parse.entity.EntityICI
import com.geesatcom.protocollibrary.Parse.entity.EntityMCI
import com.geesatcom.protocollibrary.Parse.entity.EntitySFI
import com.geesatcom.protocollibrary.bean.SubjectTaskBean
import com.geesatcom.protocollibrary.controller.DispatchManager
import com.geesatcom.protocollibrary.controller.ModuleDataManager
import com.geesatcom.protocollibrary.interfaces.ModuleOperation
import com.geesatcom.protocollibrary.utils.CustomError
import com.geesatcom.protocollibrary.utils.TaskState
import com.geesatcom.protocollibrary.wheel.SubjectTask
import io.reactivex.rxjava3.disposables.Disposable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 真机首选：把 [SatelliteModem] 直接架在厂商 SDK 上。
 *
 * **SDK 不做 IO**（附录 E.1）。它只认两件事：我们把它要发的字节送出去（[ModuleOperation.send]）、
 * 把串口/BLE 读到的字节喂回来（[DispatchManager.receive]）。所以这个类的形状是
 * "一根 [ModemBytePipe] + 一个 [DispatchManager]"，而不是"一个会自己连模组的黑盒"。
 *
 * 和 [SentenceModem] 不是重复建设：那一份活在 JVM 侧，是**独立于厂商解析器的证人** ——
 * 到货后对不上账时，得有一条不依赖厂商代码的路径能说出"模组实际发了什么"。
 * 两份实现共用同一套语义（三态、写锁、预算、工程模式窗），所以
 * [com.carpet.satdemo.core.engine.DemoEngine] 那边一个字都不用改：换硬件只换这里。
 *
 * ⚠️ 这个类**没法在 JVM 单测里跑**：它 import 的是 aar 里的类，`android.util.Pair` 那一层也要求 Android。
 * 所以它只有 `:app:assembleDebug` 这道编译门，行为验证得到货后连着真模组做（§8 近期四件事 #1）。
 */
class VendorSdkModem(
    /** 串口或 BLE 那半截。方向永远以 App 侧为准 */
    private val pipe: ModemBytePipe,
    /** 型号只能注入：ICI 里没有型号字段，GT1005 对应 GM1001 还是 GM1003 还没确认（§9 #12） */
    private val model: ModuleModel = ModuleModel.WORST_KNOWN,
    private val clock: MonoClock = MonoClock { System.currentTimeMillis() },
    /** §9 #13 有了答复就在这里换成 [LengthUnit.BYTES]，结构不动 */
    private val lengthUnit: LengthUnit = LengthUnit.WORST_KNOWN,
    /** 本地语句（读 DII/ICI、入队）的应答超时。这些是模组自己回，不涉星，所以是秒级 */
    private val cmdTimeoutMs: Long = 3_000,
    /** MCQ 等的是星上确认，量级是分钟；90 秒是演示能等的上限，不是协议常数 */
    private val mcqTimeoutMs: Long = 90_000,
    override val gate: ModemGate = ModemGate(),
) : SatelliteModem {

    private var dispatch: DispatchManager? = null
    private var dataManager: ModuleDataManager? = null
    private val disposables = CopyOnWriteArrayList<Disposable>()

    @Volatile
    private var passthrough: PassthroughLog? = null

    private val downlinkHandlers = CopyOnWriteArrayList<(String) -> Unit>()
    private val lockHandlers = CopyOnWriteArrayList<(SatLock?) -> Unit>()

    override var caps: ModuleCaps? = null
        private set

    var sessionReady: Boolean = false
        private set

    /** SDK 的 DII 里能读到的序列号，工程模式窗抬头用 */
    var moduleSerial: String? = null
        private set

    var lastTpiAtMs: Long? = null
        private set

    /** 已交给模组、还没等到发射结果的报文：模组 ID → 原始 hex（和 [SentenceModem] 同一个理由） */
    private val inFlight = ConcurrentHashMap<Int, String>()
    private val droppedHex = ConcurrentLinkedQueue<String>()

    @Volatile
    private var lastLock: SatLock? = null

    /**
     * DII/ICI 是"问一次答一次"，但 SDK 从 RxJava 子线程把应答推给监听器，
     * 所以用无界通道接：先订阅、再 sendGMO，谁先到都不会漏。
     */
    private val diiChannel = Channel<EntityDII>(Channel.UNLIMITED)
    private val iciChannel = Channel<EntityICI>(Channel.UNLIMITED)

    override val available: Boolean get() = pipe.isOpen

    /** SDK 会自己组 MCQ 那条语句（线格式只有它有，规范正文里没写 —— §9 #13） */
    override val supportsImmediateSend: Boolean get() = true

    /**
     * 接线。顺序按厂商文档 §5：**先 new DispatchManager（把我们这根管道交给它）→
     * 注册字节入口 → start()**。start 之前 `getDataManager()` 是空的。
     */
    fun start() {
        if (dispatch != null) return
        val d = DispatchManager(PipeOperation())
        // 模组方向：管道读到的字节原样喂给 SDK，解析和分发是它的事
        pipe.onBytes { bytes -> d.receive(bytes) }
        dispatch = d
        d.start()
        val data = d.getDataManager() ?: error("start() 之后 getDataManager() 仍为 null：SDK 没起来")
        dataManager = data
        subscribe(data)
        note("SDK 已接管：型号=$model 长度口径=${lengthUnit.name}")
    }

    /**
     * 订阅全部走 [ModuleDataManager]。
     *
     * ⚠️ **不要自己 new `ModuleDataManager`**：它的构造方法是**包私有**
     * （`ModuleDataManager(ModuleOperation, android.os.Looper)`，实测 javap），厂商只让从
     * [DispatchManager.getDataManager] 拿。自己 new 出来的话 Looper 填错就是"监听器一次都不回调"，
     * 那是现场最难查的一种死法。
     *
     * ⚠️ 监听器参数是 **`java.util.function.Consumer`**（不是 rxjava 的），返回值却是
     * `io.reactivex.rxjava3` 的 `Disposable`：签名混了两套生态。所以 `close()` 必须 dispose，
     * 否则回调会继续往已撤的通道里发。
     */
    private fun subscribe(data: ModuleDataManager) {
        disposables += data.setICIListening { e ->
            if (e !== EntityICI.empty) iciChannel.trySend(e)
        }
        disposables += data.setDIIListening { e ->
            if (e !== EntityDII.empty) {
                moduleSerial = e.deviceSerialNumber
                diiChannel.trySend(e)
            }
        }
        disposables += data.setMCIMessageListening { e: EntityMCI ->
            // 〔规 §3.1.11 注2〕数据字段是十六进制的 ASCII 码，原样交给上层解
            e.hexStr?.let { hex -> downlinkHandlers.forEach { it(hex) } }
        }
        disposables += data.setSFIMessageListening { e: EntitySFI -> onSfi(e) }
        disposables += data.setSelectedSatelliteListening { pair ->
            val name = pair?.first
            if (name.isNullOrEmpty()) publishLock(null) else publishLock(SatLock(name, (pair.second ?: 0f).toDouble()))
        }
        disposables += data.setEarthStationListening { ready ->
            // 地球站可见与否决定"实时回传"这条权限能不能真用（〔规 §3.1.4 注5〕），只记不判
            note("地球站 ${if (ready == true) "可见" else "不可见"}")
        }
    }

    /**
     * 上电会话（E.10 #2）。**SDK 不替我们做这件事** —— 它只解析，会话还是规范那套顺序：
     * DII → ICI →（无效卡就停）→ TPI → 打开 PDI 连续输出。
     *
     * 和 [SentenceModem] 的区别只有一处：那里手写 `$CCGMO,…`，这里调 [ModuleDataManager.sendGMO]。
     */
    suspend fun bringUp(): SentenceModem.BringUpOutcome {
        if (!pipe.isOpen) return SentenceModem.BringUpOutcome.PipeClosed
        if (gate.isUpgrading) return SentenceModem.BringUpOutcome.Upgrading
        val data = dataManager ?: return SentenceModem.BringUpOutcome.PipeClosed
        sessionReady = false

        val dii = readOnce("DII", cmdTimeoutMs) { diiChannel }
            ?: return SentenceModem.BringUpOutcome.NoReply("DII")
        moduleSerial = dii.deviceSerialNumber

        val entity = readOnce("ICI", cmdTimeoutMs) { iciChannel }
            ?: return SentenceModem.BringUpOutcome.NoReply("ICI")
        val c = entity.toCaps() ?: return SentenceModem.BringUpOutcome.NoReply("ICI")
        if (c.isInvalidCard) return SentenceModem.BringUpOutcome.InvalidCard(c.cardId)
        caps = c

        // TPI 是必配项：位置没推进去，模组存了报文也发不出去、下行也收不到（〔规 §3.1.7 注〕）
        val first = pendingTpi ?: return SentenceModem.BringUpOutcome.NeedsLocation
        if (!updateLocation(first)) return SentenceModem.BringUpOutcome.NoReply("TPI")

        // 打开 PDI 连续输出（模式 2 = 开指定语句，频度 1 秒 —— 和语句层那一份同一个数）
        data.sendGMO("PDI", GMO_OPEN, 1)
        sessionReady = true
        return SentenceModem.BringUpOutcome.Ready(c, null)
    }

    /** 单次读取一条语句：模式 2 + 频度 0（〔规 §3.1.6〕），DII/ICI/CII 只能这么读 */
    private suspend fun <T> readOnce(target: String, timeoutMs: Long, slot: () -> Channel<T>): T? {
        val data = dataManager ?: return null
        val ch = slot()
        val wrote = gate.write(target) {
            data.sendGMO(target, GMO_OPEN, 0)
            true
        } ?: return null
        if (!wrote) return null
        return withTimeoutOrNull(timeoutMs) { ch.receiveCatching().getOrNull() }
    }

    // ---- SatelliteModem ----

    /**
     * ⚠️ SDK 的 `updateLocation(double, double)` **返回 void，`ModuleDataManager` 也没有 TPI 监听器**
     * —— jar 里确实有 `bean.EntityTPI`，但没有任何公开入口把它交出来。
     *
     * 所以这里的 true 只表示**"一个字节都没被写锁挡住、已经交给 SDK"**，不等于 core 契约里
     * 那句"模组认了这条"。[SentenceModem] 能等到 `$GSTPI` 回显，这一路等不到。
     * 已记进 §9 待问：要么给 TPI 回调，要么给一条能读回当前位置的语句。
     *
     * 参数顺序：SDK 是 (经度, 纬度)，语句是纬在前 —— 走 [GeodeticPosition] 就不可能传反。
     */
    override suspend fun updateLocation(position: GeodeticPosition): Boolean {
        pendingTpi = position
        val data = dataManager ?: return false
        if (caps == null) return false
        val wrote = gate.write("TPI") {
            data.updateLocation(position.longitude.degrees, position.latitude.degrees)
            true
        } ?: return false
        if (wrote) lastTpiAtMs = clock.nowMs()
        return wrote
    }

    private var pendingTpi: GeodeticPosition? = null

    override suspend fun enqueueSfq(hex: String): SatSend =
        sendQueued(SatPath.QUEUED, hex) { data, hex2 -> awaitTask(cmdTimeoutMs, "SFQ") { cb -> data.sendMsgSFQ(RECEIVER_PLATFORM, hex2, cb) } }

    override suspend fun sendMcq(hex: String): SatSend =
        sendQueued(SatPath.IMMEDIATE, hex) { data, hex2 -> awaitTask(mcqTimeoutMs, "MCQ") { cb -> data.sendMsgMCQ(RECEIVER_PLATFORM, hex2, cb) } }

    /**
     * 两条路的门和收场完全一样，只有超时和动词不同，所以共用这一份。
     * 分开写会漂移 —— 而这里的每一条门都对应一种"用户看到的不同动作"。
     */
    private suspend fun sendQueued(
        via: SatPath,
        hex: String,
        block: suspend (ModuleDataManager, String) -> TaskOutcome,
    ): SatSend {
        val data = precheck(hex) ?: return SatSend.failed(via, precheckReason())
        return when (val outcome = block(data, hex)) {
            is TaskOutcome.Failed -> SatSend(SatResult.FAIL, via, outcome.reason)
            TaskOutcome.TimedOut -> SatSend.failed(via, SatFailReason.NO_REPLY)
            // awaitTask 已经 cancel 过任务。这里只能报"没等到任何回音"，不能报"星上没认"：
            // 前者让人等下一次过境，后者让人换开阔地重发，是两种动作（§6.1 红线一）
            is TaskOutcome.Done -> done(via, hex, outcome.bundle)
        }
    }

    private fun done(via: SatPath, hex: String, bundle: Any?): SatSend = when {
        // SFQ 的 COMPLETE 给的是模组报文 ID（〔SDK文 §8.2〕）。存下它，超时丢弃时才捡得回原文
        via == SatPath.QUEUED -> {
            val id = bundle as? Int
            if (id != null && id > 0) inFlight[id] = hex
            SatSend(SatResult.NO_ACK, via, messageId = id?.takeIf { it > 0 })
        }
        // 〔SDK文 §8.3〕MCQ 的 COMPLETE + bundle==NO_ACK 是**合法终局**：发出去了、星上没认。
        // 它既不是 FAIL 也不是 COMPLETE —— 三态各说各的话，合并一次就骗用户一次
        bundle === CustomError.NO_ACK -> SatSend(SatResult.NO_ACK, via)
        else -> SatSend(SatResult.COMPLETE, via)
    }

    /** 三道门：能力档、会话、写锁，最后才是预算。任何一道没过都是"一个字节都没写出去" */
    private fun precheck(hex: String): ModuleDataManager? {
        val data = dataManager ?: return null
        val c = caps ?: return null
        if (!sessionReady || gate.isUpgrading) return null
        val payload = runCatching { hexToBytes(hex) }.getOrNull() ?: return null
        // 比的是 MessageBudget 折出来的字节数，不是 ICI 那个原始数值（见 [LengthUnit]）
        if (!c.budget(lengthUnit).fitsFrame(payload.size)) return null
        return data
    }

    /** 门是 why-not-send 的成因，红字必须说清是哪一种，不能都写成"发送失败" */
    private fun precheckReason(): SatFailReason = when {
        dataManager == null -> SatFailReason.NO_REPLY
        caps == null -> SatFailReason.NOT_PROVISIONED
        !sessionReady -> SatFailReason.NOT_PROVISIONED
        gate.isUpgrading -> SatFailReason.UPGRADING
        else -> SatFailReason.TOO_LONG
    }

    private sealed interface TaskOutcome {
        data class Done(val bundle: Any?) : TaskOutcome
        data class Failed(val reason: SatFailReason) : TaskOutcome
        data object TimedOut : TaskOutcome
    }

    /**
     * 把 [SubjectTask] 的回调流收成一次挂起结果。
     *
     * 厂商的回调会**推进度**（INIT/START），也可能被调多次，所以这里只认第一个终局状态；
     * 超时则 `cancel`，别让任务悬在 SDK 的 wheel 里挡住后面几条。
     * 回调线程是 RxJava 子线程，通道无界：宁可先堆着，也别在回调线程里挂起。
     */
    private suspend fun awaitTask(
        timeoutMs: Long,
        label: String,
        start: (Consumer<SubjectTaskBean>) -> SubjectTask<*>?,
    ): TaskOutcome {
        val ch = Channel<SubjectTaskBean>(Channel.UNLIMITED)
        val task = gate.write(label) { start { bean -> ch.trySend(bean) } }
            ?: return TaskOutcome.Failed(SatFailReason.UPGRADING)
        val deadline = clock.nowMs() + timeoutMs
        while (true) {
            val left = deadline - clock.nowMs()
            if (left <= 0) {
                runCatching { task.cancel(null) }
                return TaskOutcome.TimedOut
            }
            val bean = withTimeoutOrNull(left) { ch.receiveCatching().getOrNull() }
            if (bean == null) {
                runCatching { task.cancel(null) }
                return TaskOutcome.TimedOut
            }
            when (bean.state) {
                TaskState.INIT, TaskState.START -> Unit
                TaskState.COMPLETE -> return TaskOutcome.Done(bean.bundle)
                TaskState.FAIL -> return TaskOutcome.Failed(bean.bundle.toFailReason())
                null -> Unit
            }
        }
    }

    /**
     * FAIL 的 bundle 有两种：`CustomError`（SDK 自己的错）或 `Integer`（模组回的 SFI/FBI 原因码），
     * 〔SDK文 §8.2/§8.3〕。
     */
    private fun Any?.toFailReason(): SatFailReason = when (this) {
        CustomError.INITIALIZE_ERROR -> SatFailReason.UPGRADING
        CustomError.INPUT_ERROR -> SatFailReason.TOO_LONG
        CustomError.TIMEOUT_ERROR -> SatFailReason.NO_REPLY
        CustomError.NO_ACK -> SatFailReason.TRANSMIT_FAILED
        CustomError.REMOVE -> SatFailReason.DROPPED_BY_TIMEOUT
        is Int -> when (SfiFailReason.fromCode(this)) {
            SfiFailReason.TOO_LONG -> SatFailReason.TOO_LONG
            SfiFailReason.BUFFER_FULL -> SatFailReason.QUEUE_FULL
            SfiFailReason.TIMEOUT -> SatFailReason.DROPPED_BY_TIMEOUT
            null -> SatFailReason.TRANSMIT_FAILED
        }
        else -> SatFailReason.NO_REPLY
    }

    /** SFI 上报：发出去了就撤账，被超时丢弃就把 hex 交回 App 侧补发队列 */
    private fun onSfi(e: EntitySFI) {
        val id = e.id
        if (SfiFailReason.fromCode(e.failCode) == SfiFailReason.TIMEOUT) {
            val hex = inFlight.remove(id)
            if (hex != null) droppedHex += hex
            note("模组超时丢弃：报文ID=$id${if (hex == null) "（App 侧没有这条原文）" else " 已交回 App 队列"}")
        } else if (e.state == SfiState.SENT.code) {
            inFlight.remove(id)
        }
    }

    override fun drainDropped(): List<String> {
        val out = ArrayList(droppedHex)
        droppedHex.clear()
        return out
    }

    /**
     * ⚠️ SDK 这一路**读不到位置**：`EntityDII` 只有版本和序列号，没有经纬度字段
     * （而〔规 §3.1.2 注〕本来就说 DII 里的位置是外部 TPI 喂进去的回显）。
     * 所以位置源只能是手机自己（§3.2 定位融合）—— 这里返回 null 是**如实**，不是没实现。
     */
    override suspend fun readFix(): Fix? = null

    /** 星历不在基础通信 SDK 里，走 [StarManagerEphemeris]；[com.carpet.satdemo.core.runtime.DemoRuntime] 会优先用那一份 */
    override suspend fun secondsToNextPass(): Long? = null

    override fun onDownlink(handler: (String) -> Unit) {
        downlinkHandlers += handler
    }

    override fun onSatelliteLock(handler: (SatLock?) -> Unit) {
        lockHandlers += handler
        handler(lastLock)
    }

    private fun publishLock(lock: SatLock?) {
        if (lock == lastLock) return
        lastLock = lock
        lockHandlers.forEach { it(lock) }
    }

    /**
     * 两路喂同一个窗口（E.10 #10）：管道上的线路原文，和 SDK 自己吐的 `passthrough`。
     * 后者方向标 [RawLine.Direction.UNCLASSIFIED] —— 内容不是协议语句、方向也由模组决定，
     * 硬归到 TX/RX 会让工程师以为我们真发过那条。
     */
    override fun attachPassthrough(log: PassthroughLog) {
        passthrough = log
        pipe.onRawLine { log.record(it) }
        log.note("模组=厂商SDK 型号=$model 长度口径=${lengthUnit.name}")
    }

    /**
     * 收尾。`dispose()` 必须先做：SDK 的回调线程不跟我们一起停，
     * 撤晚一步就是"页面没了还在往通道里发"。
     *
     * ⚠️ **只调 `stop()`，没有 `destroy()`**：资料包里那份 `protocollibrary.jar` 有 `destroy()`，
     * 我们实际依赖的 `protocollibrary_1.0.0.aar` 里没有（实测两份不是同一个构建）。
     * 以 aar 为准 —— 这条差异已经记进附录 E.2，问厂商要一份对得上的。
     */
    override fun close() {
        disposables.forEach { runCatching { it.dispose() } }
        disposables.clear()
        dispatch?.let { runCatching { it.stop() } }
        dispatch = null
        dataManager = null
        pipe.close()
    }

    /** 模组队列深度，工程模式窗抬头用 */
    val moduleQueueDepth: Int get() = inFlight.size

    /**
     * 队列剩余（〔SDK文 §8.5〕）。负数按厂商口径是"查询失败"，这里原样区分 ——
     * 别让"不知道"显示成"空的"。
     */
    suspend fun queryQueueRemaining(): Int? {
        val c = caps ?: return null
        val data = dataManager ?: return null
        val ch = Channel<Int>(Channel.UNLIMITED)
        val task = gate.write("SFP") { data.queryMsgSFQ { n -> n?.let { ch.trySend(it) } } } ?: return null
        val n = withTimeoutOrNull(cmdTimeoutMs) { ch.receiveCatching().getOrNull() }
        if (n == null) runCatching { task.cancel(null) }
        return n?.takeIf { it >= 0 }?.coerceAtMost(c.model.queueCapacity)
    }

    /** 清空模组待发队列（〔SDK文 §7.1〕）。演示收尾、换卡时用 */
    fun clearModuleQueue() {
        dataManager?.clearSFQ()
        inFlight.clear()
        note("已 clearSFQ")
    }

    /** 工程模式窗的抬头数据。型号没确认这件事必须写在脸上 */
    fun windowHeaderCaps(): String? = caps?.let {
        "${it.model} · 等级${it.lengthLevel.level}(${it.maxMessageBytes}B) · ${it.service}" +
            (moduleSerial?.let { s -> " · SN $s" } ?: "")
    }

    private fun note(text: String) {
        passthrough?.note(text, clock.nowMs())
    }

    /**
     * ICI → 能力档。
     *
     * ⚠️ `EntityICI.getMaxMsgLength()` 这个名字读起来像**字节数**，实测（javap -c）它只是
     * `return this.msgLength`，而 `ParseICI` 是把语句字段**原样 parseInt 塞进去**的 ——
     * 也就是说 SDK 眼里那个数就是〔规 §3.1.4 字段 9〕的**等级 1~5**，中间没有任何换算表。
     * 这里仍然两头都认（1~5 当等级、90/200/230/430/1900 当字节数反推），因为
     * "模组按哪个数拒收"和"SDK 按哪个数解析"是两件事，后者已经清楚、前者还没答（§9 #13）。
     */
    private fun EntityICI.toCaps(): ModuleCaps? {
        val service = AccessService.fromCode(serviceType) ?: return null
        val level = lengthLevelOrNull(maxMsgLength) ?: return null
        return ModuleCaps(
            cardId = id ?: ModuleCaps.INVALID_CARD_ID,
            model = model,
            lengthLevel = level,
            service = service,
            communication = CommunicationBits(communicationType),
            satelliteAckAvailable = satelliteResponseMode == 1,
            networking = networkingType,
            moduleSerial = moduleSerial,
        )
    }

    /**
     * SDK 要求应用实现的那几件事（附录 E.1）。做成内部类而不是让本类 implements：
     * [DispatchManager] 会在**它自己的线程**上调这些方法，把它们摆在 `SatelliteModem`
     * 的公开接口上，上层就分不清"谁有权调"了。
     */
    private inner class PipeOperation : ModuleOperation {

        /** SDK → 模组。管道会把这一行记进工程模式窗，方向 TX */
        override fun send(data: ByteArray) {
            pipe.write(data)
        }

        /**
         * 〔SDK文 §4.1〕`isReady=false` 的含义**包含模组正在升级**（`INITIALIZE_ERROR`）。
         * 这是写锁的另一半：SDK 说没准备好，我们就该停止往里写（E.10 #6）。
         * 现在只记录不立旗 —— 升级流程真接上 `updateSOC` 时由那里发起，别让两条路互相覆盖。
         */
        override fun initialization(isReady: Boolean) {
            note(if (isReady) "SDK initialization(true)：就绪" else "SDK initialization(false)：未就绪（含升级中）")
        }

        override fun passthrough(pactWrap: String) {
            passthrough?.record(RawLine(RawLine.Direction.UNCLASSIFIED, pactWrap), clock.nowMs())
        }

        override fun powerOn() {
            note("SDK 请求上电 powerOn")
        }

        override fun powerDown() {
            note("SDK 请求断电 powerDown")
        }
    }

    companion object {
        /** GMO 模式 2 = 打开指定语句（〔规 §3.1.6〕）；频度 0 = 单次 */
        private const val GMO_OPEN = 2

        /** 〔规 §3.1.12 注1〕收信方填 0 = 发给平台 */
        private const val RECEIVER_PLATFORM = "0"

        private fun lengthLevelOrNull(raw: Int): MsgLengthLevel? =
            // 先当等级；不当等级再看它是不是某个档的字节数
            MsgLengthLevel.fromLevel(raw) ?: MsgLengthLevel.entries.firstOrNull { it.maxBytes == raw }
    }
}
