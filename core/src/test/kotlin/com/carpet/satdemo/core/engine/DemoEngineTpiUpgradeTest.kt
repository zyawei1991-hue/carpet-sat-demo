package com.carpet.satdemo.core.engine

import com.carpet.satdemo.core.link.SosCopy
import com.carpet.satdemo.core.modem.ModemGate
import com.carpet.satdemo.core.modem.MonoClock
import com.carpet.satdemo.core.modem.SatFailReason
import com.carpet.satdemo.core.modem.SimulatedModem
import com.carpet.satdemo.core.modem.TpiTracker
import com.carpet.satdemo.core.protocol.GnssSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * E.10 #5 + #6 在引擎这一层的两条承诺：
 *  - 升级期间按下去的 SOS **不会蒸发**，写锁一放开就自己补出去；
 *  - 展示端拿得到 TPI 陈旧度和升级进度，不用自己猜。
 */
class DemoEngineTpiUpgradeTest {

    private class Clock(var now: Long = 1_795_200_000_000L) : MonoClock {
        override fun nowMs(): Long = now
        fun advance(ms: Long) {
            now += ms
        }
    }

    private fun modem(clock: Clock) = SimulatedModem(clock = clock, txLatencyMs = 0, sleep = { })

    /** 起一场按住不放的升级，返回 (升级已经进到临界区了, 结束它要 complete 的那个) */
    private fun heldUpgrade(gate: ModemGate, clock: Clock, pct: Int = 37): Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>> {
        val inside = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        CoroutineScope(Dispatchers.Unconfined).launch {
            gate.runUpgrade(clock.now) { progress ->
                progress(pct)
                inside.complete(Unit)
                hold.await()
            }
        }
        return inside to hold
    }

    @Test
    fun `升级期间按 SOS 会留在 App 队列 锁放开就补出去`() = runBlocking {
        val clock = Clock()
        val m = modem(clock)
        val e = DemoEngine(m, ownerId = 12_061L)
        e.onFix(30.2741, 120.1551, GnssSource.gt1005, hdop = null, fixAgeSec = 2.0)

        val (inside, hold) = heldUpgrade(m.gate, clock)
        inside.await()
        assertTrue(m.gate.isUpgrading)

        val out = e.triggerSos(nowSec = clock.now / 1000, cellOk = false)
        assertEquals(SatFailReason.UPGRADING, out.reason, "被写锁挡下要说成写锁，不是发送失败")
        assertEquals(listOf(out.frameHex), e.pending.toList(), "一个字节都没写出去的 SOS 必须还留在 App 队列")

        val line = SosCopy.statusLine(out.send, nextPassInS = null)
        assertTrue(line.contains("升级"), "现场要能看到是升级挡住了：$line")
        for (banned in SosCopy.BANNED) assertFalse(line.contains(banned), "踩了 §6.1 红线：$line")

        hold.complete(Unit)
        // 等 runUpgrade 的 finally 把旗子放下
        while (m.gate.isUpgrading) yield()
        e.drainPending(clock.now)
        assertTrue(e.pending.isEmpty(), "写锁一放开，SOS 要立刻补出去，不能等下一个 RECOVER")
    }

    @Test
    fun `快照带上 TPI 年龄和升级进度`() = runBlocking {
        val clock = Clock()
        val m = modem(clock)
        val tpi = TpiTracker(m, clock, intervalMs = 60_000L)
        val e = DemoEngine(m, ownerId = 12_061L, tpi = tpi)
        e.onFix(30.2741, 120.1551, GnssSource.gt1005, hdop = null, fixAgeSec = 2.0)

        assertEquals(TpiTracker.State.FRESH, e.tickTpi()?.state)
        assertEquals(0L, e.snapshot().sat.tpiAgeS)
        assertEquals(false, e.snapshot().sat.tpiStale)

        // App 被挂住没 tick（真机上就是协程被取消/进程被冻），过期要能上屏
        clock.advance(SentenceModemStaleWindow)
        val stale = e.snapshot().sat
        assertEquals(true, stale.tpiStale, "位置过期必须让展示端打那行红字（E.5）")
        assertEquals(SentenceModemStaleWindow / 1000, stale.tpiAgeS)

        val (inside, hold) = heldUpgrade(m.gate, clock)
        inside.await()
        assertEquals(37, e.snapshot().sat.upgradePct, "§5.2 那排按钮的禁用态就看这个数")
        hold.complete(Unit)
    }

    companion object {
        private const val SentenceModemStaleWindow = 15 * 60_000L
    }
}
