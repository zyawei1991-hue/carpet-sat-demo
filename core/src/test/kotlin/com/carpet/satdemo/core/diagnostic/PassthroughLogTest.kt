package com.carpet.satdemo.core.diagnostic

import com.carpet.satdemo.core.diagnostic.PassthroughLog.Companion.MAX_LINE_CHARS
import com.carpet.satdemo.core.modem.MonoClock
import com.carpet.satdemo.core.modem.RawLine
import com.carpet.satdemo.core.modem.SimulatedModem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 工程模式日志窗（E.10 #10）。测试盯着三件事：
 * 坏行有没有被留下、窗口有没有界限、时间是不是同一个钟。
 */
class PassthroughLogTest {

    private class Clock(startMs: Long = 1_000) {
        var now = startMs
        val mono = MonoClock { now }
        fun advance(ms: Long) {
            now += ms
        }
    }

    @Test
    fun `合法语句标绿 带上动词`() {
        val fx = Clock()
        val log = PassthroughLog(clock = fx.mono)
        val good = sentence("GS", "SFI", listOf("0", "7", "1"))
        val line = log.record(RawLine(RawLine.Direction.FROM_MODULE, good))
        assertTrue(line.checksumOk == true)
        assertEquals("SFI", line.verb)
        assertEquals(fx.now, line.atMs)
        assertNull(line.detail)
    }

    @Test
    fun `校验和错的行照样进窗 而且要说清错在哪`() {
        val log = PassthroughLog()
        val mangled = sentence("GS", "SFI", listOf("0", "7", "1"))
        val broken = mangled.dropLast(2) + if (mangled.endsWith("00")) "01" else "00"
        val line = log.record(RawLine.Direction.FROM_MODULE, broken)
        assertEquals(false, line.checksumOk, "校验和被改动正是半工口读丢一位的物证，不能不记")
        assertTrue("期望" in (line.detail ?: ""), "要给具体数字：${line.detail}")
        assertEquals(1, log.badFrames)
    }

    @Test
    fun `不是语句的行标成未知 而不是坏`() {
        val log = PassthroughLog()
        val line = log.record(RawLine.Direction.UNCLASSIFIED, "BOOT: module ready")
        assertNull(line.checksumOk, "开机打印不是坏包，标红了工程师就会去查串口")
        assertNull(line.verb)
        assertEquals(0, log.badFrames)
    }

    @Test
    fun `窗口有界 丢最旧 并且知道自己丢过多少`() {
        val log = PassthroughLog(capacity = 3)
        repeat(5) { log.note("第 $it 条") }
        assertEquals(3, log.size)
        assertEquals(2, log.dropped)
        assertEquals(listOf("第 2 条", "第 3 条", "第 4 条"), log.snapshot().map { it.text })
        // 标题行必须把"滚过了几条"讲出来，否则工程师以为看到的就是全部
        assertTrue("已滚过 2 条" in log.header(null, null))
    }

    @Test
    fun `长行截断但保留原长`() {
        val log = PassthroughLog()
        val long = sentence("CC", "SFQ", listOf("0", "0", "A".repeat(400)))
        val line = log.record(RawLine.Direction.TO_MODULE, long)
        assertEquals(MAX_LINE_CHARS, line.text.length)
        assertTrue(line.truncated)
        assertEquals(long.length, line.rawLength)
        assertTrue("原长" in line.render(), "屏上要说清被截掉了多少：${line.render()}")
    }

    @Test
    fun `时间戳来自调用方 不是窗自己读系统时间`() {
        val fx = Clock(startMs = 5_000)
        val log = PassthroughLog(clock = fx.mono)
        val a = log.note("A")
        fx.advance(1_500)
        val b = log.note("B")
        assertEquals(5_000, a.atMs)
        assertEquals(6_500, b.atMs)
        // IO 线程带进来的时刻要能盖过默认值：同一轮问答的两行必须用同一个时间基
        val c = log.record(RawLine.Direction.FROM_MODULE, "x", atMs = 6_600)
        assertEquals(6_600, c.atMs)
    }

    @Test
    fun `方向是窗口的第一信息 谁发的比发了什么更难查`() {
        val log = PassthroughLog()
        log.record(RawLine.Direction.TO_MODULE, sentence("CC", "CEQ", emptyList()))
        log.record(RawLine.Direction.FROM_MODULE, sentence("GS", "CES", listOf("0", "0")))
        assertEquals(listOf("TX", "RX"), log.snapshot().map { it.direction.label })
    }

    @Test
    fun `clear 把计数一起归零`() {
        val log = PassthroughLog(capacity = 1)
        log.note("a")
        log.note("b")
        assertEquals(1, log.dropped)
        log.clear()
        assertEquals(0, log.size)
        assertEquals(0, log.dropped)
        assertFalse("已滚过" in log.header(null, null))
    }

    @Test
    fun `标题行把模组能力档和队列一起报出来`() {
        val log = PassthroughLog(capacity = 10)
        val h = log.header("GM1003/等级1/短接入", "待补发 2 条 / 已交模组 1 条（额度 50），挤掉 0 条，捡回 0 条")
        assertTrue("模组 GM1003" in h)
        assertTrue("待补发 2 条" in h)
    }

    @Test
    fun `窗口是模组接口的一部分 真机靠它 彩排也靠它`() {
        // 这条测的是接线形状：任何 SatelliteModem 实现都能被灌进同一个窗口，
        // 屏上那个视图因此不必知道自己面对的是 SDK、语句层还是替身
        val log = PassthroughLog()
        val modem = SimulatedModem(clock = MonoClock { 7_000 }, sleep = { })
        modem.attachPassthrough(log)
        assertEquals(1, log.size, "替身没有线路，至少要报一句「我是替身」")
        assertEquals(RawLine.Direction.UNCLASSIFIED, log.snapshot().single().direction)
        modem.simulateDropped("1100002F")
        assertTrue(log.snapshot().any { "丢弃" in it.text }, "静默丢弃必须在窗里看得见")
    }

    /** 组一条校验和正确的语句（走协议层自己算，别在这里抄一个数字） */
    private fun sentence(speaker: String, verb: String, fields: List<String>): String {
        val body = speaker + verb + if (fields.isEmpty()) "" else "," + fields.joinToString(",")
        val sum = body.toByteArray(Charsets.US_ASCII).fold(0) { acc, b -> acc xor b.toInt() } and 0xFF
        return "$" + body + "*%02X".format(sum)
    }
}
