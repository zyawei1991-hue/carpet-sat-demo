package com.carpet.satdemo.core.runtime

import com.carpet.satdemo.core.engine.DemoEngine
import com.carpet.satdemo.core.modem.MonoClock
import com.carpet.satdemo.core.modem.SimulatedModem
import com.carpet.satdemo.core.protocol.EventKind
import com.carpet.satdemo.core.protocol.GnssSource
import com.carpet.satdemo.core.protocol.MsgType
import com.carpet.satdemo.core.protocol.ScreenMessage
import com.carpet.satdemo.core.protocol.toHex
import com.carpet.satdemo.core.screen.ScreenLinkServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 屏上那三条横幅的**发送方**在这里。
 *
 * 之前契约里 fence_exit / sat_visible / downlink_msg 三个 kind 一个调用点都没有，
 * 屏那边把 on_event 接上也不会弹 —— 现场看到的就是"几个画面在轮播"。
 * 所以钉三件事：边沿只报一次、ttl 一定带上（屏靠它把过期的一条丢掉）、下行正文只在可打印时才给。
 */
class ScreenEventPushTest {

    private class Clock(var now: Long) : MonoClock {
        override fun nowMs(): Long = now
        fun advance(ms: Long) {
            now += ms
        }
    }

    private val fenceCenter = Pair(30.2741, 120.1551)
    private val fenceRadius = 600.0

    private class Harness(startMs: Long) {
        val clock = Clock(startMs)
        val modem = SimulatedModem(
            clock = clock,
            passPeriodMs = 720_000,
            passWindowMs = 240_000,
            txLatencyMs = 0,
            sleep = { },
        )
        val sent = ArrayList<ScreenMessage>()
        val runtime = DemoRuntime(
            engine = DemoEngine(modem, DemoRuntime.DEFAULT_OWNER_ID),
            server = ScreenLinkServer(),
            clockMs = { clock.now },
        ).also { it.onSend = { msgs -> sent += msgs } }

        fun events(kind: EventKind): List<ScreenMessage> =
            sent.filter { it.t == MsgType.event && it.event?.kind == kind }

        /** 只留事件帧，state 那 1Hz 一帧不参与断言 */
        fun tick() = runBlocking { runtime.tickOnce() }
    }

    /** 起点选在窗口之外，且离窗口打开还有几步：T0 % 720000 = 0 会正好落在窗口里 */
    private fun harness() = Harness(1_795_200_000_000L + 400_000)

    @Test
    fun `越界只在跨过边界那一拍报一次`() = runBlocking {
        val h = harness()
        h.runtime.setFence(true, "西湖景区", fenceCenter.first, fenceCenter.second, fenceRadius)
        h.runtime.onFix(fenceCenter.first, fenceCenter.second, GnssSource.phone, 1.2, 2.0)
        h.tick()
        assertTrue(h.events(EventKind.fence_exit).isEmpty(), "第一拍只是建立基线，不该报越界")

        // 往北 0.01° ≈ 1112 米，出界
        h.runtime.onFix(30.2841, fenceCenter.second, GnssSource.phone, 1.2, 2.0)
        h.tick()
        h.tick()
        assertEquals(1, h.events(EventKind.fence_exit).size, "待在外面不该每拍再报一次")

        val payload = assertNotNull(h.events(EventKind.fence_exit).first().event?.payload)
        assertEquals("西湖景区", payload["name"]?.toString()?.trim('"'))
        assertNotNull(h.events(EventKind.fence_exit).first().event?.ttlMs, "越界这条必须带 ttl，屏要拿它判过期")

        h.runtime.onFix(fenceCenter.first, fenceCenter.second, GnssSource.phone, 1.2, 2.0)
        h.tick()
        assertEquals(1, h.events(EventKind.fence_enter).size)
    }

    @Test
    fun `关掉围栏期间重新打开不算越界`() = runBlocking {
        val h = harness()
        h.runtime.setFence(true, "西湖景区", fenceCenter.first, fenceCenter.second, fenceRadius)
        h.runtime.onFix(fenceCenter.first, fenceCenter.second, GnssSource.phone, 1.2, 2.0)
        h.tick()
        h.runtime.setFence(false, "西湖景区")
        h.tick()
        h.runtime.onFix(30.2841, fenceCenter.second, GnssSource.phone, 1.2, 2.0)
        h.tick()
        assertTrue(h.events(EventKind.fence_exit).isEmpty(), "围栏没开的时候越不越界都不该打扰屏幕")
    }

