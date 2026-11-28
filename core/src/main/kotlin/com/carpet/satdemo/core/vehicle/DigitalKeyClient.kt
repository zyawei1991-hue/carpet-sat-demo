package com.carpet.satdemo.core.vehicle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.time.Duration

/**
 * 数字钥匙合作方（风控在线开放平台，服务域 open.figps.com）设备侧接入。
 *
 * 规矩全部出自对方文档原文，三条直接影响写法：
 * - **鉴权**：`signature = md5( md5(key) + time )` —— key 要加密两次（文档三行红字强调）。
 *   accessToken 时效 2h，按官方建议本地只缓存 1.5h；认证≤2次/分、功能≤10次/秒，超频即限流。
 * - **空字段不返回**：值为空的键整个省略 —— 每个字段都按「键可能不存在」取，
 *   油量/外接电压这类条件字段绝不当默认能力。
 * - **坐标**：不传 mapType 即 wgs84（大地坐标系），统一按 wgs84 走，不碰厂商口径漂移。
 *
 * 为什么住 core：手机和彩排台都要能取数（同 Weather 的理由）。本类只碰 App↔云端，
 * **不碰 App↔圆屏的下行协议**，不加新 frame `t`，不占卫星报文预算。
 *
 * 诚实红线：失败态必须可分辨 —— 没配凭据 / 出网失败 / HTTP 非 2xx / 对方业务码 /
 * 返回形状不对，五种各说各的，绝不合并成「加载失败」。
 */
object DigitalKeyClient {

    var baseUrl: String = "https://open.figps.com/api"
    /** appid/key 由 :app 从仓库外注入（BuildConfig←local.properties），本类不落任何密钥 */
    var appid: String = ""
    var key: String = ""

    val configured: Boolean get() = appid.isNotBlank() && key.isNotBlank()

    /** 缺哪一项就说哪一项 */
    fun missingOf(): List<String> = buildList {
        if (appid.isBlank()) add("APPID（登录名）")
        if (key.isBlank()) add("KEY（登录秘钥）")
    }

    sealed class Dk<out T> {
        data class Ok<T>(val value: T) : Dk<T>()
        /** 凭据没配齐，出网前就挡下 */
        data class NoCred(val fields: List<String>) : Dk<Nothing>()
        /** 出网本身失败（DNS/连接/超时），带异常名，不与接口业务错混淆 */
        data class Net(val detail: String) : Dk<Nothing>()
        data class Http(val status: Int) : Dk<Nothing>()
        /** 通了但对方回业务码：code 原样带出，msg 是对方 result/message 原文 */
        data class Api(val code: Int, val msg: String) : Dk<Nothing>()
        /** 返回体不是预期形状 —— 宁可报「没解析出」也不猜值 */
        data class BadShape(val what: String) : Dk<Nothing>()
    }

    data class DeviceStatus(
        val accOn: Boolean?,
        val speedKmh: Int?,
        val chargePercent: Int?,
        val extVoltageDv: Int?,   // 0.1V 单位，仅特定型号
        val oilVolumeMl: Int?,    // ml，仅带油感线型号
    )

    data class LocationInfo(
        val lngWgs84: Double?,
        val latWgs84: Double?,
        val address: String?,
    )

    data class InstructionReceipt(val historyId: String, val imei: String)
    data class InstructionResult(val uuid: String, val executed: Boolean, val response: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(6))
        .readTimeout(Duration.ofSeconds(10))
        .retryOnConnectionFailure(true)
        .build()

    // ---- 签名与令牌缓存（认证≤2/min：命中缓存绝不出网）----

