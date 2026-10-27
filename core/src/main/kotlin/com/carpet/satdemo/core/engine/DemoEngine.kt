package com.carpet.satdemo.core.engine

import com.carpet.satdemo.core.link.CellStatus
import com.carpet.satdemo.core.link.LinkEvent
import com.carpet.satdemo.core.link.LinkPhase
import com.carpet.satdemo.core.link.LinkPolicy
import com.carpet.satdemo.core.link.LinkSignal
import com.carpet.satdemo.core.link.LinkStateMachine
import com.carpet.satdemo.core.modem.SatLock
import com.carpet.satdemo.core.modem.SatResult
import com.carpet.satdemo.core.modem.SatelliteModem
import com.carpet.satdemo.core.protocol.EventKind
import com.carpet.satdemo.core.protocol.EventPayload
import com.carpet.satdemo.core.protocol.FenceState
import com.carpet.satdemo.core.protocol.GnssSource
import com.carpet.satdemo.core.protocol.GnssState
import com.carpet.satdemo.core.protocol.LinkPrimary
import com.carpet.satdemo.core.protocol.LinkState
import com.carpet.satdemo.core.protocol.MsgType
import com.carpet.satdemo.core.protocol.SatPhase
import com.carpet.satdemo.core.protocol.SatState
import com.carpet.satdemo.core.protocol.ScreenMessage
import com.carpet.satdemo.core.protocol.SentinelState
import com.carpet.satdemo.core.protocol.SosFrame
import com.carpet.satdemo.core.protocol.SosFrameEncoder
import com.carpet.satdemo.core.protocol.SosLevel
import com.carpet.satdemo.core.protocol.SosState
import com.carpet.satdemo.core.protocol.StatePayload
import com.carpet.satdemo.core.protocol.toHex
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 把链路状态机、卫星模块、SOS 流程拧成一股的中间层。
 *
 * 刻意不碰 Android API，也不碰 socket —— App 只负责把传感器读数喂进来、
 * 把 [snapshot] 推给展示端。这样整条业务链在 JVM 单测里就能跑完，
 * 不用等真机、不用模拟器、不用卫星。
 */
