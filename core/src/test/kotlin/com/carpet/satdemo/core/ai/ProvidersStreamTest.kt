package com.carpet.satdemo.core.ai

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §5.1-1 流式那一趟的形状：请求带 stream、SSE 逐块按句交、首句没出去之前随时能退老路。
 * transportStream 全程是假流 —— 单测不许真出网（同 [OpenAiCompatProvider] 的注入约定）。
 */
class ProvidersStreamTest {

    private val ep = AiEndpoint("https://gw.example/v1", "k", "m")

    private fun chunk(content: String?): String =
        "data: {\"choices\":[{\"delta\":{\"content\":${content?.let { "\"$it\"" } ?: "null"}}}]}"

    @Test
    fun `SSE 增量按句交 顺序不乱 返回全文`() {
        val lines = listOf(
            """data: {"choices":[{"delta":{"role":"assistant"}}]}""",
            "",
            chunk("今天天气"),
            chunk("不错。建议"),
            chunk("出行！"),
            "data: [DONE]",
            chunk("DONE 之后的块不算"),
        )
        val seen = mutableListOf<String>()
        var sentBody = ""
        val p = OpenAiCompatProvider(
            ep,
            transportStream = { _, body, _, onLine ->
                sentBody = body
                lines.forEach(onLine)
            },
        )
        val out = runBlocking { p.completeStream("事实", emptyList(), "天气怎么样", onSentence = { seen += it }) }
        assertEquals("今天天气不错。建议出行！", out)
        // 跨块的句子要拼齐了才交，句号归前一句
        assertEquals(listOf("今天天气不错。", "建议出行！"), seen)
        assertTrue(sentBody.contains("\"stream\":true"))
    }

    @Test
    fun `content 为 null 的首块不念出 null 两个字`() {
        val seen = mutableListOf<String>()
        val p = OpenAiCompatProvider(
            ep,
            transportStream = { _, _, _, onLine ->
                onLine(chunk(null))
                onLine(chunk("就一句"))
                onLine("data: [DONE]")
            },
        )
        val out = runBlocking { p.completeStream("f", emptyList(), "q", onSentence = { seen += it }) }
        assertEquals("就一句", out)
        // 没有标点的尾段在 DONE 时也要交出去，不能只藏在返回值里
        assertEquals(listOf("就一句"), seen)
    }

    @Test
    fun `网关不理 stream 回的整段 JSON 直接认 不再重打一遭`() {
        var normalCalled = false
        val p = OpenAiCompatProvider(
            ep,
            transport = { _, _, _ -> normalCalled = true; "{}" },
            transportStream = { _, _, _, _ ->
                throw NotStreamResponse("""{"choices":[{"message":{"content":"整段答。"}}]}""")
            },
        )
        val seen = mutableListOf<String>()
        val out = runBlocking { p.completeStream("f", emptyList(), "q", onSentence = { seen += it }) }
        assertEquals("整段答。", out)
        assertEquals(listOf("整段答。"), seen)
        assertFalse(normalCalled)
    }

    @Test
    fun `首句还没出去就断 整条退回非流式老路`() {
        var normalCalled = false
        val p = OpenAiCompatProvider(
            ep,
            transport = { _, _, _ -> normalCalled = true; """{"choices":[{"message":{"content":"老路答上了。"}}]}""" },
            transportStream = { _, _, _, _ -> throw java.net.SocketTimeoutException("连接已断") },
        )
        val seen = mutableListOf<String>()
        val out = runBlocking { p.completeStream("f", emptyList(), "q", onSentence = { seen += it }) }
        assertEquals("老路答上了。", out)
        assertTrue(normalCalled)
        // 老路那趟没有流式回调，半句都不会提前交
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `前半句已经开口就断 交回积累的前半截 不许重打整段`() {
        var normalCalled = false
        val p = OpenAiCompatProvider(
            ep,
            transport = { _, _, _ -> normalCalled = true; "不该再来" },
            transportStream = { _, _, _, onLine ->
                onLine(chunk("第一句到了。"))
                throw java.net.SocketTimeoutException("这之后没有下一包")
            },
        )
        val seen = mutableListOf<String>()
        val out = runBlocking { p.completeStream("f", emptyList(), "q", onSentence = { seen += it }) }
        assertEquals(listOf("第一句到了。"), seen)
        assertEquals("第一句到了。", out)
        assertFalse(normalCalled)
    }

    @Test
    fun `每句过闸 踩线那句在交给宿主之前就换掉`() {
        val stub = object : AiProvider {
            override val label = "踩线流式假源"
            override val live = true
            override suspend fun complete(f: String, h: List<ChatTurn>, q: String, p: Persona) = null
            override suspend fun completeStream(
                f: String, h: List<ChatTurn>, q: String, p: Persona, onSentence: (String) -> Unit,
            ): String? {
                onSentence("收到。")
                onSentence("救援已在路上。")
                return "收到。救援已在路上。"
            }
        }
        val seen = mutableListOf<String>()
        val gw = AiGateway(provider = stub)
        runBlocking { gw.ask("嗯嗯随便聊聊", SkillContext(scene = "车"), onSentence = { seen += it }) }
        assertEquals(
            listOf("收到。", "这句话我不能替平台说 —— 我只能确认卫星这一侧的状态。"),
            seen,
        )
    }
}
