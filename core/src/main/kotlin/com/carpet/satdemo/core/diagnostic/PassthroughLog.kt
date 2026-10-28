package com.carpet.satdemo.core.diagnostic

import com.carpet.satdemo.core.modem.MonoClock
import com.carpet.satdemo.core.modem.RawLine
import com.carpet.satdemo.core.protocol.GsatParseError
import com.carpet.satdemo.core.protocol.GsatParseResult
import com.carpet.satdemo.core.protocol.GsatSentence

/**
 * **工程模式日志窗**（E.10 #10 / §8 近期四件事 #3）：把串口上真实来过的每一行、
 * 以及厂商 SDK `passthrough(String)` 吐出来的原始输出，攒成一个有界环形缓冲，供屏上翻查。
 *
 * 为什么值得单独一层：现场排障只有两种问题 —— "当时那条报文到底发出去了没有"，
 * 和"到底有没有来过这条语句"。两种都只能靠原始行回答，而 App 侧的解析路径
 * 恰好把答案过滤掉了（校验和错的、动词不认的、没人问就推上来的）。
 *
 * 三条约束都是"不这么做窗口就没用"级别的：
 *  - **坏行照样进窗**。校验和错的那一行往往正是"半工口读丢一位"的物证，
 *    只记合法语句等于把要看的证据删掉。
 *  - **有界、丢最旧**。工程模式一开就是几十分钟，1 秒一条的 PDI 能把内存吃掉；
 *    而人要看的永远是"刚刚发生了什么"。所以留最后 N 条，同时记下落掉的条数 ——
 *    不记的话工程师会以为自己看到的就是全部。
 *  - **时间用注入时钟**。窗里的相对时间要和 App 事件流对齐，两套时钟就没法对。
 *
 * 只进内存：不落盘、不上传。帧里带着经纬度（SOS 报文本来就有位置），
 * 存下来就变成隐私问题，上传则是另一套合规口径（§7）。
 */
