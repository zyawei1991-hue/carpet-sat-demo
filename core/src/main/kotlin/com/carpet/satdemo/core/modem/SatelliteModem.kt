package com.carpet.satdemo.core.modem

import com.carpet.satdemo.core.diagnostic.PassthroughLog
import com.carpet.satdemo.core.protocol.GeodeticPosition
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
     * 立即发射并等星上确认。SDK 侧对应 sendMsgMCQ。
     *
     * 注意**卫星 ACK 只代表卫星收到，不代表星座核心网收到**（〔规 §3.1.4 注6〕）：
     * 即使是 [SatResult.COMPLETE] 也不能对外说"已送达"（§6.1 红线一）。
     * 失败成因看 [SatSend.reason]。
     *
     * 语句级实现会抛 [UnsupportedOperationException]：MCQ 的线格式不在规范正文里（§9 #13）。
     * 调用方要么先看 [supportsImmediateSend]，要么像 [SosCoordinator] 那样用 runCatching 兜住。
     */
    suspend fun sendMcq(hex: String): SatSend

    /**
     * 入模组的 FIFO 队列，等下次过境自动发。SDK 侧对应 sendMsgSFQ。
     *
     * **收下 ≠ 送达**：返回 [SatResult.NO_ACK] + [SatPath.QUEUED] 只说明它进了模组队列。
     * 所以 App 侧那份持久队列不能在这一步就清掉 ——
     * GM1003 会在 4 分钟后把没发出去的丢掉（[SatFailReason.DROPPED_BY_TIMEOUT]），
     * 丢了要重新排队，否则 SOS 会静默消失（E.6）。
     */
    suspend fun enqueueSfq(hex: String): SatSend

    /**
     * 手机位置反向喂给模组（E.5）。SDK 侧对应 `updateLocation(经度, 纬度)`。
     * 上电一次、之后 1~10 分钟一次；模组复位/掉电即丢；**经纬度直接影响下行能不能收到**。
     *
     * 参数是 [GeodeticPosition] 而不是两个 double：SDK 的 `(经, 纬)` 和语句里的 `(纬, 经)`
     * 顺序正好相反，裸 double 传参编译器抓不住（E.5 末尾那条坑）。
     *
     * @return **只能读成"这一条有没有被送出去"**：false = 升级写锁挡下、链路没开、模组没应答。
     * 语句层实现能等到 TPI 回显，所以它的 true 含义强一些；
     * **厂商 SDK 路线的 true 只到"已交给 SDK"** —— 〔码〕实测 `updateLocation()` 返回 void，
     * 且 `ModuleDataManager` 的六个监听器里**没有 TPI 那一个**（`EntityTPI` 这个类比在但没暴露），
     * 所以"模组认了这条位置"在应用侧压根不可观测（§9 #17，附录 E.5/E.12）。
     * 上层因此**不许把它显示成"位置已同步"**，只能说"最近一次推送于 N 秒前"。
     */
    suspend fun updateLocation(position: GeodeticPosition): Boolean

    /**
     * 升级期间的全局写锁（E.10 #6）。〔SDK文 §9-2〕"正在更新模组任务时所有数据发送都不可用"，
     * 所以这不是可选保险。没有升级概念的替身实现给 [ModemGate.IDLE] 就行。
     */
    val gate: ModemGate
        get() = ModemGate.IDLE

    /**
     * 取走并清空"模组收下之后又主动丢弃"的报文（GM1003 的 4 分钟超时，〔规 §3.1.13 注〕）。
     *
     * 场景层替身没有这个概念，返回空即可；语句层实现必须给真的，否则 SOS 会静默消失（E.6）。
     */
    fun drainDropped(): List<String> = emptyList()

    /** 平台下行。SDK 侧对应 setMCIMessageListening */
    fun onDownlink(handler: (hex: String) -> Unit)

    /** 锁定卫星名 + CNR，展示端画「对星中 / 已锁定」用 */
    fun onSatelliteLock(handler: (SatLock?) -> Unit)

    /**
     * 距离下次过境还有多少秒。星历缓存过期或没联网拉过星历 -> null。
     * SOS 三次无 ACK 时展示端要显示这个数，否则用户只能干等。
     */
    suspend fun secondsToNextPass(): Long? = null

    /**
     * 把模组接进**工程模式日志窗**（E.10 #10）。
     *
     * 这一条是现场排障的救命稻草：出问题时要一眼分清"模组没回"还是"我们没发"，
     * 那句话只有原始语句能回答。真机上这一路接的是厂商的 `DispatchManager.passthrough(String)`，
     * 语句层实现接的是自己那根字节管道 —— 两条路喂同一个窗口，屏上不用区分实现。
     *
     * 场景层替身没有线路概念，默认什么都不做；别为了"演示里也有行"去伪造语句。
     */
    fun attachPassthrough(log: PassthroughLog) {
        // 替身实现没有线路可记
    }

    fun close()
}

/**
 * 从模组读回来的位置。坐标用带字段名的类型（理由见 [updateLocation]），
 * 顺带把"这是 TPI 回显、不是模组自己定位的"那层含义留在调用方的注释里（〔规 §3.1.2 注〕）。
 */
data class Fix(val position: GeodeticPosition, val hdop: Double?, val ageSec: Double?)

/** null 表示当前没有锁定任何星（搜星中或地平线以下） */
data class SatLock(val name: String, val cnr: Double)
