package com.carpet.satdemo.core.ephemeris

import com.carpet.satdemo.core.modem.MonoClock
import com.carpet.satdemo.core.protocol.GeodeticPosition
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class Clock(var now: Long = 1_795_200_000_000L) : MonoClock {
    override fun nowMs(): Long = now
    fun advance(ms: Long) {
        now += ms
    }
}

/** 只按脚本回话的星历源，SDK 到货前用它，到货后 `StarManagerEphemeris` 换成同一形状 */
private class ScriptedSource : EphemerisSource {
    var updates = 0
    var calculates = 0
    var lastRequest: PassRequest? = null
    var nextResult: EphResult = EphResult(EphState.SUCCESS, emptyList(), 1L)
    var updateFails = false

    override suspend fun update(): Long? {
        updates++
        return if (updateFails) null else 1_795_100_000_000L
    }

    override suspend fun calculate(request: PassRequest): EphResult {
        calculates++
        lastRequest = request
        return nextResult
    }
}

/**
 * 星历接入（E.10 #7）。这一层的价值全在"什么时候不该问 SDK"和"问不出来时怎么说"，
 * 所以断言集中在调用次数和文案上，不集中在数值上。
 */
class EphemerisTrackerTest {

    private val hangzhou = GeodeticPosition.of(30.2741, 120.1551)
    private val shaoxing = GeodeticPosition.of(30.0, 120.58)       // 离杭州约 55 km
    private val nearby = GeodeticPosition.of(30.2745, 120.1555)    // 离杭州约 50 m

    private fun pass(startMs: Long, windowMs: Long = 4 * 60_000, name: String = "GW-03") =
        SatellitePass(name, startMs, startMs + windowMs, 120.0, 300.0, 55.0)

    @Test
    fun `没定位时不去问 SDK`() = runBlocking {
        val src = ScriptedSource()
        val t = EphemerisTracker(src, Clock())
        val s = t.refresh(null)
        assertEquals(EphState.NO_FIX, s.state)
        assertEquals(0, src.calculates, "没定位算出来的窗口是别人家的天，不该拿去画倒计时")
        assertEquals(0, src.updates)
    }

    @Test
    fun `每轮只 update 一次 剩下交给 refreshMs`() = runBlocking {
        val c = Clock()
        val src = ScriptedSource().apply { nextResult = EphResult(EphState.SUCCESS, listOf(pass(c.now + 60_000)), 1L) }
        val t = EphemerisTracker(src, c, EphemerisConfig(horizonHours = 6.0))

        t.refresh(hangzhou)
        t.refresh(hangzhou)
        t.refresh(hangzhou)
        assertEquals(1, src.updates, "〔SDK文 §5.2〕每日一次，不是每次重算都下一遍")
        assertEquals(1, src.calculates, "没到点、没挪窝、窗口还在，就别再问一遍")

        // 第二天：到 updateIntervalMs 才再下载
        c.advance(EphemerisConfig().updateIntervalMs)
        t.refresh(hangzhou)
        assertEquals(2, src.updates)
    }

    @Test
    fun `挪出 20 公里立刻重算 挪 50 米不算`() = runBlocking {
        val c = Clock()
        val src = ScriptedSource().apply { nextResult = EphResult(EphState.SUCCESS, listOf(pass(c.now + 60_000)), 1L) }
        val t = EphemerisTracker(src, c)

        t.refresh(hangzhou)
        t.refresh(nearby)
        assertEquals(1, src.calculates, "GPS 抖那几十米不该重算，车根本没用")

        t.refresh(shaoxing)
        assertEquals(2, src.calculates, "〔SDK文 §5.2〕位置变化超 20 公里必须主动重算，旧列表整个作废")
        assertEquals(shaoxing, src.lastRequest?.position)
    }

    @Test
    fun `窗口走完就该重算 别把过去的窗口挂在屏上`() = runBlocking {
        val c = Clock()
        val src = ScriptedSource().apply { nextResult = EphResult(EphState.SUCCESS, listOf(pass(c.now + 60_000)), 1L) }
        val t = EphemerisTracker(src, c, EphemerisConfig(refreshMs = 30 * 60_000L))

        val first = t.refresh(hangzhou)
        assertEquals(60L, first.nextPassInS)
        c.advance(6 * 60_000)
        val done = t.status()
        assertNull(done.nextPassInS, "没有未来窗口时倒计时必须是 null（屏上画 --），不能留 0 —— 0 会被读成'现在就能发'")

        t.refresh(hangzhou)
        assertEquals(2, src.calculates, "列表走完就该重问，不然屏上会一直挂着一个已经过去的窗口")
    }

