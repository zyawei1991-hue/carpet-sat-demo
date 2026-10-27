package com.carpet.satdemo.core.modem

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

    /** 被 sendMcq / enqueueSfq 收到过的包，供演示脚本和断言检查 */
    val sent: MutableList<String> = ArrayList()

    override val available: Boolean get() = reachable

    override suspend fun readFix(): Fix? = lastFix

    override suspend fun sendMcq(hex: String): SatResult {
        if (!reachable) return SatResult.FAIL
        sent += hex
        sleep(txLatencyMs)
        val result = if (inPassWindow()) SatResult.COMPLETE else SatResult.NO_ACK
        lockHandler?.invoke(if (inPassWindow()) SatLock(nextPassName(), CNR) else null)
        return result
    }

    override suspend fun enqueueSfq(hex: String): Boolean {
        if (!reachable) return false
        sent += hex
        return true
    }

    override suspend fun updateLocation(lon: Double, lat: Double) {
        lastFix = Fix(lon, lat, hdop = 2.5, ageSec = 1.0)
    }

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
        private val DEFAULT_FIX = Fix(120.1551, 30.2741, hdop = 2.5, ageSec = 2.0)
    }
}
