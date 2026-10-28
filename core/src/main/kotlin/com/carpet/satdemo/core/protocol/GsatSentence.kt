package com.carpet.satdemo.core.protocol

import java.nio.charset.StandardCharsets

/**
 * 时空道宇「通用数据接口规范 V1.0」的语句层：`$` + 说话方 2 字 + 动词 3 字 + `,字段…` + `*` + 校验和 + CRLF。
 *
 * 这一层是从规范里那几条**带真实校验和的例句**反推验证出来的（见 `GsatSentenceTest`）：
 * `$CCCOP,2*42` / `$CCSFP,2,1,,*46` / `$CCSFP,1,2,,*46` 三条都能被本文件的算法逐字节复现，
 * 所以异或口径是实测的，不是猜的。
 *
 * 只管语句形状，不管语义 —— 语义在 [GsatVerb] 和各 Entity 里。
 */

/** 谁在说话。规范用前两位区分方向，动词是后三位。 */
enum class GsatSpeaker(val tag: String) {
    /** 外设 → 通信单元 */
    HOST("CC"),

    /** 通信单元 → 外设 */
    UNIT("GS"),
    ;

    companion object {
        fun fromTag(tag: String): GsatSpeaker? = entries.firstOrNull { it.tag == tag }
    }
}

/**
 * 语句标识符。规范 §2.1.2 那 14 个是文档化的；下面还列了 SDK 里出现过、
 * 但规范正文没给字段表的（〔E.3 末〕），它们需要能**被解析**而不是被丢弃 ——
 * 现场排障时这些未知语句恰恰是唯一线索。
 */
object GsatVerb {
    const val COP = "COP"; const val DII = "DII"; const val CII = "CII"; const val ICI = "ICI"
    const val PDI = "PDI"; const val CEQ = "CEQ"; const val CEI = "CEI"; const val CES = "CES"
    const val MCI = "MCI"; const val GMO = "GMO"; const val TPI = "TPI"; const val SFQ = "SFQ"
    const val SFI = "SFI"; const val SFP = "SFP"

    /** 文档化过的语句：GMO 只允许对这 8 个目标开关输出 */
    val DOCUMENTED = setOf(COP, DII, CII, ICI, PDI, CEQ, CEI, CES, MCI, GMO, TPI, SFQ, SFI, SFP)

    /** SDK 里有对应 Parse/Entity 类、规范正文却没给字段表的 */
    val UNDOCUMENTED_IN_SDK = setOf("FBI", "FBIS", "MER", "RET", "UPR", "RTS", "ACK", "SPI", "MCQ", "UPF")
}

/**
 * 一条已解析的语句。[fields] 保留**原文**（不 trim、不去空格）——
 * 原因见 [GsatSentence.parse]：校验和算的是 `$` 和 `*` 之间的**原始字节**，
 * 而规范自己的例句里就有 `$GSCEI, hhmmss.ss,a,…` 这种「逗号后带空格」的写法，
 * 一旦在求和之前规范化，合法语句会被判成坏包。
 */