    @Test
    fun `算不出来时按退避补试 不是 1Hz 死磕`() = runBlocking {
        val c = Clock()
        val src = ScriptedSource().apply { nextResult = EphResult(EphState.TIME_OUT, emptyList(), null) }
        val t = EphemerisTracker(src, c, EphemerisConfig(retryBackoffMs = 60_000L))

        t.refresh(hangzhou)
        repeat(10) { c.advance(1_000); t.refresh(hangzhou) }
        assertEquals(1, src.calculates, "刚算砸就 1Hz 反复问，只会把主线程那条链挤得更死")

        c.advance(60_000)
        t.refresh(hangzhou)
        assertEquals(2, src.calculates, "退避到了要自己再试一次，不能等用户重启")
    }

    @Test
    fun `下载失败不挡重算 但要标记要联网`() = runBlocking {
        val c = Clock()
        val src = ScriptedSource().apply {
            updateFails = true
            nextResult = EphResult(EphState.SUCCESS, listOf(pass(c.now + 120_000)), null)
        }
        val t = EphemerisTracker(src, c)
        val s = t.refresh(hangzhou)
        assertTrue(s.networkNeeded, "屏上要能看出'这批星历没更新成功'")
        assertNotNull(s.nextPass, "本地缓存还能算出今晚的窗口时，不该因为下载失败就不给倒计时（〔SDK文 §4〕dataState > 0 那一档）")
    }

    @Test
    fun `上次成功只记成功那一次`() = runBlocking {
        val c = Clock()
        val src = ScriptedSource().apply { nextResult = EphResult(EphState.SUCCESS, listOf(pass(c.now + 60_000)), 1L) }
        val t = EphemerisTracker(src, c)

        assertNull(t.status().lastSuccessAtMs, "一次都没算过的时候必须是 null —— 屏上那句「上次成功是几点」不能凭空写")

        val ok = t.refresh(hangzhou)
        assertEquals(c.now, ok.lastSuccessAtMs, "算成功那一刻才是「上次成功」")

        // 换一批坏星历，到重算点再问一次：时间不能跟着失败的尝试走
        src.nextResult = EphResult(EphState.TIME_OUT, emptyList(), null)
        c.advance(EphemerisConfig().refreshMs)
        val bad = t.refresh(hangzhou)
        assertEquals(EphState.TIME_OUT, bad.state)
        assertEquals(c.now - EphemerisConfig().refreshMs, bad.lastSuccessAtMs, "拿「最后一次尝试」冒充「上次成功」，用户读到的是数据还新，正好反了")

        // 没定位那一支同理：这一轮算不出，但历史上成功过的那次仍然要说得出
        val noFix = t.refresh(null)
        assertEquals(EphState.NO_FIX, noFix.state)
        assertEquals(c.now - EphemerisConfig().refreshMs, noFix.lastSuccessAtMs)
    }

    @Test
    fun `手动重试绕过退避 立刻再问一遍`() = runBlocking {
        val c = Clock()
        val src = ScriptedSource().apply { nextResult = EphResult(EphState.NETWORK_ERROR, emptyList(), null) }
        val t = EphemerisTracker(src, c, EphemerisConfig(retryBackoffMs = 60_000L))

        t.refresh(hangzhou)
        c.advance(1_000)
        t.refresh(hangzhou)
        assertEquals(1, src.calculates, "退避没到，1Hz 那圈不该替用户反复问")

        t.refresh(hangzhou, force = true)
        assertEquals(2, src.calculates, "用户按了「重试」就是明确要现在再问一次，退避是给自动循环定的，不是给人按的")
    }

    @Test
    fun `星历失效的每句话都不许说成没卫星`() {
        for (state in EphState.entries) {
            val msg = EphemerisCopy.failMessage(state) ?: continue
            for (forbidden in EphemerisCopy.FORBIDDEN) {
                assertFalse(msg.contains(forbidden), "$state 的文案「$msg」踩了红线：把星历问题说成了卫星问题（E.9）")
            }
            assertTrue(msg.isNotBlank())
        }
        // NETWORK_ERROR 的正确动作是联网，这条必须写在句子里
        assertTrue(EphemerisCopy.failMessage(EphState.NETWORK_ERROR)!!.contains("联网"))
    }

    @Test
    fun `仰角阈值真的传给了 SDK`() = runBlocking {
        val c = Clock()
        val src = ScriptedSource()
        val t = EphemerisTracker(src, c, EphemerisConfig(minElevationDeg = 35.0, horizonHours = 3.0, altitudeM = 42.0))
        t.refresh(hangzhou)
        val r = src.lastRequest!!
        assertEquals(35.0, r.minElevationDeg, 1e-9, "〔E.9〕阈值要按终端实际可通信仰角配，不能一路吃厂商默认的 20°")
        assertEquals(3.0, r.horizonHours, 1e-9)
        assertEquals(42.0, r.altitudeM, 1e-9)
    }

