package com.carpet.satdemo.core.ai

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §5.2 两条口径的单测面：**历史按人格分道** + **会话窗口（20 秒内算同一段对话，出窗即新一轮）**。
 * 判的都是"发给模型的那份 history"——哨兵假源把每一轮收到的历史原样留下，比对口径就是模型口径。
 */
class GatewaySessionTest {

    private class Spy : AiProvider {
        override val label = "哨兵假源"
        override val live = true
        val seen = mutableListOf<List<ChatTurn>>()
        private var n = 0
        override suspend fun complete(f: String, h: List<ChatTurn>, q: String, p: Persona): String? = null
        override suspend fun completeStream(
            f: String, h: List<ChatTurn>, q: String, p: Persona, onSentence: (String) -> Unit,
        ): String? {
            seen += h
            return "第${++n}答。"
        }
    }

    private class Rig(sessionWindowMs: Long = 20_000) {
        var clock = 1_795_200_000_000L
        val logs = mutableListOf<String>()
        val spy = Spy()
        val gw = AiGateway(
            provider = spy,
            log = { logs += it },
            nowMs = { clock },
            sessionWindowMs = sessionWindowMs,
        )

        fun ask(q: String, scene: TurnScene? = null) =
            runBlocking { gw.ask(q, SkillContext(scene = "车"), scene) }
    }

    @Test
    fun `亲子态的聊天的 不接在守护态历史后面`() {
        val r = Rig()
        r.ask("今天心情不错", TurnScene.family)
        r.ask("给我讲个故事", TurnScene.assistant)
        // 第二条人格收到的历史必须是空的 —— 串过去的表现就是"助手页接着娃的话题聊"
        assertTrue(r.spy.seen[1].isEmpty(), r.spy.seen[1].toString())
        // 各自道内还是连续的：同一人格第二问要能看见第一问
        r.ask("然后呢", TurnScene.family)
        assertEquals(listOf("user", "assistant"), r.spy.seen[2].map { it.role })
        assertEquals("今天心情不错", r.spy.seen[2][0].content)
    }

    @Test
    fun `窗口内追问带上前文 出窗即新一轮`() {
        val r = Rig()
        r.ask("你好呀")
        r.clock += 5_000
        r.ask("然后呢")
        assertEquals(2, r.spy.seen[1].size, "5 秒后追问，该带上一轮")
        r.clock += 21_000
        r.ask("你好呀")
        assertTrue(r.spy.seen[2].isEmpty(), "过了 20 秒窗，旧话不许陪跑：${r.spy.seen[2]}")
        assertContains(r.logs.joinToString(), "会话窗口过了")
    }

    @Test
    fun `出窗只清过期那条道 别的人格不受牵连`() {
        val r = Rig()
        r.ask("今天心情不错", TurnScene.family)
        r.clock += 21_000
        r.ask("你好呀")            // 守护道是空的，无从过期
        r.clock += 5_000
        // 家族道上一问距"现在"也过了窗（26 秒）——该被清；不是被守护道的动静保着
        r.ask("娃说了啥", TurnScene.family)
        assertTrue(r.spy.seen.last().isEmpty(), r.spy.seen.last().toString())
        assertFalse(r.logs.any { it.contains("守护") }, "过期日志只报清掉的那条道")
    }
}
