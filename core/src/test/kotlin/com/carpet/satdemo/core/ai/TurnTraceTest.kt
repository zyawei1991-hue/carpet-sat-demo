package com.carpet.satdemo.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §5.1 四段打点的形状与配对。假时钟：每个数都得是能当场核死的整数 */
class TurnTraceTest {

    private var now = 0L
    private val lines = mutableListOf<String>()
    private fun trace() = TurnTrace(sink = { lines.add(it) }, nowMs = { now })

    @Test
    fun `屏起一轮 四个点两个数 全程齐`() {
        val t = trace()
        now = 1_000; t.asrDone("t1")
        now = 2_000; t.requestSent("t1")
        now = 5_000; t.firstToken("t1")
        now = 6_500; t.firstAudio("t1")
        assertEquals(2, lines.size)
        assertEquals("打点 t1：识别完→发出 1000 ms，发出→首字回 3000 ms", lines[0])
        assertEquals("打点 t1：首字回→首帧推屏 1500 ms，全程（识别完→首帧上屏）5500 ms", lines[1])
    }

    @Test
    fun `手机起头没有识别那一段 日志不假装有`() {
        val t = trace()
        now = 100; t.requestSent("t2")
        now = 900; t.firstToken("t2")
        now = 1_400; t.firstAudio("t2")
        assertEquals("打点 t2：发出→首字回 800 ms", lines[0])
        // 无 asr → 第二行不带"全程"
        assertEquals("打点 t2：首字回→首帧推屏 500 ms", lines[1])
    }

    @Test
    fun `首帧只报一次 纯文字轮不报第二行也不报错`() {
        val t = trace()
        t.requestSent("t3"); t.firstToken("t3")
        now = 1_000; t.firstAudio("t3")
        val before = lines.size
        t.firstAudio("t3")
        t.firstAudio("t-从未存在")
        assertEquals(before, lines.size)
    }

    @Test
    fun `流式下 firstToken 每句都来 只认第一下`() {
        val t = trace()
        now = 100; t.requestSent("t5")
        now = 500; t.firstToken("t5")
        now = 600; t.firstToken("t5")
        assertEquals(1, lines.size)
        now = 900; t.firstAudio("t5")
        // 首帧推屏从**第一句**起算：这一段的数才是"模型写第一句之外"的全部耗时
        assertEquals("打点 t5：首字回→首帧推屏 400 ms", lines[1])
    }

    @Test
    fun `纯文字答句等不到首帧 表被挤掉不泄漏`() {
        val t = trace()
        repeat(20) { i -> t.requestSent("t$i"); t.firstToken("t$i") }
        // 最早的几条已经被挤出：再来首帧既不该出日志，也不该抛
        lines.clear()
        t.firstAudio("t0")
        assertTrue(lines.isEmpty())
        // 还在表里的照常出
        t.firstAudio("t19")
        assertEquals(1, lines.size)
    }
}
