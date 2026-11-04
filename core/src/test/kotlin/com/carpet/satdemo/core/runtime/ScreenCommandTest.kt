package com.carpet.satdemo.core.runtime

import com.carpet.satdemo.core.engine.DemoEngine
import com.carpet.satdemo.core.link.CellStatus
import com.carpet.satdemo.core.modem.MonoClock
import com.carpet.satdemo.core.modem.SimulatedModem
import com.carpet.satdemo.core.protocol.CommandType
import com.carpet.satdemo.core.protocol.DialogDir
import com.carpet.satdemo.core.protocol.DialogPayload
import com.carpet.satdemo.core.protocol.UiCard
import com.carpet.satdemo.core.protocol.UiEmotion
import com.carpet.satdemo.core.protocol.UiState
import com.carpet.satdemo.core.screen.CardPolicy
import com.carpet.satdemo.core.screen.ScreenLinkServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 屏发上来的三条切卡请求，**在 core 这一侧的落点**。
 *
 * linkcheck.mjs 也跑这三条，但它对着的是彩排台当下那个相位：蜂窝一掉、窗口一近就轮到卫星卡，
 * 那时 poke 让路是正确答案，脚本看不出"dispatch 通没通"。这里用假时钟把相位钉死，
 * 只问一件事：请求有没有变成推出去那一帧的 `state.ui`。
 */
class ScreenCommandTest {

    private class Clock(var now: Long) : MonoClock {
        override fun nowMs(): Long = now
        fun advance(ms: Long) { now += ms }
    }

    private class Harness {
        val clock = Clock(1_795_200_000_000L)
        val modem = SimulatedModem(
            clock = clock,
            passPeriodMs = 720_000,
            passWindowMs = 240_000,
            txLatencyMs = 0,
            sleep = { },
        )
        val runtime = DemoRuntime(
            engine = DemoEngine(modem, DemoRuntime.DEFAULT_OWNER_ID),
            server = ScreenLinkServer(),
            clockMs = { clock.now },
        )

        init {
            // 满格蜂窝 + 窗口还远：策略落在兜底那两张，这一档里切卡和戳屏都该看得见
            runtime.onCellSample(CellStatus(hasInternetCapability = true, validated = true, signalLevel = 4))
        }

        /** 走一整拍，把这一帧推出去的 ui 拿回来 */
        fun ui(): UiState = runBlocking {
            runtime.tickOnce()
            runtime.status.value.snapshot?.ui
                ?: throw AssertionError("1Hz 那一帧没带 ui —— 策略没跑，屏只会画总览")
        }

        fun cmd(c: CommandType, dir: Int? = null): Boolean =
            runtime.handleScreenCommand(c, dir?.let { JsonObject(mapOf("dir" to JsonPrimitive(it))) })
    }

    @Test
    fun `切卡请求把下一张写回这一帧`() {
        val h = Harness()
        val before = h.ui().card
        assertTrue(h.cmd(CommandType.card_next, 1), "card_next 该被接住")
        val after = h.ui().card
        assertNotEquals(before, after, "请求接住了但 ui 没变：屏上不会动")

        assertTrue(h.cmd(CommandType.card_next, -1))
        assertEquals(before, h.ui().card, "往回滑该回到原来那张 —— 切卡是个环，不是单程")
    }

    @Test
    fun `dir 缺省和发歪都按往后一张`() {
        val h = Harness()
        h.ui()
        assertTrue(h.runtime.handleScreenCommand(CommandType.card_next, null), "dir 没带的时候缺省是 +1")
        val c1 = h.ui().card
        h.runtime.handleScreenCommand(CommandType.card_next, null)
        val c2 = h.ui().card
        assertNotEquals(c1, c2, "两次 +1 之后该再往前一张")

        // 屏发歪（不是个数字）不许把整条链路带崩，也不许当成"没发生"
        assertTrue(h.runtime.handleScreenCommand(CommandType.card_next, JsonObject(mapOf("dir" to JsonPrimitive("x")))))
        val c3 = h.ui().card
        assertNotEquals(c2, c3, "解不出 dir 时按 +1 走，而不是静悄悄什么都不做")
    }

