package com.carpet.satdemo.core.engine

import com.carpet.satdemo.core.ephemeris.EphResult
import com.carpet.satdemo.core.ephemeris.EphState
import com.carpet.satdemo.core.ephemeris.EphemerisConfig
import com.carpet.satdemo.core.ephemeris.EphemerisCopy
import com.carpet.satdemo.core.ephemeris.EphemerisSource
import com.carpet.satdemo.core.ephemeris.EphemerisTracker
import com.carpet.satdemo.core.ephemeris.PassRequest
import com.carpet.satdemo.core.ephemeris.SatellitePass
import com.carpet.satdemo.core.modem.MonoClock
import com.carpet.satdemo.core.modem.SimulatedModem
import com.carpet.satdemo.core.protocol.GnssSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 星历接进引擎之后（E.10 #7），圆屏那一帧要多背四样东西：
 * 窗口长度、最大仰角、方位角指针、以及**星历失效时那句人话**。
 * 这里钉的是优先序：星历说话算数，星历失灵才轮到模组的粗估。
 */
class DemoEngineEphemerisTest {

    private class Clock(var now: Long = 1_795_200_000_000L) : MonoClock {
        override fun nowMs(): Long = now
        fun advance(ms: Long) {
            now += ms
        }
    }

    private class StubSource(var result: EphResult) : EphemerisSource {
        override suspend fun update(): Long? = 1_795_100_000_000L
        override suspend fun calculate(request: PassRequest) = result
    }

    private fun engineWith(
        clock: Clock,
        result: EphResult,
    ): Pair<DemoEngine, SimulatedModem> {
        val modem = SimulatedModem(clock = clock, txLatencyMs = 0, sleep = { })
        val tracker = EphemerisTracker(StubSource(result), clock, EphemerisConfig(refreshMs = 60_000L))
        return DemoEngine(modem, ownerId = 12_061L, ephemeris = tracker) to modem
    }

    @Test
    fun `星历给的窗口和方位角会投到屏上`() = runBlocking {
        val c = Clock()
        val window = SatellitePass("GW-03", c.now + 90_000, c.now + 90_000 + 5 * 60_000, 135.0, 300.0, 61.0)
        val (e, _) = engineWith(c, EphResult(EphState.SUCCESS, listOf(window), 1L))
        e.onFix(30.2741, 120.1551, GnssSource.gt1005, hdop = null, fixAgeSec = 2.0)

        assertNotNull(e.tickEphemeris())
        val sat = e.snapshot().sat
        assertEquals(90L, sat.nextPassInS?.toLong())
        assertEquals(EphState.SUCCESS, sat.ephState)
        assertEquals(null, sat.ephFail, "正常时不该占着那行红字")
        assertEquals(300, sat.nextPassDurS, "窗口长度是厂商给的 endTime-startTime，屏上靠它判断这条窗口够不够发完队列")
        assertEquals(61.0, sat.nextPassMaxEl!!, 1e-9)
        assertEquals(135.0, sat.aimAz!!, 1e-9, "还没入境，指针指入境方位角")
    }

    @Test
    fun `星历算不出来时倒计时退回模组粗估 但红字说的是联网`() = runBlocking {
        val c = Clock()
        val (e, modem) = engineWith(c, EphResult(EphState.NETWORK_ERROR, emptyList(), null))
        e.onFix(30.2741, 120.1551, GnssSource.gt1005, hdop = null, fixAgeSec = 2.0)
        // 模组那边是固定周期，能给个数；星历没了这个数不能跟着清空，否则屏上什么都没有了
        val modemValue = modem.secondsToNextPass()

        val status = e.tickEphemeris()!!
        assertEquals(EphState.NETWORK_ERROR, status.state)
        assertEquals(modemValue, e.nextPassInS, "星历失灵时宁可退回粗估，也别把倒计时整条抹掉")

        val sat = e.snapshot().sat
        assertEquals("星历数据已过期，请联网更新后重试", sat.ephFail)
        for (forbidden in EphemerisCopy.FORBIDDEN) {
            assertFalse(sat.ephFail!!.contains(forbidden), "把星历问题说成卫星问题，用户会举着天线干等（E.9）：${sat.ephFail}")
        }
        assertTrue(sat.ephState == EphState.NETWORK_ERROR, "状态码也要一起下去，展示端才能把这条和'终端离线'分两行画")
    }

    @Test
    fun `没定位时不去问星历 屏上给一句人话`() = runBlocking {
        val c = Clock()
        val (e, _) = engineWith(c, EphResult(EphState.SUCCESS, emptyList(), 1L))
        val status = e.tickEphemeris()!!
        assertEquals(EphState.NO_FIX, status.state)
        assertEquals("还没有定位，暂时算不出过境窗口", e.snapshot().sat.ephFail)
    }
}
