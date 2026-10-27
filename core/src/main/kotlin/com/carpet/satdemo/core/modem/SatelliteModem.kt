package com.carpet.satdemo.core.modem

/** SDK 的 MCQ 回调只有这三种终局，这里原样映射出来，不额外造概念。 */
enum class SatResult { COMPLETE, FAIL, NO_ACK }

/**
 * 卫星 Modem 的传输无关抽象。对应 SDK 里 DispatchManager + ModuleOperation 那层。
 *
 * 存在的首要理由是解阻塞：时空道宇的 BLE 协议文档还没给，但 RS232 口现在就能通，
 * 而真机到货前 App 也要能跑完整逻辑。所以三种实现可以换着插：
 *  - [SimulatedModem]  无硬件，跑演示脚本和单测
 *  之后  UartModem（USB 转 TTL） / BleModem（SDK 到手后）
 *
 * 全部 suspend：卫星链路是分钟级时延，用回调套回调会把 SOS 重发逻辑写成一团面条。
 */
interface SatelliteModem {

    /** 模块当前是否可达（BLE 已连 / 串口已开） */
    val available: Boolean

    /** 直读模块 GNSS。手机 GPS 不可用时（车内、屏蔽袋）这是唯一位置源 */
    suspend fun readFix(): Fix?

    /**
     * 立即发射并等 ACK。SDK 侧对应 sendMsgMCQ。
     * 返回 [SatResult.NO_ACK] 表示发出去了但星上没确认，不代表没收到。
     */
    suspend fun sendMcq(hex: String): SatResult

    /** 入 FIFO 队列，等下次过境自动发。SDK 侧对应 sendMsgSFQ */
    suspend fun enqueueSfq(hex: String): Boolean

    /** 每次 GNSS 定位后必须调，1-10 分钟一次。不调星历预测会失效 */
    suspend fun updateLocation(lon: Double, lat: Double)

    /** 平台下行。SDK 侧对应 setMCIMessageListening */
    fun onDownlink(handler: (hex: String) -> Unit)

    /** 锁定卫星名 + CNR，展示端画「对星中 / 已锁定」用 */
    fun onSatelliteLock(handler: (SatLock?) -> Unit)

    /**
     * 距离下次过境还有多少秒。星历缓存过期或没联网拉过星历 -> null。
     * SOS 三次无 ACK 时展示端要显示这个数，否则用户只能干等。
     */
    suspend fun secondsToNextPass(): Long? = null

    fun close()
}

data class Fix(val lon: Double, val lat: Double, val hdop: Double?, val ageSec: Double?)

/** null 表示当前没有锁定任何星（搜星中或地平线以下） */
data class SatLock(val name: String, val cnr: Double)
