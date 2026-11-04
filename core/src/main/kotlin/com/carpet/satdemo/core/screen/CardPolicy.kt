package com.carpet.satdemo.core.screen

import com.carpet.satdemo.core.protocol.SosLevel
import com.carpet.satdemo.core.protocol.StatePayload
import com.carpet.satdemo.core.protocol.UiCard
import com.carpet.satdemo.core.protocol.UiEmotion

/**
 * 「这块屏此刻该说哪件事」。
 *
 * 为什么在 core 而不是固件：固件自己挑卡的话，同一辆车在手机和屏上会讲两件事，
 * 而 §6.1 那套文案红线只能约束过网关的那一路。也为什么不是演示脚本的计时器：
 * 脚本每 6 秒换一张卡，观众看到的就是"几个画面在轮播"，和车里发生的事没有关系。
 *
 * 一张表，从上到下第一个成立的赢。判据全部取自 [StatePayload]，不看时钟以外的东西：
 * 手机那边每一拍都重算一次，所以场景一变下一帧就是那张卡，不需要谁去"切"。
 */
object CardPolicy {

    /**
     * 窗口外还有这么多秒也算"要过境了"——屏上那个 mm:ss 倒计时得先于窗口出现。
     * 但这条只在**蜂窝不顶用**时才生效，理由见 [decide] 里那段注释。
     */
    const val SOON_IN_S = 120

    /** 答句上屏之后留这么久。过了就回到该说的事，不让一句话一直占着整张圆屏 */
    const val DIALOG_FRESH_MS = 30_000

    /** 守望看到动静之后那张脸留多久 */
    const val MOTION_FRESH_MS = 15_000

    /**
     * 连续这么多秒没有任何"要说的话"，屏落回钟面。
     *
     * 这不是"表盘好看"：这块屏**常供电**（带壳版不随盒带电池，§8 写死了这条前提），
     * 桌面上它就是一块第二屏。而"没事发生"时画状态总览，观众看到的是一页没人读的数；
     * 画一块还在走的表，才像它名字里那个"屏"。90 秒是取的"够让人怀疑它死了，又不至于错过事"。
     */
    const val IDLE_TO_CLOCK_MS = 90_000

    /** 被戳一下之后这张脸留多久。之后回到该说的事 —— 一次触摸不该长期占住整块屏 */
    const val POKE_FRESH_MS = 8_000

    /**
     * @param notable 这一条决定算不算"有事要说"。**只有兜底那两支是 false**，
     *                所以钟面那一条判的是"多久没人说过话"，而不是"多久没换过卡" ——
     *                后者永远不成立，因为兜底那张每秒都在把自己算成新事件
     */
    data class Decision(
        val card: UiCard,
        val emotion: UiEmotion,
        val why: String,
        val notable: Boolean = true,
    )

    /**
     * @param dialogDownAtMs 最近一句**说给屏听**的话是几点；null = 这轮还没有
     * @param manual         App 设置页上那个选择器的决定。给了就不算，那是车主的意图
     * @param pokeAtMs       最近一次有人在玻璃上戳了一下；屏的请求制见 protocol/screen-link.md「上行」那节
     * @param lastNotableAtMs 最近一次"有事要说"是几点；null = 这一轮从头到尾没事，那就直接是钟面
     */
    fun decide(
        snap: StatePayload,
        nowMs: Long,
        dialogDownAtMs: Long? = null,
        manual: UiCard? = null,
        pokeAtMs: Long? = null,
        lastNotableAtMs: Long? = null,
    ): Decision {
        manual?.let { return Decision(it, calmEmotion(snap), "手动钉在 $it") }

        val sos = snap.sos.level
        if (sos == SosLevel.armed || sos == SosLevel.pending || sos == SosLevel.sent) {
            return Decision(UiCard.sat, UiEmotion.worried, "呼救还没走完，屏上要说倒计时")
        }
        if (snap.fence.active && !snap.fence.inside) {
            return Decision(UiCard.status, UiEmotion.worried, "已越界")
        }
        // 倒计时卡**不抢一条还好的蜂窝**。这条限定是有代价的取舍：
        // 星座 12 分钟一轮，光按"窗口还有 N 秒"切卡的话，屏每 12 分钟就会被一次
        // 谁都不关心的过境劫走，坐在车里的家长看到的是同一张卫星卡反复弹上来。
        // 只有网没了、卫星开始变成"唯一那条路"时，它才真的值得占住整块屏。
        val inS = snap.sat.nextPassInS
        if (inS != null && inS <= SOON_IN_S && !snap.link.cellOk) {
            return if (inS <= 0) Decision(UiCard.sat, UiEmotion.excited, "窗口开着，卫星顶上了")
            else Decision(UiCard.sat, UiEmotion.worried, "蜂窝不顶用，窗口还有 ${inS}s")
        }
        // 戳屏排在对话之前：有人刚在这块玻璃上做过动作，先给他一张会动的脸，
        // 否则"点了没反应"就是他被劝退的那一秒。
        // 但它排在上面三条安全话轮之后 —— 一次触摸不能把倒计时、越界、过境窗口劫走，
        // 这也是屏只能发请求、不能自己切卡的全部理由
        if (pokeAtMs != null && nowMs - pokeAtMs < POKE_FRESH_MS) {
            return Decision(UiCard.emotion, UiEmotion.excited, "刚被戳了一下")
        }
        if (dialogDownAtMs != null && nowMs - dialogDownAtMs < DIALOG_FRESH_MS) {
            // 答句刚下去的时候屏上该画这句，但终端都离线了还配一张笑脸是自相矛盾：
            // #36 实拍第 8~9 步，hero 写着「卫星终端离线…」而脸上是 happy，
            // 观众先怀疑的是屏坏了。happy 只留给"终端在线、这句话确实是好消息"那一档
            val mood = if (snap.sat.moduleOk) UiEmotion.happy else UiEmotion.sad
            return Decision(UiCard.dialog, mood, "刚答了一句，还在等它说完")
        }
        val motion = snap.sentinel?.lastMotionTs
        if (snap.sentinel?.armed == true && motion != null && nowMs - motion < MOTION_FRESH_MS) {
            return Decision(UiCard.emotion, UiEmotion.thinking, "守望看到动静")
        }
        // 闲到落回钟面。why 里不写"闲了 N 秒"那种会变的数：调用方拿 why 的变化当日志闸门，
        // 一个每秒都变的措辞会把 1Hz 的日志刷满
        if (lastNotableAtMs == null || nowMs - lastNotableAtMs >= IDLE_TO_CLOCK_MS) {
            return Decision(UiCard.clock, calmEmotion(snap), "闲下来，落回钟面", notable = false)
        }
        return Decision(UiCard.status, calmEmotion(snap), "没事发生，画状态总览", notable = false)
    }

    /** 兜底那张用中性脸；模组离线时换成"难过"—— 那是这台车此刻唯一诚实的表情 */
    private fun calmEmotion(snap: StatePayload): UiEmotion =
        if (!snap.sat.moduleOk) UiEmotion.sad else UiEmotion.neutral
}