    internal fun md5Lower(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** signature = md5( md5(key) + time秒 )，与请求体里的 time 用同一个值 */
    internal fun signatureFor(key: String, timeSec: Long): String =
        md5Lower(md5Lower(key) + timeSec.toString())

    @Volatile private var token: String? = null
    @Volatile private var tokenUntilMs: Long = 0L
    private const val TOKEN_CACHE_MS = 90 * 60 * 1000L // 官方建议缓存 1.5h（时效本身 2h）

    internal fun invalidateToken() { token = null; tokenUntilMs = 0L }

    private suspend fun ensureToken(): Dk<String> {
        val now = System.currentTimeMillis()
        token?.let { if (now < tokenUntilMs && it.isNotBlank()) return Dk.Ok(it) }
        if (!configured) return Dk.NoCred(missingOf())
        val timeSec = now / 1000L
        val body = "{\"appid\":\"${esc(appid)}\",\"time\":$timeSec,\"signature\":\"${signatureFor(key, timeSec)}\"}"
        val o = when (val r = postRaw("$baseUrl/auth", body)) {
            is Dk.Ok -> r.value
            else -> return r as Dk<Nothing>
        }
        val code = o.int("code") ?: -1
        if (code != 0) return Dk.Api(code, o.str("result") ?: o.str("message") ?: "认证失败")
        val tk = o.str("accessToken")
        if (tk.isNullOrBlank()) return Dk.BadShape("认证返回里没有 accessToken")
        token = tk
        // expiresIn 是毫秒；只信比 1.5h 短的（异常值按 1.5h 保守处理）
        val expireInMs = o.int("expiresIn")?.toLong()?.coerceAtMost(TOKEN_CACHE_MS) ?: TOKEN_CACHE_MS
        tokenUntilMs = now + minOf(expireInMs, TOKEN_CACHE_MS)
        return Dk.Ok(tk)
    }

    /** 端点自检入口：只走鉴权一步，成功即「链路通」 */
    suspend fun authProbe(): Dk<String> = ensureToken()

    // ---- 业务接口（评估 §1 表逐条对上）----

    /** 车况：ACC/速度/电量白捡，油量/外接电压是条件字段（型号没有就是没有） */
    suspend fun deviceStatus(imei: String): Dk<DeviceStatus> =
        envelope("/device/status?imei=${esc(imei)}").flatMap { root ->
            val d = root.obj("data") ?: root
            Dk.Ok(
                DeviceStatus(
                    accOn = d.str("accStatus")?.toBooleanStrictOrNull(),
                    speedKmh = d.int("speed"),
                    chargePercent = d.int("chargePercentage"),
                    extVoltageDv = d.int("extVoltage"),
                    oilVolumeMl = d.int("oilVolume"),
                )
            )
        }

    /** 寻车：实时定位 + 对方直接给的中文地址（不再调地址反解析，那条禁轮询） */
    suspend fun deviceLocation(imei: String): Dk<LocationInfo> =
        envelope("/api/device/location?imei=${esc(imei)}").flatMap { root ->
            val d = root.obj("data") ?: root
            Dk.Ok(
                LocationInfo(
                    lngWgs84 = d.dbl("lng") ?: d.dbl("longitude"),
                    latWgs84 = d.dbl("lat") ?: d.dbl("latitude"),
                    address = d.str("address"),
                )
            )
        }

    /** 区间里程（非总里程）：秒级时间戳，跨度≤1 个月、只能取 9 个月内 —— 限制由调用方遵守 */
    suspend fun deviceMiles(imei: String, startSec: Long, endSec: Long): Dk<Double> =
        envelope("/device/miles?imei=${esc(imei)}&startTime=$startSec&endTime=$endSec").flatMap { root ->
            val d = root.obj("data") ?: root
            val v = d.dbl("miles") ?: d.dbl("mileage")
                ?: return@flatMap Dk.BadShape("里程接口没解析出 miles/mileage 字段")
            Dk.Ok(v)
        }

    /**
     * 数字钥匙指令。instruction 用文档枚举原文：
     * UNLOCK DOOR / LOCK DOOR / UNLOCK TRUNK / LOCK TRUNK / ENGINE START / ENGINE STOP。
     * 车牌号与设备号至少传一个；返回的 historyId 用于查执行结果。
     */
    suspend fun sendInstruction(
        instruction: String,
        imei: String? = null,
        licenseNumber: String? = null,
        mobile: String? = null,
    ): Dk<List<InstructionReceipt>> {
        if (imei.isNullOrBlank() && licenseNumber.isNullOrBlank())
            return Dk.BadShape("车牌号与设备号至少要传一个")
        if (!configured) return Dk.NoCred(missingOf())
        val tk = when (val t = ensureToken()) {
            is Dk.Ok -> t.value
            else -> return t as Dk<Nothing>
        }
        val parts = buildList {
            mobile?.takeIf { it.isNotBlank() }?.let { add("\"mobile\":\"${esc(it)}\"") }
            licenseNumber?.takeIf { it.isNotBlank() }?.let { add("\"licenseNumber\":\"${esc(it)}\"") }
            imei?.takeIf { it.isNotBlank() }?.let { add("\"imei\":\"${esc(it)}\"") }
            add("\"instruction\":\"${esc(instruction)}\"")
        }
        return postEnvelope("/xinye/instruction/send?accessToken=${esc(tk)}", "{${parts.joinToString(",")}}")
            .flatMap { root ->
                val arr = root.arr("details") ?: return@flatMap Dk.Ok(emptyList())
                Dk.Ok(
                    arr.mapNotNull {
                        val it0 = it.jsonObject
                        val hid = it0.str("historyId") ?: return@mapNotNull null
                        InstructionReceipt(hid, it0.str("imei") ?: "")
                    }
                )
            }
    }

    /** 查指令执行结果（status 1=已执行 0=未响应）。发送给 historyId、查询走 uuid —— 口径未澄清（评估六项之一），先透传同一个号 */
    suspend fun instructionResult(id: String): Dk<List<InstructionResult>> =
        envelope("/instruction/relay?uuid=${esc(id)}").flatMap { root ->
            val arr = root.arr("data") ?: root.arr("details") ?: return@flatMap Dk.Ok(emptyList())
            Dk.Ok(
                arr.map {
                    val r = it.jsonObject
                    InstructionResult(
                        uuid = r.str("uuid") ?: id,
                        executed = r.str("status") == "1",
                        response = r.str("response") ?: "",
                    )
                }
            )
        }

    // ---- 传输层（阻塞 IO 全部切到 Dispatchers.IO；只吞 Exception 不吞 Throwable）----

    private suspend fun envelope(pathAndQuery: String): Dk<JsonObject> {
        if (!configured) return Dk.NoCred(missingOf())
        val tk = when (val t = ensureToken()) {
            is Dk.Ok -> t.value
            else -> return t as Dk<Nothing>
        }
        val sep = if (pathAndQuery.contains('?')) '&' else '?'
        val o = when (val r = getRaw("$baseUrl$pathAndQuery$sep" + "accessToken=${esc(tk)}")) {
            is Dk.Ok -> r.value
            else -> return r as Dk<Nothing>
        }
        val code = o.int("code")
        if (code != null && code != 0)
            return Dk.Api(code, o.str("result") ?: o.str("message") ?: "接口返回码 $code")
        return Dk.Ok(o)
    }

    private fun <T, R> Dk<T>.flatMap(f: (T) -> Dk<R>): Dk<R> = when (this) {
        is Dk.Ok -> f(value)
        is Dk.NoCred -> this
        is Dk.Net -> this
        is Dk.Http -> this
        is Dk.Api -> this
        is Dk.BadShape -> this
    }

    private suspend fun getRaw(url: String): Dk<JsonObject> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use Dk.Http(r.code)
                parseOrBad(r.body?.string() ?: "")
            }
        } catch (e: Exception) {
            Dk.Net("${e.javaClass.simpleName}: ${e.message ?: "无详情"}")
        }
    }

    private suspend fun postRaw(url: String, body: String): Dk<JsonObject> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url)
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use Dk.Http(r.code)
                parseOrBad(r.body?.string() ?: "")
            }
        } catch (e: Exception) {
            Dk.Net("${e.javaClass.simpleName}: ${e.message ?: "无详情"}")
        }
    }

    private suspend fun postEnvelope(url: String, body: String): Dk<JsonObject> =
        postRaw(url, body).flatMap { o ->
            val code = o.int("code")
            if (code != null && code != 0)
                Dk.Api(code, o.str("result") ?: o.str("message") ?: "接口返回码 $code")
            else Dk.Ok(o)
        }

    private fun parseOrBad(text: String): Dk<JsonObject> =
        try {
            Dk.Ok(json.parseToJsonElement(text).jsonObject)
        } catch (e: Exception) {
            Dk.BadShape("返回体不是 JSON 对象（${e.javaClass.simpleName}）")
        }

    // 对方「空字段不返回」—— 所有取值必须容忍键不存在，且数字可能是串可能是数
    private fun JsonObject.str(k: String): String? =
        (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
    private fun JsonObject.int(k: String): Int? = str(k)?.toIntOrNull()
    private fun JsonObject.dbl(k: String): Double? = str(k)?.toDoubleOrNull()
    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.arr(k: String): kotlinx.serialization.json.JsonArray? = this[k] as? kotlinx.serialization.json.JsonArray

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
