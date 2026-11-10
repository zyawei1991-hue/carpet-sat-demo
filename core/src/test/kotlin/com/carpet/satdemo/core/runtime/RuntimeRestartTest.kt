package com.carpet.satdemo.core.runtime

import com.carpet.satdemo.core.engine.DemoEngine
import com.carpet.satdemo.core.modem.SimulatedModem
import com.carpet.satdemo.core.screen.ScreenLinkServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 现场那条红字：`ScreenLinkServer can only be started once.`
 *
 * 「停止演示 → 再开始演示」在 App 里就是同一个 [DemoRuntime] 上 `stop()` 再 `start()`，
 * 而 Java-WebSocket 1.5.6 的 `start()` 只看 `selectorthread` 是不是 null，`stop()` 不清它 ——
 * 所以复用同一个 server 对象的话，第二次永远起不来，跟端口、Wi-Fi、屏都没关系。
 *
 * 这里钉三件事：第二次起得来、换的是新对象、上一轮改过的那两样东西没丢。
 */
class RuntimeRestartTest {

    private fun runtime(server: ScreenLinkServer = ScreenLinkServer()) = DemoRuntime(
        engine = DemoEngine(SimulatedModem(), DemoRuntime.DEFAULT_OWNER_ID),
        server = server,
        clockMs = { 1_795_200_000_000L },
    )

    @Test
    fun `stop then start comes up again`() {
        val rt = runtime()
        try {
            rt.start(autoTick = false)
            assertEquals(null, rt.status.value.serverError, "第一次就起不来，先看端口是不是被别的实例占了")

            rt.stop()
            rt.start(autoTick = false)
            assertEquals(null, rt.status.value.serverError, "第二次 start 不许再报 can only be started once")
        } finally {
            rt.shutdown()
        }
    }

    @Test
    fun `restart carries over paired screens and the access switch`() {
        val rt = runtime()
        try {
            rt.start(autoTick = false)
            val first = rt.server
            rt.allowScreen("WS175B-A3F7")
            rt.setAcceptScreens(true)

            rt.stop()
            rt.start(autoTick = false)

            assertTrue(rt.server !== first, "第二次 start 还拿着旧对象，那它一定起不来")
            assertEquals(setOf("WS175B-A3F7"), rt.allowedScreens(), "白名单在第二次 start 后丢了，现场就得在 1.75 寸屏上重新找编号")
            assertTrue(rt.server.accepting, "「允许圆屏接入」在第二次 start 后丢了，屏会被 demo lock 挡在门外")
            assertEquals(0, rt.status.value.attempts, "换的新对象接入计数必须从 0 起，否则现场会把上一轮的尝试当成这一轮的")
        } finally {
            rt.shutdown()
        }
    }
}
