package com.carpet.satdemo.core.link

/**
 * 4G / 卫星 链路切换状态机。策略见 protocol/link-policy.md。
 *
 * 四态而不是两态：PROBE 和 RECOVER 是演示时"有戏"的瞬间，
 * 展示端要靠它们演"正在尝试卫星""正在补发离线期间 N 条数据"。
 *
 * 不做任何 IO，不依赖 Android，时间从外部注入 —— 全部行为可单测。
 */
enum class LinkPhase { CELL, PROBE, SAT, RECOVER }

data class LinkPolicy(
    /** 判据持续多久才认定蜂窝不可用 */
    val cellBadConfirmMs: Long = 20_000,
    /** 判据持续多久才认定蜂窝恢复 */
    val cellGoodConfirmMs: Long = 30_000,
    /** 信号等级 <=1 持续多久算"预感要不通"，提前切避免丢包 */
    val lowSignalGraceMs: Long = 15_000,
    /** 低信号阈值。Android 各厂商 getLevel() 口径不一，所以不用 dBm 绝对值 */
    val lowSignalLevel: Int = 1,
    /** 连续几次探测失败直接判定不可用，不等确认窗口 */
    val consecutiveFailuresForBad: Int = 3,
    val probeIntervalCellMs: Long = 30_000,
    val probeIntervalSatMs: Long = 10_000,
    /** 卫星发出后等多久算无 ACK */
    val satAckTimeoutMs: Long = 60_000,
    val sosMaxAttempts: Int = 3,
)

/** 蜂窝侧一次观测。由 App 侧的 ConnectivityManager + 主动探测汇总而来。 */
data class CellStatus(
    val hasInternetCapability: Boolean,
    val validated: Boolean,
    val signalLevel: Int,
    val consecutiveProbeFailures: Int = 0,
) {
    val usable: Boolean
        get() = hasInternetCapability && validated

    /** 「真不通」：系统认为没网，或探测包连续超时 */
    fun hardDown(p: LinkPolicy): Boolean =
        !hasInternetCapability || consecutiveProbeFailures >= p.consecutiveFailuresForBad

    /** 「预感要不通」：还连着但信号等级贴地，提前切避免丢包 */
    fun lowSignal(p: LinkPolicy): Boolean = signalLevel <= p.lowSignalLevel

    fun suspect(p: LinkPolicy): Boolean = hardDown(p) || lowSignal(p)

    /** 判据要持续多久才认定不可用。低信号给更短的窗口 */
    fun confirmWindowMs(p: LinkPolicy): Long = if (hardDown(p)) p.cellBadConfirmMs else p.lowSignalGraceMs
}

sealed interface LinkSignal {
    data class Cell(val status: CellStatus, val atMs: Long) : LinkSignal
    data class SatAck(val atMs: Long, val ok: Boolean) : LinkSignal
    /** 离线队列刷完，RECOVER 可以收尾 */
    data class QueueDrained(val atMs: Long) : LinkSignal
    data class Tick(val atMs: Long) : LinkSignal
}

sealed interface LinkEvent {
    data class PhaseChanged(val from: LinkPhase, val to: LinkPhase, val atMs: Long) : LinkEvent
    /** 需要向卫星发一个探测包 */
    data class ProbeRequested(val atMs: Long) : LinkEvent
    /** 需要把离线缓存补发出去 */
    data class FlushRequested(val atMs: Long) : LinkEvent
    /** 探测包超时没等到 ACK */
    data class ProbeTimedOut(val atMs: Long) : LinkEvent
}

