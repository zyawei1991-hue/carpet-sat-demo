package com.carpet.satdemo.core.screen

import com.carpet.satdemo.core.protocol.FenceState
import com.carpet.satdemo.core.protocol.GnssSource
import com.carpet.satdemo.core.protocol.GnssState
import com.carpet.satdemo.core.protocol.LinkPrimary
import com.carpet.satdemo.core.protocol.LinkState
import com.carpet.satdemo.core.protocol.SatPhase
import com.carpet.satdemo.core.protocol.SatState
import com.carpet.satdemo.core.protocol.SosLevel
import com.carpet.satdemo.core.protocol.SosState
import com.carpet.satdemo.core.protocol.SentinelState
import com.carpet.satdemo.core.protocol.StatePayload
import com.carpet.satdemo.core.protocol.UiCard
import com.carpet.satdemo.core.protocol.UiEmotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「这块屏此刻该说哪件事」的那张表。
 *
 * 之前这块屏看着像轮播，根因不是排版：卡是演示脚本按步数换的，和车里发生的事没有关系。
 * 现在决定来自状态本身，所以这里钉的是**优先序**，尤其是那两条最容易写错的：
 * 一次 12 分钟就轮到一次的过境，不能把一条还好的蜂窝顶下去；而呼救一旦没走完，它顶一切。
 */
class CardPolicyTest {

    private val now = 1_795_200_400_000L

    private fun snap(
        cellOk: Boolean = true,
        inS: Int? = 900,
        sos: SosLevel = SosLevel.normal,
        fence: FenceState = FenceState(active = false, inside = true),
        sentinel: SentinelState? = null,
        moduleOk: Boolean = true,
    ) = StatePayload(
        link = LinkState(primary = if (cellOk) LinkPrimary.cell else LinkPrimary.sat, cellOk = cellOk, satOk = !cellOk),
        sat = SatState(phase = SatPhase.idle, nextPassInS = inS, moduleOk = moduleOk, queuePending = 0),
        gnss = GnssState(lat = 30.2741, lon = 120.1551, source = GnssSource.phone),
        fence = fence,
        sos = SosState(level = sos),
        sentinel = sentinel,
    )

    @Test
    fun `蜂窝还顶用时，倒计时再近也不抢整块屏`() {
        val d = CardPolicy.decide(snap(cellOk = true, inS = 10), now, lastNotableAtMs = now)
        assertEquals(UiCard.status, d.card, "网还在，屏不该被一次例行的过境劫走")
    }

    @Test
    fun `网没了才轮到卫星卡，窗口内外两张脸不一样`() {
        val waiting = CardPolicy.decide(snap(cellOk = false, inS = 100), now)
        assertEquals(UiCard.sat, waiting.card)
        assertEquals(UiEmotion.worried, waiting.emotion)

        val open = CardPolicy.decide(snap(cellOk = false, inS = 0), now)
        assertEquals(UiCard.sat, open.card)
        assertEquals(UiEmotion.excited, open.emotion, "窗口开着，卫星顶上了 —— 这是好消息，不该一脸愁")
    }

    @Test
    fun `星历算不出窗口时不画倒计时卡`() {
        // nextPassInS = null 是"不知道什么时候有窗"，不是"没有窗"：屏上该说的是状态盘里那行人话
        val d = CardPolicy.decide(snap(cellOk = false, inS = null), now, lastNotableAtMs = now)
        assertEquals(UiCard.status, d.card)
    }

    @Test
    fun `呼救没走完压过一切，包括满格蜂窝`() {
        for (level in listOf(SosLevel.armed, SosLevel.pending, SosLevel.sent)) {
            val d = CardPolicy.decide(snap(cellOk = true, inS = 600, sos = level), now)
            assertEquals(UiCard.sat, d.card, "$level 的时候屏上要一直留着倒计时")
        }
        // acked 是平台回执之后的那一档：横幅已经弹过了，不该把卡一直占死
        val acked = CardPolicy.decide(snap(cellOk = true, inS = 600, sos = SosLevel.acked), now, lastNotableAtMs = now)
        assertEquals(UiCard.status, acked.card)
    }

    @Test
    fun `越界排在对话前面`() {
        val d = CardPolicy.decide(
            snap(fence = FenceState(active = true, inside = false, name = "西湖景区")),
            now,
            dialogDownAtMs = now - 1_000,
        )
        assertEquals(UiCard.status, d.card)
        assertEquals(UiEmotion.worried, d.emotion)
    }

    @Test
    fun `答句只待一小会儿，过后把屏还给该说的事`() {
        val fresh = CardPolicy.decide(snap(), now, dialogDownAtMs = now - CardPolicy.DIALOG_FRESH_MS + 1_000)
        assertEquals(UiCard.dialog, fresh.card)

        val stale = CardPolicy.decide(
            snap(), now,
            dialogDownAtMs = now - CardPolicy.DIALOG_FRESH_MS - 1_000,
            lastNotableAtMs = now,
        )
        assertEquals(UiCard.status, stale.card, "一句话不能一直占着整块圆屏")
    }

