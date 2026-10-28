package com.carpet.satdemo.core.ephemeris

import com.carpet.satdemo.core.protocol.GeodeticPosition
import kotlinx.serialization.Serializable

/**
 * 一次过境。字段一一对〔SDK文 §5.1.3〕的 `CalculateResultBean`，厂商给的就是这些。
 *
 * 比我们原来自己建模的那颗"下次过顶 N 秒"信息量大得多：窗口有**起止**、有**进出方位角**、
 * 有**最大仰角** —— 于是圆屏能多演一个野外真有用的东西：**该把车头/天线朝哪边**（E.9）。
 */
data class SatellitePass(
    val starName: String,
    /** 入境时刻（Unix 毫秒） */
    val startMs: Long,
    /** 出境时刻（Unix 毫秒） */
    val endMs: Long,
    /** 入境方位角，正北 0° 顺时针 0~360 */
    val startAzimuthDeg: Double,
    val endAzimuthDeg: Double,
    /** 这次过境的最大仰角。越低越容易被楼和山体挡掉 */
    val maxElevationDeg: Double,
) {
    val windowSec: Long get() = ((endMs - startMs) / 1000).coerceAtLeast(0)

    fun inWindow(nowMs: Long): Boolean = nowMs in startMs..endMs

    /** 还有多少秒入境。已在窗口里给 0，已出境给 null */
    fun secondsUntilStart(nowMs: Long): Long? =
        if (nowMs > endMs) null else ((startMs - nowMs) / 1000).coerceAtLeast(0)

    /**
     * 过境进行中的方位角。厂商只给进/出两个角，中间按时间线性插值 ——
     * 分钟级窗口里 LEO 的方位角变化本来就接近匀速，够画那根指针了，
     * 但**别拿它当测算依据**（真要不要准，得让厂商给逐秒星点）。
     */
    fun azimuthAt(nowMs: Long): Double {
        val span = endMs - startMs
        if (span <= 0) return startAzimuthDeg
        val t = ((nowMs - startMs).toDouble() / span).coerceIn(0.0, 1.0)
        // 走 Shortest way：从 350° 转到 20° 是往北边穿过 0°，不是绕地球大半圈
        var delta = endAzimuthDeg - startAzimuthDeg
        if (delta > 180.0) delta -= 360.0
        if (delta < -180.0) delta += 360.0
        return (startAzimuthDeg + delta * t).wrap360()
    }

    companion object {
        internal fun Double.wrap360(): Double = ((this % 360.0) + 360.0) % 360.0
    }
}

/**
 * 星历不可用是哪一种不可用。前四个值**和〔SDK文 §5.1.2〕的 `resultState` 一一对应**，
 * 别合并 —— 用户能做的事完全不同：网络问题去联网，阈值问题去改配置。
 * [NO_FIX] 是我们自己加的：没定位时压根不该去问 SDK。
 */
@Serializable
enum class EphState { SUCCESS, NETWORK_ERROR, TIME_OUT, CALCULATION_FAILED, NO_FIX }

/**
 * 星历失效的人话（E.9 那条硬要求）。
 *
 * 〔SDK文〕把 `NETWORK_ERROR` 定义成"无星历数据且无缓存"，这跟"天上没卫星"是两件不同的事，
 * 但界面上最容易犯的错就是把前者显示成后者 —— 用户会举着天线对着天空等，
 * 而真正该做的是连一次网。**这句话必须由 App 说，不能让固件自己编**，
 * 所以 [EphemerisTracker] 直接把成品文案投给圆屏。
 */
object EphemerisCopy {
    fun failMessage(state: EphState): String? = when (state) {
        EphState.SUCCESS -> null
        EphState.NETWORK_ERROR -> "星历数据已过期，请联网更新后重试"
        EphState.TIME_OUT -> "星历计算超时，稍后自动重试"
        EphState.CALCULATION_FAILED -> "星历算不出过境窗口，可能是仰角阈值设得太高"
        EphState.NO_FIX -> "还没有定位，暂时算不出过境窗口"
    }

    /** 这些说法会把"星历过期"伪装成"卫星没信号"，红线，不许出现在文案里 */
    val FORBIDDEN = listOf("无卫星信号", "没有卫星", "搜星失败", "卫星不可用")
}

/** 一次过境预测的请求。坐标只收 [GeodeticPosition]，理由同 TPI：SDK 的 (经,纬) 和语句的 (纬,经) 顺序相反 */
data class PassRequest(
    val position: GeodeticPosition,
    /** 天线海拔，米。〔SDK文 §5.1〕RequestBean.alt */
    val altitudeM: Double,
    /**
     * 可通信最低仰角。**默认 20° 只是厂商的默认值，不是我们的**（〔E.9〕：
     * 要按终端实际可通信仰角配，装在车顶和装在车里不是一回事），所以它从这里注入而不是写死。
     */
    val minElevationDeg: Double,
    /** 往后看几个小时。〔SDK文 §5.1〕RequestBean.hour */
    val horizonHours: Double,
)

data class EphResult(
    val state: EphState,
    /** 已按开始时间排序（厂商保证，〔SDK文 §5.1.3〕）。非 SUCCESS 时为空 */
    val passes: List<SatellitePass>,
    /** 用的哪一批星历数据的时间戳；对应 getStarManager 的 dataState 回调 */
    val dataTimestampMs: Long?,
)

/**
 * 厂商 `StarManager` 的接口面（〔SDK文 §4 / §5〕）。
 *
 * 注意**线程模型**：星历 SDK 必须主线程调用、主线程回调（E.8），
 * 而基础通信的回调全在子线程。所以这个接口在 core 里做成 suspend，
 * Android 侧那层适配器负责把主线程约束吃掉，core 不碰 `Looper`。
 */
interface EphemerisSource {
    /** 下载新星历。返回用的数据时间戳，null = 下载失败（对应 dataState 回调的 -1/0） */
    suspend fun update(): Long?

    suspend fun calculate(request: PassRequest): EphResult

    /** 〔SDK文 §8〕不用时要 release，省电也省内存 */
    fun release() {}
}

/** 重算策略。全是运行时注入值，外场能改一个数就调，不用重编译 */
data class EphemerisConfig(
    val minElevationDeg: Double = 20.0,
    val horizonHours: Double = 6.0,
    val altitudeM: Double = 0.0,
    /** 〔SDK文 §5.2〕位置变化超过这个距离要主动重算 */
    val recomputeDistanceM: Double = 20_000.0,
    /** 就算没动没挪，隔这么久也重算一次（窗口会自己走完） */
    val refreshMs: Long = 30 * 60_000L,
    /** 〔SDK文 §5.2〕建议每日在有网时 update() 一次 */
    val updateIntervalMs: Long = 24 * 60 * 60_000L,
    /** 算不出来时的补试间隔，比 refreshMs 短，别死等 */
    val retryBackoffMs: Long = 60_000L,
)
