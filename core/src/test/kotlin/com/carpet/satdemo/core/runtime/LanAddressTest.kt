package com.carpet.satdemo.core.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 接入点选哪张网卡。**选错的代价比"没有接入点"更大**：
 * App 会报一个看着正常的 IPv4，屏照着填，永远连不上，而页面上还写着服务在跑。
 */
class LanAddressTest {

    private fun ap(name: String, host: String) = LanAddress(name, host)

    @Test
    fun `蜂窝那一路再像地址也不能报出去`() {
        val picked = pickLanAddress(
            listOf(
                ap("rmnet_data0", "10.66.20.8"),
                ap("wlan0", "192.168.1.23"),
            ),
        )
        assertEquals("wlan0", picked?.iface)
    }

    @Test
    fun `只有蜂窝时宁可空着`() {
        assertNull(pickLanAddress(listOf(ap("rmnet_data0", "10.66.20.8"))))
    }

    @Test
    fun `热点和Wi-Fi同时开着时，两张都算能走的那条路`() {
        // 手机开热点给屏连、自己同时连着路由器 —— 演示现场就是这个形状。
        // 挑中哪张由系统给的顺序决定，但绝不能挑到蜂窝，也不能返回 null
        val picked = pickLanAddress(
            listOf(ap("rmnet_data0", "10.66.20.8"), ap("ap0", "192.168.43.1"), ap("wlan0", "192.168.1.23")),
        )
        assertEquals(true, picked?.iface == "ap0" || picked?.iface == "wlan0")
    }

    @Test
    fun `不会挑到链路本地地址`() {
        val picked = pickLanAddress(
            listOf(
                ap("wlan0", "169.254.7.9"),
                ap("swlan0", "192.168.43.1"),
            ),
        )
        assertEquals("swlan0", picked?.iface)
    }

    @Test
    fun `回环和 0 地址都不算`() {
        assertNull(pickLanAddress(listOf(ap("lo", "127.0.0.1"), ap("wlan0", "0.0.0.0"))))
    }
}
