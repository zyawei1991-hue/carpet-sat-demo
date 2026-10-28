package com.carpet.satdemo.core.devices

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 设备总线的看护。测的不是"能不能存"，是那三条会让人在演示现场说谎的行为：
 * 掉线不能被抹掉、1Hz 同步不能把在线时长刷成 0、快照要整份换。
 */
class DeviceBusTest {

    private fun screen(id: String, link: DeviceLink, detail: List<String> = listOf("固件 1.0.0")) = Device(
        id = "screen:$id",
        kind = DeviceKind.DisplayScreen,
        title = id,
        transport = Transport.LanWs,
        link = link,
        detail = detail,
    )

    @Test
    fun `offline keeps the row and the reason`() {
        val bus = DeviceBus()
        bus.upsert(screen("A3F7", DeviceLink.Online(1_000)))
        bus.relink("screen:A3F7", DeviceLink.Offline(9_000, "链路断开"))

        val d = bus.find("screen:A3F7")
        assertTrue(d?.link is DeviceLink.Offline, "断开必须留在台账上，掉线那一刻用户最需要看到它")
        assertEquals(Tone.Alert, d?.tone)
        assertEquals(listOf("固件 1.0.0"), d?.detail, "relink 不能把型号和固件版本一起抹掉")
    }

    @Test
    fun `merge keeps the online instant so uptime is not reset every second`() {
        val bus = DeviceBus()
        bus.upsert(screen("A3F7", DeviceLink.Online(1_000)))
        repeat(60) { bus.merge(screen("A3F7", DeviceLink.Online(2_000L + it))) }

        val link = bus.find("screen:A3F7")?.link as DeviceLink.Online
        assertEquals(1_000L, link.since, "1Hz 重新登记把在线时长刷成 0，现场就看不出这台屏在反复掉线重连")
    }

    @Test
    fun `merge notifies only when something actually changed`() {
        val bus = DeviceBus()
        var hits = 0
        bus.subscribe { hits++ }
        val same = screen("A3F7", DeviceLink.Online(1_000))
        bus.upsert(same)
        hits = 0
        bus.merge(same)
        assertEquals(0, hits, "整份没变的 merge 也在通知，等于每秒把 UI 重建一次")
        bus.merge(same.copy(detail = listOf("固件 1.0.1")))
        assertEquals(1, hits)
    }

    @Test
    fun `degraded is not green`() {
        val bus = DeviceBus()
        bus.upsert(screen("A3F7", DeviceLink.Degraded(1_000, "契约版本不匹配")))
        assertEquals(Tone.Wait, bus.find("screen:A3F7")?.tone)
    }

    @Test
    fun `satellite row sorts first and drop removes it`() {
        val bus = DeviceBus()
        bus.upsert(screen("Z9", DeviceLink.Online(0)))
        bus.upsert(
            Device(
                id = "sat:gt1005",
                kind = DeviceKind.SatelliteModule,
                title = "GM1003",
                transport = Transport.Uart,
                link = DeviceLink.Online(0),
            ),
        )
        assertEquals("sat:gt1005", bus.current().first().id, "卫星终端是这个产品的主轴，第一行必须是它")

        bus.drop("screen:Z9")
        assertNull(bus.find("screen:Z9"))
        assertNotEquals(0, bus.current().size)
    }
}
