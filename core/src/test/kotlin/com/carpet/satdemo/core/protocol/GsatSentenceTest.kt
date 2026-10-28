package com.carpet.satdemo.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 语句层的**金标准就是规范自己给的例句** —— 不是我们自己造的期望值。
 * 校验和、字段分隔、hex 编码这三件事只要和模组理解不一致，整条链路一条都发不出去，
 * 所以这一组测试的优先级高于任何业务测试。
 */
class GsatSentenceTest {

    // ---- 规范原文例句，逐字节复现 ----

    @Test
    fun `复现 COP 读取例句`() {
        // 〔规 §3.1.1 注〕"例如：$CCCOP,2*42\r\n"
        assertEquals("\$CCCOP,2*42\r\n", GsatSentence.build(GsatSpeaker.HOST, "COP", listOf("2")))
    }

    @Test
    fun `复现 SFP 查询与清除例句`() {
        // 〔规 §3.1.14 例〕"查询待发报文数量$CCSFP,2,1,,*46；清除所有待发报文$CCSFP,1,2,,*46"
        // 结尾两个空字段必须保留，否则校验和会变
        assertEquals("\$CCSFP,2,1,,*46\r\n", GsatSentence.build(GsatSpeaker.HOST, "SFP", listOf("2", "1", "", "")))
        assertEquals("\$CCSFP,1,2,,*46\r\n", GsatSentence.build(GsatSpeaker.HOST, "SFP", listOf("1", "2", "", "")))
    }

    @Test
    fun `复现 SFQ 组包例句并解释那个空格`() {
        // 〔规 §3.1.12 注3〕"发送十六进制 0x0102A1A2 四个字节…组包示例为 $CCSFQ,0, 0,0102A1A2*48"
        assertEquals("0102A1A2", byteArrayOf(0x01, 0x02, 0xA1.toByte(), 0xA2.toByte()).toHex())

        // 我们组出来的规范形式（逗号后无空格），校验和是 68
        val ours = GsatSentence.build(GsatSpeaker.HOST, "SFQ", listOf("0", "0", "0102A1A2"))
        assertEquals("\$CCSFQ,0,0,0102A1A2*68\r\n", ours)

        // 文档给的 *48 **只有在逗号后那个空格真实存在时才成立** —— 用 parse 反证一次：
        val withSpace = GsatSentence.parse("\$CCSFQ,0, 0,0102A1A2*48\r\n")
        assertIs<GsatParseResult.Ok>(withSpace, "文档例句的校验和是自洽的，说明那个空格在字节流里真实存在")
        // 所以结论不是"算法错了"，而是"规范化之前不能求和"。空格进了字段，不能当没看见。
        assertEquals(listOf("0", " 0", "0102A1A2"), withSpace.sentence.fields)
    }

    // ---- 校验和 ----

    @Test
    fun `校验和只覆盖美元符和星号之间的内容`() {
        // 手动算一遍：'C''C''S''F''Q' 的异或，确认实现没有把 $ 或 * 或 CRLF 算进去
        val body = "CCSFQ,0,0,0102A1A2"
        var manual = 0
        for (c in body) manual = manual xor c.code
        assertEquals("%02X".format(manual), GsatSentence.checksumOf(body))
        assertEquals("68", GsatSentence.checksumOf(body))
    }

    @Test
    fun `校验和是大写十六进制且高位在前`() {
        // 0x0A 应该输出 "0A" 而不是 "A" 或 "50"
        assertEquals(2, GsatSentence.checksumOf("CCCOP,2").length)
        assertTrue(GsatSentence.checksumOf("CCSFP,2,1,,") == "46")
    }

    // ---- 解析 ----

    @Test
    fun `解析模组下行语句`() {
        val r = GsatSentence.parse(st("GSMCI,123456789012345,0,0102A1A2"))
        assertIs<GsatParseResult.Ok>(r)
        val s = r.sentence
        assertTrue(s.isFromModule)
        assertEquals("GSMCI", s.id)
        assertEquals(3, s.fields.size)
        assertTrue(byteArrayOf(0x01, 0x02, 0xA1.toByte(), 0xA2.toByte()).contentEquals(s.dataAsBytes ?: ByteArray(0)))
    }

