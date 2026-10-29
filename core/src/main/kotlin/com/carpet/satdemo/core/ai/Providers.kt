package com.carpet.satdemo.core.ai

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
 * OpenAI 兼容端点。用 JDK 17 自带的 `java.net.http` —— `:core` 是纯 JVM 模块，
 * 引 OkHttp / Retrofit 会为了一个接口把整个网络栈拖进单测里。
 */
class OpenAiCompatProvider(
    private var endpoint: AiEndpoint,
    private val timeout: Duration = Duration.ofSeconds(12),
    private val transport: (HttpRequest) -> String = ::send,
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
        val req = try {
            HttpRequest.newBuilder(URI.create(endpoint.baseUrl.trimEnd('/') + "/chat/completions"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ${endpoint.apiKey}")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        } catch (e: IllegalArgumentException) {
            return null // base URL 形状不对，不试第二眼
        }
        return runCatching { parse(transport(req)) }.getOrNull()
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
            val choices = obj["choices"] as? kotlinx.serialization.json.JsonArray ?: return null
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

        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build()

        private fun send(req: HttpRequest): String =
            client.send(req, HttpResponse.BodyHandlers.ofString()).let { resp ->
                if (resp.statusCode() / 100 != 2) error("http ${resp.statusCode()}") else resp.body()
            }
    }
}
