package com.carpet.satdemo.core.ai

import com.carpet.satdemo.core.protocol.DialogDir
import com.carpet.satdemo.core.protocol.DialogOrigin
import com.carpet.satdemo.core.protocol.DialogPayload
import com.carpet.satdemo.core.protocol.DialogSource
import com.carpet.satdemo.core.protocol.ScreenPage
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
 * `scene` 记住它归手机哪一页（[TurnScene]），出站时同一份记录再镜像回圆屏。
 * 两边看到的是同一个 list，不是"各存一份再对齐"；手机上那一页看到的是**同一个 list 的视图**。
 */
enum class TurnFrom { Me, Ai }

/**
 * 一句话归手机上的哪一页。
 *
 * 只有两个取值，因为手机上有独立对话的页面就这两个（车辆、家人）：
 * 首页那条是**总时间线**，什么都不归、什么都显示；「我的」页不问话。
 * null = 归不进任何一页 —— 比如屏报它在「时钟」页时说的话，那种句子只属于总时间线。
 *
 * 这一刀是"不同场景的功能页面还是应该区分开"和"所有对话整合成一份"的接缝：
 * 记录**永远只有一份**（[ConversationLog]），页面上按 scene 各取各的视图，
 * 不是每页存一份再想办法对齐 —— 存两份就一定会有对不上的那一天。
 */
enum class TurnScene { vehicle, family }

/**
 * 屏报上来的那一页换算成手机的一页。**只有两条映射得上**，其余一律 null：
 * 亲子/车载那两页在两边指的是同一件事，时钟、图片、选场景那种页上的话没有对应的手机页面，
 * 猜一个反而会让车主在错误的页里看见别人的对话。
 */
fun screenSceneOf(page: ScreenPage?): TurnScene? = when (page) {
    ScreenPage.vehicle -> TurnScene.vehicle
    ScreenPage.kids -> TurnScene.family
    else -> null
}

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
    /**
     * 这一轮归手机的哪一页。手机起的问题由发起那一屏带进来；
     * 圆屏起的从 [screenSceneOf] 换算，**屏没报过页就是 null**（只在总时间线里出现）。
     */
    val scene: TurnScene? = null,
) {
    /** 是不是"我们生成的话"。屏那侧的云答的不是，界面上必须分开标 */
    val ours: Boolean get() = !source.foreignCloud
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
     * [DialogSource.foreignCloud] 那两路的话不是我们生成的，但它们即将出现在屏幕上、
     * 并永久留在车主的记录里 —— 这两件事任何一件都值得拦一道。
     *
     * [scene] 不在契约里：页归屏、内容归手机，屏说的是它自己那套页名（[ScreenPage]），
     * 换算成手机的一页是宿主的事，所以由调用方给。
     */
    fun record(d: DialogPayload, scene: TurnScene? = null): Turn? {
        val from = if (d.dir == DialogDir.up) TurnFrom.Me else TurnFrom.Ai
        return append(
            Turn(
                from = from,
                text = d.text,
                source = d.source.toReply(),
                skill = d.skill,
                via = d.origin,
                turnId = d.turnId,
                scene = scene,
            ),
        )
    }

    fun clear() {
        _turns.value = emptyList()
        seen.clear()
    }

    /**
     * 「娃在屏上说了啥」要的那几句：**只数屏说上来的**（`via=screen` 且是问句那一条），
     * 手机上打的字不算 —— 车主问的是车那边有没有人跟屏说话。
     *
     * [excluding] 把正在答的这一轮排掉：那一轮如果本来就是屏起头的，不加这一句
     * 回答会把用户刚问的那句原样念回去（「屏上最近有 3 句：「屏上说了啥」…」）。
     *
     * 筛法住在这儿而不是两个宿主各写一遍：手机筛出一条、彩排台筛出另一条的话，
     * 同一块屏在两种场合会回答出两句不一样的话，那种分歧在现场没法当场解释。
     */
    fun screenTalk(limit: Int = 3, excluding: String? = null): List<String> = _turns.value
        .filter { it.via == DialogOrigin.screen && it.from == TurnFrom.Me && it.turnId != excluding }
        .takeLast(limit)
        .map { it.text }

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
    DialogSource.muse_cloud -> ReplySource.MuseCloud
    else -> ReplySource.LocalRule
}

fun ReplySource.toDialog(): DialogSource = when (this) {
    ReplySource.Model -> DialogSource.model
    ReplySource.LocalFallback -> DialogSource.local_fallback
    ReplySource.Denied -> DialogSource.denied
    ReplySource.XiaozhiCloud -> DialogSource.xiaozhi_cloud
    ReplySource.MuseCloud -> DialogSource.muse_cloud
    ReplySource.LocalRule -> DialogSource.local_rule
}