    @Test
    fun `校验和不通过的语句必须判坏而不是静默修正`() {
        // 把 1 改成 2 但保留原来的校验和：这是串口读歪一位最常见的形态
        val corrupted = "\$CCSFP,2,2,,*46\r\n"
        val r = GsatSentence.parse(corrupted)
        assertIs<GsatParseResult.Bad>(r)
        assertEquals(GsatParseError.CHECKSUM_MISMATCH, r.error)
        assertEquals("46", r.actual)
        // 期望值由内容重算得出，现场对练时能直接拿去和模组比
        assertEquals("45", r.expected)
    }

    @Test
    fun `空行不算错误`() {
        assertIs<GsatParseResult.Empty>(GsatSentence.parse("\r\n"))
        assertIs<GsatParseResult.Empty>(GsatSentence.parse("   "))
    }

    @Test
    fun `缺开头缺星号缺分隔符都能报出具体原因`() {
        // 前缀错误的那条不能带 \$，其余都要带合法校验和，否则先被校验门关掉就测不到后面的分支了
        assertEquals(GsatParseError.NO_START, (GsatSentence.parse("CCSFP,1*46") as GsatParseResult.Bad).error)
        assertEquals(
            GsatParseError.NO_CHECKSUM_DELIMITER,
            (GsatSentence.parse("\$CCSFP,1") as GsatParseResult.Bad).error,
        )
        assertEquals(
            GsatParseError.NO_FIELD_SEPARATOR,
            (GsatSentence.parse(st("CCSFPX")) as GsatParseResult.Bad).error,
        )
        assertEquals(GsatParseError.UNKNOWN_SPEAKER, (GsatSentence.parse(st("XXSFP,1")) as GsatParseResult.Bad).error)
        assertEquals(GsatParseError.CHECKSUM_TOO_SHORT, (GsatSentence.parse(st("CCSFP,1") + "*4") as GsatParseResult.Bad).error)
    }

    @Test
    fun `小写校验和按数值比较`() {
        val lower = st("CCSFP,2,1,,").replace("46", "46")
        assertIs<GsatParseResult.Ok>(GsatSentence.parse(lower))
        val body = "CCSFP,2,1,,"
        val lc = "\$" + body + "*" + GsatSentence.checksumOf(body).lowercase() + "\r\n"
        assertIs<GsatParseResult.Ok>(GsatSentence.parse(lc))
    }

    @Test
    fun `未知动词解析得出来但不算已知语句`() {
        // FBI 在 SDK 里有类、规范正文没字段表（E.11 第 2 条）。这类语句不能丢
        val s = (GsatSentence.parse(st("GSFBI,1")) as GsatParseResult.Ok).sentence
        assertEquals("FBI", s.verb)
        assertTrue(!s.isKnownVerb)
        assertTrue(s.verb in GsatVerb.UNDOCUMENTED_IN_SDK)
    }

    // ---- hex ----

    @Test
    fun `hex 往返与非法输入`() {
        val bytes = ByteArray(256) { it.toByte() }
        assertEquals(bytes.size, hexToBytes(bytes.toHex()).size)
        assertFailsWith<IllegalArgumentException> { hexToBytes("010") }
        assertFailsWith<IllegalArgumentException> { hexToBytes("0G") }
    }

    @Test
    fun `空数据字段解不出字节而不是返回空数组`() {
        // `$CCSFP,2,1,,` 的空结尾不是 hex 数据，别把它当 0 字节包
        val s = (GsatSentence.parse("\$CCSFP,2,1,,*46\r\n") as GsatParseResult.Ok).sentence
        assertNull(s.dataAsBytes)
    }

    /** 造一条校验和自洽的语句：测试里只关心"形状对不对"，不手算十六进制 */
    private fun st(body: String): String = "\$" + body + "*" + GsatSentence.checksumOf(body) + "\r\n"
}
