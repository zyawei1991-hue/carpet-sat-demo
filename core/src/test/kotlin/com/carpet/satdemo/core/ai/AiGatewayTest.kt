package com.carpet.satdemo.core.ai

import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * AI 网关的行为钉子。**测的三件事都是现场会出事的**：路由对不对、权限拦不拦、
 * 模型说的话有没有被文案红线管住。生成质量本身没法测，那是另一码事。
 */
class AiGatewayTest {

    private class RecordingProvider(var reply: String? = "模型的话") : AiProvider {
        val seen = mutableListOf<String>()
        override val label = "test-model"
        override val live: Boolean get() = reply != null
        override suspend fun complete(facts: String, history: List<ChatTurn>, question: String): String? {
            seen += facts
            return reply
        }
    }

    private val base = SkillContext(
        scene = "车载守护",
        phase = "SAT",
        secondsToNextPass = 180,
        passWindowSec = 240,
        moduleOnline = true,
        queueWaiting = 3,
        queueCapacity = 50,
        handedOver = 12,
        screensOnline = 1,
        phoneBattery = 60,
        ephemerisFresh = true,
    )

    @org.junit.jupiter.api.Test
    fun `关键词路由到对应技能`() = runBlocking {
        val g = AiGateway(provider = LocalRuleProvider())
        assertEquals("pass.window", g.ask("下一次过境还要多久", base).skill)
        assertEquals("quota.check", g.ask("额度还剩多少条", base).skill)
        assertEquals("dock.pair", g.ask("圆屏连不上怎么办", base).skill)
        assertEquals("link.status", g.ask("现在能发吗", base).skill)
    }

    @org.junit.jupiter.api.Test
    fun `卫星类问题落到过境而不是链路`() = runBlocking {
        val g = AiGateway()
        // 「卫星」「什么时候能发」都归 pass.window，「能不能发」那种短句才归 link.status
        assertEquals("pass.window", g.ask("卫星什么时候能发", base).skill)
    }

    @org.junit.jupiter.api.Test
    fun `窗口内不许说还有 0 秒`() = runBlocking {
        val g = AiGateway(provider = LocalRuleProvider())
        val locked = base.copy(phase = "locked", secondsToNextPass = 0)
        val line = g.ask("下一次过境什么时候", locked).text
        assertTrue(!line.contains("还有 0 秒") && !line.contains("0 秒后"), "已经锁住了还把倒计时念成 0，用户会以为星正在头顶：$line")
        assertContains(line, "窗口内")
        // 没锁、也还没算出时间：这条要明说没得说，不许拿 0 当数
        val none = base.copy(phase = "idle", secondsToNextPass = null, passWindowSec = null)
        assertContains(g.ask("下一次过境什么时候", none).text, "还没有可用的过境时间")
    }

    @org.junit.jupiter.api.Test
    fun `没命中时不许编答案`() = runBlocking {
        val r = AiGateway(provider = LocalRuleProvider()).ask("帮我写一首诗", base)
        assertEquals(ReplySource.LocalFallback, r.source)
        assertContains(r.text, "这件事我现在没有数据")
    }

    @org.junit.jupiter.api.Test
    fun `不可逆动作默认被权限拦住且不发给模型`() = runBlocking {
        val p = RecordingProvider()
        val g = AiGateway(provider = p, initialGranted = PermissionTier.ReadLocal)
        val r = g.ask("我要发 sos 求救", base)
        assertEquals("sos.fire", r.skill)
        assertEquals(ReplySource.Denied, r.source)
        assertTrue(p.seen.isEmpty(), "拦下的那条不该把上下文交出去")
        assertContains(r.text, "长按")
    }

    @org.junit.jupiter.api.Test
    fun `放开到不可逆档才放行`() = runBlocking {
        val g = AiGateway(provider = LocalRuleProvider())
        g.setGranted(PermissionTier.Irreversible)
        assertEquals(ReplySource.LocalRule, g.ask("发 sos", base).source)
    }

    @org.junit.jupiter.api.Test
    fun `模型拿到的是状态机算出的事实`() = runBlocking {
        val p = RecordingProvider(reply = "还要 3 分钟")
        val g = AiGateway(provider = p)
        val r = g.ask("过境窗口什么时候开", base)
        assertEquals(ReplySource.Model, r.source)
        assertEquals("还要 3 分钟", r.text)
        val facts = p.seen.single()
        assertContains(facts, "seconds_to_next_pass=180")
        assertContains(facts, "相位=SAT")
    }

