package com.carpet.satdemo.core.runtime

import com.carpet.satdemo.core.ai.MicUplink
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
import com.carpet.satdemo.core.protocol.AnswerMood
import com.carpet.satdemo.core.protocol.CommandType
import com.carpet.satdemo.core.protocol.DialogDir
import com.carpet.satdemo.core.protocol.DialogPayload
import com.carpet.satdemo.core.protocol.EventKind
import com.carpet.satdemo.core.protocol.EventPayload
import com.carpet.satdemo.core.protocol.GnssSource
import com.carpet.satdemo.core.protocol.MsgType
import com.carpet.satdemo.core.protocol.SatPhase
import com.carpet.satdemo.core.protocol.ScreenCodec
import com.carpet.satdemo.core.protocol.ScreenMessage
import com.carpet.satdemo.core.protocol.ScreenPage
import com.carpet.satdemo.core.protocol.SosLevel
import com.carpet.satdemo.core.protocol.StatePayload
import com.carpet.satdemo.core.protocol.UiCard
import com.carpet.satdemo.core.protocol.UiEmotion
import com.carpet.satdemo.core.protocol.UiState
import com.carpet.satdemo.core.protocol.hexToBytes
import com.carpet.satdemo.core.screen.CardPolicy
import com.carpet.satdemo.core.screen.ScreenLinkServer
import java.net.BindException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * App 侧唯一的业务入口。
 *
 * Android 相关的东西（定位、蜂窝、socket、通知）都从外面喂进来，
 * 这个类只管：攒状态 -> 每秒推给展示端 -> 把按钮动作变成协议消息。
 * 所以它能脱离手机跑单测，也能在 GT1005 到货前先用 [SimulatedModem] 彩排。
 */
