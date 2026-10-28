package com.carpet.satdemo.core.link

import com.carpet.satdemo.core.modem.SatFailReason
import com.carpet.satdemo.core.modem.SatPath
import com.carpet.satdemo.core.modem.SatResult
import com.carpet.satdemo.core.modem.SatSend
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * §6.1 红线一的守门测试。
 *
 * 前两条是穷举扫描：三态 × 两条路径 × 全部成因 × 有无过境时间 × 有无平台回执，
 * 组合出来的每一句都不许含 [SosCopy.BANNED]。这条测试的价值在于**以后改文案改坏了会当场红**，
 * 而不是等演示现场有人指着屏幕说"刚才那句是不是骗人的"。
 */
class SosCopyTest {

    private val results = SatResult.entries
    private val paths = SatPath.entries
    private val reasons = listOf(null) + SatFailReason.entries.toList()
    private val passes = listOf(null, 0L, 289L)

    @Test
    fun `没有任何一种组合会说出红线上的词`() {
        for (r in results) {
            for (p in paths) {
                for (f in reasons) {
                    for (np in passes) {
                        for (ack in listOf(false, true)) {
                            val line = SosCopy.statusLine(SatSend(r, p, f), np, ack)
                            for (banned in SosCopy.BANNED) {
                                assertFalse(
                                    line.contains(banned),
                                    "组合($r,$p,$f,$np,$ack) 写出了禁用词「$banned」：$line",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** 「卫星已收到」这句只在真的 COMPLETE 时出现，别的态一律不许蹭 */
    @Test
    fun `卫星已收到只在星上真 ACK 时说`() {
        for (r in results) {
            for (p in paths) {
                for (f in reasons) {
                    val line = SosCopy.statusLine(SatSend(r, p, f), 289L)
                    assertEquals(r == SatResult.COMPLETE, line.contains("卫星已收到"), "$r/${p.name}/$f → $line")
                }
            }
        }
    }

    /** 星上 ACK 和平台回执是两回事，界面文案必须能区分（〔规 §3.1.4 注6〕） */
    @Test
    fun `星上 ACK 的文案不等于平台回执的文案`() {
        val satAcked = SosCopy.statusLine(SatSend(SatResult.COMPLETE, SatPath.IMMEDIATE))
        val platformAcked = SosCopy.statusLine(SatSend(SatResult.COMPLETE, SatPath.IMMEDIATE), platformAcked = true)
        assertNotEquals(satAcked, platformAcked)
        assertTrue(platformAcked.contains("平台已"))
        assertFalse(satAcked.contains("平台已"))
    }

    /** 排队 ≠ 已发。SFQ 只证明模组把报文存下来了 */
    @Test
    fun `排队路径说不清过境就不算发出去`() {
        val line = SosCopy.statusLine(SatSend(SatResult.NO_ACK, SatPath.QUEUED, messageId = 7))
        assertTrue(line.contains("终端队列"), "排队态要说清楚是存进了队列：$line")
        assertTrue(line.contains("下次过顶"), "还没过境，必须给出可等的动作：$line")
    }

    /** NO_ACK 和 FAIL 该做的动作不一样（前者等过境、后者换开阔地重发），文案不能撞车 */
    @Test
    fun `没确认和没发出去是两句话`() {
        for (p in paths) {
            for (f in SatFailReason.entries) {
                val noAck = SosCopy.statusLine(SatSend(SatResult.NO_ACK, p), 289L)
                val failed = SosCopy.statusLine(SatSend(SatResult.FAIL, p, f), 289L)
                assertNotEquals(noAck, failed, "$p/$f：发出去没确认 和 压根没发出去 不能同一句话")
                assertTrue(failed.contains(SosCopy.failPhrase(f)), "失败态必须带上成因：$failed")
            }
        }
    }

    @Test
    fun `失败成因逐条都有人话`() {
        // 每种成因都得给出不含"原因未知"的句子，否则现场排障等于没信息
        for (f in SatFailReason.entries) {
            val line = SosCopy.failPhrase(f)
            assertTrue(line.isNotEmpty() && !line.contains("未知"), "$f 没有对应文案")
        }
        assertEquals("原因未知", SosCopy.failPhrase(null))
    }

    /** 星历没到手时显示 0 秒，会被读成"马上就好" —— 这是最坏的一种撒谎 */
    @Test
    fun `没有星历时不说零秒`() {
        assertTrue(SosCopy.nextPassPhrase(null)!!.contains("未知"))
        assertFalse(SosCopy.nextPassPhrase(null)!!.contains("0"))
        assertTrue(SosCopy.nextPassPhrase(289L)!!.contains("4 分 49 秒"))
        assertTrue(SosCopy.nextPassPhrase(-1L)!!.contains("窗口内"))
    }
}
