package com.carpet.satdemo.core.ai

import com.carpet.satdemo.core.protocol.DialogDir
import com.carpet.satdemo.core.protocol.DialogOrigin
import com.carpet.satdemo.core.protocol.DialogPayload
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §5.1-2 先答后补的形状：模型到点没吭声，技能那句本机话术先开口；
 * 模型后面回来照常补充；模型流式已经吐过一句就绝不插嘴（双嘴红线）；
 * 模型最终没回来时整条只念一遍 —— 重播那句靠 answeredEarly 打标。
 */
/** 桩技能表。文件级：嵌套类看不见外层实例字段（同 DialogBridgeTest 的 CTX） */
private val REG = SkillRegistry(
    listOf(
        Skill(
            id = "s",
            title = "桩技能",
            keywords = listOf("链路"),
            needs = PermissionTier.ReadLocal,
        ) { SkillResult(skillId = "s", intent = "i", fields = emptyMap(), line = "本机：终端在线。") },
    ),
)

class GatewayLocalFirstTest {

    /** delayMs 后才回话的"模型"。answer=null 表示这回答不上来 */
    private class SlowProvider(private val delayMs: Long, private val answer: String?) : AiProvider {
        override val label = "慢半拍假源"
        override val live = true
        override suspend fun complete(f: String, h: List<ChatTurn>, q: String, p: Persona) =
            completeStream(f, h, q, p, {})

        override suspend fun completeStream(
            f: String, h: List<ChatTurn>, q: String, p: Persona, onSentence: (String) -> Unit,
        ): String? {
            delay(delayMs)
            return answer
        }
    }

    @Test
    fun `模型及时回来 本机那句不插嘴`() {
        val early = mutableListOf<AiReply>()
        val gw = AiGateway(skills = REG, provider = SlowProvider(10, "模型答。"), localFirstMs = 300)
        val reply = runBlocking { gw.ask("链路怎么样", SkillContext(), onLocalFirst = { early += it }) }
        assertTrue(early.isEmpty())
        assertEquals(ReplySource.Model, reply.source)
        assertFalse(reply.answeredEarly)
    }

    @Test
    fun `模型磨蹭 本机那句先交 模型回来照常补充`() {
        val early = mutableListOf<AiReply>()
        val gw = AiGateway(skills = REG, provider = SlowProvider(300, "模型补充。"), localFirstMs = 40)
        val reply = runBlocking { gw.ask("链路怎么样", SkillContext(), onLocalFirst = { early += it }) }
        assertEquals(1, early.size)
        assertEquals("本机：终端在线。", early[0].text)
        assertEquals(ReplySource.LocalRule, early[0].source)
        assertEquals("s", early[0].skill)
        // 模型后到的那句才是这一轮的正文，不再带早到标
        assertEquals("模型补充。", reply.text)
        assertEquals(ReplySource.Model, reply.source)
        assertFalse(reply.answeredEarly)
    }

    @Test
    fun `模型到点没回 早答算数 整段是重播不再开口`() {
        val early = mutableListOf<AiReply>()
        val gw = AiGateway(skills = REG, provider = SlowProvider(300, null), localFirstMs = 40)
        val reply = runBlocking { gw.ask("链路怎么样", SkillContext(), onLocalFirst = { early += it }) }
        assertEquals(1, early.size)
        // 文本与早到句相同 —— 宿主靠 answeredEarly 决定"入账但闭嘴"，不然屏上就是两句一样的
        assertEquals(early[0].text, reply.text)
        assertTrue(reply.answeredEarly)
        assertEquals(ReplySource.LocalFallback, reply.source)
    }