    @Test
    fun `离线时那句答不配笑脸`() {
        // #36 真机实拍：卫星卡写着「卫星终端离线」，同一时刻对话卡脸上是 happy。
        // 屏自己跟自己对不上，观众第一反应是这块屏坏了 —— 而不是"哦，终端掉线了"。
        // 答句该不该占住卡面是一件事（占，别让上下半句忽然换题），
        // 那张脸要跟的是终端脸色，不是"刚说了句话"这个动作
        val up = CardPolicy.decide(snap(), now, dialogDownAtMs = now - 1_000)
        assertEquals(UiCard.dialog, up.card)
        assertEquals(UiEmotion.happy, up.emotion)

        val down = CardPolicy.decide(snap(moduleOk = false), now, dialogDownAtMs = now - 1_000)
        assertEquals(UiCard.dialog, down.card)
        assertEquals(UiEmotion.sad, down.emotion, "终端没了还笑，那是这块屏上最不像话的一张脸")
    }

    @Test
    fun `守望看到动静画那张脸`() {
        val d = CardPolicy.decide(snap(sentinel = SentinelState(armed = true, lastMotionTs = now - 5_000)), now)
        assertEquals(UiCard.emotion, d.card)

        // 撤防之后同一串时间戳不该再把屏切走
        val off = CardPolicy.decide(
            snap(sentinel = SentinelState(armed = false, lastMotionTs = now - 5_000)), now,
            lastNotableAtMs = now,
        )
        assertEquals(UiCard.status, off.card)
    }

    @Test
    fun `手动钉住压过所有判据`() {
        val d = CardPolicy.decide(snap(cellOk = false, inS = 0, sos = SosLevel.sent, moduleOk = false), now, manual = UiCard.clock)
        assertEquals(UiCard.clock, d.card)
        // 表情不跟手动那张走：车主钉的是"我要看钟"，脸仍该说实话
        assertEquals(UiEmotion.sad, d.emotion)
    }

    @Test
    fun `刚有过事画状态盘，模组没了就换那张脸`() {
        assertEquals(UiEmotion.neutral, CardPolicy.decide(snap(), now, lastNotableAtMs = now).emotion)
        assertEquals(UiEmotion.sad, CardPolicy.decide(snap(moduleOk = false), now, lastNotableAtMs = now).emotion)
    }

    /**
     * 屏是常供电的（带壳版不带电池），桌面上它就是一块第二屏。
     * "没事发生"还画状态总览，观众看到的是一页没人读的数。
     */
    @Test
    fun `闲够九十分钟落回钟面`() {
        val justBefore = CardPolicy.decide(snap(), now, lastNotableAtMs = now - CardPolicy.IDLE_TO_CLOCK_MS + 1)
        assertEquals(UiCard.status, justBefore.card, "差一秒也不该切，切早了演示现场会看到屏自己跳")

        val idle = CardPolicy.decide(snap(), now, lastNotableAtMs = now - CardPolicy.IDLE_TO_CLOCK_MS)
        assertEquals(UiCard.clock, idle.card)
        assertFalse(idle.notable, "钟面不算一次新事件 —— 算的话它每秒都在把自己刷新成刚刚有事，永远出不去这张")
    }

    @Test
    fun `从头到尾没事也是钟面`() {
        val d = CardPolicy.decide(snap(), now)
        assertEquals(UiCard.clock, d.card, "lastNotableAtMs 为 null 是\"这一轮从头到尾没人说过话\"，不是\"刚刚有过事\"")
        assertFalse(d.notable)
    }

    @Test
    fun `戳一下只活跃八秒，而且劫不走倒计时`() {
        val poked = CardPolicy.decide(snap(), now, pokeAtMs = now - 1_000)
        assertEquals(UiCard.emotion, poked.card, "有人在玻璃上做过动作，先给他一张会动的脸")
        assertTrue(poked.notable, "戳一下算一次有事：否则八秒窗口一过直接掉钟面，那一下白戳")

        val after = CardPolicy.decide(snap(), now, pokeAtMs = now - CardPolicy.POKE_FRESH_MS - 1, lastNotableAtMs = now)
        assertEquals(UiCard.status, after.card, "八秒到了就该把屏还掉，一次触摸不能长期占住整块圆屏")

        // 同一次戳，遇上呼救没走完 —— 屏上只能留倒计时。这条是"屏发的是请求不是命令"的全部理由
        val duringSos = CardPolicy.decide(snap(cellOk = true, inS = 600, sos = SosLevel.pending), now, pokeAtMs = now - 1_000)
        assertEquals(UiCard.sat, duringSos.card, "戳一下不能把呼救倒计时劫走")
    }

    @Test
    fun `钟面不挡安全那一句`() {
        // 闲多久都不该把越界压掉：这条是那张表里唯一不许被兜底吞掉的话
        val d = CardPolicy.decide(
            snap(fence = FenceState(active = true, inside = false, name = "西湖景区")),
            now,
            lastNotableAtMs = now - 10 * 60_000,
        )
        assertEquals(UiCard.status, d.card)
        assertTrue(d.notable)
    }
}
