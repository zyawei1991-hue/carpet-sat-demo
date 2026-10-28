package com.carpet.satdemo.core.modem

import com.carpet.satdemo.core.protocol.GeodeticPosition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 升级写锁（E.10 #6）的两条硬性质：
 *  1. 升级一开始，后来的写入**立刻**被拒（不是排队等 flash 写完 —— 卫星窗口等不起）；
 *  2. 已经在飞的写入不会被升级插队 —— 半条 flash 数据中间塞进一条 SFQ 是最难复现的那种坏。
 */
class ModemGateTest {

    @Test
    fun `升级中立旗后 新写入立刻被拒且不带副作用`() = runBlocking {
        val gate = ModemGate()
        val inside = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        var bodyRan = false
        val upgraded = launch {
            gate.runUpgrade(0L) { progress ->
                bodyRan = true
                progress(30)
                inside.complete(Unit)
                hold.await()
            }
        }
        inside.await()
        assertTrue(gate.isUpgrading)
        assertTrue(bodyRan)
        assertEquals(30, gate.progressPct)

        var sideEffect = 0
        val out = gate.write("SFQ") { sideEffect++; "ok" }
        assertNull(out, "升级期间这次写应该被挡在门外")
        assertEquals(0, sideEffect, "被挡下时 block 一个字都不该执行")

        hold.complete(Unit)
        upgraded.join()
        assertFalse(gate.isUpgrading)
        assertNull(gate.progressPct)
        assertEquals("ok", gate.write("SFQ") { sideEffect++; "ok" })
        assertEquals(1, sideEffect, "锁放开后要立刻放行，不然补发要等到下一个周期")
    }

    @Test
    fun `升级排在在场写入之后 不插队`() = runBlocking {
        val gate = ModemGate()
        val order = ArrayList<String>()
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val writing = launch {
            gate.write("TPI") {
                order += "write-in"
                inside.complete(Unit)
                release.await()
                order += "write-out"
            }
        }
        inside.await()
        val upgrading = launch { gate.runUpgrade(0L) { order += "upgrade" } }

        // 此刻升级已经立旗，但拿不到锁 —— 在等在场写入收尾
        release.complete(Unit)
        joinAll(writing, upgrading)
        assertEquals(listOf("write-in", "write-out", "upgrade"), order, "升级不能插到一条写中间")
    }

    @Test
    fun `不会套第二场升级`() = runBlocking {
        val gate = ModemGate()
        val inside = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val first = launch {
            gate.runUpgrade(0L) {
                inside.complete(Unit)
                hold.await()
            }
        }
        inside.await()
        val second = gate.runUpgrade(0L) { }
        assertNull(second, "第二场升级不该跑起来")
        hold.complete(Unit)
        first.join()
    }

    /** [SatelliteModem.updateLocation] 现在收带名字的类型，这里钉住那条反序陷阱 */
    @Test
    fun `写锁也挡得住 SDK 那个经纬度反序的入口`() = runBlocking {
        val modem = SimulatedModem(clock = { 0L })
        val pos = GeodeticPosition.of(30.2741, 120.1551)
        assertTrue(modem.updateLocation(pos), "空闲时应该推得进去")
        assertEquals(30.2741, assertNotNull(modem.readFix()).position.latitude.degrees, 1e-9)
    }
}
