package com.carpet.satdemo.core.ephemeris

import com.carpet.satdemo.core.modem.MonoClock
import com.carpet.satdemo.core.protocol.GeodeticPosition

/**
 * 星历接入（E.10 #7）。把厂商 `StarManager` 的"一次性问一遍"变成我们能 1Hz 画的稳定状态。
 *
 * 三件事按〔SDK文 §5.2 / §8〕的口径建模，都是原来没有的：
 *  1. **每日 `update()`** —— 星历是要联网下载的轨道数据，不更新就会算出几年前的过境；
 *  2. **位置变化超 20 公里主动重算** —— 车开走了，旧窗口列表整个作废；
 *  3. **算不出来要说清是哪种算不出来** —— `NETWORK_ERROR` 的正确动作是联网，
 *     说成"没卫星信号"会把用户支到空旷处干等（E.9）。
 *
 * 线程模型不在这层处理：core 只暴露 suspend，Android 适配器去扛"星历必须主线程"这条（E.8）。
 */
class EphemerisTracker(
    private val source: EphemerisSource,
    private val clock: MonoClock = MonoClock { System.currentTimeMillis() },
    val config: EphemerisConfig = EphemerisConfig(),
    private val log: (String) -> Unit = {},
) {

    data class Status(
        val state: EphState,
        val passes: List<SatellitePass>,
        /** 用的是哪一批星历数据。null = 从没成功 update 过 */
        val dataTimestampMs: Long?,
        val computedAtMs: Long?,
        val position: GeodeticPosition?,
        /** 正在窗口里 —— 这时候才真能发 */
        val inPass: SatellitePass?,
        /** 当前或下一个窗口 */
        val nextPass: SatellitePass?,
        /** 圆屏那根指针该指的方向（正北 0° 顺时针）；没窗口就画不出指针 */
        val aimAzimuthDeg: Double?,
        /** 最近一次星历下载失败，屏上要打"请联网更新"而不是别的 */
        val networkNeeded: Boolean,
        /** 距下次入境的秒数。在窗口里给 0；没窗口给 null（**别给 0**，0 会被读成"现在就能发"） */
        val nextPassInS: Long?,
    ) {
        val failMessage: String? get() = EphemerisCopy.failMessage(state)
        val actionable: Boolean get() = state == EphState.SUCCESS && nextPass != null
    }

    private var passes: List<SatellitePass> = emptyList()
    private var state: EphState = EphState.NO_FIX
    private var dataTimestampMs: Long? = null
    private var computedAtMs: Long? = null
    private var cachedPosition: GeodeticPosition? = null
    private var lastUpdateAtMs: Long? = null
    private var networkNeeded = false

    /**
     * 该算就算，不该算就只回当前状态。调用方是 App 的 1Hz 循环，所以这里必须自己判断"到点没"，
     * 不能指望调用方记得 20 公里那条规则。
     */
    suspend fun refresh(
        position: GeodeticPosition?,
        altitudeM: Double = config.altitudeM,
        force: Boolean = false,
    ): Status {
        val now = clock.nowMs()
        if (position == null) {
            // 没定位就别去问 SDK：算出来的窗口是别人家的天
            state = EphState.NO_FIX
            passes = emptyList()
            cachedPosition = null
            return status(now)
        }
        if (!force && !due(position, now)) return status(now)

        val sinceUpdate = lastUpdateAtMs?.let { now - it }
        if (force || sinceUpdate == null || sinceUpdate >= config.updateIntervalMs) {
            // 下载失败不挡重算：本地缓存可能还能算出今晚的窗口（〔SDK文 §4〕dataState 的 >0 那一档）
            val ts = runCatching { source.update() }.getOrNull()
            lastUpdateAtMs = now
            if (ts == null) {
                networkNeeded = true
                log("星历下载失败，先用本地缓存算")
            } else {
                networkNeeded = false
                dataTimestampMs = ts
            }
        }

        val result = runCatching {
            source.calculate(
                PassRequest(position, altitudeM, config.minElevationDeg, config.horizonHours),
            )
        }.getOrElse {
            log("星历计算抛异常：${it.message}")
            EphResult(EphState.CALCULATION_FAILED, emptyList(), dataTimestampMs)
        }
        state = result.state
        passes = result.passes.sortedBy { it.startMs }
        dataTimestampMs = result.dataTimestampMs ?: dataTimestampMs
        computedAtMs = now
        cachedPosition = position
        if (result.state != EphState.SUCCESS) {
            log("星历不可用：${result.state.name} —— ${EphemerisCopy.failMessage(result.state)}")
        }
        return status(now)
    }

    /** 不产生任何调用，纯读。1Hz 快照和圆屏画的那根指针都从这里取 */
    fun status(): Status = status(clock.nowMs())

    fun release() = source.release()

    private fun due(
        position: GeodeticPosition,
        now: Long,
    ): Boolean {
        val at = computedAtMs ?: return true
        val moved = cachedPosition?.let { position.distanceMetersTo(it) >= config.recomputeDistanceM } == true
        if (moved) return true
        // 算砸了要早点再试一次（网络可能只是抖一下），但不能 1Hz 死磕 SDK
        if (state != EphState.SUCCESS) return now - at >= config.retryBackoffMs
        val exhausted = passes.lastOrNull()?.let { now > it.endMs } ?: true
        return exhausted || now - at >= config.refreshMs
    }

    private fun status(now: Long): Status {
        val inPass = passes.firstOrNull { it.inWindow(now) }
        val next = inPass ?: passes.firstOrNull { now <= it.endMs }
        return Status(
            state = state,
            passes = passes,
            dataTimestampMs = dataTimestampMs,
            computedAtMs = computedAtMs,
            position = cachedPosition,
            inPass = inPass,
            nextPass = next,
            aimAzimuthDeg = next?.let { if (inPass != null) it.azimuthAt(now) else it.startAzimuthDeg },
            networkNeeded = networkNeeded,
            nextPassInS = next?.secondsUntilStart(now),
        )
    }
}
