package com.carpet.satdemo.core.modem

import com.carpet.satdemo.core.protocol.GsatParseResult
import com.carpet.satdemo.core.protocol.GsatSentence
import com.carpet.satdemo.core.protocol.GsatSpeaker
import java.nio.charset.StandardCharsets

/**
 * 模组的**字节管道**抽象。这一层就是厂商 SDK 里 `ModuleOperation.send(byte[])` +
 * `DispatchManager.receive(byte[])` 要求应用实现的那件事（附录 E.1）——
 * SDK 不做 IO，串口/BLE/USB 谁来回话由我们接。
 *
 * 刻意只传字节、不传语句：分帧（哪一段算一条语句）是链路特性，不是协议特性。
 * BLE 一次一整包、串口一次半行，都得在这里处理完。
 */
interface ModemBytePipe {

    val isOpen: Boolean

    /** 往模组方向写字节 */
    fun write(bytes: ByteArray)

    /** 模组方向的字节流回调。可能在 IO 线程上被调用，实现方要保证回调里不阻塞 */
    fun onBytes(handler: (ByteArray) -> Unit)

    /** 原始收发流水，给工程模式日志窗（E.10 #10 / §8 近期四件事 #3）。不做解析、不做过滤 */
    fun onTrace(handler: (String) -> Unit)

    fun close()
}

/**
 * 内存里的一对管道。测试和「假模组」用它对接，等价于把杜邦线插上：
 * 一边 write 的字节会原样出现在另一边的回调里。
 */
class InMemoryBytePipe private constructor(
    private val name: String,
    private val peerRef: PeerRef,
) : ModemBytePipe {

    /** 双向引用要有一个可写的容器兜着，否则构造期拿不到对面 */
    class PeerRef {
        var peer: InMemoryBytePipe? = null
    }

    private var byteHandler: ((ByteArray) -> Unit)? = null
    private var traceHandler: ((String) -> Unit)? = null
    private var open = true

    override val isOpen: Boolean get() = open

    override fun write(bytes: ByteArray) {
        if (!open) return
        traceHandler?.invoke("$name → ${String(bytes, StandardCharsets.US_ASCII).trim()}")
        peerRef.peer?.deliver(bytes)
    }

    override fun onBytes(handler: (ByteArray) -> Unit) {
        byteHandler = handler
    }

    override fun onTrace(handler: (String) -> Unit) {
        traceHandler = handler
    }

    override fun close() {
        open = false
    }

    private fun deliver(bytes: ByteArray) {
        if (open) byteHandler?.invoke(bytes)
    }

    /** 测试内部再注入一条模组主动上报，模拟"没问就推上来" */
    fun injectFromPeer(bytes: ByteArray) = deliver(bytes)

    companion object {
        /** 造一对连好的管道：app 侧和 module 侧。每个 ref 都指向**对面**，指自己就成了回环 */
        fun pair(): Pair<InMemoryBytePipe, InMemoryBytePipe> {
            val toApp = PeerRef()
            val toModule = PeerRef()
            val app = InMemoryBytePipe("APP", toModule)
            val module = InMemoryBytePipe("MODULE", toApp)
            toApp.peer = app
            toModule.peer = module
            return app to module
        }
    }
}

/**
 * 字节流 → 语句流的分帧器。
 *
 * 串口不会按"一条语句"给你吐字节：一条语句可能被拆成两段、两段之间夹着空行、
 * 也可能两段粘在一起一次到。这里按 CRLF 切，**不完整的部分留在缓冲里等下一段**。
 *
 * 校验和错误的处理策略写在 [onBadFrame]：宁可不发也不认错，所以坏帧一律上报并丢弃。
 */
class SentenceFramer(
    private val onSentence: (GsatSentence) -> Unit,
    private val onBadFrame: (GsatParseResult.Bad) -> Unit = {},
) {

    private val buffer = StringBuilder()

    /** 语句最大长度：留足余量，防止模组一直不吐 CRLF 把内存吃光 */
    private val maxBufferChars = 2048

    fun push(bytes: ByteArray) = push(String(bytes, StandardCharsets.US_ASCII))

    fun push(chunk: String) {
        for (c in chunk) {
            when (c) {
                '\r' -> Unit
                '\n' -> flushLine()
                else -> {
                    buffer.append(c)
                    if (buffer.length > maxBufferChars) {
                        onBadFrame(
                            GsatParseResult.Bad(
                                com.carpet.satdemo.core.protocol.GsatParseError.OVERFLOW,
                                "<超过 ${maxBufferChars} 字符仍未见 CRLF，缓冲已清空>",
                            ),
                        )
                        buffer.setLength(0)
                    }
                }
            }
        }
    }

    /** 半行残留（模组复位、拔线）时由调用方清掉，别把上一条的尾巴接到下一条上 */
    fun reset() {
        buffer.setLength(0)
    }

    private fun flushLine() {
        val line = buffer.toString()
        buffer.setLength(0)
        when (val r = GsatSentence.parse(line)) {
            is GsatParseResult.Ok -> if (r.sentence.isFromModule()) onSentence(r.sentence)
            is GsatParseResult.Bad -> onBadFrame(r)
            is GsatParseResult.Empty -> Unit
        }
    }
}

/**
 * 这条语句是不是模组发的。
 *
 * **不能只看前缀**：〔规 §3.1.11〕MCI 的官方例句写成 `$CCMCI,120000,0,,0102A1A2*74` ——
 * 下行语句用了 `CC` 前缀，而且那个校验和**只有按 CC 才算得出来**（按 GS 算是 60）。
 * 例句自洽说明厂商自己就是这么发的，所以只认前缀会把真下行当成回环丢掉。
 */
private fun GsatSentence.isFromModule(): Boolean =
    verb in MODULE_ONLY_VERBS || speaker == GsatSpeaker.UNIT

private val MODULE_ONLY_VERBS = setOf("MCI", "SFI", "PDI", "ICI", "DII", "CII", "CEI", "CES")

/** 组一条发往模组的语句的便捷入口 */
fun hostSentence(verb: String, fields: List<String>): ByteArray =
    GsatSentence.build(GsatSpeaker.HOST, verb, fields).toByteArray(StandardCharsets.US_ASCII)
