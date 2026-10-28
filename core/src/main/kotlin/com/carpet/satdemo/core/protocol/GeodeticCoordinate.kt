package com.carpet.satdemo.core.protocol

import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * TPI / DII 里的度分坐标：纬度 `ddmm.mmmm`、经度 `dddmm.mmmm`（〔规 §3.1.7〕，前面的 0 也要传输）。
 *
 * 单独建类型有两个原因：
 *  1. **SDK 的 `updateLocation(double longitude, double latitude)` 是 (经, 纬)，
 *     语句里的字段顺序是 (纬, 经)** —— 两个顺序正好相反，用裸 `(Double, Double)` 传参
 *     编译器抓不住，一旦传反就是"定位偏到地球对面"这种要到外场才暴露的 bug。
 *  2. 格式化必须锁 `Locale.ROOT`：中文 locale 下 `%.4f` 会吐出 `3016,4460`，
 *     逗号直接把语句字段冲掉。
 */
@JvmInline
value class GeodeticLatitude(val degrees: Double) {
    init {
        require(degrees.isFinite() && abs(degrees) <= 90.0) { "纬度越界: $degrees" }
    }

    /** 语句里的方向位：N 北纬 / S 南纬。0 度按 N 发（规范只给了 N/S 两个取值） */
    val hemisphere: String get() = if (degrees >= 0) "N" else "S"
    val field: String get() = toDegMinField(degrees, degDigits = 2)
}

@JvmInline
value class GeodeticLongitude(val degrees: Double) {
    init {
        require(degrees.isFinite() && abs(degrees) <= 180.0) { "经度越界: $degrees" }
    }

    /** E 东经 / W 西经 */
    val hemisphere: String get() = if (degrees >= 0) "E" else "W"
    val field: String get() = toDegMinField(degrees, degDigits = 3)
}

/** 带名字的位置。发给模组、以及从模组读回来都用它。 */
data class GeodeticPosition(
    val latitude: GeodeticLatitude,
    val longitude: GeodeticLongitude,
) {
    /**
     * TPI 的字段顺序：**纬在前、经在后**（`$--TPI,类别,时间,纬度,N/S,经度,E/W*hh`）。
     * 注意这与 SDK `updateLocation(longitude, latitude)` 的参数顺序相反。
     */
    val tpiFields: List<String>
        get() = listOf(latitude.field, latitude.hemisphere, longitude.field, longitude.hemisphere)

    companion object {
        /** 十进制度 → 位置。西经/南纬用负数表示，与 Android Location 的口径一致 */
        fun of(latDeg: Double, lonDeg: Double) = GeodeticPosition(GeodeticLatitude(latDeg), GeodeticLongitude(lonDeg))

        /** 从语句字段还原（`3016.4460` + `S` → -30.2741） */
        fun fromTpiFields(latField: String, latHemi: String, lonField: String, lonHemi: String): GeodeticPosition? {
            val lat = fromDegMin(latField, sign = if (latHemi.uppercase() == "S") -1.0 else 1.0) ?: return null
            val lon = fromDegMin(lonField, sign = if (lonHemi.uppercase() == "W") -1.0 else 1.0) ?: return null
            return runCatching { of(lat, lon) }.getOrNull()
        }
    }

    /**
     * 两点间大圆距离（米）。球面近似够用 —— 唯一的调用点是〔SDK文 §5.2〕
     * "位置变化超过 20 公里要主动重算星历"，那个阈值本来就不精确。
     */
    fun distanceMetersTo(other: GeodeticPosition): Double {
        val phi1 = Math.toRadians(latitude.degrees)
        val phi2 = Math.toRadians(other.latitude.degrees)
        val dPhi = phi2 - phi1
        val dLambda = Math.toRadians(other.longitude.degrees - longitude.degrees)
        val h = sin(dPhi / 2) * sin(dPhi / 2) + cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }
}

/** 球半径。只服务于上面那个"20 公里算不算挪窝"的判断，精度要求很低 */
private const val EARTH_RADIUS_M = 6_371_000.0

/**
 * 十进制度 → 度分串。小数部分保留 4 位（= 亚米级，够用了）。
 *
 * `degDigits` 决定度的宽度：纬度 2、经度 3。**必须补零**，
 * `120.1551` → `12009.3060` 里的 `09` 少了那个零，模组会读成 `1200.93060`。
 */
internal fun toDegMinField(deg: Double, degDigits: Int): String {
    val a = abs(deg)
    var d = floor(a).toLong()
    // 分保留 4 位小数，所以全程按整数走，避免格式化再进一次浮点误差
    var scaled = ((a - d) * 60.0 * 10_000.0).roundToInt().toLong()
    // 59.99995 四舍五入会把分推到 60，这时进位到度，否则会发出 "3060.0000" 这种非法分钟数
    if (scaled >= 600_000L) {
        scaled -= 600_000L
        d += 1
    }
    return "%0${degDigits}d%02d.%04d".format(Locale.ROOT, d, scaled / 10_000, scaled % 10_000)
}

/** 度分串 → 十进制度（带符号）。字段非法就返回 null，不做兜底猜测。 */
internal fun fromDegMin(field: String, sign: Double): Double? {
    val f = field.trim()
    val dot = f.indexOf('.')
    if (dot <= 0) return null
    // 度分格式里分钟恒为 2 位整数 + 小数，所以度的长度 = 点号位置 - 2
    val degLen = dot - 2
    if (degLen < 1) return null
    val deg = f.substring(0, degLen).toDoubleOrNull() ?: return null
    val min = f.substring(degLen).toDoubleOrNull() ?: return null
    if (min < 0 || min >= 60) return null
    return sign * (deg + min / 60.0)
}
