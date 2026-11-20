package com.carpet.satdemo.core.ai

import com.carpet.satdemo.core.protocol.DialogDir
import com.carpet.satdemo.core.protocol.DialogOrigin
import com.carpet.satdemo.core.protocol.DialogPayload
import com.carpet.satdemo.core.protocol.DialogSource
import com.carpet.satdemo.core.protocol.MsgType
import com.carpet.satdemo.core.protocol.ScreenMessage
import com.carpet.satdemo.core.protocol.ScreenPage
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContains
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

    private class Harness(
        initialGranted: PermissionTier = PermissionTier.ReadLocal,
        /** 屏报上来的那一页换算出来的手机页。默认 null = 屏还没报过页 */
        screenScene: () -> TurnScene? = { null },
        provider: AiProvider = LocalRuleProvider(),
    ) {
        val pushed = mutableListOf<DialogPayload>()
        val answered = mutableListOf<DialogPayload>()
        val notes = mutableListOf<String>()
        var clock = 1_795_200_000_000L
        val gateway = AiGateway(
            provider = provider,
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
            screenScene = screenScene,
            onAnswer = { answered += it },
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
    fun `屏侧云两档走同一套规矩 Muse 那一路不许漏`() = runBlocking {
        // 圆屏换到 Muse 底座以后，屏本机那条 ASR→云→TTS 成了主要来路。
        // 这一条钉的是"两档云共用一个谓词"：将来再加一档时若只在 xiaozhi 那支加分支，
        // Muse 的话就会既进技能又回镜，等于让别人的模型替我们决定发不发卫星报文
        for (src in listOf(DialogSource.xiaozhi_cloud, DialogSource.muse_cloud)) {
            val h = Harness()
            h.bridge.onScreenFrame(h.frame(up("车到哪了", "s-$src", src)))
            val line = h.bridge.log.turns.value.single()
            assertFalse(line.ours, "$src 该算别人说的")
            assertEquals(src.toReply(), line.source, "$src 的来源档被洗掉了")
            assertTrue(h.pushed.isEmpty(), "$src 不回镜")
            assertTrue(h.gateway.history.isEmpty(), "$src 触发了技能：${h.gateway.history}")
        }
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

    @Test
    fun `养护提醒那句骑的就是对话帧 归类靠 skill 不靠新字段`() {
        // 2026-11-06 拍板：为了这句在屏上加一个新的文本字段不做 —— 句子本来就在 dialog 帧里，
        // 而"这条是养护、不是谁问出来的"归手机（和以后的服务器）按 skill 归类存。
        // 规则引擎在手机版那边（§9 的 M1/M2），这里只钉**载体**：一帧、一个 skill、一份记录。
        val maint = Rule("maint.due", 30 * 60_000) {
            ProactiveTip("maint", "胎压该补到 2.5 bar 了，出门前看一眼。")
        }
        val h = Harness()
        val bridge = DialogBridge(
            gateway = h.gateway,
            facts = { CTX },
            push = { h.pushed += it },
            launch = { body -> runBlocking { body() } },
            note = { h.notes += it },
            nowMs = { h.clock++ },
            heartbeat = Heartbeat(nowMs = { h.clock++ }, rules = listOf(maint)),
        )
        bridge.scanProactive()

        val frame = h.pushed.single()
        assertEquals(DialogDir.down, frame.dir, "养护那句是屏说出来的，不是屏问出去的")
        assertEquals("maint", frame.skill, "归类的那一项没上帧，服务端和手机都分不出它是养护")
        assertEquals("胎压该补到 2.5 bar 了，出门前看一眼。", frame.text)
        val line = bridge.log.turns.value.single()
        assertEquals("maint", line.skill)
        assertTrue(line.proactive, "没人问它就该看得出是主动说的")

        // 分类要真分得开：这条不许冒进"娃在屏上说了啥"那几句里。
        // 那一筛的条件是"屏起头的问句"，而养护是手机主动播报的答句形状 —— 加进来的话
        // 家长问一句"娃说了啥"，答案里多出一句胎压，那种东西在现场没法当场解释
        bridge.onScreenFrame(h.frame(up("今天放学下雨了", "s-30")))
        assertEquals(listOf("今天放学下雨了"), bridge.log.screenTalk())
    }

    @Test
    fun `手机上从哪一页问的就归哪一页 问和答一起归`() = runBlocking {
        val h = Harness()
        h.bridge.ask("车在哪", DialogOrigin.phone, scene = TurnScene.vehicle)
        val lines = h.bridge.log.turns.value
        assertEquals(listOf(TurnScene.vehicle, TurnScene.vehicle), lines.map { it.scene })
        // 另一页看不见这一轮：场景页各看各的那串，首页那条总时间线才全显
        assertEquals(0, lines.filter { it.scene == TurnScene.family }.size)
    }

    @Test
    fun `屏起的那一轮按屏自己报的页归 屏没报页就不归任何一页`() = runBlocking {
        var page: ScreenPage? = null
        val h = Harness(screenScene = { screenSceneOf(page) })
        // 屏还没报页：那句话只属于首页那条总时间线，不许手机替它猜一个"亲子"
        h.bridge.onScreenFrame(h.frame(up("今晚几点天黑", "s-20")))
        assertEquals(listOf<TurnScene?>(null, null), h.bridge.log.turns.value.map { it.scene })

        page = ScreenPage.kids
        h.bridge.onScreenFrame(h.frame(up("给我讲个故事", "s-21")))
        // 归页的是**问的那一刻**屏报上来的那一页，答句跟着走：半途屏切了页不该把这句挪走
        val round = h.bridge.log.turns.value.takeLast(2)
        assertEquals(listOf(TurnScene.family, TurnScene.family), round.map { it.scene })

        page = ScreenPage.clock
        h.bridge.onScreenFrame(h.frame(up("几点了", "s-22")))
        assertEquals(null, h.bridge.log.turns.value.takeLast(2).first().scene, "时钟页在手机上没有对应的那一页")
    }

    /** 记下网关把每一轮判给了谁：人格这一刀看不见摸不着，只能这样钉住 */
    private class PersonaSpy : AiProvider {
        override val label = "spy"
        override val live = true
        val seen = mutableListOf<Persona>()
        override suspend fun complete(
            facts: String,
            history: List<ChatTurn>,
            question: String,
            persona: Persona,
        ): String? {
            seen += persona
            return "spy：$persona"
        }
    }

    @Test
    fun `屏起头没归页的那一轮用助手人格 手机上没归页的照旧是守护`() = runBlocking {
        // 屏常停在中心脸页，换算出来没归页。归页照旧是 null（上一条钉住），但**人格**不能掉进
        // Persona.of(null)=Guardian：模型手里整张表全是卫星，问月亮都答过顶
        // （owner 2026-11-16「答话全绕卫星」）。手机首页/主动播报没有设备可推断，照旧守护
        val spy = PersonaSpy()
        val h = Harness(provider = spy)
        h.bridge.onScreenFrame(h.frame(up("给我讲讲月亮", "s-40")))
        h.bridge.ask("心情不太好", DialogOrigin.phone)
        assertEquals(listOf(Persona.Assistant, Persona.Guardian), spy.seen)
        // 归页那格没被人格带着改：答句记录仍旧不归任何一页
        assertEquals(listOf<TurnScene?>(null, null, null, null), h.bridge.log.turns.value.map { it.scene })

        val kids = PersonaSpy()
        val hk = Harness(provider = kids, screenScene = { screenSceneOf(ScreenPage.kids) })
        hk.bridge.onScreenFrame(hk.frame(up("给我讲个故事", "s-41")))
        // 归了页的照页面走人格：亲子页不许被"屏起头=助手"这条盖掉
        assertEquals(listOf(Persona.Family), kids.seen)
    }

    @Test
    fun `屏上说的话归进家人页以后能当场问回来`() = runBlocking {
        // 这一条是 scene 那一刀的全部理由：车主在家人页问"娃在屏上说了啥"，
        // 答案只能来自那份真实记录，而不是界面现编的一句安慰
        var page: ScreenPage? = ScreenPage.kids
        val h = Harness(screenScene = { screenSceneOf(page) })
        h.bridge.onScreenFrame(h.frame(up("今晚几点天黑", "s-23")))
        page = null            // 车主在手机上问，屏报没报页都不影响这一问归哪页
        h.bridge.ask("娃在屏上说了啥", DialogOrigin.phone, scene = TurnScene.family)

        val answer = h.bridge.log.turns.value.last()
        assertEquals(TurnScene.family, answer.scene)
        assertEquals("kids.recap", answer.skill)
        assertContains(answer.text, "今晚几点天黑")
    }

    @Test
    fun `屏自己问的那一句不会被当成屏上说的话念回去`() = runBlocking {
        // 事实表里那几句是**答之前**从记录里取的，正在答的这一轮已经入账了。
        // 不排掉的话回答是"屏上最近有 2 句：「娃在屏上说了啥」…"—— 把自己的问话当成了别人的话
        val h = Harness(screenScene = { TurnScene.family })
        h.bridge.onScreenFrame(h.frame(up("今天放学下雨了", "s-24")))
        h.bridge.onScreenFrame(h.frame(up("娃在屏上说了啥", "s-25")))
        val answer = h.bridge.log.turns.value.last()
        assertEquals("kids.recap", answer.skill)
        assertContains(answer.text, "今天放学下雨了")
        assertFalse(answer.text.contains("娃在屏上说了啥"), answer.text)
        assertContains(answer.text, "有 1 句")
    }

    @Test
    fun `主动播报和忙时那句不占模型历史`() = runBlocking {
        // 心跳最短 60 秒一条，发给模型只取最后 6 条。主动提醒要是也记进去，
        // 连着几句「围栏越界」就把车主真问过的那几轮挤出窗口，下一轮的模型前面没有来由
        val h = Harness()
        val full = SkillContext(scene = "车", queueWaiting = 49, queueCapacity = 50, fenceActive = true, fenceInside = false)
        val bridge = DialogBridge(
            gateway = h.gateway,
            facts = { full },
            push = { _ -> },
            launch = { body -> runBlocking { body() } },
            nowMs = { h.clock++ },
        )
        bridge.scanProactive()
        assertTrue(h.gateway.history.isEmpty(), "主动播报进了模型历史：${h.gateway.history}")

        // 真问一轮：历史里只该多出一问一答这两条，主动播报绝不混进来
        bridge.ask("链路", DialogOrigin.phone)
        assertEquals(2, h.gateway.history.size, "${h.gateway.history}")
        assertEquals(listOf("user", "assistant"), h.gateway.history.map { it.role })
    }

    @Test
    fun `屏上的失败说明要上屏但不出声`() = runBlocking {
        // 语音没配 key / 转写没连上：只有手机知道，只丢进日志的话站在屏前的人看到的是
        // "我说了话，屏没反应"。但这件事没有一句"回答"可念 —— 出声会把事故演成对话
        val h = Harness()
        h.bridge.notice("语音输入没配 key", "m-42")
        val line = h.bridge.log.turns.value.single()
        assertEquals("语音输入没配 key", line.text)
        assertEquals(DialogOrigin.screen, line.via)
        assertEquals(TurnFrom.Ai, line.from)
        assertEquals("m-42", line.turnId)
        assertEquals(1, h.pushed.size)
        assertEquals("m-42", h.pushed[0].turnId)
        assertTrue(h.answered.isEmpty(), "失败说明不该合成语音：${h.answered}")
    }

    @Test
    fun `忙时那句仍然要入账和上屏`() = runBlocking {
        // 不进模型历史（上一条），但对用户来说它还是一句话：屏上要看得见，
        // 不然车主按第二次时屏幕一动不动，只能再按第三次
        val slow = object : AiProvider {
            override val label = "slow"
            override val live = true
            override suspend fun complete(
                facts: String,
                history: List<ChatTurn>,
                question: String,
                persona: Persona,
            ): String {
                kotlinx.coroutines.delay(50)
                return "答一"
            }
        }
        val h = Harness(provider = slow)
        // 同一个 runBlocking 线程：第一条挂在 provider 里，第二条才看得到 _thinking=true，
        // 也才不违反 ConversationLog 的"只从一个线程动"
        val first = launch { h.bridge.ask("车在哪", DialogOrigin.phone) }
        yield()
        h.bridge.ask("额度", DialogOrigin.phone)
        first.join()
        assertTrue(h.bridge.log.turns.value.any { it.text.contains("我在想上一条") }, h.texts().toString())
        assertTrue(h.pushed.any { it.text.contains("我在想上一条") })
        // 忙时那一句也没进模型历史：下一轮发给模型的两条仍然只是第一轮的问答
        assertEquals(listOf("user", "assistant"), h.gateway.history.map { it.role })
    }
}
