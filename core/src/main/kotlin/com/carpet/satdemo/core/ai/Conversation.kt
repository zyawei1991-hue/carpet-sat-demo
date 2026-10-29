package com.carpet.satdemo.core.ai

import com.carpet.satdemo.core.protocol.DialogDir
import com.carpet.satdemo.core.protocol.DialogOrigin
import com.carpet.satdemo.core.protocol.DialogPayload
import com.carpet.satdemo.core.protocol.DialogSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 对话记录。**这一份是整个产品里唯一的一条对话时间线**，圆屏和手机都只是它的两个入口。
 *
 * 为什么宿主必须在手机而不是圆屏：
 * ① 回答要读的事实（链路相位、额度、围栏、位置）全在手机的状态机里，圆屏拿不到；
 * ② §6.1 那条文案红线只能约束经过网关的话 —— 记录落在别处，红线就形同虚设；
 * ③ 车主的诉求很具体：**在手机上看到刚才是不是有人在车那边跟屏说话**。
 *    这句话反过来也成立：圆屏上说的话只有圆屏自己记得，等于没有记录。
 *
 * 所以规则是：谁说话都往这里追加一条 [Turn]，`via` 记住是从哪块屏进来的，
 * 出站时同一份记录再镜像回圆屏。两边看到的是同一个 list，不是"各存一份再对齐"。
 */
enum class TurnFrom { Me, Ai }

data class Turn(
    val from: TurnFrom,
    val text: String,
    val source: ReplySource,
    val skill: String? = null,
    val proactive: Boolean = false,
    /** 这一轮是从哪儿起的：手机打字，还是圆屏说话 */
    val via: DialogOrigin = DialogOrigin.phone,
    /** 一轮问答共用同一个 id（上行和下行各一条 Turn，所以去重键是 id + from） */
    val turnId: String,
) {
    /** 是不是"我们生成的话"。小智云那一路不是，界面上必须分开标 */
    val ours: Boolean get() = source != ReplySource.XiaozhiCloud
}

/**
 * 会话记录本体。线程不安全 —— 和 DemoRuntime 一样只从主 scope 动它，
 * 链路那边进来的帧由 AppRuntime 切回同一线程后再 append。
 */
class ConversationLog(
    private val cap: Int = 200,
    /** 文案闸。默认放行，App 侧传 [AiGateway.guard] 进来：话不是我们生成的也要过这一道 */
    private val guard: (String) -> String = { it },
    private val log: (String) -> Unit = {},
) {
    private val _turns = MutableStateFlow<List<Turn>>(emptyList())
    val turns: StateFlow<List<Turn>> = _turns

    private val seen = LinkedHashSet<String>()

    val size: Int get() = _turns.value.size

    /**
     * 追加一轮。**返回落库的那条（文案已经过闸），重复时被丢掉返回 null** ——
     * 圆屏按两下就出现两条一样的回答是不可接受的，而调用方（AppRuntime）要拿返回值镜像回屏，
     * 所以它得看见"存进去的到底是哪句话"，不能自己再拼一遍。
     */
    fun append(turn: Turn): Turn? {
        val key = "${turn.turnId}:${turn.from.name}"
        if (!seen.add(key)) {
            log("这一轮已经记过了（${turn.turnId}），丢掉重复的那条")
            return null
        }
        val before = _turns.value
        val kept = turn.copy(text = guard(turn.text))
        _turns.value = (before + kept).takeLast(cap)
        // 被挤出去的那几轮要把去重键一起放掉，否则 cap 到了上限，旧 id 反而永久占位
        (before + kept).take((before.size + 1) - _turns.value.size).forEach {
            seen.remove("${it.turnId}:${it.from.name}")
        }
        return kept
    }

    /**
     * 把契约上的一帧对话变成记录。**进来的路上就过一次文案闸**：
     * [DialogSource.xiaozhi_cloud] 那一路的话不是我们生成的，但它即将出现在屏幕上、
     * 并永久留在车主的记录里 —— 这两件事任何一件都值得拦一道。
     */
    fun record(d: DialogPayload): Turn? {
        val from = if (d.dir == DialogDir.up) TurnFrom.Me else TurnFrom.Ai
        return append(
            Turn(
                from = from,
                text = d.text,
                source = d.source.toReply(),
                skill = d.skill,
                via = d.origin,
                turnId = d.turnId,
            ),
        )
    }

    fun clear() {
        _turns.value = emptyList()
        seen.clear()
    }

    /** 镜像给圆屏：只发"该让屏显示的那几条"，主动提醒也算，屏上不该看不到自己为什么开口 */
    fun toDialog(t: Turn, dir: DialogDir): DialogPayload = DialogPayload(
        turnId = t.turnId,
        dir = dir,
        text = t.text,
        source = t.source.toDialog(),
        skill = t.skill,
        origin = t.via,
    )
}

fun DialogSource.toReply(): ReplySource = when (this) {
    DialogSource.model -> ReplySource.Model
    DialogSource.local_fallback -> ReplySource.LocalFallback
    DialogSource.denied -> ReplySource.Denied
    DialogSource.xiaozhi_cloud -> ReplySource.XiaozhiCloud
    else -> ReplySource.LocalRule
}

fun ReplySource.toDialog(): DialogSource = when (this) {
    ReplySource.Model -> DialogSource.model
    ReplySource.LocalFallback -> DialogSource.local_fallback
    ReplySource.Denied -> DialogSource.denied
    ReplySource.XiaozhiCloud -> DialogSource.xiaozhi_cloud
    ReplySource.LocalRule -> DialogSource.local_rule
}
