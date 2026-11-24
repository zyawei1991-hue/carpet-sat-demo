package com.carpet.satdemo.core.guard

import com.carpet.satdemo.core.devices.Device
import com.carpet.satdemo.core.devices.DeviceKind
import com.carpet.satdemo.core.devices.DeviceLink
import com.carpet.satdemo.core.devices.Transport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 断联提醒的判定规则。这里钉的全是**误报**方向的条件 —— 这个功能第一次响错，
 * 用户就把开关关了，之后真留在车里的孩子就再也没有提醒了。
 *
 * 时间全由调用方喂（没有 sleep），所以"宽限期"这件事是可证的而不是抽到的。
 */
class LeaveBehindWatchTest {

    private fun dev(
        id: String,
        kind: DeviceKind = DeviceKind.DisplayScreen,
        link: DeviceLink = DeviceLink.Online(0),
    ) = Device(id = id, kind = kind, title = id, transport = Transport.LanWs, link = link)

    private val t0 = 1_795_200_000_000L

    private fun armed(vararg devices: Device): LeaveBehindWatch {
        val w = LeaveBehindWatch()
        assertNull(w.arm(devices.toList(), t0), "布防该成功：${devices.map { it.id }}")
        return w
    }

    @Test
    fun `没有连着车的设备就不许开这个开关`() {
        val w = LeaveBehindWatch()
        // 卫星模组和手机自己都跟着人走，拿它们当"留在车上的那台"是错的判据
        val why = w.arm(
            listOf(
                dev("sat:gt1005", DeviceKind.SatelliteModule),
                dev("phone:self", DeviceKind.PhoneSensor),
            ),
            t0,
        )
        assertNotNull(why, "台账里没有一台是可能留在车上的，开关不能开")
        assertEquals(LeaveBehindWatch.Phase.Idle, w.phase)
        assertTrue(w.tick(listOf(), 500.0, t0 + 600_000).isEmpty(), "没布成就一条都不该说")
    }

    @Test
    fun `已经离线的那台不算盯住了`() {
        val w = LeaveBehindWatch()
        assertNotNull(
            w.arm(listOf(dev("screen:A", link = DeviceLink.Offline(t0 - 9_000, "链路断开"))), t0),
            "布防时就已经断着的设备，断联这件事没有起点，不该让它凑数",
        )
    }

    @Test
    fun `断联加离开圈外才报，缺一不可`() {
        val w = armed(dev("screen:A"))
        // 人还在圈里，屏断了 —— 更像屏自己重启
        val early = w.tick(listOf(dev("screen:A", link = DeviceLink.Offline(t0 + 5_000))), null, t0 + 5_000)
        assertEquals(1, early.size)
        assertTrue(early.first() is LeaveBehindWatch.Note.Lost)
        val inPlace = w.tick(listOf(dev("screen:A", link = DeviceLink.Offline(t0 + 5_000))), null, t0 + 70_000)
        assertEquals(listOf("InPlace"), inPlace.map { it::class.simpleName })

        // 宽限期够了、人也出了圈 —— 这一下才该响
        val fire = w.tick(listOf(dev("screen:A", link = DeviceLink.Offline(t0 + 5_000))), 120.0, t0 + 71_000)
        val f = fire.single() as LeaveBehindWatch.Note.Fire
        assertEquals(120L, f.awayM)
        assertEquals(LeaveBehindWatch.Phase.Alarmed, w.phase)

        // 同一场断联不重复响
        assertTrue(
            w.tick(listOf(dev("screen:A", link = DeviceLink.Offline(t0 + 5_000))), 400.0, t0 + 80_000).isEmpty(),
            "已经报过就该闭嘴，每拍重响等于把用户的通知栏点着",
        )
    }

