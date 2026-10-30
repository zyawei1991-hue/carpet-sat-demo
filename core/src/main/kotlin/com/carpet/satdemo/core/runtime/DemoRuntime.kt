package com.carpet.satdemo.core.runtime

import com.carpet.satdemo.core.engine.DemoEngine
import com.carpet.satdemo.core.diagnostic.PassthroughLog
import com.carpet.satdemo.core.devices.Device
import com.carpet.satdemo.core.devices.DeviceBus
import com.carpet.satdemo.core.devices.DeviceKind
import com.carpet.satdemo.core.devices.DeviceLink
import com.carpet.satdemo.core.devices.Transport
import com.carpet.satdemo.core.ephemeris.EphState
import com.carpet.satdemo.core.ephemeris.EphemerisTracker
import com.carpet.satdemo.core.link.CellStatus
import com.carpet.satdemo.core.link.LinkPhase
import com.carpet.satdemo.core.modem.MonoClock
import com.carpet.satdemo.core.modem.SatResult
import com.carpet.satdemo.core.modem.SimulatedModem
import com.carpet.satdemo.core.modem.TpiTracker
import com.carpet.satdemo.core.link.SosCopy
import com.carpet.satdemo.core.modem.SosProgress
import com.carpet.satdemo.core.protocol.AckPayload
import com.carpet.satdemo.core.protocol.DialogPayload
import com.carpet.satdemo.core.protocol.EventKind
import com.carpet.satdemo.core.protocol.EventPayload
import com.carpet.satdemo.core.protocol.GnssSource
import com.carpet.satdemo.core.protocol.MsgType
import com.carpet.satdemo.core.protocol.SatPhase
import com.carpet.satdemo.core.protocol.ScreenCodec
import com.carpet.satdemo.core.protocol.ScreenMessage
import com.carpet.satdemo.core.protocol.StatePayload
import com.carpet.satdemo.core.screen.ScreenLinkServer
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * App 侧唯一的业务入口。
 *
 * Android 相关的东西（定位、蜂窝、socket、通知）都从外面喂进来，
 * 这个类只管：攒状态 -> 每秒推给展示端 -> 把按钮动作变成协议消息。
 * 所以它能脱离手机跑单测，也能在 GT1005 到货前先用 [SimulatedModem] 彩排。
 */
