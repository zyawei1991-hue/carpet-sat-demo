package com.carpet.satdemo.app

import com.carpet.satdemo.core.engine.DemoEngine
import com.carpet.satdemo.core.link.CellStatus
import com.carpet.satdemo.core.link.LinkPhase
import com.carpet.satdemo.core.modem.SatResult
import com.carpet.satdemo.core.modem.SimulatedModem
import com.carpet.satdemo.core.protocol.EventKind
import com.carpet.satdemo.core.protocol.EventPayload
import com.carpet.satdemo.core.protocol.GnssSource
import com.carpet.satdemo.core.protocol.MsgType
import com.carpet.satdemo.core.protocol.ScreenMessage
import com.carpet.satdemo.core.protocol.StatePayload
import java.net.Inet4Address
import java.net.NetworkInterface
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
    ownerId: Long = DEFAULT_OWNER_ID,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    val engine = DemoEngine(SimulatedModem(), ownerId)
    private val server = ScreenLinkServer()

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    /** 展示端推上来的动作（圆屏上的按键、语音意图），由 Activity 接线 */
    var onCommand: (ScreenMessage) -> Unit = {}

    data class Status(
        val running: Boolean = false,
        val phase: LinkPhase = LinkPhase.CELL,
        val snapshot: StatePayload? = null,
        val clients: Int = 0,
        val lastSos: SatResult? = null,
        val localIp: String? = null,
        val log: List<String> = emptyList(),
    )

    fun start() {
        if (loop != null) return
        server.onMessage = { msg ->
            if (msg.t == MsgType.command) onCommand(msg)
            _status.value = _status.value.copy(clients = server.clientCount)
        }
        server.start()
        _status.value = _status.value.copy(running = true, localIp = firstLanAddress())
        log("展示端接入点 ws://${_status.value.localIp}:${ScreenLinkServer.PORT}")
        loop = scope.launch { tickForever() }
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
        push(engine.onCellStatus(sample, System.currentTimeMillis()))
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
        val out = engine.triggerSos(nowSec = System.currentTimeMillis() / 1000, cellOk = cellOk())
        _status.value = _status.value.copy(lastSos = out.result)
        log("SOS ${out.result}${if (out.hasFix) "" else "（无定位）"} ${out.frameHex}")
        push(
            MsgType.event,
            EventKind.sos_triggered,
            "frame_hex" to JsonPrimitive(out.frameHex),
            "has_fix" to JsonPrimitive(out.hasFix),
            "sat_result" to JsonPrimitive(out.result.name.lowercase()),
        )
    }

    private fun cellOk() = lastCell.usable

    private var lastCell = CellStatus(hasInternetCapability = false, validated = false, signalLevel = 0)
    private var seq = 0L

    private suspend fun tickForever() {
        var counter = 0
        while (true) {
            val now = System.currentTimeMillis()
            push(engine.onTick(now))
            if (counter % HEARTBEAT_EVERY == 0 && !engine.heartbeat(nowSec = now / 1000, cellOk = cellOk())) {
                log("心跳进离线队列，共 ${engine.pending.size} 条")
            }
            counter++
            val snap = engine.snapshot(now)
            // 1Hz 全量状态。断线时展示端保留最后一帧，所以这条同时也是它的"还活着"信号
            push(
                listOf(
                    ScreenMessage(
                        t = MsgType.state,
                        seq = seq++,
                        ts = now,
                        state = snap,
                    ),
                ),
            )
            _status.value = _status.value.copy(
                phase = engine.phase,
                snapshot = snap,
                clients = server.clientCount,
            )
            delay(1_000)
        }
    }

    private fun push(
        t: MsgType,
        kind: EventKind,
        vararg fields: Pair<String, JsonElement>,
    ) {
        val id = "evt-${seq + 1}"
        push(
            listOf(
                ScreenMessage(
                    t = t,
                    seq = seq++,
                    ts = System.currentTimeMillis(),
                    event = EventPayload(kind = kind, eventId = id, payload = JsonObject(linkedMapOf(*fields))),
                ),
            ),
        )
    }

    private fun push(messages: List<ScreenMessage>) {
        if (messages.isEmpty()) return
        server.broadcast(messages)
    }

    fun log(line: String) {
        _status.value = _status.value.copy(log = (_status.value.log + line).takeLast(40))
    }

    companion object {
        /** 占位。真实值等时空道宇分配车主 ID 段位，见 docs/DECISIONS.md D3 的外部依赖 */
        const val DEFAULT_OWNER_ID = 12_061L
        private const val HEARTBEAT_EVERY = 60

        fun firstLanAddress(): String? = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }
}