class LinkStateMachine(
    val policy: LinkPolicy = LinkPolicy(),
) {
    var phase: LinkPhase = LinkPhase.CELL
        private set

    private var badSinceMs: Long? = null
    private var goodSinceMs: Long? = null
    private var lastProbeAtMs: Long? = null
    private var probeAwaitingAck = false

    /** 只读，给展示端画状态用 */
    fun secondsInCurrentPhase(nowMs: Long): Long? =
        (if (phase == LinkPhase.CELL) goodSinceMs else badSinceMs)?.let { (nowMs - it) / 1000 }

    fun onSignal(signal: LinkSignal): List<LinkEvent> {
        val out = ArrayList<LinkEvent>(2)
        when (signal) {
            is LinkSignal.Cell -> onCell(signal.status, signal.atMs, out)
            is LinkSignal.SatAck -> onSatAck(signal, out)
            is LinkSignal.QueueDrained -> onDrained(signal, out)
            is LinkSignal.Tick -> onTick(signal.atMs, out)
        }
        return out
    }

    private fun onCell(status: CellStatus, nowMs: Long, out: MutableList<LinkEvent>) {
        if (status.suspect(policy)) {
            goodSinceMs = null
            if (badSinceMs == null) badSinceMs = nowMs
        } else if (status.usable) {
            badSinceMs = null
            if (goodSinceMs == null) goodSinceMs = nowMs
        } else {
            // 有能力但未通过校验：既不算坏也不算好，冻结两个计时器
        }

        when (phase) {
            LinkPhase.CELL -> {
                val held = badSinceMs?.let { nowMs - it } ?: 0
                if (held >= status.confirmWindowMs(policy)) goTo(LinkPhase.PROBE, nowMs, out)
            }
            LinkPhase.SAT -> {
                val held = goodSinceMs?.let { nowMs - it } ?: 0
                if (held >= policy.cellGoodConfirmMs) goTo(LinkPhase.RECOVER, nowMs, out)
            }
            else -> Unit
        }
    }

    private fun onSatAck(ack: LinkSignal.SatAck, out: MutableList<LinkEvent>) {
        if (!probeAwaitingAck) return
        probeAwaitingAck = false
        if (phase == LinkPhase.PROBE) {
            // 卫星通了就切，不等蜂窝 —— 蜂窝已经确认坏了才发的探测
            goTo(LinkPhase.SAT, ack.atMs, out)
        }
    }

    private fun onDrained(signal: LinkSignal.QueueDrained, out: MutableList<LinkEvent>) {
        if (phase == LinkPhase.RECOVER) goTo(LinkPhase.CELL, signal.atMs, out)
    }

    private fun onTick(nowMs: Long, out: MutableList<LinkEvent>) {
        when (phase) {
            LinkPhase.PROBE -> {
                if (probeAwaitingAck && lastProbeAtMs != null &&
                    nowMs - lastProbeAtMs!! >= policy.satAckTimeoutMs
                ) {
                    // 卫星也不通：还是得切到 SAT，否则离线期间一条都发不出去
                    out += LinkEvent.ProbeTimedOut(nowMs)
                    goTo(LinkPhase.SAT, nowMs, out)
                }
            }
            LinkPhase.CELL -> maybeProbe(nowMs, policy.probeIntervalCellMs, out)
            LinkPhase.SAT -> maybeProbe(nowMs, policy.probeIntervalSatMs, out)
            LinkPhase.RECOVER -> out += LinkEvent.FlushRequested(nowMs)
        }
    }

    private fun maybeProbe(nowMs: Long, interval: Long, out: MutableList<LinkEvent>) {
        if (probeAwaitingAck) return
        val last = lastProbeAtMs
        if (last != null && nowMs - last < interval) return
        lastProbeAtMs = nowMs
        probeAwaitingAck = true
        out += LinkEvent.ProbeRequested(nowMs)
    }

    private fun goTo(next: LinkPhase, nowMs: Long, out: MutableList<LinkEvent>) {
        if (next == phase) return
        val prev = phase
        phase = next
        probeAwaitingAck = false
        out += LinkEvent.PhaseChanged(prev, next, nowMs)
        if (next == LinkPhase.PROBE) {
            lastProbeAtMs = nowMs
            probeAwaitingAck = true
            out += LinkEvent.ProbeRequested(nowMs)
        }
        if (next == LinkPhase.RECOVER) out += LinkEvent.FlushRequested(nowMs)
    }
}
