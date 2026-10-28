package com.carpet.satdemo.core.protocol

/**
 * 「通信单元发送消息长度」这个数到底是**字节**还是 **hex 字符**。
 *
 * 〔规 §3.1.4 注7〕的字面口径是字节，但 `sendMsgSFQ`/`sendMsgMCQ` 传的是 hex 字符串，
 * 厂商没说明等级里的数字按哪一头算（§9 #13）。猜错了两种后果不对称：
 * 按字符算只是少发点载荷，按字节算猜大了模组直接拒 —— 而那时 SOS 已经排到队首了。
 * 所以默认走 [WORST_KNOWN]，等回答来了只换这一个取值。
 */
enum class LengthUnit(val fieldPerByte: Int) {
    /** 〔规 §3.1.4 注7〕字面口径 */
    BYTES(1),

    /** 未确认前的最坏口径：两个 hex 字符才算一个字节 */
    HEX_CHARS(2),
    ;

    companion object {
        val WORST_KNOWN = HEX_CHARS
    }
}

/**
 * 报文预算：**从运行时读到的 `ICI` 等级推出来，调用点不许出现常数。**
 *
 * E.4 的架构结论是"报文骨架按最坏的那一档设计"，靠的就是这里把等级、单位口径、
 * 帧开销三件事一次性算清楚；否则 24 这个数会以魔法常数的形态散进分片逻辑里，
 * 换成等级 5（1900）时没人记得还有几处要跟着改。
 */
data class MessageBudget(val level: MsgLengthLevel, val unit: LengthUnit = LengthUnit.WORST_KNOWN) {

    /** 一条报文的数据字段实际能用多少字节 */
    val dataFieldBytes: Int get() = level.maxBytes / unit.fieldPerByte

    /** 分片帧除了载荷本身要占掉的字节：20 B 帧 + 1 B 分片序号 */
    val frameOverheadBytes: Int get() = EXT_OVERHEAD_BYTES

    /** 一片能装多少载荷字节。等级 1 按字符口径算正好是 24，和原来的常数对上 */
    val chunkBytes: Int get() = (dataFieldBytes - EXT_OVERHEAD_BYTES).coerceAtLeast(0)

    /** 载荷总量上限：frag 高 4 位只放得下 15 片 */
    val maxPayloadBytes: Int get() = chunkBytes * MAX_FRAGMENTS

    /** 整帧（含 20 字节骨架）发得出去吗 */
    fun fitsFrame(frameBytes: Int): Boolean = frameBytes in 1..dataFieldBytes

    /** 这个载荷要拆几片；装不下就返回 [Int.MAX_VALUE]，调用方据此判"发不了"而不是硬发 */
    fun fragments(payloadBytes: Int): Int =
        if (payloadBytes > maxPayloadBytes) Int.MAX_VALUE else (payloadBytes + chunkBytes - 1) / chunkBytes

    /** 这个预算还能不能用分片（数据字段连一帧骨架都装不下时，分片本身就无意义） */
    val canFragment: Boolean get() = chunkBytes > 0

    companion object {
        /** 骨架 20 B + 分片头 1 B；见 [SosFrameEncoder.encodeExt] */
        const val EXT_OVERHEAD_BYTES = 21

        /** frag 高 4 位承载 total */
        const val MAX_FRAGMENTS = 15

        fun from(caps: ModuleCaps?, unit: LengthUnit = LengthUnit.WORST_KNOWN) =
            MessageBudget(caps?.lengthLevel ?: MsgLengthLevel.WORST_KNOWN, unit)

        val WORST_KNOWN = MessageBudget(MsgLengthLevel.WORST_KNOWN, LengthUnit.WORST_KNOWN)
    }
}
