package com.carpet.satdemo.core.runtime

import com.carpet.satdemo.core.engine.DemoEngine
import com.carpet.satdemo.core.link.CellStatus
import com.carpet.satdemo.core.link.LinkPhase
import com.carpet.satdemo.core.modem.SatResult
import com.carpet.satdemo.core.modem.SimulatedModem
import com.carpet.satdemo.core.modem.SosProgress
import com.carpet.satdemo.core.protocol.AckPayload
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    /** 展示端推上来的动作（圆屏上的按键、语音意图），由 Activity 接线 */
    var onCommand: (ScreenMessage) -> Unit = {}

    /** 每一批出站消息都过这里。演示驱动器用它录成 jsonl，回放给展示端 UI 看 */
    var onSend: (List<ScreenMessage>) -> Unit = {}

    data class Status(
        val running: Boolean = false,
        val phase: LinkPhase = LinkPhase.CELL,
        val snapshot: StatePayload? = null,
        val clients: Int = 0,
        val lastSos: SatResult? = null,
        val localIp: String? = null,
        val log: List<String> = emptyList(),
    )

    /**
     * @param autoTick false = 只起服务和接线，1Hz 那圈由调用方推。
     *   演示驱动器要自己控制假时钟，不能再让内部循环掺进来跳。
     */
    fun start(autoTick: Boolean = true) {
        if (loop != null) return
        server.onMessage = { msg ->
            if (msg.t == MsgType.command) onCommand(msg)
            _status.value = _status.value.copy(clients = server.clientCount)
        }
        server.onHello = { msg ->
            val hello = requireNotNull(msg.hello)
            val ok = hello.protocols?.contains(ScreenCodec.CONTRACT_VERSION) ?: true
            log("展示端接入 ${hello.screenId} fw=${hello.fw}" + if (ok) "" else "（契约不匹配）")
            ackNow(
                forSeq = msg.seq,
                screenId = hello.screenId,
                error = if (ok) null else "proto_mismatch",
            )
            pushState()
        }
        engine.onSosProgress = { p ->
            if (p is SosProgress.SatSending && p.attempt > 1) log("SOS 第 ${p.attempt} 次重发")
        }
        server.start()
        _status.value = _status.value.copy(running = true, localIp = firstLanAddress())
        log("展示端接入点 ws://${_status.value.localIp}:${ScreenLinkServer.PORT}")
        if (autoTick) loop = scope.launch { tickForever() }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        server.stop()
        _status.value = _status.value.copy(running = false, clients = 0)
        log("服务停止")
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

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
        log(
            "SOS ${out.result} ×${out.attempts}${if (out.hasFix) "" else "（无定位）"} ${out.frameHex}" +
                (out.nextPassInS?.let { "，下一条过顶 ${it / 60}:${it % 60}" } ?: ""),
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
        push(engine.drainProbe(now))
        push(engine.drainPending(now))
        engine.nextPassInS = runCatching { engine.modem.secondsToNextPass() }.getOrNull()
        counter++
        if (counter % heartbeatEvery == 0 && !engine.heartbeat(nowSec = now / 1000, cellOk = cellOk())) {
            log("心跳进离线队列，共 ${engine.pending.size} 条")
        }
        val snap = engine.snapshot()
        // 1Hz 全量状态。断线时展示端保留最后一帧，所以这条同时也是它的"还活着"信号
        push(listOf(ScreenMessage(t = MsgType.state, seq = seq.getAndIncrement(), ts = now, state = snap)))
        _status.value = _status.value.copy(
            phase = engine.phase,
            snapshot = snap,
            clients = server.clientCount,
        )
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

        fun firstLanAddress(): String? = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }
}