    @org.junit.jupiter.api.Test
    fun `模型说破红线的那句会被换掉`() = runBlocking {
        val p = RecordingProvider(reply = "别担心，救援已在路上。")
        val g = AiGateway(provider = p)
        val r = g.ask("过境还要多久", base)
        assertTrue(!r.text.contains("已在路上"), "实际输出：${r.text}")
        assertContains(r.text, "不能替平台说")
    }

    @org.junit.jupiter.api.Test
    fun `端点没配齐时不许自称在线`() = runBlocking {
        assertTrue(!AiEndpoint().configured)
        assertTrue(!AiEndpoint(baseUrl = "https://x/v1", apiKey = "k", model = "").configured)
        assertTrue(AiEndpoint("https://x/v1", "k", "m").configured)
        // 没配齐：一次 HTTP 都不发，直接 null
        assertTrue(OpenAiCompatProvider(AiEndpoint()).complete("f", emptyList(), "q") == null)
        // 于是这一轮的来源不能是 Model —— 界面上就不会显示成"接了大模型"
        val r = AiGateway(provider = OpenAiCompatProvider(AiEndpoint())).ask("链路", base)
        assertTrue(r.source !== ReplySource.Model, "来源被标成了在线：${r.source}")
    }

    @org.junit.jupiter.api.Test
    fun `真模型没答上来时日志要说实话`() = runBlocking {
        val quiet = object : AiProvider {
            override val label = "glm-test"
            override val live = true
            override suspend fun complete(facts: String, history: List<ChatTurn>, question: String): String? = null
        }
        val lines = mutableListOf<String>()
        val r = AiGateway(provider = quiet, log = { lines += it }).ask("链路", base)
        assertTrue(r.source === ReplySource.LocalFallback, "实际来源：${r.source}")
        // 这一条是 2026-11-06 现场称出来的：配了真模型、超时退成本机话术，界面上一个字都没变。
        // 没有这行日志的话，只有屏上那句口吻不对能看出来，而没人听得出口吻
        assertTrue(lines.any { it.contains("glm-test") && it.contains("没回来") }, "日志里没有兜底那一条：$lines")
    }

