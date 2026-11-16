package com.carpet.satdemo.core.ai

import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * §7（2026-11-15）：天气查询的取数层。Open-Meteo，免费、无 key、免注册 ——
 * 天然满足「key 不进仓库」；出网这件事本身由权限档管（`needs=ReadCloud`）。
 *
 * 为什么在 core：手机和彩排台都要能问天气，跟 [DialogBridge] 住 core 是同一条理由。
 *
 * 诚实红线在这里落地：
 * - 没定位 → 不猜城市，回「没拿到定位」；
 * - 没网/接口挂了 → 回「没取到」，**不用旧缓存冒充新数据**（缓存只在取数成功时刷新）；
 * - 天气码只译成晴/云/雨这几档 —— WMO 码 0~3 是云量渐进，硬细分「小雨中雨」就超出
 *   这个接口给得准的范围了。
 */
object Weather {

    /** WMO weather code → 给人听的那一档。没列出的码归「多云」，不硬译 */
    internal fun codeCn(code: Int): String = when (code) {
        0 -> "晴"
        1, 2 -> "多云转晴"
        3 -> "阴"
        45, 48 -> "有雾"
        51, 53, 55, 56, 57 -> "毛毛雨"
        61, 63, 65, 66, 67, 80, 81, 82 -> "有雨"
        71, 73, 75, 77, 85, 86 -> "有雪"
        95, 96, 99 -> "有雷雨"
        else -> "多云"
    }

    /** 一次成功取到的明天预报。字段直接来自 Open-Meteo 的 daily 数组，不加工数字 */
    data class Forecast(
        val codeCn: String,
        val tMaxC: Double,
        val tMinC: Double,
        val precipProb: Int,
        val fetchedAtMs: Long,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(java.time.Duration.ofSeconds(6))
        .readTimeout(java.time.Duration.ofSeconds(8))
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    /** 缓存 30 分钟。key 是取整后的坐标 —— 同一个停车点反复问不该反复出网 */
    @Volatile private var cacheLat: Int? = null
    @Volatile private var cacheLon: Int? = null
    @Volatile private var cacheValue: Forecast? = null
    private const val CACHE_MS = 30 * 60 * 1000L

    /**
     * 取明天的预报。失败返回 null（含缓存过期）—— 调用方负责说「没取到」，不编。
     * 缓存按坐标+时效双重判定：换地方、过期都重新取，旧数据不冒充新数据。
     */
    fun tomorrow(lat: Double, lon: Double, nowMs: Long): Forecast? {
        val la = (lat * 100).toInt()
        val lo = (lon * 100).toInt()
        cacheValue?.let { c ->
            if (cacheLat == la && cacheLon == lo && nowMs - c.fetchedAtMs < CACHE_MS) return c
        }
        val url = "https://api.open-meteo.com/v1/forecast" +
            // 坐标只给到百分之一度（约 1 km）—— 天气不需要更细，也不把精确位置交给第三方
            "?latitude=${"%.2f".format(lat)}&longitude=${"%.2f".format(lon)}" +
            "&daily=weathercode,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&forecast_days=2&timezone=auto"
        return runCatching {
            val body = client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string() ?: return null
            }
            val daily = json.parseToJsonElement(body).jsonObject["daily"]?.jsonObject ?: return null
            // index 1 = 明天（index 0 是今天）；数组任一缺项都算这次没取到
            val codes = daily["weathercode"]?.jsonArray ?: return null
            val tmax = daily["temperature_2m_max"]?.jsonArray ?: return null
            val tmin = daily["temperature_2m_min"]?.jsonArray ?: return null
            val pp = daily["precipitation_probability_max"]?.jsonArray ?: return null
            if (codes.size < 2 || tmax.size < 2 || tmin.size < 2 || pp.size < 2) return null
            Forecast(
                codeCn = codeCn(codes[1].jsonPrimitive.content.toIntOrNull() ?: return null),
                tMaxC = tmax[1].jsonPrimitive.content.toDoubleOrNull() ?: return null,
                tMinC = tmin[1].jsonPrimitive.content.toDoubleOrNull() ?: return null,
                precipProb = pp[1].jsonPrimitive.content.toIntOrNull() ?: return null,
                fetchedAtMs = nowMs,
            ).also {
                cacheLat = la; cacheLon = lo; cacheValue = it
            }
        }.getOrNull()
    }
}