class PassthroughLog(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: MonoClock = MonoClock { System.currentTimeMillis() },
) {
    data class Line(
        val atMs: Long,
        val direction: RawLine.Direction,
        /** 可能被截断后的原文（[rawLength] 是截断前的长度） */
        val text: String,
        /**
         * 校验和判定。
         *  - true = 是 `$` 语句且校验和对得上
         *  - false = 是语句但内容被改动（读串位、丢字节）—— 只有这一种值得标红
         *  - null = 这行根本不是语句（开机打印、SDK 调试输出、我们的注解）
         */
        val checksumOk: Boolean?,
        /** 坏在哪里，比如"校验和 期望 7A 实得 03"。要给具体数字，只说"坏了"没法排障 */
        val detail: String?,
        /** 语句动词，比如 `SFI`；认不出来为 null */
        val verb: String?,
        val rawLength: Int,
    ) {
        val truncated: Boolean get() = rawLength > text.length

        /** 屏上一行的宽度有限，方向 + 动词 + 原因先给，原文被挤掉也要保住"这行坏没坏" */
        fun render(): String = buildString {
            append(direction.label).append(' ')
            verb?.let { append(it).append(' ') }
            append(text)
            if (truncated) append("…（原长 $rawLength）")
            detail?.let { append("  ⟵ ").append(it) }
        }
    }

    private val lines = ArrayDeque<Line>()

    /** 因为窗口满而被挤出去的条数 */
    var dropped: Int = 0
        private set

    val size: Int get() = lines.size

    /** 坏帧（校验和被改动）的条数。这一个数字是"链路质量"的唯一现场指标 */
    val badFrames: Int get() = lines.count { it.checksumOk == false }

    /** 旧 → 新。展示端从尾部反向渲染即可 */
    fun snapshot(): List<Line> = lines.toList()

    fun clear() {
        lines.clear()
        dropped = 0
    }

    /** 收管道 / SDK 的原始行 */
    fun record(line: RawLine, atMs: Long = clock.nowMs()): Line = record(line.direction, line.text, atMs)

    /**
     * 收我们自己的注解（"模组超时丢弃 3 条"这类），方向标 [RawLine.Direction.UNCLASSIFIED]。
     * 走同一条路进同一个窗口：工程师不该在两个界面之间来回切着拼时间线。
     */
    fun note(text: String, atMs: Long = clock.nowMs()): Line = record(RawLine.Direction.UNCLASSIFIED, text, atMs)

    /**
     * 记一行。[atMs] 默认用注入时钟；IO 线程那边回调进来时往往已经带着自己的时间戳，
     * 那种情况下必须显式传，否则同一轮问答的两行会拿到两个不同的时刻。
     */
    fun record(direction: RawLine.Direction, text: String, atMs: Long = clock.nowMs()): Line {
        val trimmed = text.trim('\r', '\n')
        var checksumOk: Boolean? = null
        var detail: String? = null
        var verb: String? = null
        when (val r = GsatSentence.parse(trimmed)) {
            is GsatParseResult.Ok -> {
                checksumOk = true
                verb = r.sentence.verb
            }
            // 只判形状、不取语义：窗口要回答"这行坏没坏"，而解码失败的语句依然有排障价值
            is GsatParseResult.Bad -> {
                detail = describe(r)
                if (r.error == GsatParseError.CHECKSUM_MISMATCH) checksumOk = false
                verb = r.raw.trimStart('$').takeIf { it.isCcOrGsPrefixed() }?.substring(2, 5)
            }
            is GsatParseResult.Empty -> Unit
        }
        val shown = if (trimmed.length > MAX_LINE_CHARS) trimmed.substring(0, MAX_LINE_CHARS) else trimmed
        val line = Line(atMs, direction, shown, checksumOk, detail, verb, trimmed.length)
        lines.addLast(line)
        while (lines.size > capacity) {
            lines.removeFirst()
            dropped++
        }
        return line
    }

    /**
     * 窗口标题。一屏放不下整段能力档，工程师至少要知道这是哪台模组、哪个报文等级、
     * 队列里还压着几条 —— 这三样决定了下面那些行该怎么解释。
     */
    fun header(capsSummary: String?, queueSummary: String?): String =
        listOfNotNull(
            "工程模式 · 原始语句（留最近 $capacity 条${if (dropped > 0) "，已滚过 $dropped 条" else ""}）",
            capsSummary?.let { "模组 $it" },
            queueSummary,
        ).joinToString(" | ")

    private fun describe(bad: GsatParseResult.Bad): String = when (bad.error) {
        GsatParseError.CHECKSUM_MISMATCH -> "校验和 期望 ${bad.expected} 实得 ${bad.actual}"
        GsatParseError.OVERFLOW -> "一直没等到行尾，缓冲已清空"
        GsatParseError.NO_START -> "不是语句（没有 \$ 开头）"
        GsatParseError.NO_CHECKSUM_DELIMITER -> "缺 * 校验和分隔符"
        GsatParseError.CHECKSUM_TOO_SHORT -> "校验和不足两位"
        GsatParseError.TOO_SHORT -> "整行太短，取不出说话方和动词"
        GsatParseError.UNKNOWN_SPEAKER -> "说话方不是 CC/GS：${bad.raw.take(3)}"
        GsatParseError.NO_FIELD_SEPARATOR -> "标识符后既无逗号也无校验和"
        else -> bad.error.name
    }

    private fun String.isCcOrGsPrefixed(): Boolean =
        length >= 5 && (startsWith("CC") || startsWith("GS"))

    companion object {
        /**
         * 120 条。圆屏一行容得下约 40 字符、一屏 6 行，翻 20 页到头；
         * 再多不是"看不过来"的问题，是内存和功耗的问题 —— 屏端自己会限流。
         */
        const val DEFAULT_CAPACITY = 120

        /** 等级 5（1900 B）的 SFQ 光数据字段就有 3800 个 hex 字符，整行存下来没必要 */
        const val MAX_LINE_CHARS = 200
    }
}