class DemoEngine(
    private val modem: SatelliteModem,
    ownerId: Long,
    val policy: LinkPolicy = LinkPolicy(),
) {
    private val machine = LinkStateMachine(policy)
    private val encoder = SosFrameEncoder(ownerId)

    var phase: LinkPhase = machine.phase
        private set

    /** 离线期间攒着等补发的报文。RECOVER 态由 App 刷出去 */
    val pending: ArrayDeque<String> = ArrayDeque()

    /** 平台下发的最后一条原始报文，SOS 回执靠它 */
    var lastDownlinkHex: String? = null
        private set

    private var fix: GnssState? = null
    private var phoneBatteryPct = 100
    private var fence = FenceState(active = false, inside = true)
    private var sentinel = SentinelState()
    private var demoMode = false
    private var sosLevel = SosLevel.normal
    private var sosSinceTs: Long? = null
    private var sosAttempts = 0
    private var lock: SatLock? = null
    private var seq = 0L

    init {
        modem.onSatelliteLock { lock = it }
        modem.onDownlink { lastDownlinkHex = it }
    }

    fun onCellStatus(status: CellStatus, nowMs: Long): List<ScreenMessage> =
        emit(machine.onSignal(LinkSignal.Cell(status, nowMs)), nowMs)

    fun onTick(nowMs: Long): List<ScreenMessage> =
        emit(machine.onSignal(LinkSignal.Tick(nowMs)), nowMs)

    fun onSatAck(nowMs: Long, ok: Boolean): List<ScreenMessage> =
        emit(machine.onSignal(LinkSignal.SatAck(nowMs, ok)), nowMs)

    /** 手机 GPS 或 GT1005 GNSS 出了个定位 */
    fun onFix(lat: Double, lon: Double, source: GnssSource, hdop: Double?, fixAgeSec: Double?) {
        fix = GnssState(lat, lon, altM = null, hdop = hdop, source = source, fixAgeS = fixAgeSec)
    }

    fun onPhoneBattery(pct: Int) {
        phoneBatteryPct = pct.coerceIn(0, 100)
    }

    fun onDemoModeChanged(on: Boolean) {
        demoMode = on
    }

    fun armSentinel(armed: Boolean) {
        sentinel = sentinel.copy(armed = armed)
    }

    /** 越界距离由调用方算好传进来，core 不引地理库 */
    fun onFenceDistance(distM: Double) {
        fence = fence.copy(inside = distM >= 0, distM = distM)
    }

    fun setFence(active: Boolean, name: String?) {
        fence = fence.copy(active = active, inside = true, name = name, distM = null)
    }

    fun fenceBreached(): Boolean = fence.active && !fence.inside

    /**
     * 心跳。CELL 态由 App 自己走蜂窝上报，这里只处理卫星那一路；
     * 模块没收下就进 [pending]，别丢。
     */
    suspend fun heartbeat(nowSec: Long, cellOk: Boolean): Boolean {
        val f = fix ?: return false
        val hex = encoder.encode(
            SosFrame.MSG_HEARTBEAT, f.lat, f.lon, nowSec, phoneBatteryPct, flags = frameFlags(cellOk),
        ).toHex()
        if (phase == LinkPhase.CELL) return true
        if (modem.enqueueSfq(hex)) return true
        pending.addLast(hex)
        return false
    }

    /**
     * SOS。不进链路状态机、不等蜂窝判定 —— 见 protocol/link-policy.md「SOS 例外」。
     * 没有定位时也照样要发，用 flags.bit2 标定位已过期，让平台知道这条不可信到什么程度。
     */
    suspend fun triggerSos(nowSec: Long, cellOk: Boolean): SosResult {
        sosLevel = SosLevel.pending
        sosSinceTs = nowSec * 1000
        sosAttempts++

        val f = fix
        val lat = f?.lat ?: 0.0
        val lon = f?.lon ?: 0.0
        val hex = encoder.encode(SosFrame.MSG_SOS, lat, lon, nowSec, phoneBatteryPct, flags = frameFlags(cellOk)).toHex()

        val result = runCatching { modem.sendMcq(hex) }.getOrDefault(SatResult.FAIL)
        sosLevel = if (result == SatResult.COMPLETE) SosLevel.acked else SosLevel.sent
        return SosResult(result, hex, f != null)
    }

    data class SosResult(val result: SatResult, val frameHex: String, val hasFix: Boolean)

    fun snapshot(nowMs: Long): StatePayload = StatePayload(
        link = LinkState(
            primary = if (phase == LinkPhase.CELL || phase == LinkPhase.RECOVER) LinkPrimary.cell else LinkPrimary.sat,
            cellOk = phase == LinkPhase.CELL || phase == LinkPhase.RECOVER,
            cellDbm = null,
            satOk = modem.available && lock != null,
        ),
        sat = SatState(
            phase = satPhase(),
            satName = lock?.name,
            cnr = lock?.cnr,
            nextPassInS = null,
            moduleBattery = null,
        ),
        gnss = fix ?: GnssState(0.0, 0.0, altM = null, hdop = null, source = GnssSource.phone, fixAgeS = null),
        fence = fence,
        sos = SosState(sosLevel, sosSinceTs, sosAttempts.takeIf { it > 0 }),
        sentinel = sentinel,
        phoneBattery = phoneBatteryPct,
        demoMode = demoMode,
    )

    private fun satPhase(): SatPhase = when {
        !modem.available -> SatPhase.idle
        phase == LinkPhase.PROBE -> SatPhase.searching
        lock != null -> SatPhase.locked
        pending.isNotEmpty() -> SatPhase.tx_pending
        else -> SatPhase.idle
    }

    private fun frameFlags(cellOk: Boolean): Int {
        var flags = 0
        if (cellOk) flags = flags or SosFrame.FLAG_DUAL
        val f = fix
        if (f != null && f.source != GnssSource.phone) flags = flags or SosFrame.FLAG_SRC_GT1005
        // 没定位和定位超过 30s 都算过期，展示端要说清楚
        if (f == null || (f.fixAgeS ?: 999.0) > 30.0) flags = flags or SosFrame.FLAG_STALE_FIX
        if (sentinel.armed) flags = flags or SosFrame.FLAG_SENTINEL_ARMED
        return flags
    }

    private fun emit(events: List<LinkEvent>, nowMs: Long): List<ScreenMessage> {
        phase = machine.phase
        if (events.isEmpty()) return emptyList()
        return events.mapNotNull { toMessage(it, nowMs) }
    }

    private fun toMessage(e: LinkEvent, nowMs: Long): ScreenMessage? = when (e) {
        is LinkEvent.PhaseChanged -> event(
            nowMs,
            EventKind.link_switched,
            "from" to JsonPrimitive(e.from.name.lowercase()),
            "to" to JsonPrimitive(e.to.name.lowercase()),
            "at_ms" to JsonPrimitive(e.atMs),
        )
        is LinkEvent.FlushRequested -> event(
            nowMs,
            EventKind.track_reset,
            "pending" to JsonPrimitive(pending.size),
        )
        // 探测包是内部动作，展示端只关心相位变化，别把噪声推过去
        is LinkEvent.ProbeRequested, is LinkEvent.ProbeTimedOut -> null
    }

    private fun event(nowMs: Long, kind: EventKind, vararg fields: Pair<String, JsonElement>): ScreenMessage =
        ScreenMessage(
            t = MsgType.event,
            seq = seq++,
            ts = nowMs,
            event = EventPayload(
                kind = kind,
                eventId = "evt-${seq}",
                payload = JsonObject(linkedMapOf(*fields)),
            ),
        )
}
