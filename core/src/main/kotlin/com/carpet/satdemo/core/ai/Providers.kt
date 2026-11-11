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
import kotlinx.serialization.json.JsonPrimitive
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
    /**
     * 关掉网关那侧的「先想再答」。实测同一个端点同一个问题，开着思考 8.8 秒、关掉 3.2 秒
     * （关掉之后最差也见过 10 秒那一趟），而 12 秒是这趟请求的超时上限 ——
     * 不关的话真实问答**会擦着超时线退成本机话术**。
     * 但 `thinking` 不是 OpenAI 标准字段，别的网关可能直接 400，所以网关拒了这一项时
     * [OpenAiCompatProvider] 会自动去掉它再试一次，不是把整个答案丢掉。
     */
    val disableThinking: Boolean = false,
) {
    /** 三个都填了才算配好。少一个都不试：试了就是等一次超时，用户在首屏上看着转圈 */
    val configured: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    /** 真正要 POST 的地址。基址先过 [normalizeBaseUrl]，见那条注释 */
    val chatUrl: String get() = normalizeBaseUrl(baseUrl) + "/chat/completions"
}

/**
 * 把用户抄进来的地址收成「`/v1` 这一层」。
 *
 * 现场填错的那一次是 `https://api.maomaoai.pro/v1/responses` —— 那是 OpenAI **Responses API**
 * 的完整调用地址，不是 Base URL。旧实现只在尾巴上拼 `/chat/completions`，于是真的去 POST
 * `/v1/responses/chat/completions`，网关回 404 `Invalid URL`，而失败又被吞成 null，
 * 界面上只剩「未接模型」。用户没有任何线索能看出是地址多了五个字符。
 *
 * 所以这里把常见的「整条调用地址」都退回到基址：`/chat/completions`、`/completions`、
 * `/responses`、`/v1/messages`。只做减法、不做加法 —— 没写 `/v1` 的仍然不加，
 * 那属于网关自己的路径口径，猜错了是另一种 404。
 */
fun normalizeBaseUrl(raw: String): String {
    var s = raw.trim().trimEnd('/')
    for (suffix in listOf("/chat/completions", "/completions", "/responses", "/v1/messages")) {
        if (s.endsWith(suffix)) {
            s = s.removeSuffix(suffix).trimEnd('/')
            break
        }
    }
    return s
}

/**
 * 这个基址实际该试的几种写法，**按可信度排序**。
 *
 * [normalizeBaseUrl] 只做减法，因为它不该替网关猜路径；但"猜"这件事在自检里是对的 ——
 * 猜错了只是多打一次请求，猜对了用户就少折腾一轮。现场这两条都真发生过：
 * - `https://api.maomaoai.pro`（少了 `/v1`）→ 网关回 **200 的 HTML 首页**，解析不出对话，界面只会说"不是对话格式"；
 * - `https://api.maomaoai.pro/v1/v1`（多抄了一截）→ 404。
 *
 * 所以：没有路径的补 `/v1`，连着两个 `/v1` 的收掉一个。**已经带了别的路径段就不动** ——
 * 那可能是自建网关的 `/api`，我们再补一层只是另一种 404。
 */
fun baseUrlCandidates(raw: String): List<String> {
    val base = normalizeBaseUrl(raw)
    if (base.isEmpty()) return emptyList()
    val path = runCatching { URI.create(base).path ?: "" }.getOrDefault("").trim('/')
    val segments = path.split('/').filter { it.isNotBlank() }
    return when {
        segments.isEmpty() -> listOf(base, "$base/v1")
        // /v1/v1：这一层本来就是多抄的，只试收掉之后那一个。回头再试 /v1/v1 只是白等一次 404
        segments.size > 1 && segments.all { it.equals("v1", ignoreCase = true) } ->
            listOf(base.substringBeforeLast("/"))
        else -> listOf(base)
    }.distinct()
}

data class ChatTurn(val role: String, val content: String)

interface AiProvider {
    val label: String
    val live: Boolean

