package com.carpet.satdemo.core.engine

import com.carpet.satdemo.core.link.CellStatus
import com.carpet.satdemo.core.modem.MonoClock
import com.carpet.satdemo.core.modem.SimulatedModem
import com.carpet.satdemo.core.protocol.GnssSource
import com.carpet.satdemo.core.protocol.ModuleCaps
import com.carpet.satdemo.core.protocol.ModuleModel
import com.carpet.satdemo.core.queue.OutboxClass
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E.10 #8 在引擎这一层的四条承诺：
 *  - 队列容量、被挤掉的条数都按**模组型号**走，并且投屏（展示端不用自己猜 50 还是 500）；
 *  - 模组没报告"我丢了这条"时，App 按型号口径自己发现该重推；
 *  - 捡回来的 SOS 不等 RECOVER 就冲出去；
 *  - 模组掉电后要不要整体重推，**方向按型号相反**（GM1003 要，GM1001 绝对不能）。
 */
class DemoEngineOutboxTest {

    private class Clock(var now: Long = 1_795_200_000_000L) : MonoClock {
        override fun nowMs(): Long = now
        fun advance(ms: Long) {
            now += ms
        }
    }

    private val dead = CellStatus(hasInternetCapability = false, validated = false, signalLevel = 0)

    private fun modem(clock: Clock, caps: ModuleCaps = ModuleCaps.worstKnown()) = SimulatedModem(
        clock = clock,
        txLatencyMs = 0,
        sleep = { },
    ).apply { capsOverride = caps }

    /** 判死蜂窝，好让心跳真的落到卫星那一路（CELL 态心跳走 4G，不占卫星额度） */
    private fun driveOffline(e: DemoEngine, clock: Clock, ms: Long) {
        val end = clock.nowMs() + ms
        while (clock.nowMs() <= end) {
            e.onCellStatus(dead, clock.nowMs())
            e.onTick(clock.nowMs())
            clock.advance(1_000)
        }
    }

    @Test
    fun `掉线攒心跳时屏上给出容量和被挤掉的条数`() = runBlocking {
        val clock = Clock()
        val m = modem(clock)
        val e = DemoEngine(m, ownerId = 12_061L)
        e.onFix(30.2741, 120.1551, GnssSource.phone, null, 2.0)
        driveOffline(e, clock, 25_000)
        m.reachable = false

        // 帧里的时间戳是**分钟**粒度，所以每条心跳要隔一分钟才是一条新的报文
        repeat(51) { i -> e.heartbeat(nowSec = (clock.nowMs() + i * 60_000) / 1000, cellOk = false) }

        val s = e.snapshot()
        assertEquals(50, s.sat.queuePending, "GM1003 只有 50 个槽，第 51 条排不进去")
        assertEquals(50, s.sat.queueCapacity, "容量得一起上屏 —— 光说待补发 50 条，没人知道这已经满了")
        assertEquals(1, s.sat.queueDropped, "挤掉了一条就得画出来，不能假装一条没丢")
        assertEquals(OutboxClass.Telemetry, e.outbox.peek(clock.nowMs()).maxByOrNull { it.kind.priority }?.kind)
    }

    @Test
    fun `模组报告丢弃后 SOS 不等 RECOVER 就重推`() = runBlocking {
        val clock = Clock()
        val m = modem(clock)
        val e = DemoEngine(m, ownerId = 12_061L)
        e.onFix(30.2741, 120.1551, GnssSource.phone, null, 2.0)
        val out = e.triggerSos(clock.nowMs() / 1000, cellOk = true)

        m.simulateDropped(out.frameHex)
        clock.advance(1_000)
        e.onTick(clock.nowMs())
        assertEquals(1, e.pending.size, "被丢掉的得回到待补发，这是原来的口径")

        val before = m.sent.size
        e.drainPending(clock.nowMs())
        assertTrue(m.sent.size > before, "SOS 被模组丢回来就该马上重推，不能等蜂窝判满一次 RECOVER")
    }

    @Test
    fun `模组掉电时按型号决定要不要整体重推`() = runBlocking {
        val parked = parkOneSos(Clock(), ModuleCaps.worstKnown().copy(model = ModuleModel.GM1003))
        // RAM 型（GM1003）：交出去的那条掉电即空，必须回来重推
        assertEquals(0, parked.e.outbox.size, "已经交给模组了，待推数该是 0")
        assertEquals(1, parked.e.outbox.handedSize, "但 App 那份盯梢副本还在")
        parked.m.reachable = false
        parked.e.onTick(parked.clock.nowMs())
        assertEquals(1, parked.e.outbox.size, "GM1003 队列在 RAM 里，掉电即空 —— 不重推那些报文就真没了")

        val flash = parkOneSos(Clock(), ModuleCaps.worstKnown().copy(model = ModuleModel.GM1001))
        flash.m.reachable = false
        flash.e.onTick(flash.clock.nowMs())
        assertEquals(0, flash.e.outbox.size, "GM1001 存在 FLASH 里，重推等于把同一条 SOS 再发一遍")
        assertEquals(1, flash.e.outbox.handedSize)
    }

    @Test
    fun `模组没报告丢弃时按型号超时自己拿回来`() = runBlocking {
        val clock = Clock()
        val m = modem(clock)
        val e = DemoEngine(m, ownerId = 12_061L)
        // 一条已经交给模组、模组还没发出去的越界告警
        e.outbox.offer("A1", OutboxClass.Alarm)
        e.outbox.markHanded("A1", clock.nowMs())

        e.onTick(clock.nowMs() + 3 * 60_000)
        assertEquals(0, e.outbox.size, "差一分钟还不到 GM1003 的丢弃时限")
        e.onTick(clock.nowMs() + 4 * 60_000)
        assertEquals(1, e.outbox.size, "模组那边已经偷偷丢了，App 不猜就只能等它报告（§9 #14 还没确认）")
        assertEquals(0, e.outbox.handedSize)
    }

    /**
     * 演一条 SOS 被升级写锁挡下、随后成功交给模组的状态。
     * 返回的模组此时是可达的，测试再把它切断就能看型号分岔。
     */
    private fun parkOneSos(clock: Clock, caps: ModuleCaps): Parked {
        val m = modem(clock, caps)
        val e = DemoEngine(m, ownerId = 12_061L)
        e.onFix(30.2741, 120.1551, GnssSource.phone, null, 2.0)

        val inside = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        CoroutineScope(Dispatchers.Unconfined).launch {
            m.gate.runUpgrade(clock.nowMs()) { progress ->
                progress(50)
                inside.complete(Unit)
                hold.await()
            }
        }
        runBlocking { inside.await() }
        runBlocking { e.triggerSos(clock.nowMs() / 1000, cellOk = false) }
        assertEquals(1, e.outbox.size, "升级写锁挡下的 SOS 得留在 App 队列")

        hold.complete(Unit)
        runBlocking {
            while (m.gate.isUpgrading) yield()
            e.drainPending(clock.nowMs())
        }
        return Parked(e, m, clock)
    }

    /** 一条已经成功交给模组的 SOS，连着它的时钟 —— 后面要在这上面演型号分岔 */
    private class Parked(val e: DemoEngine, val m: SimulatedModem, val clock: Clock)
}
