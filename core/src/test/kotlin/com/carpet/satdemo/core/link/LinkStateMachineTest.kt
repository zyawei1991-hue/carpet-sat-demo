package com.carpet.satdemo.core.link

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 演示脚本依赖这几条迁移路径，改动前先看这里的断言。
 * 时间全部注入，不需要 sleep，也不需要真机。
 */
class LinkStateMachineTest {

    private val good = CellStatus(hasInternetCapability = true, validated = true, signalLevel = 4)
    private val dead = CellStatus(hasInternetCapability = false, validated = false, signalLevel = 0)
    private val weak = CellStatus(hasInternetCapability = true, validated = true, signalLevel = 1)

    /** 每 stepMs 喂一次蜂窝观测，返回沿途发生的事件 */
    private fun walk(
        machine: LinkStateMachine,
        status: (Long) -> CellStatus,
        fromMs: Long,
        toMs: Long,
        stepMs: Long,
    ): List<LinkEvent> {
        val out = ArrayList<LinkEvent>()
        var t = fromMs
        while (t <= toMs) {
            out += machine.onSignal(LinkSignal.Cell(status(t), t))
            out += machine.onSignal(LinkSignal.Tick(t))
            t += stepMs
        }
        return out
    }

    private fun phases(events: List<LinkEvent>) =
        events.filterIsInstance<LinkEvent.PhaseChanged>().map { it.to }

    @Test
    fun `cell to probe needs the full confirmation window, not one bad reading`() {
        val sm = LinkStateMachine()
        assertEquals(LinkPhase.CELL, sm.phase)

        // 单点抖动：坏一次又被好读数冲掉，不该切
        walk(sm, { if (it == 5_000L) dead else good }, 0, 15_000, 1_000)
        assertEquals(LinkPhase.CELL, sm.phase)

        // 持续坏，未满 20s 仍留在 CELL
        val until19 = walk(sm, { dead }, 20_000, 38_000, 1_000)
        assertEquals(LinkPhase.CELL, sm.phase)
        assertTrue(phases(until19).isEmpty())

        // 跨过 20s 才进 PROBE，并立刻要求发探测包
        val until21 = walk(sm, { dead }, 38_000, 41_000, 1_000)
        assertEquals(LinkPhase.PROBE, sm.phase)
        assertEquals(listOf(LinkPhase.PROBE), phases(until21))
        assertTrue(until21.any { it is LinkEvent.ProbeRequested })
    }

    @Test
    fun `consecutive probe failures count as hard down but still need the window`() {
        val sm = LinkStateMachine()
        val flapping = CellStatus(
            hasInternetCapability = true,
            validated = true,
            signalLevel = 4,
            consecutiveProbeFailures = 3,
        )
        // 「真不通」走 20s 确认窗口，单次计数抖动不该把卫星通道占住
        walk(sm, { flapping }, 0, 15_000, 1_000)
        assertEquals(LinkPhase.CELL, sm.phase)

        walk(sm, { flapping }, 15_000, 25_000, 1_000)
        assertEquals(LinkPhase.PROBE, sm.phase)
    }

    @Test
    fun `probe reaches sat on ack and recovers to cell on drain`() {
        val sm = LinkStateMachine()
        walk(sm, { dead }, 0, 25_000, 1_000)
        assertEquals(LinkPhase.PROBE, sm.phase)

        val acked = sm.onSignal(LinkSignal.SatAck(30_000, ok = true))
        assertEquals(listOf(LinkPhase.SAT), phases(acked))

        // 蜂窝回来了，但要攒够 30s 才认定恢复（非对称迟滞，防抖动来回切）
        val back = walk(sm, { good }, 30_000, 65_000, 1_000)
        assertEquals(LinkPhase.RECOVER, sm.phase)
        assertTrue(back.any { it is LinkEvent.FlushRequested }, "进 RECOVER 就要触发补发")

        val drained = sm.onSignal(LinkSignal.QueueDrained(60_000))
        assertEquals(listOf(LinkPhase.CELL), phases(drained))
    }

    @Test
    fun `probe timeout still hands over to sat`() {
        val sm = LinkStateMachine()
        walk(sm, { dead }, 0, 25_000, 1_000)
        assertEquals(LinkPhase.PROBE, sm.phase)

        // 探测发出后 satAckTimeoutMs 内没有任何 ACK
        val late = sm.onSignal(LinkSignal.Tick(25_000 + 61_000))
        assertTrue(late.any { it is LinkEvent.ProbeTimedOut })
        assertEquals(LinkPhase.SAT, sm.phase, "卫星不通也得切，否则离线期间一条都发不出去")
    }

    @Test
    fun `low signal is a suspect reading and counts toward the switch`() {
        val sm = LinkStateMachine()
        walk(sm, { weak }, 0, 25_000, 1_000)
        assertEquals(LinkPhase.PROBE, sm.phase, "等级 <=1 持续满窗口就该提前切，避免等到彻底断")
    }

    @Test
    fun `probe is rate limited while awaiting ack`() {
        val sm = LinkStateMachine(LinkPolicy(probeIntervalCellMs = 1_000))
        var probes = 0
        walk(sm, { good }, 0, 10_000, 500).forEach { if (it is LinkEvent.ProbeRequested) probes++ }
        // 发了探测包没收到 ACK 就不再叠探测，避免把卫星通道占满
        assertTrue(probes <= 2, "10s 内探测次数异常：$probes")
        assertEquals(LinkPhase.CELL, sm.phase)
    }

    @Test
    fun `policy thresholds are honoured when shortened for rehearsal`() {
        val fast = LinkStateMachine(LinkPolicy(cellBadConfirmMs = 5_000, cellGoodConfirmMs = 5_000))
        walk(fast, { dead }, 0, 8_000, 1_000)
        assertEquals(LinkPhase.PROBE, fast.phase)
        fast.onSignal(LinkSignal.SatAck(9_000, ok = true))
        walk(fast, { good }, 9_000, 17_000, 1_000)
        assertEquals(LinkPhase.RECOVER, fast.phase)
    }
}
