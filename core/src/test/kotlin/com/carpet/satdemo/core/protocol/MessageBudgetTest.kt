package com.carpet.satdemo.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 报文预算（E.10 #10）。这几条测试守的是一件事：
 * **一帧/一片装多少字节只能从注入的 `ICI` 等级推出来**，常数不许从别处漏进来。
 */
class MessageBudgetTest {

    @Test
    fun `最坏档下 L1 只折出 45 字节 一片 24 字节`() {
        val b = MessageBudget(MsgLengthLevel.L1)
        assertEquals(LengthUnit.WORST_KNOWN, b.unit)
        assertEquals(45, b.dataFieldBytes)
        // 20 字节骨架 + 1 字节分片头
        assertEquals(21, b.frameOverheadBytes)
        assertEquals(24, b.chunkBytes)
        assertEquals(24 * 15, b.maxPayloadBytes)
    }

    @Test
    fun `单位口径是唯一被 §9 13 决定的那一维 换了它结构不动`() {
        val bytes = MessageBudget(MsgLengthLevel.L1, LengthUnit.BYTES)
        assertEquals(90, bytes.dataFieldBytes)
        assertEquals(69, bytes.chunkBytes)
        assertTrue(bytes.fitsFrame(SosFrame.FRAME_LEN))
    }

    @Test
    fun `骨架按最低等级也发得出去 这是 E4 那个架构结论的落点`() {
        MsgLengthLevel.entries.forEach { level ->
            assertTrue(caps(level).budget().fitsFrame(SosFrame.FRAME_LEN), "$level 该装得下一条 20 字节骨架")
        }
        assertFalse(caps(MsgLengthLevel.L1).budget().fitsFrame(46))
    }

    @Test
    fun `装不下时回答发不了 而不是拆成十六片`() {
        val b = MessageBudget(MsgLengthLevel.L1)
        assertEquals(2, b.fragments(40))
        assertEquals(Int.MAX_VALUE, b.fragments(b.maxPayloadBytes + 1))
        val e = assertFailsWith<IllegalArgumentException> {
            SosFrameEncoder(12_061L).encodeExt(ByteArray(b.maxPayloadBytes + 1), 30.0, 120.0, WHEN_SEC, 50, b)
        }
        assertTrue("上限" in e.message!!, "拒发要说清上限是多少：${e.message}")
    }

    @Test
    fun `等级换了 同样的载荷片数跟着换`() {
        val payload = ByteArray(200)
        // 字符口径：L1 一片 24 B → 9 片；L4 一片 194 B → 2 片
        assertEquals(9, MessageBudget(MsgLengthLevel.L1).fragments(payload.size))
        assertEquals(2, MessageBudget(MsgLengthLevel.L4).fragments(payload.size))
    }

    @Test
    fun `分片按注入的预算切 不是按写死的 24`() {
        val budget = MessageBudget(MsgLengthLevel.L4, LengthUnit.BYTES) // 一片 409 B
        val parts = SosFrameEncoder(12_061L)
            .encodeExt(ByteArray(300), 30.0, 120.0, WHEN_SEC, 50, budget)
        assertEquals(1, parts.size, "300 B 在 409 B 的片里就该一片装完")
        assertEquals(300, decodeExtFrames(parts).size)
    }

    @Test
    fun `预算比 ICI 原始数值小 因为单位口径没定死`() {
        val c = caps(MsgLengthLevel.L3)
        assertEquals(230, c.maxMessageBytes)
        assertEquals(115, c.budget().dataFieldBytes)
        // 直接拿 maxMessageBytes 判断就是拿"猜大了"的那一头赌 SOS
        assertTrue(c.budget().fitsFrame(100))
        assertFalse(c.budget().fitsFrame(116))
    }

    private fun caps(level: MsgLengthLevel) = ModuleCaps.worstKnown().copy(lengthLevel = level)

    companion object {
        const val WHEN_SEC = 1_795_200_000L
    }
}
