package com.carpet.satdemo.core.ai

import java.net.HttpURLConnection
import java.net.URI
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 模型端点。**只做成 OpenAI 兼容的 `/chat/completions`** —— 市面上几乎所有网关（自建、Azure、
 * 各家国产模型）都兼容这一形状，我们不为一家公司写死一条链路，也不自己造协议。
 *
 * 关键约束：**没配端点时不许假装能聊**。[AiGateway] 会退回本地话术并把 `source` 标成
 * [ReplySource.LocalFallback]，界面上必须显示「未接真源」。演示现场最尴尬的事是 AI 说了一句漂亮话，
 * 观众以为接了大模型，其实是规则模板。
 */
@Serializable
data class AiEndpoint(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
) {
    /** 三个都填了才算配好。少一个都不试：试了就是等一次超时，用户在首屏上看着转圈 */
    val configured: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

data class ChatTurn(val role: String, val content: String)

interface AiProvider {
    val label: String
    val live: Boolean

    /** 拿结构化事实 + 对话历史，生成最后那句话。失败要返回 null，不许抛到 UI 线程 */
    suspend fun complete(facts: String, history: List<ChatTurn>, question: String): String?
}

/** 本地话术器：把 Skill 自己那句结论原样说出来。**这是默认 provider**，所以断网、没 key 也能演示，但界面得标 */
class LocalRuleProvider : AiProvider {
    override val label = "本机规则"
    override val live = false

    override suspend fun complete(facts: String, history: List<ChatTurn>, question: String): String? = null
}

/**
 * OpenAI 兼容端点。
 *
 * ⚠️ **走的是 `HttpURLConnection`，不是 JDK 11 那个 `java.net.http.HttpClient`**。
 * 上一版用了后者，编译和 214 条单测全绿、桌面彩排台也跑得完美 —— 因为那些都在 JDK 17 上跑。
 * **Android 到现在都没有 `java.net.http` 这个包**，而 `AppRuntime` 在 `Application.onCreate`
 * 里就构造这个 provider，类初始化一碰到 `HttpClient` 就是 NoClassDefFoundError，
 * 于是两台手机装上去**点开就闪退，一行界面都没有**。纯 JVM 模块里那种"桌面也有"的 API，
 * 必须逐个确认 Android 有没有 —— 这条由 `:core` 的 AndroidCompatTest 扫源码看着。
 *
 * 传输从外面注入（[transport]）：单测和工程模式换掉这一跳就能验请求形状，不需要真起一个网关。
 */
class OpenAiCompatProvider(
    private var endpoint: AiEndpoint,
    private val timeout: Duration = Duration.ofSeconds(12),
    private val transport: (url: String, body: String, headers: Map<String, String>) -> String =
        { u, b, h -> post(u, b, h, timeout.toMillis()) },
) : AiProvider {

    override val label: String get() = endpoint.model.ifBlank { "远端模型" }

    override val live: Boolean get() = endpoint.configured

    fun setEndpoint(next: AiEndpoint) {
        endpoint = next
    }

    override suspend fun complete(facts: String, history: List<ChatTurn>, question: String): String? {
        if (!endpoint.configured) return null
        val body = buildString {
            append("{\"model\":\"").append(escape(endpoint.model)).append("\",")
            append("\"temperature\":0.2,\"messages\":[")
            append("{\"role\":\"system\",\"content\":\"").append(escape(SYSTEM)).append("\"},")
            append("{\"role\":\"system\",\"content\":\"").append(escape("已知事实（只有这些是真的，缺的就说缺）：\n$facts")).append("\"}")
            history.takeLast(6).forEach { h ->
                append(",{\"role\":\"").append(h.role).append("\",\"content\":\"").append(escape(h.content)).append("\"}")
            }
            append(",{\"role\":\"user\",\"content\":\"").append(escape(question)).append("\"}]"
            )
            append("}")
        }
        val url = try {
            URI.create(endpoint.baseUrl.trimEnd('/') + "/chat/completions").toURL().toString()
        } catch (e: IllegalArgumentException) {
            return null // base URL 形状不对，不试第二眼
        }
        val headers = mapOf(
            "Content-Type" to "application/json",
            "Authorization" to "Bearer ${endpoint.apiKey}",
        )
        // Dispatchers.IO：Android 在主线程做网络会直接抛 NetworkOnMainThreadException，
        // 而这一句的调用方（DialogBridge）跑在宿主给的作用域里，宿主是 Main
        return withContext(Dispatchers.IO) {
            runCatching { parse(transport(url, body, headers)) }.getOrNull()
        }
    }

    companion object {
        /**
         * 系统提示词里两条是硬要求：**不许编数字**（数字只能来自 facts），**SOS 一类不可逆动作不许替用户决定**
         * （§6.1 文案红线同样约束模型 —— 它也不能说"已送达"）。
         */
        private const val SYSTEM =
            "你是车宠守护助手。规则：1) 只根据给出的已知事实回答，缺事实就直说不知道，绝不编数字或状态；" +
                "2) 不得声称求救、消息已送达或平台已受理，除非事实里明确写了已交核心网；" +
                "3) 不可逆动作（SOS、远程熄火、扣费）只能提示用户自己在界面上确认，不能替用户执行；" +
                "4) 中文，简短，像设备在说话，不像客服。"

        val json = Json { ignoreUnknownKeys = true }

        /**
         * 兼容两种返回：非流式的 choices[0].message.content，和个别网关把 content 直接放在顶层。
         * 解不开一律返回 null —— 网关回一句 HTML 错误页是很常见的事，那不该让 App 侧炸。
         */
        fun parse(raw: String): String? = runCatching {
            val obj = json.parseToJsonElement(raw) as? JsonObject ?: return null
            val choices = obj["choices"] as? JsonArray ?: return null
            val first = choices.firstOrNull() as? JsonObject ?: return null
            (first["message"] as? JsonObject)?.get("content")?.let { return it.jsonPrimitive.content }
            first["text"]?.jsonPrimitive?.content
        }.getOrNull()

        fun escape(s: String): String = buildString {
            s.forEach { c ->
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
                }
            }
        }

        /**
         * 一次 POST。**这里不许出现 `java.net.http.*`**（见类注释那次闪退）：
         * `HttpURLConnection` 从 API 1 就有，JDK 上也一样能跑，桌面彩排台和手机才是同一条代码。
         *
         * 两个超时都必须显式设：不设读超时等于无限等，现场网关不回话会把那条线程挂死。
         * 非 2xx 抛出去，由 [complete] 那一层收成 null —— 网关回 401、回 HTML 错误页都是常态。
         */
        private fun post(url: String, body: String, headers: Map<String, String>, readMs: Long): String {
            val conn = URI.create(url).toURL().openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 6_000
            conn.readTimeout = readMs.coerceIn(1_000, 60_000).toInt()
            conn.doOutput = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            try {
                // 请求体要一次写完再关流。关流之后 connect() 才真正发出去，所以这两句的顺序不能反
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val text = (if (code / 100 == 2) conn.inputStream else conn.errorStream)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                if (code / 100 != 2) error("http $code")
                return text
            } finally {
                conn.disconnect()
            }
        }
    }
}