    @Test
    fun `在窗口里倒计时给零 指针跟着瞬时方位角走`() = runBlocking {
        val c = Clock()
        val entry = pass(c.now - 60_000, windowMs = 4 * 60_000)
        val src = ScriptedSource().apply { nextResult = EphResult(EphState.SUCCESS, listOf(entry), 1L) }
        val t = EphemerisTracker(src, c)
        val s = t.refresh(hangzhou)
        assertEquals(0L, s.nextPassInS, "已经在窗口里，倒计时就该是 0，这是'现在能发'的唯一凭据")
        assertNotNull(s.inPass)
        assertNotNull(s.aimAzimuthDeg, "窗口里要给瞬时方位角，指针才能跟着走")
    }

    @Test
    fun `状态是按开始时间排好序的`() = runBlocking {
        val c = Clock()
        val src = ScriptedSource().apply {
            nextResult = EphResult(
                EphState.SUCCESS,
                listOf(pass(c.now + 20 * 60_000, name = "GW-07"), pass(c.now + 5 * 60_000, name = "GW-02")),
                1L,
            )
        }
        val t = EphemerisTracker(src, c)
        val s = t.refresh(hangzhou)
        assertEquals(listOf("GW-02", "GW-07"), s.passes.map { it.starName }, "乱序的话倒计时会指着一条已经飞走的星")
        assertEquals("GW-02", s.nextPass?.starName)
    }
}

/** 厂商那个"进出两个方位角"的插值单独钉 —— 走错方向指针会指向天顶的另一头 */
class SatellitePassTest {

    @Test
    fun `方位角插值走最短弧`() {
        val p = SatellitePass("GW-01", startMs = 0, endMs = 100_000, startAzimuthDeg = 350.0, endAzimuthDeg = 20.0, maxElevationDeg = 40.0)
        assertEquals(357.5, p.azimuthAt(25_000), 1e-6, "从 350° 到 20° 是往北穿过 0°，不是绕大半圈")
        assertEquals(5.0, p.azimuthAt(50_000), 1e-6)
        assertEquals(350.0, p.azimuthAt(0), 1e-6)
        assertEquals(20.0, p.azimuthAt(100_000), 1e-6)
    }

    @Test
    fun `窗口外的秒数是 null 不是负数`() {
        val p = SatellitePass("GW-01", startMs = 1_000_000, endMs = 1_100_000, startAzimuthDeg = 0.0, endAzimuthDeg = 90.0, maxElevationDeg = 30.0)
        assertEquals(1_000L, p.secondsUntilStart(0))
        assertEquals(0L, p.secondsUntilStart(1_050_000))
        assertNull(p.secondsUntilStart(2_000_000), "出境了还给倒计时，屏上会出现一个负数")
        assertFalse(p.inWindow(2_000_000))
    }
}

/** 没硬件时演的那一条，也得真的按仰角阈值筛星 —— 否则阈值改了演示看不出来 */
class SimulatedEphemerisSourceTest {

    private val hangzhou = GeodeticPosition.of(30.2741, 120.1551)

    @Test
    fun `阈值抬高会筛掉低仰角的那几颗`() = runBlocking {
        val c = Clock()
        val src = SimulatedEphemerisSource(clock = c)
        val low = src.calculate(PassRequest(hangzhou, 0.0, minElevationDeg = 20.0, horizonHours = 6.0))
        val high = src.calculate(PassRequest(hangzhou, 0.0, minElevationDeg = 70.0, horizonHours = 6.0))
        assertEquals(EphState.SUCCESS, low.state)
        assertTrue(high.passes.size < low.passes.size, "仰角阈值不是装饰品：70° 以上的窗口本来就该更少")
        assertTrue(high.passes.all { it.maxElevationDeg >= 70.0 })
    }

    @Test
    fun `给的窗口都在未来且按时间递增`() = runBlocking {
        val c = Clock()
        val r = SimulatedEphemerisSource(clock = c).calculate(PassRequest(hangzhou, 0.0, 20.0, 6.0))
        assertTrue(r.passes.isNotEmpty())
        assertTrue(r.passes.all { it.startMs >= c.now })
        assertEquals(r.passes.sortedBy { it.startMs }.map { it.startMs }, r.passes.map { it.startMs })
        assertTrue(r.passes.all { it.endMs > it.startMs && it.windowSec > 0 })
    }

    @Test
    fun `算不出来时 passes 是空的`() = runBlocking {
        val src = SimulatedEphemerisSource(clock = Clock()).apply { state = EphState.NETWORK_ERROR }
        val r = src.calculate(PassRequest(hangzhou, 0.0, 20.0, 6.0))
        assertEquals(EphState.NETWORK_ERROR, r.state)
        assertTrue(r.passes.isEmpty(), "〔SDK文 §5.1.2〕非 SUCCESS 时 result 为 null，我们不能伪造一条窗口")
    }
}