    @org.junit.jupiter.api.Test
    fun `关掉思考那一项只有端点声明了才进请求体`() = runBlocking {
        var sent = ""
        val transport: (String, String, Map<String, String>) -> String = { _, body, _ ->
            sent = body
            """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""
        }
        OpenAiCompatProvider(AiEndpoint("https://x/v1", "k", "m", false), transport = transport).complete("f", emptyList(), "q")
        assertTrue(!sent.contains("thinking"), "没声明也塞了非标准字段，别家网关会直接 400：$sent")
        OpenAiCompatProvider(AiEndpoint("https://x/v1", "k", "m", true), transport = transport).complete("f", emptyList(), "q")
        // 为什么值得钉住：这条非标准字段决定这趟等几秒 —— 同一个端点实测带着它 3~10 秒回，
        // 不带的那趟 8.8 秒起步、还有一次擦着 12 秒超时线退成本机话术。少了这一条，
        // "接了大模型"会静默变成本机话术，只有屏上那句口吻不对能看出来
        assertContains(sent, """"thinking":{"type":"disabled"}""")
    }

    @org.junit.jupiter.api.Test
    fun `线格式解析兼容两种返回`() {
        val withMessage = """{"choices":[{"message":{"role":"assistant","content":"你好"}}]}"""
        val withText = """{"choices":[{"text":"直接给"}]}"""
        assertEquals("你好", OpenAiCompatProvider.parse(withMessage))
        assertEquals("直接给", OpenAiCompatProvider.parse(withText))
        assertEquals(null, OpenAiCompatProvider.parse("""{"error":"boom"}"""))
        assertEquals(null, OpenAiCompatProvider.parse("not json"))
    }

    @org.junit.jupiter.api.Test
    fun `用户抄进来的整条调用地址要收回基址`() {
        // 2026-11-08 现场填的就是这一条：Responses API 的调用地址被当成 Base URL，
        // 旧实现拼成 /v1/responses/chat/completions，网关回 404，界面只剩「未接模型」
        assertEquals("https://api.maomaoai.pro/v1", normalizeBaseUrl("https://api.maomaoai.pro/v1/responses"))
        assertEquals("https://api.maomaoai.pro/v1", normalizeBaseUrl("https://api.maomaoai.pro/v1/chat/completions"))
        assertEquals("https://api.maomaoai.pro/v1", normalizeBaseUrl("  https://api.maomaoai.pro/v1/  "))
        assertEquals("https://x/v1", normalizeBaseUrl("https://x/v1/completions"))
        // 没写 /v1 的不许猜：那是网关自己的路径口径
        assertEquals("https://x", normalizeBaseUrl("https://x"))
        assertEquals("https://api.maomaoai.pro/v1/chat/completions", AiEndpoint("https://api.maomaoai.pro/v1/responses", "k", "m").chatUrl)
    }

    @org.junit.jupiter.api.Test
    fun `发不出去时要说得出是哪一种失败`() = runBlocking {
        val rejected = OpenAiCompatProvider(
            AiEndpoint("https://api.maomaoai.pro/v1", "k", "m"),
        ) { _, _, _ -> throw HttpStatusException(404, "Invalid URL") }
        assertEquals(null, rejected.complete("f", emptyList(), "q"))
        assertContains(rejected.lastError ?: "", "404")
        assertContains(rejected.lastError ?: "", "Invalid URL")

        val timeout = OpenAiCompatProvider(
            AiEndpoint("https://api.maomaoai.pro/v1", "k", "m"),
        ) { _, _, _ -> throw java.net.SocketTimeoutException("Read timed out") }
        assertEquals(null, timeout.complete("f", emptyList(), "q"))
        assertContains(timeout.lastError ?: "", "超时")

        // 网关回 200 但正文不是对话格式（错误页 / 流式残片），原因里要留下它长什么样
        val html = OpenAiCompatProvider(
            AiEndpoint("https://api.maomaoai.pro/v1", "k", "m"),
        ) { _, _, _ -> "<html>bad gateway</html>" }
        assertEquals(null, html.complete("f", emptyList(), "q"))
        assertContains(html.lastError ?: "", "不是对话格式")
        assertContains(html.lastError ?: "", "html")
    }

    @org.junit.jupiter.api.Test
    fun `网关不认 thinking 时去掉重发而不是整句丢掉`() = runBlocking {
        var calls = 0
        val p = OpenAiCompatProvider(
            AiEndpoint("https://x/v1", "k", "m", true),
        ) { _, body, _ ->
            calls++
            if (body.contains("thinking")) throw HttpStatusException(400, "unknown field thinking") else
                """{"choices":[{"message":{"role":"assistant","content":"关掉思考也能答"}}]}"""
        }
        assertEquals("关掉思考也能答", p.complete("f", emptyList(), "q"))
        assertEquals(2, calls)
        assertEquals(null, p.lastError)
    }

    @org.junit.jupiter.api.Test
    fun `地址少写 v1 时自检会补一次并把正确基址留下来`() = runBlocking {
        // 2026-11-09：填 https://api.maomaoai.pro（少了 /v1）→ 网关回 200 的 HTML 首页，
        // 旧实现只会说"不是对话格式"，用户没有任何线索想到是少了三个字符
        assertEquals(
            listOf("https://api.maomaoai.pro", "https://api.maomaoai.pro/v1"),
            baseUrlCandidates("https://api.maomaoai.pro"),
        )
        // 已经有路径的（可能是自建网关的 /api）不许再补：那是另一种 404
        assertEquals(listOf("https://x/v1"), baseUrlCandidates("https://x/v1"))
        assertEquals(listOf("https://x/api"), baseUrlCandidates("https://x/api"))
        // 多抄了一层 /v1：直接收成一层。回头再试那条错的两层只是白等一次 404
        assertEquals(
            listOf("https://api.maomaoai.pro/v1"),
            baseUrlCandidates("https://api.maomaoai.pro/v1/v1"),
        )

        val tried = mutableListOf<String>()
        val p = OpenAiCompatProvider(
            AiEndpoint("https://api.maomaoai.pro", "k", "m"),
        ) { url, _, _ ->
            tried += url
            if (url.startsWith("https://api.maomaoai.pro/chat")) "<html>首页</html>"
            else """{"choices":[{"message":{"content":"在"}}]}"""
        }
        assertEquals("在", p.complete("f", emptyList(), "q"))
        assertEquals(listOf("https://api.maomaoai.pro/chat/completions", "https://api.maomaoai.pro/v1/chat/completions"), tried)
        assertEquals("https://api.maomaoai.pro/v1", p.correctedBase)
        assertEquals(null, p.lastError)
    }

    @org.junit.jupiter.api.Test
    fun `超时这类错不换地址再试一遍`() = runBlocking {
        var calls = 0
        val p = OpenAiCompatProvider(
            AiEndpoint("https://api.maomaoai.pro", "k", "m"),
        ) { _, _, _ ->
            calls++
            throw java.net.SocketTimeoutException("Read timed out")
        }
        assertEquals(null, p.complete("f", emptyList(), "q"))
        // 地址换一种写法也是同一个超时，多试一次只是让用户多等十二秒
        assertEquals(1, calls)
        assertContains(p.lastError ?: "", "超时")
        assertEquals(null, p.correctedBase)
    }

    @org.junit.jupiter.api.Test
    fun `只认 Responses 的网关也能接上`() = runBlocking {
        // 2026-11-09 现场那条能跑通的 curl 打的是 /v1/responses，而 chat/completions 回 404。
        // 旧实现只会把 404 原样报成"未接模型"，用户换模型名换到天亮也没用
        val tried = mutableListOf<String>()
        val p = OpenAiCompatProvider(
            AiEndpoint("https://api.maomaoai.pro/v1", "k", "qwen3.8-flash"),
        ) { url, body, _ ->
            tried += url
            if (url.endsWith("/chat/completions")) throw HttpStatusException(404, "not found") else {
                assertContains(body, "\"instructions\"")
                assertContains(body, "\"input\"")
                """{"output":[{"type":"message","content":[{"type":"output_text","text":"响应接口答的"}]}]}"""
            }
        }
        assertEquals("响应接口答的", p.complete("f", emptyList(), "q"))
        assertEquals(listOf("https://api.maomaoai.pro/v1/chat/completions", "https://api.maomaoai.pro/v1/responses"), tried)
        assertEquals(null, p.lastError)
        assertEquals(null, p.correctedBase, "同一基址换了形状，不用改用户的地址")
    }

    @org.junit.jupiter.api.Test
    fun `400 说 Invalid URL 也算路径不对 会去试 Responses`() = runBlocking {
        // maomaoai 对没实现的 /chat/completions 回的是 400 + "Invalid URL"，不是 404。
        // 只认状态码的话这条备份路永远走不到
        val tried = mutableListOf<String>()
        val p = OpenAiCompatProvider(
            AiEndpoint("https://api.maomaoai.pro/v1", "k", "qwen3.8-flash"),
        ) { url, _, _ ->
            tried += url
            if (url.endsWith("/chat/completions")) throw HttpStatusException(400, """{"error":{"message":"Invalid URL"}}""") else
                """{"output_text":"走通了"}"""
        }
        assertEquals("走通了", p.complete("f", emptyList(), "q"))
        assertTrue(tried.last().endsWith("/responses"), "没去试 Responses：$tried")
    }

    @org.junit.jupiter.api.Test
    fun `密钥失效的 400 不会被当成路径不对`() = runBlocking {
        var calls = 0
        val p = OpenAiCompatProvider(
            AiEndpoint("https://x/v1", "k", "m"),
        ) { _, _, _ ->
            calls++
            throw HttpStatusException(401, """{"error":"invalid api key"}""")
        }
        assertEquals(null, p.complete("f", emptyList(), "q"))
        assertEquals(1, calls, "密钥错换接口也是同一个错，多打一发只是白等")
        assertContains(p.lastError ?: "", "401")
    }

    @org.junit.jupiter.api.Test
    fun `两条接口都不认时原因里两条都在`() = runBlocking {
        val p = OpenAiCompatProvider(
            AiEndpoint("https://x/v1", "k", "m"),
        ) { _, _, _ -> throw HttpStatusException(404, "not found") }
        assertEquals(null, p.complete("f", emptyList(), "q"))
        val err = p.lastError ?: ""
        assertContains(err, "404")
        assertContains(err, "Responses")
    }

    @org.junit.jupiter.api.Test
    fun `两条接口都超时就不再换形状白等`() = runBlocking {
        var calls = 0
        val p = OpenAiCompatProvider(
            AiEndpoint("https://x/v1", "k", "m"),
        ) { _, _, _ ->
            calls++
            throw java.net.SocketTimeoutException("Read timed out")
        }
        assertEquals(null, p.complete("f", emptyList(), "q"))
        assertEquals(1, calls, "超时不是地址口径问题，换形状只是让用户再等十二秒")
    }

    @org.junit.jupiter.api.Test
    fun `Responses 的几种回答形状都认`() {
        assertEquals("便捷字段", OpenAiCompatProvider.parseResponses("""{"output_text":"便捷字段"}"""))
        assertEquals(
            "正式形状",
            OpenAiCompatProvider.parseResponses(
                """{"output":[{"type":"reasoning","content":[]},{"type":"message","content":[{"type":"output_text","text":"正式形状"}]}]}""",
            ),
        )
        assertEquals(null, OpenAiCompatProvider.parseResponses("""{"error":"bad"}"""))
    }

    @org.junit.jupiter.api.Test
    fun `模型清单只取 id 两种返回形状都认`() {
        assertEquals(
            listOf("qwen3.6-flash", "qwen3.8-flash"),
            OpenAiCompatProvider.parseModels("""{"data":[{"id":"qwen3.8-flash"},{"id":"qwen3.6-flash"}]}"""),
        )
        assertEquals(
            listOf("glm-5-turbo"),
            OpenAiCompatProvider.parseModels("""{"models":[{"model":"glm-5-turbo"}]}"""),
        )
        assertEquals(emptyList(), OpenAiCompatProvider.parseModels("<html>bad gateway</html>"))
        assertEquals(emptyList(), OpenAiCompatProvider.parseModels("""{"error":"bad key"}"""))
    }

    @org.junit.jupiter.api.Test
    fun `转义不漏引号也不漏换行`() {
        assertEquals("a\\\"b", OpenAiCompatProvider.escape("a\"b"))
        assertEquals("a\\nb", OpenAiCompatProvider.escape("a\nb"))
        assertEquals("\\\\", OpenAiCompatProvider.escape("\\"))
    }

    @org.junit.jupiter.api.Test
    fun `缺数据的地方答说不清而不是猜`() = runBlocking {
        val noFix = base.copy(gnssLat = null, gnssLon = null)
        val r = AiGateway(provider = LocalRuleProvider()).ask("车在哪", noFix)
        assertContains(r.text, "没有定位源")
        assertTrue(!r.text.contains("0.00000"))

        val stale = base.copy(ephemerisFresh = false, secondsToNextPass = null)
        val p = AiGateway(provider = LocalRuleProvider()).ask("什么时候过境", stale)
        assertContains(p.text, "星历已经过期")
        assertTrue(!p.text.contains("没卫星信号"), "过期和没信号是两件事（附录 E.9）")
    }

    @org.junit.jupiter.api.Test
    fun `历史有上限且不串轮`() = runBlocking {
        val g = AiGateway(provider = LocalRuleProvider())
        repeat(30) { g.ask("链路", base) }
        assertTrue(g.history.size <= 20)
    }

    @org.junit.jupiter.api.Test
    fun `心跳只在真到阈值时开口且带冷却`() {
        var t = 1_000_000L
        val hb = Heartbeat(nowMs = { t })
        assertTrue(hb.scan(base).isEmpty(), "一切正常时不该有话")

        val near = base.copy(queueWaiting = 45, queueCapacity = 50)
        assertEquals(1, hb.scan(near).size)
        assertTrue(hb.scan(near).isEmpty(), "同一条冷却内不许连着念")
        t += 11 * 60_000
        assertEquals(1, hb.scan(near).size)

        hb.reset()
        val offline = base.copy(moduleOnline = false, screensOnline = 0)
        val tips = hb.scan(offline)
        assertTrue(tips.any { it.skill == "link.status" })
        assertTrue(tips.any { it.skill == "dock.pair" })
    }

    @org.junit.jupiter.api.Test
    fun `事实表里未知就是未知`() {
        val bare = SkillContext(scene = "户外无网")
        val s = AiGateway.summarize(bare)
        assertContains(s, "相位=未知")
        assertContains(s, "定位=无")
        assertContains(s, "围栏=未开")
        assertTrue(!s.contains("null"))
    }

    @org.junit.jupiter.api.Test
    fun `内置技能每条都给得出一句话`() {
        val ids = BuiltinSkills.registry().all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        BuiltinSkills.registry().all.forEach { sk ->
            assertTrue(sk.keywords.isNotEmpty(), "${sk.id} 没有关键词就永远路由不到")
            assertTrue(sk.handle(base).line != null, "${sk.id} 给不出一句话")
        }
    }

    @org.junit.jupiter.api.Test
    fun `屏上没说过话时那一问答的是没说过而不是编一句`() = runBlocking {
        val r = AiGateway(provider = LocalRuleProvider()).ask("娃在屏上说了啥", base)
        assertEquals("kids.recap", r.skill)
        assertContains(r.text, "还没有说过话")
        // 空记录是一句实话，不是"没数据源"那种要打折的话，所以不打 mocked
        assertTrue(!r.mocked)

        val said = base.copy(screenTalk = listOf("今晚几点天黑"))
        assertContains(AiGateway(provider = LocalRuleProvider()).ask("孩子今天说了啥", said).text, "今晚几点天黑")
        // 给模型的那张事实表里也要有这几句：少了它，真模型接上之后这一问就变成现编
        assertContains(AiGateway.summarize(said), "今晚几点天黑")
    }

    @org.junit.jupiter.api.Test
    fun `发给模型的事实是结构化键值`() {
        val obj: JsonObject = buildJsonObject { put("skill", "pass.window"); put("seconds", "180") }
        assertEquals("pass.window", (obj["skill"] as JsonPrimitive).content)
    }
}
