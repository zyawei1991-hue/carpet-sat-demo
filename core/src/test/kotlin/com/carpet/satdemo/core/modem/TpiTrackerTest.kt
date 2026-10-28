package com.carpet.satdemo.core.modem

import com.carpet.satdemo.core.protocol.GeodeticPosition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TPI 周期任务（E.10 #5）。
 *
 * 〔规 §3.1.7〕那 1~10 分钟不是建议而是要求：模组掉电/复位就把位置丢了，
 * 而**下行能不能收到取决于这颗位置**。所以这里钉的是"什么时候必须再推一次"。
 */
class TpiTrackerTest {

    private class Clock(var now: Long = 1_700_000_000_000L) : MonoClock {
        override fun nowMs(): Long = now
        fun advance(ms: Long) {
            now += ms
        }
    }

    /** 只关心"位置推没推、推的是哪一颗"的替身。真模组的语句层由 SentenceModemTest 钉 */
    private class SpyModem(private val clock: Clock) : SatelliteModem {
        val pushes = ArrayList<GeodeticPosition>()

        /** false 演"模组没回显 TPI"；升级那一路走真的 [gate] */
        var responsive = true
        override val gate: ModemGate = ModemGate()
        override val available: Boolean get() = true

        override suspend fun updateLocation(position: GeodeticPosition): Boolean {
            if (!responsive) return false
            return gate.write("TPI") { pushes += position; true } ?: false
        }

        override suspend fun readFix(): Fix? = pushes.lastOrNull()?.let { Fix(it, null, null) }
        override suspend fun sendMcq(hex: String) = SatSend.failed(SatPath.IMMEDIATE, SatFailReason.NO_REPLY)
        override suspend fun enqueueSfq(hex: String) = SatSend.failed(SatPath.QUEUED, SatFailReason.NO_REPLY)
        override fun onDownlink(handler: (String) -> Unit) {}
        override fun onSatelliteLock(handler: (SatLock?) -> Unit) {}
        override fun close() {}
    }

    private val nanjing = GeodeticPosition.of(30.2741, 120.1551)

    @Test
    fun `配置超界被钳回规范那 1 到 10 分钟`() {
        val c = Clock()
        // 写成 1 秒会把模组刷爆（每条 TPI 都要它回显），写成 1 小时位置必然陈旧
        assertEquals(TpiTracker.MIN_INTERVAL_MS, TpiTracker(SpyModem(c), c, intervalMs = 1_000L).intervalMs)
        assertEquals(TpiTracker.MAX_INTERVAL_MS, TpiTracker(SpyModem(c), c, intervalMs = 8 * 60 * 60_000L).intervalMs)
    }

    @Test
    fun `没有位置源时不推 也不谎称已就绪`() = runBlocking {
        val c = Clock()
        val m = SpyModem(c)
        val t = TpiTracker(m, c)
        assertEquals(TpiTracker.State.NO_SOURCE, t.tick().state)
        assertTrue(m.pushes.isEmpty())
        assertTrue(t.isStale, "一个位置都没推过去时，屏上必须报陈旧")
    }

    @Test
    fun `第一次有位置就立刻推 不等周期`() = runBlocking {
        val c = Clock()
        val m = SpyModem(c)
        val t = TpiTracker(m, c)
        t.onPosition(nanjing)
        assertEquals(TpiTracker.State.FRESH, t.tick().state)
        assertEquals(listOf(nanjing), m.pushes, "上电那一刻必须先把位置喂进去，模组那边是空的")
    }

    @Test
    fun `周期内不重复推 到点再推`() = runBlocking {
        val c = Clock()
        val m = SpyModem(c)
        val t = TpiTracker(m, c, intervalMs = 60_000L)
        t.onPosition(nanjing)
        t.tick()
        c.advance(30_000)
        t.tick()
        assertEquals(1, m.pushes.size, "没到点还一直推，等于把串口当喇叭")
        c.advance(31_000)
        t.tick()
        assertEquals(2, m.pushes.size)
    }

    /** 挪车 / 长途行驶：等一个完整周期才更新位置，中间这段下行基本收不到 */
    @Test
    fun `车挪了就马上补推`() = runBlocking {
        val c = Clock()
        val m = SpyModem(c)
        val t = TpiTracker(m, c, intervalMs = 10 * 60_000L)
        t.onPosition(nanjing)
        t.tick()
        t.onPosition(GeodeticPosition.of(30.2841, 120.1551))
        c.advance(2_000)
        t.tick()
        assertEquals(2, m.pushes.size, "位移超过阈值要当场补推")
    }

    @Test
    fun `GPS 抖动不至于触发补推`() = runBlocking {
        val c = Clock()
        val m = SpyModem(c)
        val t = TpiTracker(m, c, intervalMs = 10 * 60_000L)
        t.onPosition(nanjing)
        t.tick()
        t.onPosition(GeodeticPosition.of(30.27412, 120.15513))
        c.advance(2_000)
        t.tick()
        assertEquals(1, m.pushes.size, "0.00002° 是定位抖动，不是挪车")
    }

    @Test
    fun `模组没回显时短间隔重试 状态标成被拒`() = runBlocking {
        val c = Clock()
        val m = SpyModem(c).apply { responsive = false }
        val t = TpiTracker(m, c, intervalMs = 60_000L)
        t.onPosition(nanjing)
        t.tick()
        assertEquals(TpiTracker.State.REJECTED, t.status().state)

        m.responsive = true
        c.advance(TpiTracker.RETRY_BACKOFF_MS)
        t.tick()
        assertEquals(1, m.pushes.size, "推失败不能死等一整个周期")
    }

    @Test
    fun `升级期间推不进去 状态说是写锁不是失败`() = runBlocking {
        val c = Clock()
        val m = SpyModem(c)
        val t = TpiTracker(m, c, intervalMs = 60_000L)
        t.onPosition(nanjing)
        t.tick()
        assertEquals(1, m.pushes.size)

        val hold = CompletableDeferred<Unit>()
        val inside = CompletableDeferred<Unit>()
        val upgrading = launch { m.gate.runUpgrade(c.now) { inside.complete(Unit); hold.await() } }
        inside.await()

        c.advance(61_000)
        assertEquals(TpiTracker.State.BLOCKED, t.tick().state, "被写锁挡下要说成写锁，别报成发送失败")
        assertEquals(1, m.pushes.size, "升级期间一个字都不该写进模组")

        hold.complete(Unit)
        upgrading.join()
        c.advance(61_000)
        assertEquals(TpiTracker.State.FRESH, t.tick().state)
        assertEquals(2, m.pushes.size, "锁一放开就该把位置补进去")
    }

    @Test
    fun `超过 15 分钟没推成就是陈旧 并给出过期秒数`() = runBlocking {
        val c = Clock()
        val m = SpyModem(c)
        val t = TpiTracker(m, c, intervalMs = 60_000L, staleAfterMs = SentenceModem.TPI_STALE_DEFAULT_MS)
        t.onPosition(nanjing)
        t.tick()
        assertNull(t.staleAgeSec(), "刚推过不该报过期")

        c.advance(SentenceModem.TPI_STALE_DEFAULT_MS + 1_000)
        assertEquals(TpiTracker.State.STALE, t.status().state)
        assertEquals((SentenceModem.TPI_STALE_DEFAULT_MS + 1_000) / 1000, t.staleAgeSec(), "屏上的「过期 N 分钟」就是这个数")
        assertTrue(t.isStale)
    }
}
