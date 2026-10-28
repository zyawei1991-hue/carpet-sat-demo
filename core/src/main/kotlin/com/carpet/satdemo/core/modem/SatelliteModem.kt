package com.carpet.satdemo.core.modem

import com.carpet.satdemo.core.protocol.ModuleCaps

/** SDK 的 MCQ 回调只有这三种终局，这里原样映射出来，不额外造概念。 */
enum class SatResult { COMPLETE, FAIL, NO_ACK }

/**
 * 卫星 Modem 的传输无关抽象。对应 SDK 里 `DispatchManager` + `ModuleOperation` 那层。
 *
 * **V1.3 更正（附录 E.1）**：之前这里写的是"BLE 协议文档还没给，所以 RS232 是 Plan B"。
 * 读完 SDK 才知道方向反了 —— **SDK 根本不做 IO**，它把 `send(byte[])`/`receive(byte[])`
 * 留给应用实现，官方 demo 只是选了 BLE。所以 UART 不是 Plan B，是和 BLE 平级的一条正路。
 * 现在的实现：
 *  - [SimulatedModem]  场景层替身，跑演示脚本和彩排
 *  - [SentenceModem]   协议层替身（配 [FakeGsatModule]），到货前把解析链路整条测掉
 *  - 之后 Android 侧的 `VendorSdkModem`（直接委托 `ModuleDataManager`，真机首选）
 *
 * 全部 suspend：卫星链路是分钟级时延，用回调套回调会把 SOS 重发逻辑写成一团面条。
 */
interface SatelliteModem {

    /** 模块当前是否可达（BLE 已连 / 串口已开） */
    val available: Boolean

    /**
     * 上电会话读到的能力档：报文长度等级、长短接入、有没有卫星应答、型号。
     * **没读到（null）之前不允许发任何东西** —— 拿等级赌博的代价是报文被模组静默丢弃（E.4）。
     */
    val caps: ModuleCaps?
        get() = null

    /**
     * 支不支持"立即发射并等星上确认"。
     * 语句级实现（[SentenceModem]）报 false：**MCQ 的线格式压根不在规范正文里**（§9 #13），
     * 而厂商 SDK 报 true（它自己会组那条语句）。上层据此决定 SOS 走即时还是走排队。
     */
    val supportsImmediateSend: Boolean
        get() = true

    /**
     * 直读模块 GNSS。
     *
     * ⚠️ 〔规 §3.1.2 注〕：DII 里的时间和经纬度**均由外部通过 TPI 输入**。
     * 也就是说除非这颗模组自带 GNSS，否则读回来的是我们自己几天前推给它的回显 ——
     * 不能当位置源用。车内/屏蔽袋场景真正的位置源是手机自己（§3.2 定位融合）。
     */
    suspend fun readFix(): Fix?

    /**
     * 立即发射并等 ACK。SDK 侧对应 sendMsgMCQ。
     * 返回 [SatResult.NO_ACK] 表示发出去了但星上没确认。
     *
     * 注意**卫星 ACK 只代表卫星收到，不代表星座核心网收到**（〔规 §3.1.4 注6〕）：
     * 即使是 COMPLETE 也不能对外说"已送达"（§6.1 红线一）。
     */
    suspend fun sendMcq(hex: String): SatResult

    /** 入 FIFO 队列，等下次过境自动发。SDK 侧对应 sendMsgSFQ */
    suspend fun enqueueSfq(hex: String): Boolean

    /**
     * 手机位置反向喂给模组（E.5）。SDK 侧对应 `updateLocation(经度, 纬度)`。
     * 上电一次、之后 1~10 分钟一次；模组复位/掉电即丢；**经纬度直接影响下行能不能收到**。
     */
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