    @Test
    fun `窗口打开那一拍报 sat_visible`() {
        val h = harness()
        // 起点在窗口外（400000 % 720000 > 240000），推进到窗口里那一拍才该报
        runBlocking { h.tick() }
        assertTrue(h.events(EventKind.sat_visible).isEmpty())
        runBlocking {
            h.clock.advance(240_000)
            h.tick()
        }
        assertEquals(1, h.events(EventKind.sat_visible).size)
        assertNotNull(h.events(EventKind.sat_visible).first().event?.ttlMs)
    }

    @Test
    fun `下行正文只在解得出人话时才给屏`() = runBlocking {
        val h = harness()
        h.tick()
        val text = "卫星已收到"
        h.modem.injectDownlink(text.toByteArray(Charsets.UTF_8).toHex())
        h.tick()
        val got = h.events(EventKind.downlink_msg)
        assertEquals(1, got.size, "同一条 hex 不该每拍再报一次")
        assertEquals(text, got.first().event?.payload?.get("text")?.toString()?.trim('"'))

        // 二进制报文：hex 照给，text 明确给一个 null。屏那边解不开就不画这句，
        // 但**这个键得在** —— "解不出人话"是这条事件的一个确定结论，不是没算
        h.modem.injectDownlink(byteArrayOf(0, 1, 2, -1).toHex())
        h.tick()
        assertEquals(2, h.events(EventKind.downlink_msg).size)
        assertEquals(JsonNull, h.events(EventKind.downlink_msg)[1].event?.payload?.get("text"))
        assertEquals("000102FF", h.events(EventKind.downlink_msg)[1].event?.payload?.get("hex").toString().trim('"'))
    }

    @Test
    fun `围栏距离是到边界的距离，出界为负`() = runBlocking {
        val h = harness()
        h.runtime.setFence(true, "西湖景区", fenceCenter.first, fenceCenter.second, fenceRadius)
        h.runtime.onFix(30.2841, fenceCenter.second, GnssSource.phone, 1.2, 2.0)
        h.tick()
        val snap = assertNotNull(h.runtime.status.value.snapshot)
        assertTrue(snap.fence.distM!! < 0, "出界之后 distM 应该是负的，屏上那句「距边界多少米」要用它")
        // 600 - 1112 ≈ -512。容 5% 是因为那个距离按球面大圆算，本来就不精确
        assertTrue(snap.fence.distM!! < -400 && snap.fence.distM!! > -600, "distM=${snap.fence.distM}")
    }

    /**
     * 屏那边的去重环形缓冲跨重连不清空，所以 App 重启后第一条事件的 id
     * 不能和上一轮第一条撞上 —— 撞上了屏直接丢帧，不报错，丢的可能是 `sos_acked`。
     */
    @Test
    fun `重启后的第一条事件不与上一轮撞id`() = runBlocking {
        val first = Harness(1_795_200_000_000L + 400_000)
        val second = Harness(1_795_200_000_000L + 400_000)
        for (h in listOf(first, second)) {
            h.runtime.setFence(true, "西湖景区", fenceCenter.first, fenceCenter.second, fenceRadius)
            h.runtime.onFix(fenceCenter.first, fenceCenter.second, GnssSource.phone, 1.2, 2.0)
            h.tick()
            h.runtime.onFix(30.2841, fenceCenter.second, GnssSource.phone, 1.2, 2.0)
            h.tick()
        }
        val a = assertNotNull(first.events(EventKind.fence_exit).first().event?.eventId)
        val b = assertNotNull(second.events(EventKind.fence_exit).first().event?.eventId)
        assertTrue(a != b, "两次启动的 event_id 撞了：$a —— 屏会把它当重复直接丢掉")
        // 屏那边 strncpy 到 39 字符就截，截出来的公共前缀会被判成同一条
        assertTrue(a.length <= 39, "event_id 超过屏侧 EVENT_ID_MAX，会被截断成两个 id 共用一段：$a")
    }
}
