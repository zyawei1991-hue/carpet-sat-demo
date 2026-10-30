package com.carpet.satdemo.core.ai

import com.carpet.satdemo.core.protocol.DialogDir
import com.carpet.satdemo.core.protocol.DialogOrigin
import com.carpet.satdemo.core.protocol.DialogPayload
import com.carpet.satdemo.core.protocol.DialogSource
import com.carpet.satdemo.core.protocol.MsgType
import com.carpet.satdemo.core.protocol.ScreenMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 对话的**唯一一条接线**：谁说话 → 入账 → 该不该触发技能 → 答 → 回镜给圆屏。
 *
 * 为什么这段逻辑住在 core 而不是 App 里：它是这个产品最容易做错、又最不能在手机上试错的部分
 * （去重、镜像方向、小智云那一路不许动技能）。放在 core 才有真单测，
 * 也才让"桌面彩排台"和"手机"跑的是同一份接线 —— 两边各写一遍迟早对不上。
 *
 * App 侧只负责三件事：给 [facts]（从自己的状态机取事实表）、给 [push]（往圆屏发一帧）、
 * 把 [onScreenFrame] 接到链路服务的 dialog 回调上。
 */
class DialogBridge(
    private val gateway: AiGateway,
    /** 事实表。由宿主从自己的状态机填，桥本身不认识 DemoRuntime */
    private val facts: () -> SkillContext,
    /** 出站一帧对话。v1 的连接由服务端自己滤掉，这里不用判断版本 */
    private val push: (DialogPayload) -> Unit,
    /**
     * "把这句跑完"的执行器，App 侧就是 `scope.launch { ... }`。
     * **必须是宿主的作用域**：链路的回调不在协程里，而回答要挂起（读模型端点），
     * 谁起线程谁负责取消 —— 桥自己造一个 GlobalScope 的话，页面关掉它还在往外发。
     */
    private val launch: (suspend () -> Unit) -> Unit,
    private val note: (String) -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
    val log: ConversationLog = ConversationLog(guard = { gateway.guard(it) }, log = note),
    /** 冷却钟跟着宿主的钟走：假时钟下"第二轮还会不会再提醒"是能测的，真钟下不能 */
    private val heartbeat: Heartbeat = Heartbeat(nowMs),
) {
    private var turnSeq = 0

    /**
     * 这句还在等回答。界面拿它画那条"想"的占位气泡 ——
     * 没有它，按下发送之后屏幕上一动不动，用户只能再点一次（而这里明确不排队）。
     */
    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking

    /** 一次问答。忙的时候回一句"我在想上一条"，不排队 —— 排队会让用户以为按了两次就发两遍 */
    suspend fun ask(question: String, via: DialogOrigin = DialogOrigin.phone, turnId: String? = null) {
        val q = question.trim()
        if (q.isEmpty()) return
        val id = turnId ?: newTurnId()
        val asked = log.append(Turn(from = TurnFrom.Me, text = q, source = ReplySource.LocalRule, via = via, turnId = id))
            ?: return
        mirror(asked)
        if (_thinking.value) {
            say(gateway.recordProactive("我在想上一条，这句先不算。", skill = null), via, id)
            return
        }
        _thinking.value = true
        try {
            say(gateway.ask(q, facts()), via, id)
        } finally {
            _thinking.value = false
        }
    }

    /**
     * 圆屏说上来的一帧。三条规矩：
     * ① 入账先过文案闸（[ask] 走 [ConversationLog.append]，每一句都要过）；
     * ② **`xiaozhi_cloud` 那一路不触发任何技能** —— 小智官方云替我们答的话不是从我们的事实表里来的，
     *    拿它当问题再喂一遍网关，等于让别人的模型替我们决定要不要发卫星报文；
     * ③ 答完 [mirror] 回屏。
     *
     * 去重不在这段里另起一套：重发/重连的同一轮走到 [ask] 里 `append` 会返回 null，
     * 于是既不会记两条，也不会答两遍。
     */
    fun onScreenFrame(msg: ScreenMessage) {
        if (msg.t != MsgType.dialog) return
        val d = msg.dialog ?: return
        if (d.dir == DialogDir.down) {
            log.record(d)            // 屏把自己那一轮回显回来（真固件重连后可能这么发），只补记录不触发
            return
        }
        if (d.source == DialogSource.xiaozhi_cloud) {
            log.record(d)
            note("小智云代答一句话：只入账，不触发技能，也不回镜（那句话屏上已经显示过了）")
            return
        }
        launch { ask(d.text, DialogOrigin.screen, d.turnId) }
    }

    /** 主动播报。宿主按自己的节奏调（App 是 10 秒一轮） */
    fun scanProactive() {
        heartbeat.scan(facts()).forEach { tip ->
            // id 必须带时间戳：主动提醒有冷却但会再来一轮，复用同一个 turn_id 会被去重当成重复丢掉
            say(gateway.recordProactive(tip.text, skill = tip.skill), DialogOrigin.phone, "t-hb-${tip.skill}-${newTurnId()}")
        }
    }

    /** 网关的回执落成记录 + 上屏。主动播报也走这儿，两条路只有一份代码 */
    private fun say(reply: AiReply, via: DialogOrigin, turnId: String) {
        val stored = log.append(
            Turn(
                from = TurnFrom.Ai,
                text = reply.text,
                source = reply.source,
                skill = reply.skill,
                proactive = reply.proactive,
                via = via,
                turnId = turnId,
            ),
        ) ?: return
        mirror(stored)
    }

    /**
     * **圆屏自己说的话不回镜**：它已经把自己听到的那句画出来了，再回一条同内容的 down
     * 就是同一句问话出现两遍。手机起的那轮两句都要回 —— 车那边的人得知道车主在手机上问了什么、答了什么。
     *
     * 副作用是一条要明说的不对称：小智云那句在屏上显示的是原话，在手机记录里是过闸后的话。
     * 记录要陪车主一辈子，所以过闸那一份以手机为准；屏上那条是临时的，而且已经打了「未过网关」。
     */
    private fun mirror(turn: Turn) {
        if (turn.via == DialogOrigin.screen && turn.from == TurnFrom.Me) return
        push(
            DialogPayload(
                turnId = turn.turnId,
                // 回屏一律 down：dir 说的是屏该怎么处理这一条，up 是屏"说出去"的方向
                dir = DialogDir.down,
                text = turn.text,
                source = turn.source.toDialog(),
                skill = turn.skill,
                origin = turn.via,
            ),
        )
    }

    /** 带自增是因为同一毫秒里连点两次是真会发生的，撞了就会丢掉第二条 */
    private fun newTurnId(): String = "t-${nowMs().toString(36)}-${turnSeq++}"
}