data class GsatSentence(
    val speaker: GsatSpeaker,
    val verb: String,
    val fields: List<String>,
    /** 校验和原文（两位大写十六进制）。用于回显和排障。 */
    val checksum: String,
) {
    /** 说话方 + 动词，例如 `CCSFQ` */
    val id: String get() = speaker.tag + verb

    val isFromModule: Boolean get() = speaker == GsatSpeaker.UNIT

    /** 动词不在规范 §2.1.2 的 14 个里。未知不等于非法：照样交给上层，只打个标记。 */
    val isKnownVerb: Boolean get() = verb in GsatVerb.DOCUMENTED

    /**
     * 通信数据字段（MCI 的"接收数据"、SFQ/MCQ 的"发送数据"）按规范是
     * **十六进制的 ASCII 码**（〔规 §3.1.12 注3〕："此字段填入十六进制的 ASCII 码，
     * 因为通信单元的所有的接口协议均采用 ASCII 的格式展现"）。
     */
    val dataAsBytes: ByteArray?
        get() = fields.lastOrNull()
            // 空结尾字段（`$CCSFP,2,1,,` 那种）不是数据，别解成"零字节报文"
            ?.takeIf { it.isHexData() }
            ?.let { runCatching { hexToBytes(it) }.getOrNull() }

    /** 重新组包。规范化后的字节流：逗号后不带空格。 */
    fun encode(): String = GsatSentence.build(speaker, verb, fields)

    override fun toString(): String = encode()

    companion object {

        /**
         * 组一条发往模组的语句。
         *
         * 空字段要保留（`$CCSFP,2,1,,*46` 的结尾就是两个空字段），
         * 所以这里不接受"调用方先把空串过滤掉"的用法。
         */
        fun build(speaker: GsatSpeaker, verb: String, fields: List<String>): String {
            require(verb.length == 3) { "语句标识符必须是 3 字符: $verb" }
            val body = buildString {
                append(speaker.tag).append(verb)
                if (fields.isNotEmpty()) {
                    append(',').append(fields.joinToString(","))
                }
            }
            return "$" + body + "*" + checksumOf(body) + "\r\n"
        }

        /** 待发数据的规范写法：字节数组 → 大写十六进制 ASCII */
        fun buildData(speaker: GsatSpeaker, verb: String, prefixFields: List<String>, payload: ByteArray): String =
            build(speaker, verb, prefixFields + payload.toHex())

        /**
         * `$` 与 `*` 之间（**不含**这两个符号）全部字符按字节异或，
         * 高 4 bit 在前、低 4 bit 在后，各取一个 ASCII 十六进制字符（〔规 §2.2〕）。
         */
        fun checksumOf(body: String): String {
            var x = 0
            for (b in body.toByteArray(StandardCharsets.US_ASCII)) x = x xor b.toInt()
            return "%02X".format(x and 0xFF)
        }

        /**
         * 解析一条完整语句。
         *
         * 只剥行尾的 CR/LF 和行首尾空白，**语句内部一个字符都不动**。
         * 校验和不通过一律返回 [GsatParseResult.Bad]，因为对串口来说
         * "读歪一位"比"丢掉一条"危险得多 —— 宁可不发也不认错。
         */
        fun parse(raw: String): GsatParseResult {
            val line = raw.trim('\r', '\n', ' ', '\t')
            if (line.isEmpty()) return GsatParseResult.Empty
            if (!line.startsWith("$")) return GsatParseResult.Bad(GsatParseError.NO_START, raw)
            val star = line.lastIndexOf('*')
            if (star < 0) return GsatParseResult.Bad(GsatParseError.NO_CHECKSUM_DELIMITER, raw)
            val body = line.substring(1, star)
            val given = line.substring(star + 1).trim()
            if (given.length < 2) return GsatParseResult.Bad(GsatParseError.CHECKSUM_TOO_SHORT, raw)
            val want = checksumOf(body)
            // 模组侧偶尔用小写；只比十六进制数值本身，不比大小写
            if (!given.take(2).equals(want, ignoreCase = true)) {
                return GsatParseResult.Bad(GsatParseError.CHECKSUM_MISMATCH, raw, expected = want, actual = given.take(2).uppercase())
            }
            if (body.length < 5) return GsatParseResult.Bad(GsatParseError.TOO_SHORT, raw)
            val speaker = GsatSpeaker.fromTag(body.substring(0, 2))
                ?: return GsatParseResult.Bad(GsatParseError.UNKNOWN_SPEAKER, raw)
            val verb = body.substring(2, 5)
            val rest = body.substring(5)
            val fields = when {
                rest.isEmpty() -> emptyList()
                rest.startsWith(",") -> splitFields(rest.substring(1))
                else -> return GsatParseResult.Bad(GsatParseError.NO_FIELD_SEPARATOR, raw)
            }
            return GsatParseResult.Ok(GsatSentence(speaker, verb, fields, want))
        }

        /** 逗号分隔，空字段保留（`a,,b` → [a, "", b]）；不做 trim，理由见类注释 */
        private fun splitFields(s: String): List<String> = s.split(',')
    }
}

enum class GsatParseError {
    NO_START,
    NO_CHECKSUM_DELIMITER,
    CHECKSUM_TOO_SHORT,

    /** 内容被改动（读串位、丢字节、多余字符）都会走到这里，是最该上屏的错误 */
    CHECKSUM_MISMATCH,
    TOO_SHORT,

    /** 一直没等到 CRLF、缓冲涨到上限。拔线/模组卡死时的形态，和"内容错"不是一回事 */
    OVERFLOW,
    UNKNOWN_SPEAKER,
    NO_FIELD_SEPARATOR,
}

sealed interface GsatParseResult {
    data class Ok(val sentence: GsatSentence) : GsatParseResult

    /** 空行：串口初始化期常见，不算错误，静默跳过 */
    data object Empty : GsatParseResult
    data class Bad(
        val error: GsatParseError,
        val raw: String,
        val expected: String? = null,
        val actual: String? = null,
    ) : GsatParseResult
}

/**
 * 是不是「十六进制的 ASCII 码」那种数据字段（〔规 §3.1.12 注3〕）：
 * 非空、偶数长度、全部是 0-9/A-F/a-f。
 * 判定放在解码之前，是因为 `$CCSFP,2,1,,` 的结尾空字段和 `SFQ` 的数据字段
 * 在"最后一个字段"这个位置上长得一样，只能靠形状区分。
 */
fun String.isHexData(): Boolean =
    isNotEmpty() && length % 2 == 0 && all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }

