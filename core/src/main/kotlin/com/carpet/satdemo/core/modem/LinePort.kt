package com.carpet.satdemo.core.modem

/**
 * 半工串口上"一行一问"的最小抽象。
 *
 * GT1005 的 RS232 那条路（USB 转 TTL 直连）和 BLE 那条路，落到传输上都只是
 * "写一行、等一行、中间可能插进来一条没问的下行"。[UartBytePipe] 把它包成
 * [ModemBytePipe]，协议层就不用关心底下是串口还是 BLE。
 *
 * 实现方注意：[readLine] 会阻塞，只允许在 IO 线程上调；不要从 UI 线程进来。
 */
interface LinePort {
    val isOpen: Boolean

    /** 阻塞等一行；到 [timeoutMs] 还没等到返回 null。空行按 null 之外的内容处理由实现方决定 */
    fun readLine(timeoutMs: Long): String?

    fun writeLine(line: String)

    fun close()
}
