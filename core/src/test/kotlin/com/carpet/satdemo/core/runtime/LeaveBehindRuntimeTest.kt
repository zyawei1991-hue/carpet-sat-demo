package com.carpet.satdemo.core.runtime

import com.carpet.satdemo.core.devices.Device
import com.carpet.satdemo.core.devices.DeviceKind
import com.carpet.satdemo.core.devices.DeviceLink
import com.carpet.satdemo.core.devices.Transport
import com.carpet.satdemo.core.engine.DemoEngine
import com.carpet.satdemo.core.guard.LeaveBehindWatch
import com.carpet.satdemo.core.modem.MonoClock
import com.carpet.satdemo.core.modem.SimulatedModem
import com.carpet.satdemo.core.protocol.GnssSource
import com.carpet.satdemo.core.queue.OutboxClass
import com.carpet.satdemo.core.screen.ScreenLinkServer
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 断联提醒从「布防」到「一条报文写进模组」的那一段。
 *
 * 单测 [LeaveBehindWatchTest] 钉的是判据，这一份钉的是接线：
 * 停车圈由谁画、报警那一路走不走得到 outbox、以及那条报文在最坏一档能不能一片装下。
 * 这三件事以前都没有主人 —— 围栏开关只传名字不传圆，所以 App 侧那个围栏永远越不了界，
 * 断联提醒要是照抄那条路，就是一个开起来永远不会响的开关。
 */
class LeaveBehindRuntimeTest {

    private class Clock(var now: Long) : MonoClock {
        override fun nowMs(): Long = now
        fun advance(ms: Long) { now += ms }
    }

    private val car = Pair(30.2741, 120.1551)

    private class Harness(startMs: Long) {
        val clock = Clock(startMs)
        val modem = SimulatedModem(
            clock = clock,
            passWindowMs = Long.MAX_VALUE / 4,
            txLatencyMs = 0,
            sleep = { },
        )
        val runtime = DemoRuntime(
            engine = DemoEngine(modem, DemoRuntime.DEFAULT_OWNER_ID),
            server = ScreenLinkServer(),
            clockMs = { clock.now },
        )
        val notes = CopyOnWriteArrayList<LeaveBehindWatch.Note>()

        init {
            runtime.onLeaveBehind = { notes += it }
        }

        fun tick() = runBlocking { runtime.tickOnce() }
        fun fix(lat: Double, lon: Double) = runtime.onFix(lat, lon, GnssSource.phone, 1.2, 2.0)
        fun screen(link: DeviceLink) =
            Device("screen:WS175B-A3F7", DeviceKind.DisplayScreen, "WS175B-A3F7", Transport.LanWs, link)
    }

    private fun harness() = Harness(1_795_200_000_000L + 400_000)

    @Test
    fun `没定位时不许布防`() {
        val h = harness()
        h.runtime.devices.upsert(h.screen(DeviceLink.Online(h.clock.now)))
        val why = h.runtime.armLeaveBehind()
        assertNotNull(why, "画不出停车圈就不能开这个开关")
        assertTrue(why!!.contains("定位"))
        assertEquals(LeaveBehindWatch.Phase.Idle, h.runtime.leaveBehind.phase)
    }

    @Test
    fun `布防要看得见车上那台设备`() {
        val h = harness()
        // 台账里只有跟着人走的卫星模组
        h.runtime.devices.upsert(
            Device("sat:gt1005", DeviceKind.SatelliteModule, "GT1005", Transport.Uart, DeviceLink.Online(h.clock.now)),
        )
        h.fix(car.first, car.second)
        assertNotNull(h.runtime.armLeaveBehind(), "没有一台可能留在车上的设备时，必须给用户一个原因")
    }

