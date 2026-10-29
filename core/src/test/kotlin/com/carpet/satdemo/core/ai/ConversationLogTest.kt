package com.carpet.satdemo.core.ai

import com.carpet.satdemo.core.link.SosCopy
import com.carpet.satdemo.core.protocol.DialogDir
import com.carpet.satdemo.core.protocol.DialogOrigin
import com.carpet.satdemo.core.protocol.DialogPayload
import com.carpet.satdemo.core.protocol.DialogSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 对话记录本体。**这一块是"手机是单一事实源"这句话唯一能被测出来的地方**：
 * 圆屏说的话、手机说的话、小智云代说的话，最后都必须落到同一份 list 里，
 * 而且落进去的文本要已经过文案闸。哪一条不成立，界面上那句"我在手机上能看到圆屏的记录"就是假的。
 */
class ConversationLogTest {

    private fun up(text: String, id: String = "t-1", source: DialogSource = DialogSource.asr) = DialogPayload(
        turnId = id, dir = DialogDir.up, text = text, source = source, origin = DialogOrigin.screen,
    )

    @Test
    fun `圆屏说的一句话落到记录里并且带着来源`() {
        val log = ConversationLog()
        val turn = log.record(up("车到哪了"))
        assertNotNull(turn)
        assertEquals(TurnFrom.Me, turn.from)
        // 来源必须是 screen：车主在手机上要能看出"这话是有人在车那边问的"
        assertEquals(DialogOrigin.screen, turn.via)
        assertEquals(1, log.size)
    }

    @Test
    fun `同一个 turn_id 的同一角色说第二遍不重复入账`() {
        // 圆屏那边是触摸抖动会重发、链路重连也会重发。不去重的表现是
        // "问一句出两条一样的回答"，而这在演示现场看起来像 AI 抽风
        val log = ConversationLog()
        assertNotNull(log.record(up("车到哪了", id = "t-9")))
        assertNull(log.record(up("车到哪了", id = "t-9")))
        assertEquals(1, log.size)

        // 但同一轮的**下行**必须记得下：一轮问答在记录里就是两条
        assertNotNull(
            log.record(
                DialogPayload(turnId = "t-9", dir = DialogDir.down, text = "车在西湖景区", source = DialogSource.local_rule, origin = DialogOrigin.screen),
            ),
        )
        assertEquals(2, log.size)
    }

    @Test
    fun `入站的话先过文案闸再入账`() {
        // 小智云那一路不是我们生成的，可它一样会上屏、一样会永久留在车主记录里。
        // 拦在入账这一刻，界面再怎么渲染都拿不到越界措辞
        var replaced = 0
        val log = ConversationLog(guard = { text ->
            if (SosCopy.BANNED.any { text.contains(it) }) { replaced++; "这句话我不能替平台说 —— 我只能确认卫星这一侧的状态。" } else text
        })
        val turn = log.record(up("救援已在路上", source = DialogSource.xiaozhi_cloud))
        assertNotNull(turn)
        assertFalse(turn.text.contains("救援已在路上"))
        assertEquals(1, replaced)
        // 而且这条在记录里必须被认成"不是我们说的"
        assertFalse(turn.ours)
        assertEquals(ReplySource.XiaozhiCloud, turn.source)
    }

    @Test
    fun `挤出去的那几轮放掉去重键所以旧 id 能被重新使用`() {
        // cap 到上限后如果 seen 不清，record 会把"第 0 轮"当成重复丢掉。
        // 真机上表现是：聊久了以后某一句突然装不进去，而屏上明明显示说了
        val log = ConversationLog(cap = 3)
        for (i in 0 until 5) assertNotNull(log.record(up("第 $i 句", id = "t-$i")))
        assertEquals(3, log.size)
        assertTrue(log.turns.value.map { it.text } == listOf("第 2 句", "第 3 句", "第 4 句"))
        assertNotNull(log.record(up("重新用最早的 id", id = "t-0")))
        assertEquals(3, log.size)
    }

    @Test
    fun `镜像回圆屏时方向和来源各归各位`() {
        val log = ConversationLog()
        val asked = assertNotNull(log.record(up("还剩几条额度", id = "t-3")))
        // 我说的话回屏上要标 down（屏该显示它），origin 仍是 screen（谁先起的头）
        val echo = log.toDialog(asked, DialogDir.down)
        assertEquals(DialogDir.down, echo.dir)
        assertEquals(DialogOrigin.screen, echo.origin)
        assertEquals("t-3", echo.turnId)

        val answer = assertNotNull(
            log.append(Turn(from = TurnFrom.Ai, text = "还剩 12 条", source = ReplySource.LocalRule, turnId = "t-3", via = DialogOrigin.phone))
        )
        val down = log.toDialog(answer, DialogDir.down)
        assertEquals(DialogSource.local_rule, down.source)
        assertEquals(DialogOrigin.phone, down.origin)
    }

    @Test
    fun `两个方向的枚举来回换算不许把小智云洗成我们的规则`() {
        // source 一路要跨两个世界（契约上是 DialogSource，网关里是 ReplySource）。
        // 只要有一步图省事写成 else -> LocalRule，界面上那个「未过网关」的标就永远不亮
        for (s in listOf(DialogSource.model, DialogSource.local_fallback, DialogSource.denied, DialogSource.xiaozhi_cloud)) {
            assertEquals(s, s.toReply().toDialog(), "$s 来回换算后变了")
        }
        for (r in listOf(ReplySource.Model, ReplySource.LocalFallback, ReplySource.Denied, ReplySource.XiaozhiCloud)) {
            assertEquals(r, r.toDialog().toReply(), "$r 来回换算后变了")
        }
    }
}