class DemoRuntime(
    val engine: DemoEngine = DemoEngine(SimulatedModem(), DEFAULT_OWNER_ID),
    val server: ScreenLinkServer = ScreenLinkServer(),
    private val clockMs: () -> Long = System::currentTimeMillis,
    /** 心跳间隔（tick 数，1 tick = 1 秒）。演示时调小，否则观众等不到一条心跳 */
    private val heartbeatEvery: Int = 60,
    /**
     * 设备台账（§5.3 硬要求 #2 的落地位置）。**它在场景之外**，
     * 所以切场景、切 Tab 都不会让一台设备从页面上消失再出现。
     */
    val devices: DeviceBus = DeviceBus(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devicesSnapshot: StateFlow<List<Device>> = _devices

    /**
     * 工程模式日志窗（E.10 #10）。屏上/App 上那个窗口只读这一个对象 ——
     * 谁再攒一份，两条时间线就对不上了（窗里的时刻必须和事件流同一个钟）。
     */
    val passthrough = PassthroughLog(clock = MonoClock { clockMs() })

    /** 展示端推上来的动作（圆屏上的按键、语音意图），由 Activity 接线 */
    var onCommand: (ScreenMessage) -> Unit = {}

    /**
     * 圆屏说出来的话（ASR 文本或它自己那一侧模型的输出）。**这里不解释、不入库，只转交** ——
     * 对话记录归谁管是 App 层的事（core/ai 的 ConversationLog），链路层只管把帧收下来。
     */
    var onDialog: (ScreenMessage) -> Unit = {}

    /** 每一批出站消息都过这里。演示驱动器用它录成 jsonl，回放给展示端 UI 看 */
    var onSend: (List<ScreenMessage>) -> Unit = {}

    data class Status(
        val running: Boolean = false,
        val phase: LinkPhase = LinkPhase.CELL,
        val snapshot: StatePayload? = null,
        val clients: Int = 0,
        val lastSos: SatResult? = null,
        val localIp: String? = null,
        /** 展示端接入点没起来的原因。非空 = 1Hz 在跑但屏连不上，首页必须说实话 */
        val serverError: String? = null,
        val log: List<String> = emptyList(),
    )

    /**
     * @param autoTick false = 只起服务和接线，1Hz 那圈由调用方推。
     *   演示驱动器要自己控制假时钟，不能再让内部循环掺进来跳。
     */
    fun start(autoTick: Boolean = true) {
        if (loop != null) return
        engine.modem.attachPassthrough(passthrough)
        server.onMessage = { msg ->
            if (msg.t == MsgType.command) onCommand(msg)
            if (msg.t == MsgType.dialog) onDialog(msg)
            _status.value = _status.value.copy(clients = server.clientCount)
        }
        server.onHello = hello@{ msg ->
            // 谈得下来 = 双方版本有交集。v1 的屏**不是**"契约不匹配"，它只是不能说话 ——
            // 把老固件标成降级并打红字，明天到货现场会让人以为屏坏了
            val hello = msg.hello ?: run {
                // 没有 hello 帧体却进了 onHello，只能是屏那边把包拼错了。
                // **不许 requireNotNull**：这个 lambda 跑在 WebSocket 的读线程上，
                // 那儿的未捕获异常会把整个 App 带走 —— 一颗屏发错一个字段，不该让车上这块屏和手机一起没
                log("展示端 hello 缺字段，已忽略这一帧")
                return@hello
            }
            val ver = ScreenCodec.negotiated(hello.protocols)
            val ok = ScreenCodec.compatible(hello.protocols)
            log(
                "展示端接入 ${hello.screenId} fw=${hello.fw} 契约 v$ver" +
                    if (ok) (if (ver >= 2) " · 可对话" else " · 只能看状态") else "（契约不匹配）",
            )
            devices.upsert(
                Device(
                    id = "screen:${hello.screenId}",
                    kind = DeviceKind.DisplayScreen,
                    title = hello.screenId,
                    transport = Transport.LanWs,
                    link = if (ok) DeviceLink.Online(clockMs()) else DeviceLink.Degraded(clockMs(), "契约版本不匹配"),
                    detail = screenDetail(hello),
                    actions = listOf("revoke"),
                ),
            )
            ackNow(
                forSeq = msg.seq,
                screenId = hello.screenId,
                error = if (ok) null else "proto_mismatch",
            )
            pushState()
        }
        // 断开不能只减计数：台账上那台屏要立刻变成"离线，最后一次见到是几点"，
        // 否则现场会让人以为还连着
        server.onDisconnect = { id ->
            if (id == null) {
                log("展示端断开（没报过 screen_id）")
            } else {
                devices.relink("screen:$id", DeviceLink.Offline(clockMs(), "链路断开"))
                log("展示端 $id 已断开")
            }
        }
        // 屏发来的帧解不开时，原文进日志。明天烧固件那天，这一行就是唯一的线索
        server.onContractError = { raw, why -> log("展示端帧解不开：$why｜${raw.take(200)}") }
        // 往死连接上写会抛。不记这一句，"屏掉线之后另一块屏也不更新了"就只能靠猜
        server.onSendError = { why -> log("展示端写入失败，已断开这条连接：$why") }
        devices.subscribe { _devices.value = it }
        engine.onSosProgress = { p ->
            if (p is SosProgress.SatSending && p.attempt > 1) log("SOS 第 ${p.attempt} 次重发")
        }
        // 端口被占（上一次退出没干净、或者桌面上还开着另一个实例）**不能把 App 带走**：
        // 屏连不上是一件事，手机连状态都看不到是另一件更糟的事。记下原因、1Hz 照跑，
        // 首页那行字改口说"接入点起不来" —— 不许继续报"服务运行中"
        val bindFail = runCatching { server.start() }.exceptionOrNull()
        val lan = if (bindFail == null) lanAddress() else null
        _status.value = _status.value.copy(
            running = true,
            localIp = lan?.host,
            serverError = bindFail?.let { it.message ?: it.javaClass.simpleName },
        )
        if (bindFail == null) {
            // 接口名要一起进日志：手机同时开着 Wi-Fi 和热点时，"报哪个地址"就是现场最难查的那件事
            log("展示端接入点 ws://${lan?.host ?: "没找到"}:${ScreenLinkServer.PORT}" +
                (lan?.let { "（取自 ${it.iface}）" } ?: " —— 手机没连 Wi-Fi 也没开热点，屏找不到这条路"))
        } else {
            log("展示端接入点起不来：${_status.value.serverError} —— 1Hz 照跑，但屏连不上")
        }
        if (autoTick) loop = scope.launch { tickForever() }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        // stop() 会等选择线程退场，最多阻塞一秒级；这一句在 Activity.onDestroy 里，
        // 主线程等它一次可以，被它抛出来的 InterruptedException 打死不行
        runCatching { server.stop() }
        _status.value = _status.value.copy(running = false, clients = 0)
        log("服务停止")
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    /**
     * 工程模式窗的标题行（E.10 #10）。下面那些原始行怎么解释，取决于这三样：
     * 哪颗模组、报文长度等级读到了几、队列里还压着几条。
     * 等级只报 ICI 那个原始数值 —— 折算后的字节数依赖还没定死的单位口径（§9 #16），
     * 窗里报一个"猜出来的数"比不报更糟。
     */
    fun engineeringWindowHeader(): String = passthrough.header(
        engine.modem.caps?.let { "${it.model} · 等级${it.lengthLevel.level}(${it.maxMessageBytes}B) · ${it.service}" },
        engine.outbox.summary(),
    )

    /**
     * 卫星终端进台账。**GT1005 掉线是 [DeviceLink.Offline] 不是 [DeviceLink.Absent]**（§5.3 注）：
     * 用户最需要看到这一行的，正是掉线那一刻。
     *
     * id 固定成 `sat:gt1005`，**型号进了 id 会在换型号那次留成两行**（台账上出现一台
     * "上次见过的 GM1003"是纯粹的噪声）。型号写在 detail 里。
     */
    private fun noteModemDevice(now: Long) {
        val caps = engine.modem.caps
        val up = runCatching { engine.modem.available }.getOrDefault(false)
        val link = if (up) {
            lastModemSeen = now
            if (caps?.isInvalidCard == true) DeviceLink.Degraded(now, "无效卡，额度与长度都不能信")
            else DeviceLink.Online(now)
        } else {
            DeviceLink.Offline(lastModemSeen, "终端没有回应：查供电、串口、蓝牙")
        }
        devices.merge(
            Device(
                id = "sat:gt1005",
                kind = DeviceKind.SatelliteModule,
                title = caps?.model?.name ?: "卫星终端",
                transport = Transport.Uart,
                link = link,
                detail = listOfNotNull(
                    caps?.let { "长度等级 ${it.lengthLevel.level}（上限 ${it.maxMessageBytes}B）" },
                    caps?.let { "业务 ${it.service}" },
                    caps?.let { if (it.isInvalidCard) "卡号无效" else "卡号 ${it.cardId}" },
                    "队列 ${engine.outbox.handedSize}/${engine.outbox.policy.capacity} 已交模组，待补发 ${engine.outbox.size}",
                    "驱动 ${engine.modem::class.simpleName}",
                ),
            ),
        )
    }

    private var lastModemSeen = 0L

    /** NetworkMonitor 每秒的落点。状态机只认这里进来的蜂窝观测。 */
    fun onCellSample(sample: CellStatus) {
        lastCell = sample
        push(engine.onCellStatus(sample, clockMs()))
    }

    fun onFix(lat: Double, lon: Double, source: GnssSource, hdop: Double?, ageSec: Double?) {
        engine.onFix(lat, lon, source, hdop, ageSec)
    }

    fun onPhoneBattery(pct: Int) = engine.onPhoneBattery(pct)

    fun setDemoMode(on: Boolean) = engine.onDemoModeChanged(on)

    fun armSentinel(on: Boolean) = engine.armSentinel(on)

    fun setFence(active: Boolean, name: String?) = engine.setFence(active, name)

    /** 默认关。演示现场才放开，见 protocol/screen-link.md 的接入控制一节 */
    fun setAcceptScreens(accept: Boolean) {
        server.accepting = accept
        log(if (accept) "已放开展示端接入" else "已拒绝所有展示端接入")
    }

    /** SOS 按钮。卫星那一路无条件发，不等链路判定 */
    fun pressSos(): Job = scope.launch {
        val out = engine.triggerSos(nowSec = clockMs() / 1000, cellOk = cellOk())
        _status.value = _status.value.copy(lastSos = out.result)

        val failed = out.result != SatResult.COMPLETE
        // 这句日志和屏上那行字走同一张表：现场排练时读日志就是在读用户会看到的话（§6.1 红线一）
        log(
            "SOS ${SosCopy.statusLine(out.send, out.nextPassInS)}" +
                " ×${out.attempts}${if (out.hasFix) "" else "（无定位）"} ${out.frameHex}",
        )
        push(
            MsgType.event,
            if (failed) EventKind.sos_failed else EventKind.sos_acked,
            "frame_hex" to JsonPrimitive(out.frameHex),
            "has_fix" to JsonPrimitive(out.hasFix),
            "attempts" to JsonPrimitive(out.attempts),
            "sat_result" to JsonPrimitive(out.result.name.lowercase()),
            "next_pass_in_s" to (out.nextPassInS?.let { JsonPrimitive(it.toInt()) } ?: JsonNull),
        )
    }

    private fun cellOk() = lastCell.usable

    /**
     * 误触撤销。App 上那个"撤销 SOS"按钮走这里，圆屏上的按钮也走这里。
     *
     * 结果的话只能由 [SosCopy.cancelLine] 生成 —— 撤销比发送更容易说错，
     * 因为"按了撤销"和"救回来了"是两件事（§6.1 红线一的另一半）。
     */
    fun cancelSos(onResult: (DemoEngine.CancelResult) -> Unit = {}): Job = scope.launch {
        val out = engine.cancelSos(nowSec = clockMs() / 1000, cellOk = cellOk())
        log("SOS 撤销 ${SosCopy.cancelLine(out.send, out.alreadyHanded)} ${out.cancelFrameHex}")
        push(
            MsgType.event,
            if (out.accepted) EventKind.sos_cancelled else EventKind.sos_failed,
            "frame_hex" to JsonPrimitive(out.cancelFrameHex),
            "sat_result" to JsonPrimitive(out.send.result.name.lowercase()),
            "already_handed" to JsonPrimitive(out.alreadyHanded),
        )
        onResult(out)
    }

    /** 台账上那台屏的说明行。措辞集中在这儿，固件那边只需把 hello 报全 */
    private fun screenDetail(hello: com.carpet.satdemo.core.protocol.HelloPayload): List<String> =
        listOfNotNull(
            "固件 ${hello.fw}",
            "圆屏 ${hello.dockType}",
            hello.wifiRssi?.let { "Wi-Fi ${it} dBm" },
            hello.batteryMv?.let { "电池 ${it / 1000.0} V" } ?: "外接供电",
            hello.protocols?.let { "契约 ${it.joinToString("/")}" } ?: "契约 未申报 · 按 v1 处理",
            "接入点 ws://${_status.value.localIp ?: "-"}:${ScreenLinkServer.PORT}",
        )

    /**
     * 现场加一台屏。出厂烧死 screen_id 在演示现场是必错的（屏是卖方发的，id 我们改不了），
     * 所以这里必须能当场放行 —— 但**放行不等于放开总闸**，accepting 仍是那道门。
     */
    fun allowScreen(screenId: String): Boolean {
        val added = server.allow(screenId)
        log(if (added) "已把 $screenId 加进白名单" else "$screenId 本来就在白名单里")
        return added
    }

    fun allowedScreens(): Set<String> = server.allowed()

    /** 撤掉一台。立刻断开它，不是等超时（§ screen-link.md 安全边界那条） */
    fun revokeScreen(screenId: String) {
        server.revoke(screenId)
        devices.relink("screen:$screenId", DeviceLink.Offline(clockMs(), "已被车主移出白名单"))
        log("已移出白名单：$screenId")
    }

    private var lastCell = CellStatus(hasInternetCapability = false, validated = false, signalLevel = 0)

    /** hello 的回执在 WebSocket 线程上发，tick 在主循环发，所以序号得是原子的 */
    private val seq = AtomicLong()
    private var counter = 0

    /** ack 必须当场发。排在 1Hz 的 state 后面，展示端 3 秒超时就要重发 event */
    fun ackNow(
        forSeq: Long,
        forEventId: String? = null,
        error: String? = null,
        screenId: String? = null,
    ) {
        val msg = ScreenMessage(
            t = MsgType.ack,
            seq = seq.getAndIncrement(),
            ts = clockMs(),
            ack = AckPayload(forSeq, forEventId, error),
        )
        if (screenId != null) server.sendTo(screenId, msg) else push(listOf(msg))
    }

    /**
     * 立刻推一帧当前快照。两个用处：hello 之后不能让客户等满一秒才见到东西；
     * 彩排定格时钟不再走，但 1Hz 那帧还得接着发，否则展示端 3 秒就把链路图标变灰。
     */
    fun pushState() {
        val now = clockMs()
        push(listOf(ScreenMessage(t = MsgType.state, seq = 0, ts = now, state = engine.snapshot())))
    }

    /**
     * 把一句话送到圆屏上。手机自己打的字也走这里 —— 两边看到的是同一条记录，
     * 不是"手机一份、屏上一份"（那种东西在演示现场一定会被问出对不上）。
     */
    fun pushDialog(d: DialogPayload) {
        push(listOf(ScreenMessage(t = MsgType.dialog, seq = 0, ts = clockMs(), dialog = d)))
    }

    /** 这台屏能不能说话（契约 v2）。首页那句提示读这个，不靠猜 */
    val screenCanTalk: Boolean get() = server.dialogReady

    private suspend fun tickForever() {
        while (true) {
            tickOnce()
            delay(1_000)
        }
    }

    /** 抽出来给演示驱动器单独调，彩排时要把某一帧停住给观众看清 */
    suspend fun tickOnce() {
        val now = clockMs()
        push(engine.onTick(now))
        noteModemDevice(now)
        // TPI 是分钟级的活，但每秒问一句"到点了没"很便宜；状态一变就上日志，
        // 现场看到"下行收不到"时至少能回溯到位置喂没喂进去（E.5）
        engine.tickTpi()?.let { noteTpi(it.state) }
        push(engine.drainProbe(now))
        push(engine.drainPending(now))
        // 倒计时以厂商星历为准，算不出来才退回模组粗估 —— 这条优先序在 DemoEngine 里，这里只管记一行
        val eph = engine.tickEphemeris()
        if (eph != null) noteEph(eph) else engine.nextPassInS = runCatching { engine.modem.secondsToNextPass() }.getOrNull()
        counter++
        if (counter % heartbeatEvery == 0 && !engine.heartbeat(nowSec = now / 1000, cellOk = cellOk())) {
            log("心跳进离线队列，共 ${engine.pending.size} 条")
        }
        val snap = engine.snapshot()
        publishState(snap, now)
    }

    /**
     * 把当前这一帧全量状态推给展示端并进 [status]。
     * 1Hz 循环和「重试」都走这里 —— 手动重算完必须立刻上屏，等下一秒才动会让人以为那一下没按到。
     */
    private fun publishState(snap: StatePayload, now: Long) {
        // 断线时展示端保留最后一帧，所以这条同时也是它的"还活着"信号
        push(listOf(ScreenMessage(t = MsgType.state, seq = seq.getAndIncrement(), ts = now, state = snap)))
        _status.value = _status.value.copy(
            phase = engine.phase,
            snapshot = snap,
            clients = server.clientCount,
        )
    }

    /**
     * 卡片上那颗「重试」：立刻重算一次星历，算完当场回话（成功还是没成功，措辞由调用方定）。
     * 没星历源（场景替身那种）就回 null，调用方不许把 null 当成"成功"报给用户。
     */
    fun retryEphemeris(onDone: (EphemerisTracker.Status?) -> Unit = {}): Job = scope.launch {
        val status = engine.retryEphemeris()
        if (status != null) noteEph(status)
        publishState(engine.snapshot(), clockMs())
        onDone(status)
    }

    /**
     * 演一次模组固件升级：占住全局写锁，期间 TPI、心跳、补发、SOS 全被挡下（E.10 #6）。
     * 厂商侧的依据是〔SDK文 §9-2〕"正在更新模组任务时所有数据发送都不可用"。
     * 屏上那排按钮的禁用态看 `sat.upgrade_pct`（§5.2）。
     */
    fun firmwareUpgrade(onProgress: (Int) -> Unit = {}, steps: Int = 3): Job = scope.launch {
        val done = engine.modem.gate.runUpgrade(clockMs()) { progress ->
            repeat(steps) { i ->
                val pct = (i + 1) * 99 / steps
                progress(pct)
                onProgress(pct)
                delay(UPGRADE_STEP_MS)
            }
        }
        log(if (done == null) "升级没开始：已有一场在跑" else "升级结束，写锁已释放，被挡下的报文会补发")
    }

    private var lastTpiState: TpiTracker.State? = null

    /** TPI 状态只在跳变时记一行，否则 1Hz 会把日志刷满 */
    private fun noteTpi(state: TpiTracker.State) {
        if (state == lastTpiState) return
        lastTpiState = state
        log(
            when (state) {
                TpiTracker.State.NO_SOURCE -> "没有位置源，模组里是空的，下行大概率收不到"
                TpiTracker.State.STALE -> "位置已过期，卫星可能投递失败"
                TpiTracker.State.BLOCKED -> "TPI 被升级写锁挡下"
                TpiTracker.State.REJECTED -> "TPI 模组没有回显"
                TpiTracker.State.DUE -> "TPI 到点，推送位置"
                else -> "TPI 正常"
            },
        )
    }

    private var lastEphState: EphState? = null

    /** 星历状态同样只在跳变时记一行。**措辞用 EphemerisCopy 那一句**，别在这里另写一遍 */
    private fun noteEph(status: EphemerisTracker.Status) {
        if (status.state == lastEphState) return
        lastEphState = status.state
        val window = status.nextPass?.let { "，${it.starName} 窗口 ${it.windowSec}s" } ?: ""
        log((status.failMessage ?: "星历正常") + window)
    }

    private fun push(
        t: MsgType,
        kind: EventKind,
        vararg fields: Pair<String, JsonElement>,
    ) {

        push(
            listOf(
                ScreenMessage(
                    t = t,
                    seq = 0,
                    ts = clockMs(),
                    event = EventPayload(kind = kind, eventId = "", payload = JsonObject(linkedMapOf(*fields))),
                ),
            ),
        )
    }

    /**
     * 出站口只有一个，所以序号和 event id 都在这里统一盖。
     * 两边各自计数的话，展示端按 seq 检测丢包、按 event_id 去重都会撞车 —— 撞上一次就是白丢一条 SOS。
     */
    private fun push(messages: List<ScreenMessage>) {
        if (messages.isEmpty()) return
        val stamped = messages.map {
            val n = seq.getAndIncrement()
            it.copy(seq = n, event = it.event?.copy(eventId = "evt-$n"))
        }
        onSend(stamped)
        server.broadcast(stamped)
    }

    fun log(line: String) {
        _status.value = _status.value.copy(log = (_status.value.log + line).takeLast(40))
    }

    /**
     * App 控制台那几行字。手机和桌面彩排台共用这一份 —— 两边各写一遍的话，
     * 彩排时看到的和装到手机上看到的就是两个东西了。
     */
    fun consoleText(s: Status = _status.value): String {
        val snap = s.snapshot
        val sat = snap?.sat
        // 没锁到星时唯一有用的信息就是还有多少秒过顶；锁上了再显示倒计时就是噪声
        val waitPass =
            if (sat != null && sat.phase != SatPhase.locked) sat.nextPassInS?.takeIf { it > 0 } else null
        val satLine = when {
            sat == null -> "-"
            !sat.moduleOk -> "GT1005 离线"
            else -> listOfNotNull(
                sat.phase.toString() + (sat.satName?.let { " $it" } ?: ""),
                waitPass?.let { "距下次过顶 ${it}s" },
            ).joinToString("   ")
        }
        return buildString {
            appendLine("链路  ${s.phase}   展示端 ${s.clients} 台   ${s.localIp ?: "-"}")
            appendLine("卫星  $satLine")
            appendLine(
                "定位  ${
                    snap?.let { "%.5f, %.5f  (%s)".format(it.gnss.lat, it.gnss.lon, it.gnss.source) } ?: "无"
                }",
            )
            appendLine("SOS   ${snap?.sos?.level ?: "-"}  上次结果 ${s.lastSos ?: "-"}")
            appendLine("服务  ${if (s.running) "运行中" else "未启动"}   离线待补发 ${engine.pending.size} 条")
        }
    }

    companion object {
        /** 占位。真实值等时空道宇分配车主 ID 段位，见 docs/DECISIONS.md D3 的外部依赖 */
        const val DEFAULT_OWNER_ID = 12_061L

        /** 演示用的一场升级分几段、每段多久。真机上这个数由厂商的 updateSOC 进度决定 */
        private const val UPGRADE_STEP_MS = 400L

        /**
         * 手机自己是谁。接入点报错了等于把屏往死路上支使，所以这一格单独可测，
         * 见 [pickLanAddress]。
         */
        fun lanAddress(): LanAddress? = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nif ->
                    nif.inetAddresses.asSequence().filterIsInstance<Inet4Address>()
                        .mapNotNull { a -> a.hostAddress?.let { LanAddress(nif.name, it.substringBefore('%')) } }
                }
                .toList()
        }.getOrNull().let { pickLanAddress(it ?: emptyList()) }
    }
}