class DemoRuntime(
    val engine: DemoEngine = DemoEngine(SimulatedModem(), DEFAULT_OWNER_ID),
    server: ScreenLinkServer = ScreenLinkServer(),
    private val clockMs: () -> Long = System::currentTimeMillis,
    /** 心跳间隔（tick 数，1 tick = 1 秒）。演示时调小，否则观众等不到一条心跳 */
    private val heartbeatEvery: Int = 60,
    /**
     * 设备台账（§5.3 硬要求 #2 的落地位置）。**它在场景之外**，
     * 所以切场景、切 Tab 都不会让一台设备从页面上消失再出现。
     */
    val devices: DeviceBus = DeviceBus(),
) {
    /**
     * 每次 [start] 都换一个新对象。Java-WebSocket 1.5.6 的 `start()` 只看 `selectorthread`
     * 是不是 null，而 `stop()` 不清它 —— 同一个实例第二次起永远抛 `can only be started once.`，
     * 而这正是「停止演示 → 再开始演示」在 App 里走的那条路。
     * 构造参数保留下来是为了单测/彩排台能注入，但**每次 start 都会被覆盖**。
     */
    var server: ScreenLinkServer = server
        private set

    /** stop() 之后、下一次 start() 之前，别让旧对象迟到的关闭异常重新点亮红字 */
    @Volatile
    private var stopping = false

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

    /**
     * 展示端推上来的动作（圆屏上的按键、滑动、戳一下）。**处理不在这里做** ——
     * 那一张表是 [handleScreenCommand]，真机和彩排台共用它。这里只给调用方留一个观察点：
     * 打日志、往控制台 echo、把撤销结果写进本机那份 UI 状态。
     */
    var onCommand: (ScreenMessage) -> Unit = {}

    /**
     * 屏发来的 `sos_cancel` 落下来的结果。App 用它填首页那行"撤销去哪个过境"，
     * 彩排台用它打印 —— 屏按的撤销和手机按的撤销必须是同一句话，否则用户在两边读到两个版本。
     */
    var onCancelResult: (DemoEngine.CancelResult) -> Unit = {}

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
        /**
         * 这个接入点起来之后，**有没有任何一条接入尝试到过这台手机**（连上就被挡下的也算）。
         * 和 [clients] 分开记的理由见 [ScreenLinkServer.attemptCount]：
         * 0 台在线 + 0 次尝试 = 屏根本没进这张网；有尝试 = 屏到了但被挡，原因在日志里。
         */
        val attempts: Int = 0,
        val lastSos: SatResult? = null,
        val localIp: String? = null,
        /** 展示端接入点没起来的原因。非空 = 1Hz 在跑但屏连不上，首页必须说实话 */
        val serverError: String? = null,
        /**
         * 屏自己报上来的那一页（`page_report`）。null 有两种，都是实话：
         * 这一轮还没报过（老固件不会报，新屏重连后也还没补报），和屏已经断了。
         * **不许拿上一屏的页来填这一格** —— 手机上写着「屏在亲子页」而玻璃上没人，
         * 是这块镜像能给出的最贵的一句假话
         */
        val screenPage: ScreenPage? = null,
        val log: List<String> = emptyList(),
    )

    /**
     * @param autoTick false = 只起服务和接线，1Hz 那圈由调用方推。
     *   演示驱动器要自己控制假时钟，不能再让内部循环掺进来跳。
     */
    fun start(autoTick: Boolean = true) {
        if (loop != null) return
        stopping = false
        // 换对象而不是重开：旧实例的 selectorthread 永远不清，start() 必抛。
        // 白名单和「允许圆屏接入」是这个对象的现场状态，换的时候必须带过去，
        // 否则第二次开始演示，配好的屏会被 unknown screen / demo lock 挡在门外
        val previous = server
        server = ScreenLinkServer(previous.allowed().toMutableSet()).also { next ->
            next.accepting = previous.accepting
            next.onServerError = { ex -> reportServerFailure(next, ex) }
        }
        engine.modem.attachPassthrough(passthrough)
        server.onMessage = { msg ->
            if (msg.t == MsgType.command) {
                // 处理只在这一份里做。[onCommand] 降级成"看一眼"：App 和彩排台各写一个 when 的话，
                // 彩排演过的就不是装到手机上的那个东西，而新加的三条指令正是这样漏掉的
                if (!handleScreenCommand(msg.command?.cmd, msg.command?.args)) {
                    // 走到这里有两种：契约里有但不该由屏发的那几条，和请求被拒（SOS 期间切不走卡）。
                    // 都不是错误，也不专门回一条什么 —— 屏上那张卡没变，本身就是观众看得见的答复
                    log("屏的请求没被执行：${msg.command?.cmd}")
                }
                onCommand(msg)
            }
            if (msg.t == MsgType.dialog) onDialog(msg)
            _status.value = _status.value.copy(clients = server.clientCount, attempts = server.attemptCount)
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
            // 页也跟着清。屏不在了还写着「屏在亲子页」，是那块镜像自己编的话 ——
            // 重连之后屏会补报一条 page_report，那一格才会重新有字
            screenPage = null
            _status.value = _status.value.copy(screenPage = null)
            if (id == null) {
                log("展示端断开（没报过 screen_id）")
            } else {
                devices.relink("screen:$id", DeviceLink.Offline(clockMs(), "链路断开"))
                log("展示端 $id 已断开")
            }
        }
        // 屏发来的帧解不开时，原文进日志。明天烧固件那天，这一行就是唯一的线索
        server.onContractError = { raw, why -> log("展示端帧解不开：$why｜${raw.take(200)}") }
        // 到了门口被挡下的也要有一行：现场「屏连着热点但 App 不认」最需要的就是这一句 ——
        // 它证明包到过手机，问题在准入不在网络
        server.onRejected = { why -> log("展示端接入被挡：$why") }
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
            serverError = bindFail?.let { serverFailureLine(it) },
        )
        if (bindFail == null) {
            // 接口名要一起进日志：手机同时开着 Wi-Fi 和热点时，"报哪个地址"就是现场最难查的那件事
            log("展示端接入点 ws://${lan?.host ?: "没找到"}:${ScreenLinkServer.PORT}" +
                (lan?.let { "（取自 ${it.iface}）" } ?: " —— 手机没连 Wi-Fi 也没开热点，屏找不到这条路"))
        } else {
            log(
                "展示端接入点起不来：${_status.value.serverError}" +
                    "（${bindFail.message ?: bindFail.javaClass.simpleName}）—— 1Hz 照跑，但屏连不上",
            )
        }
        if (autoTick) loop = scope.launch { tickForever() }
    }

    /**
     * 服务端级异常是**异步**来的：Java-WebSocket 的 bind 在选择线程里做，
     * `start()` 当场返回之后才把 `BindException` 交给 `onError(null, ex)`。
     * 不接这一句，端口被占时首页会一直显示"服务运行中"，屏那边则每 11 秒重连一次。
     */
    private fun reportServerFailure(origin: ScreenLinkServer, ex: Exception) {
        if (stopping || origin !== server) return
        val line = serverFailureLine(ex)
        _status.value = _status.value.copy(serverError = line, localIp = null)
        log(
            "展示端接入点起不来：$line" +
                "（${ex.message ?: ex.javaClass.simpleName}）—— 1Hz 照跑，但屏连不上",
        )
    }

    /** 两种失败必须说成两句：端口被占能靠关掉另一个实例解决，二次 start 只能完全退出 App */
    private fun serverFailureLine(ex: Throwable): String {
        // Java-WebSocket 会把通道上的 IOException 包一层 WrappedIOException 再交出来
        var cause: Throwable? = ex
        while (cause != null) {
            when (cause) {
                is BindException -> return "端口 ${ScreenLinkServer.PORT} 被占了，先关掉另一个实例"
                is IllegalStateException -> return "这个接入点已经起过一次，停止演示再开始也不会重来 —— 需要完全退出 App"
            }
            cause = cause.cause?.takeIf { it !== cause }
        }
        return ex.message ?: ex.javaClass.simpleName
    }

    fun stop() {
        stopping = true
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

    /** 屏上画哪张卡。传一张 = 手动钉住；传 null = 交回 [CardPolicy] 那条优先序 */
    fun setUi(state: UiState?) {
        uiCardOverride = state?.card
        uiEmotionOverride = state?.emotion
        screenTzMin = state?.tzMin
        if (state == null) engine.onUiChanged(null)
    }

    /** 只给脸和时区，卡仍由策略定 —— App 设置页那句"跟随场景"和演示脚本走这条 */
    fun setScreenStyle(emotion: UiEmotion? = null, tzMin: Int? = null) {
        uiEmotionOverride = emotion
        screenTzMin = tzMin
    }

    fun armSentinel(on: Boolean) = engine.armSentinel(on)

    /** 围栏。给了圆就越界由引擎按定位算，见 [DemoEngine.setFence] 那段为什么 */
    fun setFence(active: Boolean, name: String?, centerLat: Double? = null, centerLon: Double? = null,
                 radiusM: Double = 0.0) = engine.setFence(active, name, centerLat, centerLon, radiusM)

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

    /**
     * 平台回执到了（真机上由云端推送打到 App，见 [DemoEngine.onPlatformAck] 那条红线）。
     *
     * 这里**不推 `sos_acked` 事件**：那条已经被 [pressSos] 用在"卫星应答了"那一档，
     * 而平台受理是另一件事，混用会让屏上那句「正等平台回执」在自己之后又弹一次。
     * 平台这一路只改 `state.sos.level = acked`，状态盘和卫星卡各自从表里取那行字。
     */
    fun platformAck() = engine.onPlatformAck(clockMs())

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

    /**
     * 一次进程启动一个的标签，盖进每条 `event_id`。
     * 展示端那份去重环形缓冲**跨重连不清空**（屏不重启就一直在），所以 id 只要"本次进程内唯一"不够：
     * App 重启后 seq 归零，`evt-0..evt-31` 会整批撞上屏上还留着的老 id，被当重复直接丢掉且不报错 ——
     * 撞掉的可能是 `sos_acked`。屏自己那侧就是同样的写法（`scr-%08lx`，见 screen_link.c）。
     */
    private val bootTag: String = UUID.randomUUID().toString().take(8)
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
            ack = AckPayload(
                forSeq,
                forEventId,
                error,
                // v4 上行申报：App 先发版，屏看到 4 才开 mic_chunk 上行
                protocols = listOf(ScreenCodec.CONTRACT_VERSION, 4),
            ),
        )
        if (screenId != null) server.sendTo(screenId, msg) else push(listOf(msg))
    }

    /**
     * 立刻推一帧当前快照。两个用处：hello 之后不能让客户等满一秒才见到东西；
     * 彩排定格时钟不再走，但 1Hz 那帧还得接着发，否则展示端 3 秒就把链路图标变灰。
     *
     * **这一条必须和 tickOnce 走同一个卡片决策**，不许直接发 `engine.snapshot()`：
     * 彩排台 `--hold` 期间每秒只走这里，卡决策不在这里重算的话，定格那一屏对
     * `card_next` / `poke` 完全没有反应 —— 于是脚本上那三条交互断言在彩排里必挂，
     * 而真机上 1Hz 走的是 tickForever，永远不复现。演的和装的不是一份，就是这条。
     */
    fun pushState() {
        val now = clockMs()
        push(listOf(ScreenMessage(t = MsgType.state, seq = 0, ts = now, state = noteScreenCard(engine.snapshot(), now))))
    }

    /**
     * 把一句话送到圆屏上。手机自己打的字也走这里 —— 两边看到的是同一条记录，
     * 不是"手机一份、屏上一份"（那种东西在演示现场一定会被问出对不上）。
     */
    fun pushDialog(d: DialogPayload) {
        // 记下"这句是说给屏听的"：对话卡要在答句之后自己待一会儿，而不是靠脚本第几步
        if (d.dir == DialogDir.down) {
            lastDialogDownMs = clockMs()
            // 脸跟着这句话走。在这里读，是因为手机打的字和屏问出来的答都从这一个口子下去
            dialogMood = AnswerMood.of(d.text)
            log("答句表情 → ${dialogMood?.name}")
        }
        // action 只许挂在答句上。上行那句是屏自己说出来的话，让它带着"把我自己挪到哪一页"
        // 就等于把定页权从"屏自己按"改成了"屏按自己听到的话按"，多绕一层还可能自我循环
        val out = if (d.dir == DialogDir.up && d.action != null) {
            log("屏上行的对话带了 action，已剥掉：跳页只能由答句发起")
            d.copy(action = null)
        } else {
            d
        }
        push(listOf(ScreenMessage(t = MsgType.dialog, seq = 0, ts = clockMs(), dialog = out)))
    }

    /** 这台屏能不能说话（契约 v2）。首页那句提示读这个，不靠猜 */
    val screenCanTalk: Boolean get() = server.dialogReady

    /**
     * 屏谈下来的契约版本（0 = 没接上）。能不能对话看 ≥2，能不能收语音看 ≥3 ——
     * 界面上要把这个数字摆出来，因为"没有声音"在 v2 上就是固件的事，不是 App 的事。
     */
    val screenProtocol: Int get() = server.negotiatedVersion

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
        noteScreenEvents(snap)
        // 画哪张卡由这边定（屏只照着画），答句那张脸也在这里并进 ui.emotion
        val shown = noteScreenCard(snap, now)
        // 断线时展示端保留最后一帧，所以这条同时也是它的"还活着"信号
        push(listOf(ScreenMessage(t = MsgType.state, seq = seq.getAndIncrement(), ts = now, state = shown)))
        _status.value = _status.value.copy(
            phase = engine.phase,
            snapshot = shown,
            clients = server.clientCount,
            attempts = server.attemptCount,
        )
    }

    private var lastDialogDownMs: Long? = null
    private var uiCardOverride: UiCard? = null
    private var uiEmotionOverride: UiEmotion? = null
    /** 最近一句答句自带的那张脸。只在对话卡还新鲜的那 30 秒里生效，过期就交还给策略 */
    private var dialogMood: UiEmotion? = null
    private var screenTzMin: Int? = null
    private var lastDecision: CardPolicy.Decision? = null
    private var lastPokeMs: Long? = null
    private var lastNotableMs: Long? = null
    /** 最近一帧快照。屏的请求要按当下状态决定接不接（SOS 进行中不让你把倒计时切走），靠这个读 */
    private var lastSnap: StatePayload? = null
    /** 屏报上来的那一页。只有屏能定（导航归屏），手机只读不写，切页要靠对话下行去求 */
    private var screenPage: ScreenPage? = null

    /** 最近一次策略决定，给彩排台日志和调试页回显。屏真正画的那一份在推出去的 `state.ui` 里 */
    val lastUiDecision: CardPolicy.Decision? get() = lastDecision

    /**
     * 屏上能滑着看的那几张，按这个顺序循环。
     *
     * **`dialog` 不在里面**：那张只有"刚答了一句"的时候有内容，滑到一个空对话框
     * 比滑不到它更糟 —— 观众会以为屏坏了。它由策略在答句下去那一瞬自己占住屏。
     */
    private val swipeCycle = listOf(UiCard.status, UiCard.sat, UiCard.emotion, UiCard.clock)

    /**
     * 屏发上来的请求都在这一个函数里处理。真机 App 和桌面彩排台共用这一份，
     * 两边各写一遍的话，彩排演过的就不是装到手机上的那个东西（`consoleText` 是同一条先例）。
     *
     * @return 这条请求有没有被接住。false 只给"契约里有、但这一档不该由屏发"的取值，
     *         不是错误 —— 调用方拿它记一行日志就够了，不该重试也不该断连
     */
    fun handleScreenCommand(cmd: CommandType?, args: JsonObject? = null): Boolean = when (cmd) {
        null -> false
        CommandType.sos_press -> { pressSos(); true }
        // 圆屏那颗键能长按 3 秒触发，就必须也能撤销：两边能力不对称时，
        // 用户在圆屏上按完会发现 App 里找不到那条"取消"
        CommandType.sos_cancel -> { cancelSos(onCancelResult); true }
        CommandType.card_next -> stepCard(args?.intOf("dir") ?: 1)
        CommandType.card_auto -> {
            uiCardOverride = null
            log("屏请求交回策略 · 定卡权回到优先序")
            true
        }
        CommandType.poke -> {
            lastPokeMs = clockMs()
            // 戳一下是"现场动作"，比设置页里钉住的那张脸更近，所以这一路清掉表情钉住。
            // 不清的话演示现场会出现"戳了没反应"，而那条现象和 #31 那类分叉根本分不开
            uiEmotionOverride = null
            log("屏被戳了一下 · 换一张会动的脸")
            true
        }
        CommandType.page_report -> reportScreenPage(args?.stringOf("page"))
        CommandType.mic_chunk -> ingestMicChunk(args)
        else -> false
    }

    /**
     * v4 上行：屏把按住说话那一段 PCM 推上来。拼齐一段就交 [onMicSegment]，
     * 转写和入对话归宿主 —— core 不知道网关在哪。
     *
     * **帧的形状对齐 v3 下行**（同一组 args 键，2560 字节一块），但值域按屏侧上行口径卡死：
     * 8 秒 = 100 帧 = 256 KB，超出/缺号/采样率不对整段作废 —— 半句语音比没有更难解释。
     */
    private val micCollector = MicUplink.Collector()

    /** 拼齐一段上行语音。宿主拿它封 WAV → 转写 → `dialog{dir:"up"}` 走对话那条线 */
    var onMicSegment: (MicUplink.Segment) -> Unit = {}

    private fun ingestMicChunk(args: JsonObject?): Boolean {
        val turnId = args?.stringOf("turn_id")
        val b64 = args?.stringOf("b64")
        if (turnId.isNullOrBlank() || b64.isNullOrEmpty()) {
            log("mic_chunk 缺 turn_id 或 b64，这一帧扔掉")
            return false
        }
        val seq = args.intOf("seq") ?: -1
        val total = args.intOf("total") ?: -1
        val rate = args.intOf("rate") ?: -1
        val chunk = runCatching { java.util.Base64.getDecoder().decode(b64) }.getOrElse {
            log("mic_chunk 的 b64 解不开，这一帧扔掉")
            return false
        }
        return when (val r = micCollector.ingest(turnId, seq, total, rate, chunk)) {
            is MicUplink.Collector.Result.More -> true
            is MicUplink.Collector.Result.Done -> {
                log("上行 m-段收齐：${r.segment.frames} 帧、${r.segment.pcm.size} 字节（%.1f 秒语音）".format(r.segment.seconds))
                onMicSegment(r.segment)
                true
            }
            is MicUplink.Collector.Result.Reject -> {
                log("上行语音作废：${r.reason}")
                false
            }
        }
    }

    /**
     * 屏报上来它自己在哪一页。**这不是申请，手机不许驳回也不许改判** ——
     * 定页权已经交出去了，这一条只是让手机上那块镜像别再替屏编一个位置。
     *
     * 认不出来的页名回 false：屏发了一条手机上没有的页，两种可能都足以要命
     * （固件加了一页而契约没跟，或者 args 拼错了），而这两种在屏上都看不出任何异常。
     * 让它进日志，比让它静默变成「手机以为屏还在上一页」好
     */
    private fun reportScreenPage(raw: String?): Boolean {
        val page = raw?.let { name -> ScreenPage.entries.firstOrNull { it.name == name } }
        if (page == null) {
            log("屏报的页不在契约里：${raw ?: "没带 page"}")
            return false
        }
        if (page == screenPage) return true
        screenPage = page
        _status.value = _status.value.copy(screenPage = page)
        log("屏现在在 $page 页")
        return true
    }

    /** args 里取一个整数。屏发歪了（缺键、null、不是数字）一律当没发，由调用方拿缺省 */
    private fun JsonObject.intOf(key: String): Int? = this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    /** args 里取一个字符串。同上：缺键或不是标量就当没发，交给调用方判 */
    private fun JsonObject.stringOf(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    /** 切卡请求。SOS 正在走的时候顶回去：那种时候屏上唯一该说的话是倒计时 */
    private fun stepCard(dir: Int): Boolean {
        val snap = lastSnap ?: return false
        val sos = snap.sos.level
        if (sos == SosLevel.armed || sos == SosLevel.pending || sos == SosLevel.sent) {
            log("切卡请求被拒 · 呼救还没走完，屏上只能留倒计时")
            return false
        }
        val now = uiCardOverride ?: lastDecision?.card ?: UiCard.status
        val at = swipeCycle.indexOf(now)
        val next = swipeCycle[((if (at < 0) 0 else at) + dir).mod(swipeCycle.size)]
        uiCardOverride = next
        log("屏请求切卡 · ${if (dir >= 0) "往右" else "往左"} → $next")
        return true
    }

    /**
     * 每一拍重算一次"这张圆屏此刻该说哪件事"，并把决定写回引擎和这一帧。
     *
     * 只在跳变时记一行日志：1Hz 写"屏现在画 status"会把日志刷满，
     * 而彩排时真正要问的是"它凭什么在这一秒切成 sat"。
     */
    private fun noteScreenCard(snap: StatePayload, nowMs: Long): StatePayload {
        val d = CardPolicy.decide(snap, nowMs, lastDialogDownMs, uiCardOverride, lastPokeMs, lastNotableMs)
        val downAt = lastDialogDownMs
        if (dialogMood != null && (downAt == null || nowMs - downAt >= CardPolicy.DIALOG_FRESH_MS)) {
            dialogMood = null
        }
        // 只有对话卡才吃答句那张脸：这句话说完 30 秒之后屏上换的是别的事，
        // 让一句话的表情挂到下一张卡上，观众看到的是一句好消息配一张担心的脸
        val mood = uiEmotionOverride ?: if (d.card == UiCard.dialog) dialogMood ?: d.emotion else d.emotion
        val ui = UiState(d.card, mood, screenTzMin, screenPage)
        engine.onUiChanged(ui)
        if (d.card != lastDecision?.card || d.why != lastDecision?.why) {
            log("屏画 ${d.card} · ${ui.emotion?.name}｜${d.why}")
        }
        lastDecision = d
        lastSnap = snap
        // 只有"有事要说"才刷新静默计时。兜底那两支不算，否则钟面永远到不了：
        // 没事发生时每秒都在把自己算成新事件
        if (d.notable) lastNotableMs = nowMs
        return snap.copy(ui = ui)
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

    /**
     * 三类"该在屏上弹一条横幅"的边沿：过境窗口打开、围栏越界/回来、平台下行。
     * 契约那 11 个 kind 里其余的各有出处（link_switched / track_reset 由引擎的 LinkEvent 出，
     * sos_* 由那两个按钮出），这三个此前**没有任何发送方** —— 屏接了 on_event 也永远不弹，
     * 那就是"几个画面在轮播"的由来。
     *
     * 为什么做在 1Hz 这一拍、拿上一拍的快照比，而不是塞进 DemoEngine：
     * 引擎是状态机，它管报文；"这件事值不值得打扰屏幕上的人"是展示层的判断。
     * 而且屏可能压根没连，边沿判定放这儿只是多比三个布尔。
     *
     * 下行那条走的是轮询 [DemoEngine.lastDownlinkHex]，不是再注册一个 onDownlink：
     * SimulatedModem 那份实现里 handler 只有一个槽（`downlink = handler`），
     * 在这里再注册一次会把引擎自己记 hex 那一行顶掉。
     */
    private fun noteScreenEvents(snap: StatePayload) {
        val inWindow = snap.sat.nextPassInS?.let { it <= 0 } == true
        if (inWindow && lastInPassWindow == false) {
            push(
                MsgType.event, EventKind.sat_visible,
                "sat_name" to (snap.sat.satName?.let { JsonPrimitive(it) } ?: JsonNull),
                "dur_s" to (snap.sat.nextPassDurS?.let { JsonPrimitive(it) } ?: JsonNull),
                "max_el" to (snap.sat.nextPassMaxEl?.let { JsonPrimitive(it) } ?: JsonNull),
            )
            log("进入过境窗口${snap.sat.satName?.let { " $it" } ?: ""}，已推给展示端")
        }
        lastInPassWindow = inWindow

        if (snap.fence.active) {
            val prev = lastFenceInside
            if (prev != null && prev != snap.fence.inside) {
                push(
                    MsgType.event,
                    if (snap.fence.inside) EventKind.fence_enter else EventKind.fence_exit,
                    "name" to (snap.fence.name?.let { JsonPrimitive(it) } ?: JsonNull),
                    "dist_m" to (snap.fence.distM?.let { JsonPrimitive(it) } ?: JsonNull),
                )
                log(if (snap.fence.inside) "回到围栏${snap.fence.name?.let { " $it" } ?: ""}"
                    else "已越界${snap.fence.name?.let { " $it" } ?: ""}，距边界 ${snap.fence.distM ?: "?"} 米")
            }
            lastFenceInside = snap.fence.inside
        } else {
            // 关掉围栏期间不攒边沿：重新打开那一拍不算"越界"，那只是设置动作
            lastFenceInside = null
        }

        val hex = engine.lastDownlinkHex
        if (hex != null && hex != lastDownlinkSeen) {
            lastDownlinkSeen = hex
            push(
                MsgType.event, EventKind.downlink_msg,
                "hex" to JsonPrimitive(hex),
                "text" to (printableFromHex(hex)?.let { JsonPrimitive(it) } ?: JsonNull),
            )
            log("平台下行 $hex")
        }
    }

    /** 解得开、而且全是可打印字符才给屏上看；一串方块/控制字符上屏等于在演"设备坏了" */
    private fun printableFromHex(hex: String): String? {
        val s = runCatching { String(hexToBytes(hex), Charsets.UTF_8) }.getOrNull()
        if (s.isNullOrEmpty()) return null
        if (s.any { it < ' ' || it.code == 0x7F }) return null
        return s
    }

    private var lastInPassWindow: Boolean? = null
    private var lastFenceInside: Boolean? = null
    private var lastDownlinkSeen: String? = null

    /**
     * 这条提示**还剩多少毫秒值得弹**，按 kind 定，所以这是一张表而不是各调用点的一个参数。
     *
     * 收在这儿而不是各调用点各写一个数：漏写一个，屏重连之后就会弹一条几轮之前的
     * 「卫星应答了」—— 而现场没人能解释那是什么。判定在屏那边做（它没有本地时钟，
     * 拿整帧 ts 和上一帧 state 的 ts 对表），手机端只负责把 deadline 给出去。
     */
    private val ttlByKind: Map<EventKind, Long> = mapOf(
        EventKind.sos_triggered to 15_000,
        EventKind.sos_acked to 15_000,
        EventKind.sos_failed to 15_000,
        EventKind.sos_cancelled to 15_000,
        EventKind.fence_enter to 10_000,
        EventKind.fence_exit to 10_000,
        EventKind.sentinel_motion to 10_000,
        EventKind.link_switched to 8_000,
        EventKind.sat_visible to 30_000,
        EventKind.downlink_msg to 30_000,
        EventKind.track_reset to 5_000,
    )

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
                    event = EventPayload(
                        kind = kind,
                        eventId = "",
                        payload = JsonObject(linkedMapOf(*fields)),
                        ttlMs = ttlByKind[kind],
                    ),
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
            it.copy(seq = n, event = it.event?.copy(eventId = "evt-$bootTag-$n"))
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
