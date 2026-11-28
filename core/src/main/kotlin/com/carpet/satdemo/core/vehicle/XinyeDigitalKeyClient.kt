package com.carpet.satdemo.core.vehicle

import java.io.IOException
import java.security.MessageDigest
import java.util.Properties
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit

data class XinyeCredentials(
    val appId: String,
    val key: String,
    val licenseNumber: String? = null,
    val imei: String? = null,
    val mobile: String? = null,
) {
    init {
        require(appId.isNotBlank()) { "数字钥匙配置缺少 appid" }
        require(key.isNotBlank()) { "数字钥匙配置缺少 key" }
        require(!licenseNumber.isNullOrBlank() || !imei.isNullOrBlank()) {
            "数字钥匙配置需要车牌号或设备号"
        }
    }

    override fun toString(): String =
        "XinyeCredentials(appId=${appId.take(2)}***, key=***, hasLicense=${!licenseNumber.isNullOrBlank()}, hasImei=${!imei.isNullOrBlank()})"

    companion object {
        fun fromProperties(text: String): XinyeCredentials {
            val properties = Properties().apply { load(text.reader()) }
            return XinyeCredentials(
                appId = properties.getProperty("appid").orEmpty().trim(),
                key = properties.getProperty("key").orEmpty().trim(),
                licenseNumber = properties.getProperty("licenseNumber")?.trim()?.takeIf { it.isNotEmpty() },
                imei = properties.getProperty("imei")?.trim()?.takeIf { it.isNotEmpty() },
                mobile = properties.getProperty("mobile")?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }
}

enum class XinyeInstruction(val wire: String) {
    UNLOCK_DOOR("UNLOCK DOOR"),
    LOCK_DOOR("LOCK DOOR"),
    UNLOCK_TRUNK("UNLOCK TRUNK"),
    LOCK_TRUNK("LOCK TRUNK"),
    ENGINE_START("ENGINE START"),
    ENGINE_STOP("ENGINE STOP"),
}

enum class XinyeFailureKind { Network, Http, Auth, Rejected, Receipt, Protocol, RateLimited, Cancelled }

data class XinyeApiFailure(
    val kind: XinyeFailureKind,
    val message: String,
    val partnerCode: Int? = null,
    val httpStatus: Int? = null,
)

enum class XinyeLookupIdSource { Uuid, HistoryId }

sealed interface XinyeReceipt {
    data object Pending : XinyeReceipt
    data class Responded(val response: String?) : XinyeReceipt
    data class Rejected(val failure: XinyeApiFailure) : XinyeReceipt
}

sealed interface XinyeSendResult {
    data class Accepted(
        val historyId: String?,
        val lookupId: String?,
        val lookupIdSource: XinyeLookupIdSource?,
        val replied: Boolean,
        val reply: String?,
    ) : XinyeSendResult

    data class Rejected(val failure: XinyeApiFailure) : XinyeSendResult
}

class XinyeDigitalKeyClient(
    private val credentials: XinyeCredentials,
    private val transport: (Request) -> Response = { request -> sharedHttp.newCall(request).execute() },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = Thread::sleep,
    private val beforeSend: () -> Boolean = { true },
) {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private var token: String? = null
    private var tokenExpiresAtMs = 0L
    private var nextAuthMs = 0L
    private var nextFunctionMs = 0L

    fun send(instruction: XinyeInstruction): XinyeSendResult = synchronized(lock) {
        try {
            sendLocked(instruction)
        } catch (error: ApiException) {
            XinyeSendResult.Rejected(error.failure)
        }
    }

    fun receipt(lookupId: String): XinyeReceipt = synchronized(lock) {
        if (lookupId.isBlank()) {
            return@synchronized XinyeReceipt.Rejected(
                XinyeApiFailure(XinyeFailureKind.Protocol, "回执编号为空"),
            )
        }
        try {
            receiptLocked(lookupId)
        } catch (error: ApiException) {
            XinyeReceipt.Rejected(error.failure)
        }
    }

    private fun sendLocked(instruction: XinyeInstruction): XinyeSendResult {
        val accessToken = accessToken()
        nextFunctionMs = awaitSlot(nextFunctionMs, FUNCTION_GAP_MS)
        val body = buildJsonObject {
            credentials.mobile?.let { put("mobile", it) }
            credentials.licenseNumber?.let { put("licenseNumber", it) }
            credentials.imei?.let { put("imei", it) }
            put("instruction", instruction.wire)
        }.toString()
        val request = Request.Builder()
            .url(
                serviceUrl.resolve("xinye/instruction/send")!!.newBuilder()
                    .addQueryParameter("accessToken", accessToken)
                    .build(),
            )
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        if (!beforeSend()) {
            throw ApiException(
                XinyeApiFailure(XinyeFailureKind.Cancelled, "不可逆权限已关闭，本次未发送"),
            )
        }
        val root = executeJson(request)
        val code = root.int("code")
            ?: throw ApiException(XinyeApiFailure(XinyeFailureKind.Protocol, "发送回包缺少 code"))
        if (code != 0) throw partnerError(XinyeFailureKind.Rejected, root)
        val item = root.items("details", "data").singleOrNull()
            ?: throw ApiException(XinyeApiFailure(XinyeFailureKind.Protocol, "发送回包没有唯一指令条目"))
        val itemCode = item.int("code")
            ?: throw ApiException(XinyeApiFailure(XinyeFailureKind.Protocol, "指令条目缺少 code"))
        if (itemCode != 0) throw partnerError(XinyeFailureKind.Rejected, item)
        val historyId = item.string("historyId")?.takeIf { it.isNotBlank() }
        val uuid = item.string("uuid")?.takeIf { it.isNotBlank() }
        if (historyId == null && uuid == null) {
            throw ApiException(XinyeApiFailure(XinyeFailureKind.Protocol, "发送回包没有 historyId 或 uuid"))
        }
        val status = item.int("status")
            ?: throw ApiException(XinyeApiFailure(XinyeFailureKind.Protocol, "发送条目缺少 status"))
        val replied = when (status) {
            0 -> false
            1 -> true
            else -> throw ApiException(XinyeApiFailure(XinyeFailureKind.Protocol, "发送 status 未知"))
        }
        return XinyeSendResult.Accepted(
            historyId = historyId,
            lookupId = uuid,
            lookupIdSource = if (uuid != null) XinyeLookupIdSource.Uuid else XinyeLookupIdSource.HistoryId,
            replied = replied,
            reply = if (replied) item.string("response") else null,
        )
    }

    private fun receiptLocked(lookupId: String): XinyeReceipt {
        val accessToken = accessToken()
        nextFunctionMs = awaitSlot(nextFunctionMs, FUNCTION_GAP_MS)
        val request = Request.Builder()
            .url(
                serviceUrl.resolve("instruction/relay")!!.newBuilder()
                    .addQueryParameter("accessToken", accessToken)
                    .addQueryParameter("uuid", lookupId)
                    .build(),
            )
            .get()
            .build()
        val root = executeJson(request)
        val code = root.int("code")
            ?: throw ApiException(XinyeApiFailure(XinyeFailureKind.Protocol, "回执回包缺少 code"))
        if (code != 0) throw partnerError(XinyeFailureKind.Receipt, root)
        val items = root.items("details", "data")
        if (items.isEmpty()) return XinyeReceipt.Pending
        val item = items.singleOrNull()
            ?: throw ApiException(XinyeApiFailure(XinyeFailureKind.Protocol, "回执回包不是唯一指令条目"))
        if (item.string("uuid") != lookupId) {
            throw ApiException(XinyeApiFailure(XinyeFailureKind.Protocol, "回执回包编号与查询不一致或缺失"))
        }
        val itemCode = item.int("code")
            ?: throw ApiException(XinyeApiFailure(XinyeFailureKind.Protocol, "回执条目缺少 code"))
        if (itemCode != 0) throw partnerError(XinyeFailureKind.Receipt, item)
        return when (item.int("status")) {
            1 -> XinyeReceipt.Responded(item.string("response"))
            0, null -> XinyeReceipt.Pending
            else -> throw ApiException(XinyeApiFailure(XinyeFailureKind.Protocol, "回执 status 未知"))
        }
    }

    private fun accessToken(): String {
        val now = nowMs()
        token?.let { if (now < tokenExpiresAtMs) return it }
        nextAuthMs = awaitSlot(nextAuthMs, AUTH_GAP_MS)
        val time = nowMs() / 1000L
        val signature = md5(md5(credentials.key) + time.toString())
        val body = buildJsonObject {
            put("appid", credentials.appId)
            put("time", time)
            put("signature", signature)
        }.toString()
        val request = Request.Builder()
            .url(serviceUrl.resolve("auth")!!)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val root = executeJson(request)
        val code = root.int("code")
            ?: throw ApiException(XinyeApiFailure(XinyeFailureKind.Protocol, "认证回包缺少 code"))
        if (code != 0) throw partnerError(XinyeFailureKind.Auth, root)
        val value = root.string("accessToken")?.takeIf { it.isNotBlank() }
            ?: throw ApiException(XinyeApiFailure(XinyeFailureKind.Protocol, "认证回包没有 accessToken"))
        val expiresIn = root.long("expiresIn") ?: DEFAULT_EXPIRES_MS
        if (expiresIn <= 0) {
            throw ApiException(XinyeApiFailure(XinyeFailureKind.Protocol, "认证有效期异常"))
        }
        token = value
        tokenExpiresAtMs = nowMs() + minOf(TOKEN_CACHE_MS, maxOf(1_000L, expiresIn - 30_000L))
        return value
    }

    private fun executeJson(request: Request): JsonObject {
        return try {
            transport(request).use { resp ->
                if (!resp.isSuccessful) {
                    if (resp.code == 401 || resp.code == 403) {
                        token = null
                        tokenExpiresAtMs = 0L
                    }
                    val kind = when (resp.code) {
                        401, 403 -> XinyeFailureKind.Auth
                        429 -> XinyeFailureKind.RateLimited
                        else -> XinyeFailureKind.Http
                    }
                    throw ApiException(
                        XinyeApiFailure(kind, "合作方 HTTP ${resp.code}", httpStatus = resp.code),
                    )
                }
                val text = resp.body?.string().orEmpty()
                try {
                    val element = json.parseToJsonElement(text)
                    element as? JsonObject
                        ?: throw ApiException(XinyeApiFailure(XinyeFailureKind.Protocol, "合作方回包不是 JSON 对象"))
                } catch (_: SerializationException) {
                    throw ApiException(XinyeApiFailure(XinyeFailureKind.Protocol, "合作方回包不是合法 JSON"))
                }
            }
        } catch (_: IOException) {
            throw ApiException(XinyeApiFailure(XinyeFailureKind.Network, "连不上合作方服务"))
        }
    }

    private fun awaitSlot(nextMs: Long, gapMs: Long): Long {
        val now = nowMs()
        val start = maxOf(now, nextMs)
        if (start > now) sleeper(start - now)
        return start + gapMs
    }

    private fun partnerError(kind: XinyeFailureKind, root: JsonObject): ApiException =
        ApiException(
            XinyeApiFailure(
                kind = kind,
                message = root.string("result")?.takeIf { it.isNotBlank() }
                    ?: root.string("message")?.takeIf { it.isNotBlank() }
                    ?: "合作方返回错误",
                partnerCode = root.int("code"),
            ),
        )

    private fun JsonObject.int(name: String): Int? =
        (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.toIntOrNull()

    private fun JsonObject.long(name: String): Long? =
        (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.toLongOrNull()

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun JsonObject.items(vararg names: String): List<JsonObject> {
        names.forEach { name ->
            val element = this[name] ?: return@forEach
            val parsed = when (element) {
                is JsonArray -> element.mapNotNull { it as? JsonObject }
                is JsonObject -> listOf(element)
                else -> emptyList()
            }
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    private fun md5(value: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private class ApiException(val failure: XinyeApiFailure) : Exception()

    companion object {
        private const val AUTH_GAP_MS = 30_000L
        private const val FUNCTION_GAP_MS = 100L
        private const val TOKEN_CACHE_MS = 90 * 60 * 1000L
        private const val DEFAULT_EXPIRES_MS = 2 * 60 * 60 * 1000L
        private val serviceUrl = "https://open.figps.com/api/".toHttpUrl()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        internal val sharedHttp = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            // One logical vehicle send must never be replayed by the transport.
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