    @Test
    fun `模型流式已吐一句 本机那句永远不插嘴`() {
        val chatter = object : AiProvider {
            override val label = "话痨流式假源"
            override val live = true
            override suspend fun complete(f: String, h: List<ChatTurn>, q: String, p: Persona) = null
            override suspend fun completeStream(
                f: String, h: List<ChatTurn>, q: String, p: Persona, onSentence: (String) -> Unit,
            ): String? {
                delay(20)
                onSentence("模型第一句。")
                delay(200)
                return "模型第一句。第二句。"
            }
        }
        val early = mutableListOf<AiReply>()
        val seen = mutableListOf<String>()
        val gw = AiGateway(skills = REG, provider = chatter, localFirstMs = 60)
        val reply = runBlocking {
            gw.ask("链路怎么样", SkillContext(), onSentence = { seen += it }, onLocalFirst = { early += it })
        }
        assertTrue(early.isEmpty(), "首句 20ms 就出了，60ms 的闸不该放本机话术出去")
        assertEquals(listOf("模型第一句。"), seen)
        assertEquals(ReplySource.Model, reply.source)
    }

    @Test
    fun `定时发的是重播不是取消 模型后面那句照样进历史`() {
        val gw = AiGateway(skills = REG, provider = SlowProvider(300, "模型补充。"), localFirstMs = 40)
        runBlocking { gw.ask("链路怎么样", SkillContext(), onLocalFirst = {}) }
        // 定时器不是 withTimeout：在飞的那趟没被掐死，模型的答案要照常进历史
        assertTrue(gw.history.any { it.role == "assistant" && it.content == "模型补充。" })
    }

    // ── 桥侧：早到句必须自成一轮（去重键是 turnId:from，蹭 id 会把整段答案静默吃掉）──

    private class BridgeRig(private val provider: AiProvider) {
        val pushed = mutableListOf<DialogPayload>()
        val answered = mutableListOf<DialogPayload>()
        private var clock = 1_795_200_000_000L
        private val bridge = DialogBridge(
            gateway = AiGateway(skills = REG, provider = provider, localFirstMs = 40),
            facts = { SkillContext(scene = "车") },
            push = { pushed += it },
            launch = { body -> runBlocking { body() } },
            note = {},
            nowMs = { clock++ },
            onAnswer = { answered += it },
        )

        fun ask() = runBlocking { bridge.ask("链路怎么样", DialogOrigin.phone) }
        fun texts() = bridge.log.turns.value.map { it.text }
    }

    @Test
    fun `桥侧 早到句先上屏先开口 模型回来那一轮照常补充`() {
        val r = BridgeRig(SlowProvider(300, "模型补充。"))
        r.ask()
        assertEquals(listOf("链路怎么样", "本机：终端在线。", "模型补充。"), r.texts())
        assertEquals(listOf("链路怎么样", "本机：终端在线。", "模型补充。"), r.pushed.map { it.text })
        assertEquals(listOf(DialogDir.down, DialogDir.down, DialogDir.down), r.pushed.map { it.dir })
        // 早到那帧的 id 和问句/整段的 id 不是一个 —— 但只有一帧蹭了 "-e" 尾巴
        val ids = r.pushed.map { it.turnId }
        assertEquals(ids[0], ids[2])
        assertTrue(ids[1].startsWith(ids[0]) && ids[1] != ids[0])
        // 两次开口：早到句一次、模型补充一次（内容不同，不是双嘴）
        assertEquals(listOf("本机：终端在线。", "模型补充。"), r.answered.map { it.text })
    }

    @Test
    fun `桥侧 模型没回来 整段是重播 入账但不再开口`() {
        val r = BridgeRig(SlowProvider(300, null))
        r.ask()
        // 时间线里两条同文案都入账（早到 + 重播，各自留出处），屏上只出现一次
        assertEquals(listOf("链路怎么样", "本机：终端在线。", "本机：终端在线。"), r.texts())
        assertEquals(listOf("链路怎么样", "本机：终端在线。"), r.pushed.map { it.text })
        // 嘴只开一次：重播那条不许再念
        assertEquals(listOf("本机：终端在线。"), r.answered.map { it.text })
    }
}
