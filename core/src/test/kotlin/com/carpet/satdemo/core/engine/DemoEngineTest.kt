package com.carpet.satdemo.core.engine

import com.carpet.satdemo.core.link.CellStatus
import com.carpet.satdemo.core.link.LinkPhase
import com.carpet.satdemo.core.link.LinkPolicy
import com.carpet.satdemo.core.modem.SatPath
import com.carpet.satdemo.core.modem.SatResult
import com.carpet.satdemo.core.modem.SimulatedModem
import com.carpet.satdemo.core.modem.SosCoordinator
import com.carpet.satdemo.core.modem.SosProgress
import com.carpet.satdemo.core.protocol.EventKind
import com.carpet.satdemo.core.protocol.GnssSource
import com.carpet.satdemo.core.protocol.ScreenMessage
import com.carpet.satdemo.core.protocol.SatPhase
import com.carpet.satdemo.core.protocol.SosFrame
import com.carpet.satdemo.core.protocol.SosFrameEncoder
import com.carpet.satdemo.core.protocol.SosLevel
import com.carpet.satdemo.core.protocol.decodeSosFrame
import com.carpet.satdemo.core.protocol.hexToBytes
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 端到端跑一遍演示脚本的主线：有网 -> 断网 -> 切卫星 -> 按 SOS -> 出结果。
 * 全程用 [SimulatedModem]，所以这条测试在没硬件、没手机、没卫星的 CI 上就能跑。
 * 真机到货后把 modem 换成 Uart/Ble 实现，这里的断言不应该改。
 */
class DemoEngineTest {

    /** 假时钟：只在显式 advance 时走，卫星过境窗口因此完全可控 */
    private class FakeClock(startMs: Long = 0) {
        var ms = startMs
            private set

        fun advance(d: Long) {
            ms += d
        }
    }

    private val good = CellStatus(hasInternetCapability = true, validated = true, signalLevel = 4)
    private val dead = CellStatus(hasInternetCapability = false, validated = false, signalLevel = 0)

    /** 睡莲时钟：Instant 跑法，测试里等于时间不流逝 */
    private fun instant(): suspend (Long) -> Unit = { }

    /**
     * 过境窗口用 passWindowMs 直接调到"一直可见"/"一直不可见"，
     * 不去掐绝对时间戳 —— 依赖 epoch 取模的测试换个起始时间就会假失败。
     */
    private fun newModem(clock: FakeClock, visible: Boolean = true) = SimulatedModem(
        clock = { clock.ms },
        passWindowMs = if (visible) Long.MAX_VALUE / 4 else 0,
        txLatencyMs = 0,
        sleep = instant(),
    )

    private fun newEngine(clock: FakeClock, visible: Boolean = true): Pair<DemoEngine, SimulatedModem> {
        val modem = newModem(clock, visible)
        return DemoEngine(modem, ownerId = 12_061L) to modem
    }

    /** 每秒喂一次蜂窝观测 + tick，持续 durationMs。用相对时长而不是绝对终点，起始时钟随便选 */
    private fun drive(
        e: DemoEngine,
        clock: FakeClock,
        status: CellStatus,
        durationMs: Long,
    ): List<ScreenMessage> {
        val out = ArrayList<ScreenMessage>()
        val end = clock.ms + durationMs
        while (clock.ms <= end) {
            out += e.onCellStatus(status, clock.ms)
            out += e.onTick(clock.ms)
            clock.advance(1_000)
        }
        return out
    }

    @Test
    fun `link switch events reach the screen as protocol messages`() {
        val clock = FakeClock()
        val (e, _) = newEngine(clock)

        val msgs = drive(e, clock, dead, durationMs = 25_000)
        val switched = msgs.mapNotNull { it.event }.filter { it.kind == EventKind.link_switched }
        assertTrue(switched.isNotEmpty(), "掉网后应该推 link_switched 给展示端")
        assertEquals("probe", switched.first().payload["to"]?.toString()?.trim('"'))
        assertEquals(LinkPhase.PROBE, e.phase)

        // 探测发出后卫星超时不 ACK，也要落地到 SAT，否则离线期间一条都发不出去
        drive(e, clock, dead, durationMs = 70_000)
        assertEquals(LinkPhase.SAT, e.phase)
    }

