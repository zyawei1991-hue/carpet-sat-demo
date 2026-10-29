package com.carpet.satdemo.core.ai

import com.carpet.satdemo.core.protocol.DialogDir
import com.carpet.satdemo.core.protocol.DialogOrigin
import com.carpet.satdemo.core.protocol.DialogPayload
import com.carpet.satdemo.core.protocol.DialogSource
import com.carpet.satdemo.core.protocol.MsgType
import com.carpet.satdemo.core.protocol.ScreenMessage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 一份"链路正常、队列快满"的事实表。文件级而不是成员：Harness 是嵌套类，看不见外层实例字段 */
private val CTX = SkillContext(scene = "车", phase = "locked", secondsToNextPass = 120, queueWaiting = 3, queueCapacity = 50)

/**
 * 对话接线本体的行为。**这是"手机是单一事实源、圆屏是耳朵和嘴"这句话唯一能被机器验收的地方**：
 * 每一句都要问 —— 它进没进那条时间线、它回没回屏、它是谁起的头。
 */
class DialogBridgeTest {

    private class Harness(initialGranted: PermissionTier = PermissionTier.ReadLocal) {
        val pushed = mutableListOf<DialogPayload>()
        val notes = mutableListOf<String>()
        var clock = 1_795_200_000_000L
        val gateway = AiGateway(
            provider = LocalRuleProvider(),
            initialGranted = initialGranted,
            log = { notes += it },
        )
        val bridge = DialogBridge(
            gateway = gateway,
            facts = { CTX },
            push = { pushed += it },
            launch = { body -> runBlocking { body() } },
            note = { notes += it },
            // 每取一次时间往前走 1ms：同一轮里问和答不能撞 turn_id，第二轮的 id 也不能撞上旧轮
            nowMs = { clock++ },
        )
        fun texts() = bridge.log.turns.value.map { it.text }
        fun frame(d: DialogPayload) = ScreenMessage(t = MsgType.dialog, seq = 1, ts = clock, dialog = d)
    }

    private fun up(text: String, id: String, source: DialogSource = DialogSource.asr) =
        DialogPayload(turnId = id, dir = DialogDir.up, text = text, source = source, origin = DialogOrigin.screen)

    @Test
    fun `手机上问的那一轮 问和答都要回镜到圆屏`() = runBlocking {
        val h = Harness()
        h.bridge.ask("链路怎么样", DialogOrigin.phone)
        val lines = h.bridge.log.turns.value
        assertEquals(listOf(TurnFrom.Me, TurnFrom.Ai), lines.map { it.from })
        assertTrue(lines.all { it.via == DialogOrigin.phone })
        // 屏上要同时看见车主问了什么、答了什么：只回答不加问，站在车那边的人读不出这是在答哪句
        assertEquals(listOf(DialogDir.down, DialogDir.down), h.pushed.map { it.dir })
        assertEquals(listOf("链路怎么样", lines[1].text), h.pushed.map { it.text })
        assertEquals(listOf(lines[0].turnId, lines[0].turnId), h.pushed.map { it.turnId })
        assertTrue(h.pushed.all { it.origin == DialogOrigin.phone })
    }

    @Test
    fun `圆屏说的一句话 问不回镜但答要回镜 而且沿用圆屏那个 turn_id`() = runBlocking {
        val h = Harness()
        h.bridge.onScreenFrame(h.frame(up("车在哪", "s-7")))
        val lines = h.bridge.log.turns.value
        assertEquals(listOf(TurnFrom.Me, TurnFrom.Ai), lines.map { it.from })
        assertTrue(lines.all { it.via == DialogOrigin.screen })
        // 圆屏已经把自己听到的那句画过了，再回一条同内容的 down = 同一句问话出现两遍
        assertEquals(1, h.pushed.size, "只该回镜那一句答")
        assertEquals(DialogDir.down, h.pushed[0].dir)
        // 沿用它那个 id：屏那边靠它把答钉回原来那一轮
        assertEquals("s-7", h.pushed[0].turnId)
        assertEquals(lines[1].text, h.pushed[0].text)
    }

    @Test
    fun `同一轮重发第二遍 不重复入账也不答第二遍`() = runBlocking {
        // 真链路上重发是常态：屏那边 TCP 重连、服务端 3 秒补发，都会把同一帧送两次。
        // 不去重的表现是"问一句出两条一样的回答"，在演示现场这句话听起来像 AI 抽风
        val h = Harness()
        h.bridge.onScreenFrame(h.frame(up("额度还剩多少", "s-9")))
        val before = h.bridge.log.size
        val pushedBefore = h.pushed.size
        h.bridge.onScreenFrame(h.frame(up("额度还剩多少", "s-9")))
        assertEquals(before, h.bridge.log.size)
        assertEquals(pushedBefore, h.pushed.size)
        assertEquals(2, before)   // 一轮 = 问 + 答两条
    }