    @Test
    fun `宽限期内接回来就不报`() {
        val w = armed(dev("screen:A"))
        w.tick(listOf(dev("screen:A", link = DeviceLink.Offline(t0 + 3_000))), null, t0 + 3_000)
        val back = w.tick(listOf(dev("screen:A")), null, t0 + 11_000)
        assertEquals(listOf("Back"), back.map { it::class.simpleName })
        assertEquals(LeaveBehindWatch.Phase.Armed, w.phase, "屏重启十几秒是常事，不该攒成一次告警")
        // 之后真的走远了也不该拿这次旧断联报账
        assertTrue(w.tick(listOf(dev("screen:A")), null, t0 + 200_000).isEmpty())
    }

    @Test
    fun `报过之后重新连上算解除，下一场断联还能再报`() {
        val w = armed(dev("screen:A"))
        val gone = dev("screen:A", link = DeviceLink.Offline(t0 + 5_000))
        w.tick(listOf(gone), null, t0 + 5_000)
        assertTrue(w.tick(listOf(gone), 150.0, t0 + 70_000).first() is LeaveBehindWatch.Note.Fire)
        val back = w.tick(listOf(dev("screen:A")), 150.0, t0 + 90_000).single() as LeaveBehindWatch.Note.Back
        assertTrue(back.wasAlarmed, "刚才那条报警要能被认领掉，否则通知栏留的是一句过期结论")
        assertEquals(LeaveBehindWatch.Phase.Armed, w.phase)
        // 第二场
        w.tick(listOf(gone), null, t0 + 100_000)
        assertTrue(
            w.tick(listOf(gone), 200.0, t0 + 170_000).first() is LeaveBehindWatch.Note.Fire,
            "重新连上之后再断，是一场新事件，该再报",
        )
    }

    /** 契约不匹配、没授权的屏是"不能说"，不是"不在" —— 判成断联会天天误报 */
    @Test
    fun `降级不等于断联`() {
        val w = armed(dev("screen:A"))
        val degraded = listOf(dev("screen:A", link = DeviceLink.Degraded(t0, "契约版本不匹配")))
        assertTrue(w.tick(degraded, null, t0 + 1_000).isEmpty())
        assertTrue(w.tick(degraded, 300.0, t0 + 300_000).isEmpty())
    }

    /** 白名单里被移掉的屏会从台账整条消失（DeviceBus.drop），这也是一种"不在了" */
    @Test
    fun `台账里没了的那台按断联算`() {
        val w = armed(dev("screen:A"))
        w.tick(listOf(), null, t0 + 5_000)
        val fire = w.tick(listOf(), 90.0, t0 + 70_000)
        assertTrue(fire.first() is LeaveBehindWatch.Note.Fire)
    }

    @Test
    fun `撤防之后什么都不报`() {
        val w = armed(dev("screen:A"), dev("kid:watch", DeviceKind.ChildDevice))
        w.disarm()
        assertEquals(LeaveBehindWatch.Phase.Idle, w.phase)
        assertTrue(w.tick(listOf(), 900.0, t0 + 600_000).isEmpty())
    }

    @Test
    fun `盯的是车上的设备，卫星模组掉了不算`() {
        val w = armed(dev("screen:A"), dev("sat:gt1005", DeviceKind.SatelliteModule))
        // 模组跟着手机走，人走远它必然掉；拿它当"留在车上没跟上"等于每次下车都报警
        val notes = w.tick(listOf(dev("screen:A"), dev("sat:gt1005", link = DeviceLink.Offline(t0 + 5_000))), 300.0, t0 + 300_000)
        assertTrue(notes.none { it is LeaveBehindWatch.Note.Fire }, "模组断联不该触发遗留告警：$notes")
    }

    @Test
    fun `圈外距离为零时不报`() {
        val w = armed(dev("screen:A"))
        val gone = listOf(dev("screen:A", link = DeviceLink.Offline(t0 + 5_000)))
        w.tick(gone, null, t0 + 5_000)
        // outsideM = 0 是"正踩在边界上"，不是离开；负数更不会给
        assertTrue(w.tick(gone, 0.0, t0 + 300_000).none { it is LeaveBehindWatch.Note.Fire })
    }
}