    @Test
    fun `走远且断链满宽限期，报警落到卫星队列`() {
        val h = harness()
        h.runtime.devices.upsert(h.screen(DeviceLink.Online(h.clock.now)))
        h.fix(car.first, car.second)
        assertNull(h.runtime.armLeaveBehind(radiusM = 80.0, holdMs = 60_000))
        h.tick()
        assertTrue(h.notes.isEmpty(), "布防那一拍什么都没有发生，不该说话")

        // 人往北走 0.004° ≈ 445 米，出圈；屏在 5 秒前就掉了
        h.runtime.devices.relink("screen:WS175B-A3F7", DeviceLink.Offline(h.clock.now, "链路断开"))
        h.fix(car.first + 0.004, car.second)
        h.clock.advance(10_000)
        h.tick()
        assertTrue(h.notes.none { it is LeaveBehindWatch.Note.Fire }, "宽限期还没到就该忍着")
        assertEquals(listOf("Lost"), h.notes.map { it::class.simpleName })

        h.clock.advance(60_000)
        h.tick()
        val fire = h.notes.filterIsInstance<LeaveBehindWatch.Note.Fire>().single()
        assertTrue(fire.awayM > 0, "报警那句要带上离开了多少米：${fire.awayM}")
        // tick 那一拍里的上星是 scope.launch（和 pressSos 同一形状，单测不等协程），
        // 这里 await 的是同一个函数，钉的是它编出来的帧和它落的档位
        runBlocking { h.runtime.sendLeaveBehindAlarm(fire) }
        val hex = h.modem.sent.last()
        // 90 个十六进制字符 = 最坏那一档（L1，且长度单位按 hex 字符算）一片的上限，
        // 和 ProtocolTest 那条同一个口径。报警要是一片就发完，分片在没网的现场等于多五倍失败概率
        assertTrue(hex.length <= 90, "告警这一片超出最坏一档的预算：$hex")
        assertTrue(
            h.runtime.engine.outbox.handedHexes.contains(hex),
            "交给模组的告警要留盯梢副本，模组偷偷丢了才捡得回来",
        )

        // 模组收不下的那一路：整片必须留在手机队列里，并且是按 Alarm 档，
        // 这样队满被挤的时候被踢的是遥测而不是这一条。时间过一分钟是为了换个 hex，
        // 否则同一条报文会被 outbox 当重复拒收
        h.modem.reachable = false
        h.clock.advance(61_000)
        runBlocking { h.runtime.sendLeaveBehindAlarm(fire) }
        val parked = h.runtime.engine.outbox.peek(h.clock.now)
        assertTrue(parked.any { it.kind == OutboxClass.Alarm }, "没写出去的告警得留在队里等补发：$parked")
    }

    @Test
    fun `重连上就不报警，也不往卫星发东西`() {
        val h = harness()
        h.runtime.devices.upsert(h.screen(DeviceLink.Online(h.clock.now)))
        h.fix(car.first, car.second)
        assertNull(h.runtime.armLeaveBehind(radiusM = 80.0, holdMs = 60_000))
        h.runtime.devices.relink("screen:WS175B-A3F7", DeviceLink.Offline(h.clock.now, "链路断开"))
        h.fix(car.first + 0.004, car.second)
        h.clock.advance(20_000)
        h.tick()
        h.runtime.devices.relink("screen:WS175B-A3F7", DeviceLink.Online(h.clock.now))
        h.clock.advance(120_000)
        h.tick()
        assertTrue(h.notes.none { it is LeaveBehindWatch.Note.Fire })
        assertTrue(h.notes.any { it is LeaveBehindWatch.Note.Back })
        // 只钉"没发告警"，不钉"没发任何东西"：1Hz 那圈自己会探路、发心跳，那些和这件事无关
        assertTrue(
            h.modem.sent.none { isAlarmFrame(it) },
            "没报警就不该往卫星写一条告警：${h.modem.sent}",
        )
        assertTrue(h.runtime.engine.outbox.peek(h.clock.now).none { it.kind == OutboxClass.Alarm })
    }

    /** 告警帧认正文：19 字节骨架 + 1 字节分片号之后就是 "LB:"（0x4C 0x42 0x3A） */
    private fun isAlarmFrame(hex: String): Boolean =
        hex.length >= 46 && hex.substring(40).startsWith("4C423A")
}