    @Test
    fun `交回策略之后不再压着判据`() {
        val h = Harness()
        val idle = h.ui().card
        h.cmd(CommandType.card_next, 1)
        val pinned = h.ui().card
        assertNotEquals(idle, pinned)

        assertTrue(h.cmd(CommandType.card_auto))
        // 交回之后戳一下就换得动了 —— 钉着的那张会把 poke 吃掉，这两条是同一件事的两面
        h.cmd(CommandType.poke)
        assertEquals(UiCard.emotion, h.ui().card, "card_auto 没把定卡权交回优先序的话，这里还是 $pinned")
    }

    /**
     * 钉住的时候戳屏不换卡是**当前设计**（车主的意图压过一切判据，见 CardPolicy 的 manual 那一条）。
     * 这条钉住它，免得后面有人把"戳了没反应"当成 bug 去"修"，把 App 设置页那个选择器修成摆设。
     */
    @Test
    fun `钉住期间戳屏只换脸不换卡`() {
        val h = Harness()
        h.ui()
        h.cmd(CommandType.card_next, 1)
        val pinned = h.ui().card
        assertTrue(h.cmd(CommandType.poke), "戳屏本身是被接住的，只是不许切卡")
        assertEquals(pinned, h.ui().card, "poke 只换脸，不许把车主钉住的那张卡切走")
    }

    @Test
    fun `戳屏那张脸只活八秒`() {
        val h = Harness()
        assertTrue(h.cmd(CommandType.poke))
        assertEquals(UiCard.emotion, h.ui().card)

        h.clock.advance(CardPolicy.POKE_FRESH_MS + 1L)
        assertEquals(UiCard.status, h.ui().card, "八秒一到该把屏还给该说的事 —— 一次触摸不能长期占住整块圆屏")
    }

    @Test
    fun `契约里有但不该由屏发的那条算没接住`() {
        val h = Harness()
        h.ui()
        for (c in listOf(CommandType.track_pull, CommandType.set_demo_mode, CommandType.set_fence, CommandType.reboot_ui)) {
            assertFalse(h.cmd(c), "$c 这一档还没实现，得让调用方看得见地记一行，而不是当成功")
        }
        assertFalse(h.runtime.handleScreenCommand(null), "帧里没有 command 体 = 没接住，不许当成功")
    }

    /**
     * 彩排台 `--hold` 那一屏每秒只走 [DemoRuntime.pushState]，不走 tickOnce。
     *
     * 这条钉的是"演的和装的是一份"：真机上 1Hz 走 tickForever，卡片决策每秒重算；
     * 定格那一屏要是只把 `engine.snapshot()` 原样发出去，三条切卡请求在彩排里必挂、
     * 真机上却全好 —— 那种"彩排通不过所以现场不敢用"的落差，比现场出事更贵。
     */
    @Test
    fun `定格那一屏仍然接切卡请求`() {
        val h = Harness()
        val out = ArrayList<com.carpet.satdemo.core.protocol.ScreenMessage>()
        h.runtime.onSend = { msgs -> out += msgs }
        val before = h.ui().card

        assertTrue(h.cmd(CommandType.card_next, 1), "定格时 card_next 该被接住")
        // holdScreen 走的就是这一条：不推 1Hz 那一圈，只 pushState
        h.runtime.pushState()
        val pushed = out.last().state?.ui?.card
        assertNotEquals(before, pushed, "pushState 没重算卡片决策：屏上停在 $before，脚本对着定格屏永远验不出交互")
    }

    /**
     * 脸跟着**那句答话**走，不跟着「终端在不在线」走。
     *
     * 这一档里蜂窝满格、GT1005 在线，旧规则（`moduleOk ? happy : sad`）不管答什么都笑。
     * 而 #36 实拍的就是这个形状：hero 写着坏消息、脸上是笑脸，观众先怀疑屏坏了。
     */
    @Test
    fun `答句里的坏消息压过在线的笑脸`() {
        val h = Harness()
        h.ui()
        h.runtime.pushDialog(
            DialogPayload(turnId = "mood-1", dir = DialogDir.down, text = "这件事我现在没有数据，不能凭印象答。")
        )
        val ui = h.ui()
        assertEquals(UiCard.dialog, ui.card, "答句刚下去那一帧该是对话卡")
        assertEquals(UiEmotion.worried, ui.emotion, "这句没数据还配笑脸，就是 #36 那个 bug")

        // 三十秒之后那句话过期了，表情得交还给策略，不许挂到下一张卡上
        h.clock.advance(CardPolicy.DIALOG_FRESH_MS + 1L)
        assertNotEquals(UiEmotion.worried, h.ui().emotion, "答句表情只活对话卡那 30 秒")
    }
}