    @Test
    fun `sos over satellite sets the flags the platform reads`() = runTest {
        val clock = FakeClock(1_795_200_000_000L)
        val (e, _) = newEngine(clock)
        e.onFix(30.2741, 120.1551, GnssSource.gt1005, hdop = 2.5, fixAgeSec = 2.0)
        e.onPhoneBattery(63)

        val out = e.triggerSos(nowSec = clock.ms / 1000, cellOk = true)
        assertTrue(out.result == SatResult.COMPLETE, "过境窗口内应拿到 ACK")
        val d = decodeSosFrame(hexToBytes(out.frameHex))

        assertEquals(SosFrame.MSG_SOS, d.msgType)
        assertEquals(12_061L, d.ownerId)
        assertEquals(30.2741, d.lat, 1e-7)
        assertEquals(63, d.batteryPct)
        assertTrue(d.flags and SosFrame.FLAG_DUAL != 0, "蜂窝同时在发，flags.bit0 必须置上")
        assertTrue(d.flags and SosFrame.FLAG_SRC_GT1005 != 0, "位置来自 GT1005，要标出来")
        assertFalse(d.flags and SosFrame.FLAG_STALE_FIX != 0, "2 秒前的定位不该标过期")
        // §6.1 红线一：星上 ACK 只把状态推到"已发出、卫星已应答"，离"平台已回执"还差一步
        assertEquals(SosLevel.sent, e.snapshot().sos.level)
        assertEquals(true, e.snapshot().sos.satAck)
        e.onPlatformAck()
        assertEquals(SosLevel.acked, e.snapshot().sos.level)
    }

    @Test
    fun `sos without a fix is still sent and marked stale`() = runTest {
        val clock = FakeClock(1_795_200_000_000L)
        val (e, _) = newEngine(clock)

        val out = e.triggerSos(nowSec = clock.ms / 1000, cellOk = false)
        assertFalse(out.hasFix)
        val d = decodeSosFrame(hexToBytes(out.frameHex))
        assertTrue(d.flags and SosFrame.FLAG_STALE_FIX != 0)
        assertFalse(d.flags and SosFrame.FLAG_DUAL != 0, "蜂窝不通时不该声称双发")
        assertEquals(0.0, d.lat, 1e-9)
    }

    /**
     * 演示里最容易被问到的一条：屏蔽袋里断网 + 没过境，SOS 到底发没发出去。
     * 这里要看到的是"重试到上限 + 给出下次过境时间"，而不是一个转圈的按钮。
     */
    @Test
    fun `sos outside a pass window exhausts retries and reports next pass`() {
        val clock = FakeClock(1_795_200_000_000L)
        val modem = newModem(clock, visible = false)
        val progress = ArrayList<SosProgress>()
        val coordinator = SosCoordinator(
            modem,
            SosFrameEncoder(12_061L),
            LinkPolicy(),
            cell = null,
            progress = { progress += it },
        )

        val result = runBlocking {
            coordinator.trigger(30.2741, 120.1551, clock.ms / 1000, 63, SosFrame.FLAG_SRC_GT1005, cellOk = false)
        }

        assertEquals(SatResult.NO_ACK, result.result)
        assertEquals(SatPath.IMMEDIATE, result.via)
        val exhausted = progress.filterIsInstance<SosProgress.Exhausted>().single()
        assertEquals(3, exhausted.attempts)
        assertNotNull(exhausted.nextPassInS)
        assertTrue(exhausted.nextPassInS!! > 0, "红字要给出下次过境时间，不能只说失败")
        assertEquals(3, progress.filterIsInstance<SosProgress.SatSending>().size)
    }

    @Test
    fun `模组丢弃的报文会被捡回补发队列而不是静默消失`() = runTest {
        // 〔规 §3.1.13〕GM1003 存够 4 分钟没发出去会主动丢弃。
        // App 侧在"模组收下"那一刻就清了自己那份，不捡回来 SOS 就这么没了（E.6）
        val clock = FakeClock(1_795_200_000_000L)
        val (e, modem) = newEngine(clock, visible = false)
        e.onFix(30.2741, 120.1551, GnssSource.phone, null, 2.0)
        val out = e.triggerSos(nowSec = clock.ms / 1000, cellOk = false)
        assertTrue(e.pending.isEmpty(), "模组收下时 App 不必再留")

        modem.simulateDropped(out.frameHex)
        clock.advance(1_000)
        e.onTick(clock.ms)
        assertEquals(listOf(out.frameHex), e.pending.toList(), "被模组丢掉的报文必须回到 App 队列")
    }

    @Test
    fun `satellite outage parks heartbeat instead of dropping it`() = runTest {
        // 帧里带的是 2026 epoch 起的秒数，链路计时用的是相对毫秒 —— 起始时钟必须落在 epoch 之后
        val clock = FakeClock(1_795_200_000_000L)
        val modem = SimulatedModem(clock = { clock.ms }, txLatencyMs = 0, sleep = instant())
        val e = DemoEngine(modem, ownerId = 12_061L)
        e.onFix(30.2741, 120.1551, GnssSource.gt1005, null, 2.0)

        drive(e, clock, dead, durationMs = 25_000)
        // PROBE 阶段没收到 ACK，靠 tick 超时进 SAT
        drive(e, clock, dead, durationMs = 70_000)
        assertEquals(LinkPhase.SAT, e.phase)

        modem.reachable = false
        val sent = e.heartbeat(nowSec = clock.ms / 1000, cellOk = false)
        assertFalse(sent, "模块掉线时心跳必须落到离线队列")
        assertEquals(1, e.pending.size)
    }

