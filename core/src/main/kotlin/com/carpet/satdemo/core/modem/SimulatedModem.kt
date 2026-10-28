package com.carpet.satdemo.core.modem

import com.carpet.satdemo.core.diagnostic.PassthroughLog
import com.carpet.satdemo.core.protocol.GeodeticPosition
import com.carpet.satdemo.core.protocol.ModuleCaps
import kotlinx.coroutines.delay

/** 时间源。单测里塞假时钟，让"分钟级时延"的分支不用真等。 */
fun interface MonoClock {
    fun nowMs(): Long
}

/**
 * 无硬件时的替身。演示彩排和 CI 都跑它。
 *
 * 行为按 link-policy.md 的口径建模：过境窗口内 ACK，窗口外 NO_ACK，
 * 这样"卫星未确认，请在开阔处等待下次过境"那条红字能在没星的时候也排演到。
 *
 * 不要把这里的通过率当成真机预期——真机的 NO_ACK 比率要等 §8.1 那条
 * 「测试卡配额 + 过境窗口」落实后才能标定。
 */
class SimulatedModem(
    private val clock: MonoClock = MonoClock { System.currentTimeMillis() },
    /** 模拟过境周期，默认 12 分钟 */
    var passPeriodMs: Long = 12 * 60_000,
    /** 每个周期内可通窗口长度 */
    var passWindowMs: Long = 4 * 60_000,
    /** 模拟发送耗时 */
    var txLatencyMs: Long = 6_000,
    /** false 用来演"模块掉线"，比如车断电 */
    var reachable: Boolean = true,
    private val sleep: suspend (Long) -> Unit = ::delay,
) : SatelliteModem {

    private var downlink: ((String) -> Unit)? = null
    private var lockHandler: ((SatLock?) -> Unit)? = null
    private var lastFix: Fix? = DEFAULT_FIX

    /**
     * 位置是**推给**模组的（对应真机的 TPI），不是模组测出来的。
     * 改名提醒：原来这里叫 readFix，读法反了会被当成 GNSS 源用（E.5）。
     */

    /** 被 sendMcq / enqueueSfq 收到过的包，供演示脚本和断言检查 */
    val sent: MutableList<String> = ArrayList()

    private val dropped = ArrayList<String>()

    /**
     * 工程模式窗（E.10 #10）。这里没有线路，所以只灌**注解**，方向一律 `--`：
     * 彩排时窗口的价值是"看见那条静默的失败"，不是伪造出像真语句的东西 ——
     * 演示里看着像协议、真机上没有，比窗口空着更危险。
     */
    private var passthrough: PassthroughLog? = null

    override fun attachPassthrough(log: PassthroughLog) {
        passthrough = log
        note("模组=模拟 等级=${capsOverride.lengthLevel} 型号=${capsOverride.model}（没有线路，只有注解）")
    }

    /** 谁被写锁挡下了：这行在窗口里就是"我们没发"那一侧的证据 */
    private fun note(text: String) {
        passthrough?.note(text, clock.nowMs())
    }

    /**
     * 演"存进模组队列之后又被丢掉"（GM1003 的 4 分钟超时，〔规 §3.1.13 注〕）。
     * 真机那条口径由 [SentenceModem] 从 SFI 里读出来，这里给演示脚本一个手动注入的口子。
     */
    fun simulateDropped(hex: String) {
        dropped += hex
        note("模组丢弃：$hex 已交回 App 队列")
    }

    override fun drainDropped(): List<String> {
        val out = dropped.toList()
        dropped.clear()
        return out
    }

    override val available: Boolean get() = reachable

    /** 演示里要"正在升级"这一档，直接对它 [runUpgrade] 就行，不用真等 flash */
    override val gate: ModemGate = ModemGate()

    /** 演一次模组固件升级：占住写锁，进度 0~99。升级期间所有发送应该被拒 */
    suspend fun runUpgrade(body: suspend (progress: (Int) -> Unit) -> Unit = { it(50) }): Boolean =
        gate.runUpgrade(clock.nowMs(), body) != null

    /**
     * 场景层替身默认给**最坏档**能力（等级 1 = ≤90 B、短接入、GM1003），
     * 因为演示里跑出来的结论必须能扛住真机的坏情况；要演好结果就显式换掉它。
     */
    var capsOverride: ModuleCaps = ModuleCaps.worstKnown()
    override val caps: ModuleCaps? get() = if (reachable) capsOverride else null

    /**
     * 替身也能演"这颗模组不支持即时发送"—— 语句级实现（[SentenceModem]）就是这个档，
     * 真机到货初期同样走排队那一路。SOS 的"已交模组"和"星上确认"两种结果只有在这里才演得出来。
     */
    override var supportsImmediateSend: Boolean = true

    override suspend fun readFix(): Fix? = lastFix

    override suspend fun sendMcq(hex: String): SatSend = guarded(SatPath.IMMEDIATE) {
        if (!reachable) return@guarded SatSend.failed(SatPath.IMMEDIATE, SatFailReason.NO_REPLY)
        sent += hex
        sleep(txLatencyMs)
        val result = if (inPassWindow()) SatResult.COMPLETE else SatResult.NO_ACK
        lockHandler?.invoke(if (inPassWindow()) SatLock(nextPassName(), CNR) else null)
        SatSend(result, SatPath.IMMEDIATE)
    }

    override suspend fun enqueueSfq(hex: String): SatSend = guarded(SatPath.QUEUED) {
        if (!reachable) return@guarded SatSend.failed(SatPath.QUEUED, SatFailReason.NO_REPLY)
        sent += hex
        // 场景层不建模"模组队列被超时丢弃"，那条口径由 SentenceModemTest 钉
        SatSend(SatResult.NO_ACK, SatPath.QUEUED)
    }

    /** 升级写锁挡下的发送统一给 [SatFailReason.UPGRADING]：这是"没写出去"，不是"写了没人认" */
    private suspend fun guarded(via: SatPath, block: suspend () -> SatSend): SatSend {
        val out = gate.write(via.name) { block() } ?: SatSend.failed(via, SatFailReason.UPGRADING)
        if (out.reason == SatFailReason.UPGRADING) note("写锁挡下：升级期间拒发 $via")
        return out
    }

    override suspend fun updateLocation(position: GeodeticPosition): Boolean =
        gate.write("TPI") {
            lastFix = Fix(position, hdop = 2.5, ageSec = 1.0)
            true
        } ?: false

    override fun onDownlink(handler: (String) -> Unit) {
        downlink = handler
    }

    override fun onSatelliteLock(handler: (SatLock?) -> Unit) {
        lockHandler = handler
        handler(if (inPassWindow()) SatLock(nextPassName(), CNR) else null)
    }

    /** 给演示脚本注入一条平台下行（救援指令、哨兵回执） */
    fun injectDownlink(hex: String) = downlink?.invoke(hex)

    override suspend fun secondsToNextPass(): Long? = secondsToNextPassAt()

    /** 给演示脚本直接查，不经 suspend */
    fun secondsToNextPassAt(): Long {
        val into = clock.nowMs() % passPeriodMs
        val rest = passPeriodMs - into
        return if (into < passWindowMs) 0 else rest / 1000
    }

    private fun inPassWindow(): Boolean = clock.nowMs() % passPeriodMs < passWindowMs

    override fun close() {
        downlink = null
        lockHandler = null
    }

    /** 星历 SDK 没到手前，过境窗口按固定周期取模模拟 */
    private fun nextPassName(): String = "GW-%02d".format((clock.nowMs() / passPeriodMs % 8) + 1)

    companion object {
        private const val CNR = 42.5
        private val DEFAULT_FIX = Fix(GeodeticPosition.of(30.2741, 120.1551), hdop = 2.5, ageSec = 2.0)
    }
}
