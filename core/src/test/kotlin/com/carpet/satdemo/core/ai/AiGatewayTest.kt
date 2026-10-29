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
    fun `没命中时不许编答案`() = runBlocking {
        val r = AiGateway(provider = LocalRuleProvider()).ask("帮我写一首诗", base)
        assertEquals(ReplySource.LocalFallback, r.source)
        assertContains(r.text, "没有对应的数据口径")
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
    fun `线格式解析兼容两种返回`() {
        val withMessage = """{"choices":[{"message":{"role":"assistant","content":"你好"}}]}"""
        val withText = """{"choices":[{"text":"直接给"}]}"""
        assertEquals("你好", OpenAiCompatProvider.parse(withMessage))
        assertEquals("直接给", OpenAiCompatProvider.parse(withText))
        assertEquals(null, OpenAiCompatProvider.parse("""{"error":"boom"}"""))
        assertEquals(null, OpenAiCompatProvider.parse("not json"))
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
    fun `发给模型的事实是结构化键值`() {
        val obj: JsonObject = buildJsonObject { put("skill", "pass.window"); put("seconds", "180") }
        assertEquals("pass.window", (obj["skill"] as JsonPrimitive).content)
    }
}
