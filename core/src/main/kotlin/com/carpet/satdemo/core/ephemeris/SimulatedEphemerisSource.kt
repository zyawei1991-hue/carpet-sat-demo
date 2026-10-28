package com.carpet.satdemo.core.ephemeris

import com.carpet.satdemo.core.modem.MonoClock
import java.util.Locale

/**
 * 星历 SDK 没到手时的替身，也是彩排用的"星历开关"。
 *
 * 生成的窗口和 [com.carpet.satdemo.core.modem.SimulatedModem] 用同一套周期参数，
 * 否则演示里会出现"屏上说还在窗口、模组却说没过境"这种自己打自己的帧。
 *
 * 三个可写字段是给脚本注入坏情况用的：[state] 演 `NETWORK_ERROR`，[online] 演下载失败，
 * 仰角阈值是真规则（低于阈值的那颗直接不算进来），不是开关。
 */
class SimulatedEphemerisSource(
    private val clock: MonoClock = MonoClock { System.currentTimeMillis() },
    var passPeriodMs: Long = 12 * 60_000,
    var passWindowMs: Long = 4 * 60_000,
    /** 改成非 SUCCESS 就能演"星历算不出来"那一条文案 */
    var state: EphState = EphState.SUCCESS,
    /** false = update() 下载失败，对应〔SDK文 §4〕dataState 回调的 0 */
    var online: Boolean = true,
    private val starCount: Int = 8,
) : EphemerisSource {

    var updateCalls = 0
        private set
    var calculateCalls = 0
        private set

    /** 上一次 calculate 收到的仰角阈值，断言"阈值真的被用了" */
    var lastMinElevationDeg: Double? = null
        private set

    override suspend fun update(): Long? {
        updateCalls++
        return if (online) clock.nowMs() else null
    }

    override suspend fun calculate(request: PassRequest): EphResult {
        calculateCalls++
        lastMinElevationDeg = request.minElevationDeg
        if (state != EphState.SUCCESS) return EphResult(state, emptyList(), null)

        val now = clock.nowMs()
        val horizonMs = (request.horizonHours * 3_600_000).toLong()
        val out = ArrayList<SatellitePass>()
        var index = (now / passPeriodMs).toInt()
        var start = index * passPeriodMs
        while (start < now + horizonMs) {
            if (start >= now) {
                // 最大仰角按序号在 22°~78° 之间转，让低仰角那颗能被阈值筛掉
                val maxEl = 22.0 + (index % 5) * 14.0
                if (maxEl >= request.minElevationDeg) {
                    out += SatellitePass(
                        starName = "GW-%02d".format(Locale.ROOT, (index % starCount) + 1),
                        startMs = start,
                        endMs = start + passWindowMs,
                        startAzimuthDeg = (index * 47.0) % 360.0,
                        endAzimuthDeg = (index * 47.0 + 160.0) % 360.0,
                        maxElevationDeg = maxEl,
                    )
                }
            }
            index++
            start = index * passPeriodMs
        }
        return EphResult(EphState.SUCCESS, out.sortedBy { it.startMs }, dataTimestampMs = if (online) now else null)
    }
}