    @Test
    fun `小智云那一路只入账 不触发技能也不答`() = runBlocking {
        // 别人家的模型替我们说完的那句话不是从我们的事实表里来的。
        // 再把它当问题喂一遍网关，等于让别人的输出决定我们要不要发卫星报文
        val h = Harness()
        h.bridge.onScreenFrame(h.frame(up("下次过境是什么时候", "s-1", DialogSource.xiaozhi_cloud)))
        val lines = h.bridge.log.turns.value
        assertEquals(1, lines.size)
        assertEquals(ReplySource.XiaozhiCloud, lines[0].source)
        assertFalse(lines[0].ours)
        assertTrue(h.pushed.isEmpty(), "小智云那句不回镜，屏上已经显示过了")
        assertTrue(h.notes.any { it.contains("不触发技能") }, h.notes.toString())
        // 技能一次都没跑：网关的历史是空的。这里要压住的正是"别人的输出决定我们发不发卫星报文"
        assertTrue(h.gateway.history.isEmpty(), h.gateway.history.toString())
    }

    @Test
    fun `小智云那句越界的话入账前就被换掉了`() {
        // §6.1 那句"红线不只约束我们写的模板，也约束别人的模型"落到代码上就是这里：
        // 记录要陪车主一辈子，所以过闸那一份以手机为准
        val h = Harness()
        h.bridge.onScreenFrame(h.frame(up("救援已在路上", "s-2", DialogSource.xiaozhi_cloud)))
        val line = h.bridge.log.turns.value.single()
        assertFalse(line.text.contains("救援已在路上"))
        assertTrue(line.text.contains("不能替平台说"), line.text)
        assertEquals(ReplySource.XiaozhiCloud, line.source)
        assertTrue(h.notes.any { it.contains("踩线") }, h.notes.toString())
    }

    @Test
    fun `方向为 down 的帧只补记录 不会自己问自己答`() = runBlocking {
        val h = Harness()
        // 真固件重连后可能把自己那一轮回显上来。这一句必须只补记录，
        // 否则屏和手机会互相把对方的话再触发一遍，无限对答
        h.bridge.onScreenFrame(
            ScreenMessage(
                t = MsgType.dialog, seq = 3,
                dialog = DialogPayload(turnId = "s-3", dir = DialogDir.down, text = "车在西湖景区", source = DialogSource.local_rule, origin = DialogOrigin.screen),
            ),
        )
        assertEquals(1, h.bridge.log.size)
        assertTrue(h.pushed.isEmpty())
    }

    @Test
    fun `权限没开的技能被拦下的那一句也照样上屏`() = runBlocking {
        // 拦下也要有回话，而且回话得上屏：屏上按了"求救"却什么都不显示，用户会再按三次
        val h = Harness(PermissionTier.ReadLocal)
        h.bridge.onScreenFrame(h.frame(up("求救", "s-4")))
        val lines = h.bridge.log.turns.value
        assertEquals(2, lines.size)
        assertEquals(ReplySource.Denied, lines[1].source)
        assertEquals(DialogSource.denied, h.pushed.last().source)
        assertEquals("s-4", h.pushed.last().turnId)
    }

    @Test
    fun `主动播报也回镜 并且每轮的 id 不一样`() {
        // 主动提醒有冷却但会再来一轮。复用同一个 turn_id 的话第二轮会被去重丢掉，
        // 表现是"第二次越界提醒屏上听得见、手机里却没记录"
        val h = Harness()
        val full = SkillContext(scene = "车", queueWaiting = 49, queueCapacity = 50, ephemerisFresh = false, moduleOnline = false, fenceActive = true, fenceInside = false)
        val bridge = DialogBridge(
            gateway = h.gateway,
            facts = { full },
            push = { h.pushed += it },
            launch = { body -> runBlocking { body() } },
            note = { h.notes += it },
            nowMs = { h.clock++ },
        )
        bridge.scanProactive()
        val first = bridge.log.turns.value.map { it.turnId }
        assertTrue(first.size >= 2, "几条规则该一起说话：$first")
        // 冷却是真过了一个小时，不是把表清空：清空了就测不出"id 撞车"这个 bug
        h.clock += 60 * 60_000
        bridge.scanProactive()
        val ids = bridge.log.turns.value.map { it.turnId }
        assertTrue(ids.size > first.size, "冷却过了第二轮该再提醒一次：$ids")
        assertEquals(ids.size, ids.toSet().size, "第二轮的 id 撞了旧轮，那些提醒会被当成重复丢掉")
        assertTrue(h.pushed.isNotEmpty())
        assertTrue(h.pushed.all { it.dir == DialogDir.down })
    }
}
