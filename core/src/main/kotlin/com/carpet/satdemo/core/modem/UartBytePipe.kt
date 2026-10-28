package com.carpet.satdemo.core.modem

import java.nio.charset.StandardCharsets

/**
 * RS232 / USB 转 TTL 那条路的**字节管道**实现。
 *
 * **V1.3 更正**：这个类原来叫 `UartModem`，里面还有一整张手写的 `Gt1005Verbs` 指令表
 * （GMO/ICI/SFQ 该发什么字符串、应答长什么样）。附录 E 读完 SDK 之后那张表整张作废：
 * 协议是 NMEA 风格的 ASCII 语句，组包和解析分别由 [SentenceModem] 和厂商 SDK 负责，
 * **本类只剩"把字节送出去、把字节读上来"这一件事**（E.10 #1）。
 *
 * 保留它而不是删掉的原因是它仍然有真实内容：串口和 BLE 的差异全在这一层 ——
 * 半工口、一行可能分两段到、需要一条常驻读线程。这些和指令表无关。
 */
class UartBytePipe(
    private val port: LinePort,
    /** 单次 readLine 的等待上限。太小会空转，太大拔线时回调延迟明显 */
    private val pollMs: Long = 200,
) : ModemBytePipe {

    private var byteHandler: ((ByteArray) -> Unit)? = null
    private var traceHandler: ((String) -> Unit)? = null

    @Volatile
    private var running = false
    private var reader: Thread? = null

    override val isOpen: Boolean get() = port.isOpen

    /**
     * 读线程。串口不会按语句给你对齐，所以这里只负责"把一行原样交给分帧器"，
     * 拼帧/校验一律在 [SentenceFramer] 里做 —— 层次不能反。
     */
    fun startReading() {
        if (running) return
        running = true
        reader = Thread({
            while (running) {
                val line = runCatching { port.readLine(pollMs) }.getOrNull() ?: continue
                val bytes = (line + "\r\n").toByteArray(StandardCharsets.US_ASCII)
                traceHandler?.invoke("RX ${line.trim()}")
                byteHandler?.invoke(bytes)
            }
        }, "uart-read").apply {
            isDaemon = true
            start()
        }
    }

    override fun write(bytes: ByteArray) {
        val text = String(bytes, StandardCharsets.US_ASCII).trim()
        if (!port.isOpen) return
        runCatching { port.writeLine(text) }.onSuccess { traceHandler?.invoke("TX $text") }
    }

    override fun onBytes(handler: (ByteArray) -> Unit) {
        byteHandler = handler
        // 注册了回调就自动起读线程：让 [SentenceModem] 那边不必知道"串口还需要手动 kickoff"，
        // 少一步约定就少一类"接上没反应"的现场故障
        startReading()
    }

    override fun onTrace(handler: (String) -> Unit) {
        traceHandler = handler
    }

    override fun close() {
        running = false
        reader?.interrupt()
        reader = null
        port.close()
    }
}