/** 一个候选接入点：网卡名 + IPv4。名字要一起报给用户，见 start() 那行日志 */
data class LanAddress(val iface: String, val host: String) {
    /** 蜂窝/链路本地/回环那些地址，对面永远走不到，报出去就是假接入点 */
    val usable: Boolean get() =
        !host.startsWith("127.") && !host.startsWith("169.254.") && host != "0.0.0.0"
}

private val LAN_WLAN = listOf("wlan", "swlan", "ap", "br0", "eth")
private val LAN_CELLULAR = listOf("rmnet", "netmgr", "ccmni", "radio", "ipa", "tun", "ipsec", "dummy", "lo")

/**
 * 一堆网卡里挑那条屏走得通的。
 *
 * 顺序不能反过来：手机**边连 Wi-Fi 边开热点**是演示现场的常态，这时候两个地址都"能用"，
 * 但屏只会连到其中一个上。所以先要 Wi-Fi/热点那一路，再退到"不是蜂窝就行"。
 * 蜂窝地址（rmnet_data0 那种 10.x / 192.0.0.x）挑中了最坏 —— 它看着像个正常 IPv4，
 * 屏照着填，永远连不上，而 App 上那行字还写着"接入点正常"。
 */
fun pickLanAddress(candidates: List<LanAddress>): LanAddress? {
    val usable = candidates.filter { it.usable }
    fun matches(a: LanAddress, keys: List<String>) = keys.any { a.iface.contains(it, ignoreCase = true) }
    return usable.firstOrNull { matches(it, LAN_WLAN) && !matches(it, LAN_CELLULAR) }
        ?: usable.firstOrNull { !matches(it, LAN_CELLULAR) }
}
