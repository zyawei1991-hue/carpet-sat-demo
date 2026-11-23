package com.carpet.satdemo.core.engine

import com.carpet.satdemo.core.modem.MonoClock
import com.carpet.satdemo.core.modem.SimulatedModem
import com.carpet.satdemo.core.protocol.GnssSource
import com.carpet.satdemo.core.protocol.ModuleCaps
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 预设短语发送（L3）在引擎这一层的三条承诺：
 *  - 模组收下了才许往上抬档位，**每一档都不说"对方收到了"**（〔规 §3.1.4 注6〕：星上 ACK ≠ 送达）；
 *  - 一个字节都没写出去的整片进 [com.carpet.satdemo.core.queue.Outbox] 等补发，一条不丢；
 *  - 补发把最后一片交出去的那一刻，档位跟着从"还在手机队列"升上去 —— 那行字不许永远停着骗人。
 */
class DemoEnginePresetTest {

    private class Clock(var now: Long = 1_795_200_000_000L) : MonoClock {
        override fun nowMs(): Long = now
    }

    private fun engine(clock: Clock): Pair<DemoEngine, SimulatedModem> {
        val m = SimulatedModem(clock = clock, txLatencyMs = 0, sleep = { })
            .apply { capsOverride = ModuleCaps.worstKnown() }
        val e = DemoEngine(m, ownerId = 12_061L)
        e.onFix(30.2741, 120.1551, GnssSource.phone, null, 2.0)
        return e to m
    }

    @Test
    fun `模组当场收下档位停在已交终端并留盯梢副本`() = runBlocking {
        val clock = Clock()
        val (e, m) = engine(clock)
        val out = e.sendPreset("遇到危险，请求救援", nowSec = clock.nowMs() / 1000)
        assertEquals(27, out.bytes)
        // 最保守一档：45 字节数据字段 − 21 字节分片头 = 一片 24 字节，27 字节必须拆两片
        assertTrue(out.frames == 2, "27 字节在最保守档应拆 2 片，实得 ${out.frames}")
        assertEquals(out.frames, m.sent.size, "每一片都该写到模组")
        assertEquals(DemoEngine.PresetStage.HandedToTerminal, out.stage)
        // 双写 E.6：交出去 ≠ 从 App 的账上销掉，盯梢副本要在
        assertEquals(out.frames, e.outbox.handedSize)
        assertEquals(0, e.pending.size, "都交出去了，待补发那格就该清空")
        assertTrue(out.statusLine().contains("已交给卫星模组"))
    }

    @Test
    fun `模组不通整片回手机队列一条不丢`() = runBlocking {
        val clock = Clock()
        val (e, m) = engine(clock)
        m.reachable = false
        val out = e.sendPreset("遇到危险，请求救援", nowSec = clock.nowMs() / 1000)
        assertEquals(DemoEngine.PresetStage.InPhoneQueue, out.stage)
        assertEquals(0, m.sent.size)
        assertEquals(out.frames, e.pending.size, "没写出去的片必须整条留在待补发队列")
        assertTrue(out.statusLine().contains("等发送窗口"))
    }

    @Test
    fun `补发交完最后一片档位自动升上去`() = runBlocking {
        val clock = Clock()
        val (e, m) = engine(clock)
        m.reachable = false
        val out = e.sendPreset("好的，收到", nowSec = clock.nowMs() / 1000)
        assertEquals(DemoEngine.PresetStage.InPhoneQueue, e.lastPreset?.stage)
        // 模拟 drain 那一路的回报：每片补发成功回调一次 noteFrameHanded
        e.pending.toList().forEach { e.noteFrameHanded(it) }
        assertEquals(DemoEngine.PresetStage.HandedToTerminal, e.lastPreset?.stage)
        assertEquals(out.text, e.lastPreset?.text)
    }

    @Test
    fun `每一档的措辞都不许诺对方收到`() = runBlocking {
        val clock = Clock()
        val (e, m) = engine(clock)
        val out = e.sendPreset("一切正常，请放心", nowSec = clock.nowMs() / 1000)
        val banned = listOf("已送达", "对方收到", "对方已读", "一定没事")
        for (stage in DemoEngine.PresetStage.entries) {
            val line = out.copy(stage = stage).statusLine()
            assertTrue(banned.none { line.contains(it) }, "$stage 这一档越线: $line")
            assertFalse(line.contains("平台已回执"), "$stage 不许冒充平台: $line")
        }
    }
}