    /**
     * 上一次 [complete] 为什么没答上来。**失败必须说得出原因**：
     * 旧实现把所有失败收成一个 null，界面上只有「未接模型」—— 地址多写了 `/responses`
     * 和网线没插看起来一模一样，现场只能瞎猜。null = 还没试过或上一次是成功的。
     */
    val lastError: String? get() = null

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
    // GET（拉模型清单）也要能换掉：单测里不许真出网。**放在 transport 前面** ——
    // 尾随 lambda 绑定的是最后一个参数，`OpenAiCompatProvider(ep) { _, _, _ -> … }` 那种写法
    // 在旧的单测里到处都是，参数顺序一颠倒就全部编译不过
    private val transportGet: (url: String, headers: Map<String, String>) -> String =
        { u, h -> get(u, h, timeout.toMillis()) },
    private val transport: (url: String, body: String, headers: Map<String, String>) -> String =
        { u, b, h -> post(u, b, h, timeout.toMillis()) },
) : AiProvider {

    override val label: String get() = endpoint.model.ifBlank { "远端模型" }

    override val live: Boolean get() = endpoint.configured

    @Volatile
    private var error: String? = null

    override val lastError: String? get() = error

    /**
     * 上一次成功那一趟用的基址，和用户填的不一样时这里就是**该改成什么**。
     *
     * 2026-11-09 现场：填 `https://api.maomaoai.pro`（少了 `/v1`），网关回 200 的 HTML 首页，
     * 界面上只说"不是对话格式"—— 用户没有任何线索能想到是少了三个字符。
     * 现在自检会按 [baseUrlCandidates] 再试一次，试通了把正确基址留在这一格，宿主据此改配置。
     */
    @Volatile
    var correctedBase: String? = null
        private set

    fun setEndpoint(next: AiEndpoint) {
        endpoint = next
        error = null
        correctedBase = null
    }

    override suspend fun complete(facts: String, history: List<ChatTurn>, question: String): String? {
        if (!endpoint.configured) {
            error = "地址 / 密钥 / 模型名少填了一项"
            return null
        }
        // 明文 http 在 Android 上不是"能连但慢"，是**根本发不出去**：network_security_config 默认
        // 禁明文，抛的是一句看不出所以然的 CleartextNotPermittedException。现场抄地址时少了那个 s，
        // 界面上只会说"请求失败"，而真正要改的就是三个字符
        if (endpoint.baseUrl.startsWith("http://", ignoreCase = true)) {
            error = "地址是 http://，这台 App 只放行 https（把 s 补上）"
            return null
        }
        val candidates = baseUrlCandidates(endpoint.baseUrl).ifEmpty {
            error = "地址形状不对：${endpoint.baseUrl}"
            return null
        }
        val headers = mapOf(
            "Content-Type" to "application/json",
            "Authorization" to "Bearer ${endpoint.apiKey}",
        )
        // Dispatchers.IO：Android 在主线程做网络会直接抛 NetworkOnMainThreadException，
        // 而这一句的调用方（DialogBridge）跑在宿主给的作用域里，宿主是 Main
        return withContext(Dispatchers.IO) {
            shapeFailure = false
            var last: String? = null
            for (base in candidates) {
                val url = try {
                    URI.create(base + "/chat/completions").toURL().toString()
                } catch (e: IllegalArgumentException) {
                    error = "地址形状不对：${endpoint.baseUrl}"
                    return@withContext null
                }
                val text = request(url, headers, facts, history, question)
                if (text != null) {
                    correctedBase = base.takeIf { it != normalizeBaseUrl(endpoint.baseUrl) }
                    return@withContext text
                }
                last = error
                // 只有"地址口径"这一类错才值得换下一种写法：超时、DNS、密钥错换地址也是同一个结果，
                // 多试一次只是让用户多等一个超时
                if (!shapeFailure) break
            }
            // **有些网关只实现 Responses API**。用户手里能跑通的那条 curl 打的正是 `/v1/responses`，
            // 而这家的 `/chat/completions` 回 404 —— 旧实现于是每次都报"未接模型"，
            // 换多少个模型名都一样。chat 那条整条路都撞在"地址口径"上时，再按 Responses 的形状打一遍
            if (shapeFailure) {
                for (base in candidates) {
                    val respUrl = runCatching { URI.create(base + "/responses").toURL().toString() }
                        .getOrNull() ?: continue
                    val viaResponses = requestResponses(respUrl, headers, facts, history, question)
                    if (viaResponses != null) {
                        correctedBase = base.takeIf { it != normalizeBaseUrl(endpoint.baseUrl) }
                        return@withContext viaResponses
                    }
                    last = listOfNotNull(last, error).joinToString("；")
                    if (!shapeFailure) break
                }
            }
            error = last
            null
        }
    }

    /** 上一次失败是不是"地址写法不对"这一类（404 / 200 却不是对话格式）。换基址重试只认这一类 */
    @Volatile
    private var shapeFailure = false

    /**
     * `GET {base}/models`：这把 key 在这家网关上**到底能点哪些模型**。
     *
     * 存在的理由很直接：2026-11-09 用户说"没道理不能用，你换个模型试试"。模型名是手抄进输入框的
     * 一个字符串，错一个字母网关就回 404 `model_not_found`，而从界面上看和"网断了"没差别。
     * 与其让用户去翻别人的配置截图，不如直接问网关要这张清单。
     *
     * 失败不写 [error]：这是配置页的辅助动作，不该把首页那句"模型没答上来"改掉。
     */
    suspend fun listModels(): List<String> {
        if (endpoint.baseUrl.isBlank() || endpoint.apiKey.isBlank()) return emptyList()
        val headers = mapOf("Authorization" to "Bearer ${endpoint.apiKey}")
        return withContext(Dispatchers.IO) {
            for (base in baseUrlCandidates(endpoint.baseUrl)) {
                val raw = runCatching { transportGet(base + "/models", headers) }.getOrNull() ?: continue
                val ids = parseModels(raw)
                if (ids.isNotEmpty()) return@withContext ids
            }
            emptyList()
        }
    }

    /**
     * 真发一次。**失败要把原因留下来**（[lastError]），界面上那句「未接模型」才有下半句。
     *
     * `thinking` 不是 OpenAI 标准字段，有的网关不认就回 400。旧实现是整条放弃，
     * 表现和"密钥错了"一模一样；现在按 4xx 判一次，去掉它重发 —— 代码里声明了 `disableThinking`
     * 只代表"想关"，关不掉时能答话比答话慢要紧。
     */
    private fun request(
        url: String,
        headers: Map<String, String>,
        facts: String,
        history: List<ChatTurn>,
        question: String,
    ): String? {
        var raw: String
        try {
            raw = transport(url, body(facts, history, question, endpoint.disableThinking), headers)
        } catch (e: HttpStatusException) {
            // 404/405 是路径口径不对，跟 thinking 那个字段没关系：去掉它重发只是白等一次
            shapeFailure = e.looksLikeWrongPath
            if (endpoint.disableThinking && e.code in 400..499 && !shapeFailure) {
                // 网关嫌 thinking 字段：去掉再试一次，这次的结果（无论成败）才是最终答案
                return try {
                    val retry = transport(url, body(facts, history, question, false), headers)
                    accept(retry)
                } catch (again: HttpStatusException) {
                    shapeFailure = again.looksLikeWrongPath
                    error = "HTTP ${again.code}：${again.snippet.ifBlank { "网关没给正文" }}"
                    null
                } catch (again: Exception) {
                    shapeFailure = false
                    error = reasonOf(again)
                    null
                }
            }
            error = "HTTP ${e.code}：${e.snippet.ifBlank { "网关没给正文" }}"
            return null
        } catch (e: Exception) {
            shapeFailure = false
            error = reasonOf(e)
            return null
        }
        return accept(raw)
    }

    /**
     * 按 **OpenAI Responses API** 的形状再打一次：`POST /responses`，请求体是
     * `{"model":…,"instructions":…,"input":[…]}`，回答在 `output_text` 或 `output[].content[].text`。
     *
     * 存在的理由就是现场那条 curl —— 用户手里能跑通的那条打的是 `/v1/responses`。
     * 只在 chat 那一条撞上"地址口径"类失败（404/405/200 HTML）时才走这里：
     * 网关真没实现它会回 404，多打这一发只花一次超时的一半。
     */
    private fun requestResponses(
        url: String,
        headers: Map<String, String>,
        facts: String,
        history: List<ChatTurn>,
        question: String,
    ): String? {
        var raw: String
        try {
            raw = transport(url, responsesBody(facts, history, question), headers)
        } catch (e: HttpStatusException) {
            shapeFailure = e.looksLikeWrongPath
            error = "Responses 接口 HTTP ${e.code}：${e.snippet.ifBlank { "网关没给正文" }}"
            return null
        } catch (e: Exception) {
            shapeFailure = false
            error = reasonOf(e)
            return null
        }
        val text = parseResponses(raw)?.trim()
        if (text.isNullOrEmpty()) {
            shapeFailure = true
            error = "Responses 接口回了不是对话格式的内容：${raw.take(80).replace('\n', ' ')}"
            return null
        }
        error = null
        return text
    }

    private fun responsesBody(facts: String, history: List<ChatTurn>, question: String): String = buildString {
        append("{\"model\":\"").append(escape(endpoint.model)).append("\",")
        append("\"instructions\":\"").append(escape("$SYSTEM\n已知事实（只有这些是真的，缺的就说缺）：\n$facts")).append("\",")
        append("\"input\":[")
        var has = false
        history.takeLast(6).forEach { h ->
            if (has) append(',')
            append("{\"role\":\"").append(if (h.role == "assistant") "assistant" else "user")
                .append("\",\"content\":\"").append(escape(h.content)).append("\"}")
            has = true
        }
        if (has) append(',')
        append("{\"role\":\"user\",\"content\":\"").append(escape(question)).append("\"}]}")
    }

    private fun accept(raw: String): String? {
        val text = parse(raw)?.trim()
        if (text.isNullOrEmpty()) {
            // 200 却不是对话：多半是撒到网关的网页上去了（少了 /v1 时 maomaoai 就回首页 HTML）
            shapeFailure = true
            error = "网关回了不是对话格式的内容：${raw.take(80).replace('\n', ' ')}"
            return null
        }
        error = null
        return text
    }

    /** 超时、连不上、DNS —— 这三类用户能自己动手的方向完全不同，不要合并成一句"请求失败" */
    private fun reasonOf(e: Exception): String = when (e) {
        // 直连失败最常见的两类都不是"密钥错了"。带上下一步：
        // 超时多半是模型太慢（同一个网关，带 flash 的通常 1–5 秒，带 thinking 的见过 10 秒以上），
        // 连不上多半是地址抄错或这台机器根本出不去网
        is java.net.SocketTimeoutException -> "12 秒没回话（超时）—— 换个更快的模型再试（名字里带 flash 的通常最快）"
        is java.net.UnknownHostException -> "域名解析不了：${e.message}（地址抄错了，或者这台手机出不去网）"
        is java.net.ConnectException -> "连不上网关：${e.message}（端口被挡，或者地址不是这家网关的入口）"
        // 明文被系统拦掉时抛的是这一支，消息里只有类名，用户看不出是 http/https 之差
        is java.net.SocketException -> if (e.message?.contains("Cleartext", ignoreCase = true) == true) {
            "地址是 http://，这台 App 只放行 https（把 s 补上）"
        } else {
            "连接被重置：${e.message}"
        }
        else -> "请求失败：${e.javaClass.simpleName}${e.message?.let { " · $it" } ?: ""}"
    }

    private fun body(facts: String, history: List<ChatTurn>, question: String, thinking: Boolean): String = buildString {
        append("{\"model\":\"").append(escape(endpoint.model)).append("\",")
        // 实测 20 字答句 completion 就要 158 token（145 是思考草稿），给 120 会截断漏推理，250 起步才稳
        append("\"max_tokens\":250,")
        append("\"temperature\":0.2,\"messages\":[")
        append("{\"role\":\"system\",\"content\":\"").append(escape(SYSTEM)).append("\"},")
        append("{\"role\":\"system\",\"content\":\"").append(escape("已知事实（只有这些是真的，缺的就说缺）：\n$facts")).append("\"}")
        history.takeLast(6).forEach { h ->
            append(",{\"role\":\"").append(h.role).append("\",\"content\":\"").append(escape(h.content)).append("\"}")
        }
        append(",{\"role\":\"user\",\"content\":\"").append(escape(question)).append("\"}")
        append("]")
        if (thinking) append(",\"thinking\":{\"type\":\"disabled\"}")
        append("}")
    }

    companion object {
        /**
         * 系统提示词里两条是硬要求：**不许编数字**（数字只能来自 facts），**SOS 一类不可逆动作不许替用户决定**
         * （§6.1 文案红线同样约束模型 —— 它也不能说"已送达"）。
         * 一句 ≤30 字是 2026-11-11 拍的口径：答案要进语音，长句既慢又烧 token，屏侧 2 MB 缓冲也放不下。
         */
        private const val SYSTEM =
            "你是车宠守护助手。用一句话回答，不超过30个字，直接给结论，不要解释推理过程。" +
                "规则：只根据给出的已知事实回答，缺事实就直说不知道，绝不编数字或状态；" +
                "不得声称求救、消息已送达或平台已受理，除非事实里明确写了已交核心网；" +
                "不可逆动作（SOS、远程熄火、扣费）只能提示用户自己在界面上确认，不能替用户执行；" +
                "中文，像设备在说话，不像客服。"

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

        /**
         * Responses API 的回答。三种形状都要认：
         * - `{"output_text":"…"}`（SDK 的便捷字段，有的网关照抄）
         * - `{"output":[{"content":[{"text":"…"}]}]}`（REST 的正式形状）
         * - `{"output":[{"content":[{"type":"output_text","text":"…"}]}]}`
         * 只取 message 那一类；`reasoning` 那些条目不是给用户看的。
         */
        fun parseResponses(raw: String): String? = runCatching {
            val obj = json.parseToJsonElement(raw) as? JsonObject ?: return null
            (obj["output_text"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { return it }
            val output = obj["output"] as? JsonArray ?: return null
            output.filterIsInstance<JsonObject>()
                .mapNotNull { item ->
                    (item["content"] as? JsonArray)?.filterIsInstance<JsonObject>()
                        ?.firstNotNullOfOrNull { c -> (c["text"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } }
                }
                .firstOrNull()
        }.getOrNull()

        /**
         * `GET /models` 的两种形状：`{"data":[{"id":"qwen3.6-flash"}]}`（OpenAI 标准）
         * 和 `{"models":[{...}]}`。只取 id/model 两种字段名，别的一概不要 —— 我们要的只是一串能点的名字。
         */
        fun parseModels(raw: String): List<String> = runCatching {
            val obj = json.parseToJsonElement(raw) as? JsonObject ?: return emptyList()
            val arr = (obj["data"] ?: obj["models"]) as? JsonArray ?: return emptyList()
            arr.mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                (o["id"] ?: o["model"])?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            }.distinct().sorted()
        }.getOrDefault(emptyList())

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
         * 非 2xx 连同状态码和正文前一段抛出去 —— 界面上要能分清 401（密钥）和 404（地址口径）。
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
                if (code / 100 != 2) throw HttpStatusException(code, text.take(160).replace('\n', ' '))
                return text
            } finally {
                conn.disconnect()
            }
        }

        /** 同 [post]，只读。读超时放宽到 20 秒：模型清单本身很小，慢的只是个别网关的管理面 */
        private fun get(url: String, headers: Map<String, String>, readMs: Long): String {
            val conn = URI.create(url).toURL().openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 6_000
            conn.readTimeout = readMs.coerceIn(1_000, 60_000).toInt()
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            try {
                val code = conn.responseCode
                val text = (if (code / 100 == 2) conn.inputStream else conn.errorStream)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                if (code / 100 != 2) throw HttpStatusException(code, text.take(160).replace('\n', ' '))
                return text
            } finally {
                conn.disconnect()
            }
        }
    }
}

/** 网关明确回了非 2xx。带上状态码，调用方才能判断"是不是 thinking 那项被拒" */
class HttpStatusException(val code: Int, val snippet: String) : Exception("http $code") {
    /**
     * 这一次是不是"这个 URL 在网关那边不存在"。
     *
     * 不只认 404/405：有的网关对没实现的路由回 **400 + `Invalid URL` / `unknown endpoint`**
     * （maomaoai 对 `/chat/completions` 就是这一类）。只认状态码会把这种 400 当"参数错了"，
     * 于是 Responses 那条备份路永远走不到，用户看到的原因还是含糊的一句 HTTP 400。
     */
    val looksLikeWrongPath: Boolean
        get() {
            if (code == 404 || code == 405) return true
            if (code !in 400..403) return false
            val s = snippet.lowercase()
            return listOf("invalid url", "unknown url", "unknown endpoint", "unsupported endpoint", "no route", "路径")
                .any { s.contains(it) }
        }
}