    @Test
    fun `screen snapshot never lies about a disconnected module`() {
        val clock = FakeClock()
        val (e, modem) = newEngine(clock)
        modem.reachable = false

        val s = e.snapshot()
        assertFalse(s.link.satOk)
        assertEquals(SatPhase.idle, s.sat.phase)
        assertEquals(SosLevel.normal, s.sos.level)
        assertFalse(s.sat.moduleOk, "展示端要靠这个字段区分「终端没了」和「没锁到星」")
    }

    @Test
    fun `offline heartbeats show up as a pending count on the screen`() = runTest {
        val clock = FakeClock(1_795_200_000_000L)
        val (e, modem) = newEngine(clock)
        e.onFix(30.2741, 120.1551, GnssSource.phone, null, 2.0)
        drive(e, clock, dead, durationMs = 25_000)
        modem.reachable = false
        repeat(3) {
            e.heartbeat(nowSec = clock.ms / 1000, cellOk = false)
            clock.advance(1_000)
        }

        val s = e.snapshot()
        assertEquals(3, s.sat.queuePending, "掉线期间攒了几条，展示端要能数出来")
        assertEquals(null, s.sat.satName, "终端都不在了，上一次锁定的星名不该还画在屏上")
    }

    @Test
    fun `a requested probe is actually put on the air`() = runTest {
        val clock = FakeClock(1_795_200_000_000L)
        val (e, modem) = newEngine(clock)
        drive(e, clock, dead, durationMs = 25_000)
        assertEquals(LinkPhase.PROBE, e.phase)
        assertTrue(modem.sent.isEmpty(), "状态机只是要求探测，没真发")

        e.drainProbe(clock.ms)
        assertEquals(1, modem.sent.size, "PROBE 态的探测包必须真的走一趟模块")
        assertEquals(LinkPhase.SAT, e.phase, "蜂窝已判死，探测完就该落到卫星主链路")
    }

    @Test
    fun `RECOVER only returns to CELL once the queue is actually empty`() = runTest {
        val clock = FakeClock(1_795_200_000_000L)
        val (e, modem) = newEngine(clock)
        e.onFix(30.2741, 120.1551, GnssSource.phone, null, 2.0)
        drive(e, clock, dead, durationMs = 25_000)
        e.drainProbe(clock.ms)
        assertEquals(LinkPhase.SAT, e.phase)

        modem.reachable = false
        repeat(3) {
            e.heartbeat(nowSec = clock.ms / 1000, cellOk = false)
            clock.advance(1_000)
        }
        assertEquals(3, e.pending.size)

        modem.reachable = true
        drive(e, clock, good, durationMs = 35_000)
        assertEquals(LinkPhase.RECOVER, e.phase)

        repeat(5) {
            e.drainPending(clock.ms)
            clock.advance(1_000)
        }
        assertTrue(e.pending.isEmpty(), "补发完队列就该空")
        assertEquals(LinkPhase.CELL, e.phase)
    }

    @Test
    fun `acked sos banner is retracted but an unacked one stays`() = runTest {
        val clock = FakeClock(1_795_200_000_000L)
        val (e, modem) = newEngine(clock, visible = false)
        e.onFix(30.2741, 120.1551, GnssSource.phone, null, 2.0)

        e.triggerSos(nowSec = clock.ms / 1000, cellOk = false)
        assertEquals(SosLevel.sent, e.snapshot().sos.level)
        drive(e, clock, dead, durationMs = 30_000)
        assertEquals(SosLevel.sent, e.snapshot().sos.level, "没确认的红字不能自己消失")

        modem.passWindowMs = Long.MAX_VALUE / 4
        e.triggerSos(nowSec = clock.ms / 1000, cellOk = false)
        assertEquals(SosLevel.sent, e.snapshot().sos.level, "星上 ACK 不等于平台回执，红字不能自己收（§6.1 红线一）")
        e.onPlatformAck()
        assertEquals(SosLevel.acked, e.snapshot().sos.level)
        drive(e, clock, dead, durationMs = 20_000)
        assertEquals(SosLevel.normal, e.snapshot().sos.level, "确认回执停一会儿就该收回，别一直压着轨迹图")
    }

    @Test
    fun `heartbeat on cell does not touch the satellite at all`() = runTest {
        val clock = FakeClock(1_795_200_000_000L)
        val (e, modem) = newEngine(clock)
        e.onFix(30.2741, 120.1551, GnssSource.phone, null, 2.0)

        // CELL 态心跳走蜂窝，不该占卫星额度
        assertTrue(e.heartbeat(nowSec = clock.ms / 1000, cellOk = true))
        assertTrue(modem.sent.isEmpty())
    }
}
