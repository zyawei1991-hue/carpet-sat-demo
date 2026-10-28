package com.carpet.satdemo.core.engine

import com.carpet.satdemo.core.link.CellStatus
import com.carpet.satdemo.core.link.SosCopy
import com.carpet.satdemo.core.modem.SatPath
import com.carpet.satdemo.core.modem.SatResult
import com.carpet.satdemo.core.modem.SimulatedModem
import com.carpet.satdemo.core.protocol.GnssSource
import com.carpet.satdemo.core.protocol.ModuleCaps
import com.carpet.satdemo.core.protocol.SosFrame
import com.carpet.satdemo.core.protocol.SosLevel
import com.carpet.satdemo.core.protocol.decodeSosFrame
import com.carpet.satdemo.core.protocol.hexToBytes
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * §8 R1 判据里点名的两件事：**SOS 可撤销**和**用量页**。
 *
 * 撤销这一组断言的重点不是"能不能发 CANCEL 帧"，而是**它什么时候不算数** ——
 * 原 SOS 已经交给模组之后，撤销只是又排了一条，界面说「已取消」就是在骗人。
 */
class DemoEngineCancelUsageTest {

    private fun newModem(visible: Boolean = true) = SimulatedModem(
        clock = { 0L },
        passWindowMs = if (visible) Long.MAX_VALUE / 4 else 0,
        txLatencyMs = 0,
        sleep = { },
    )

    private fun engine(visible: Boolean = true): Pair<DemoEngine, SimulatedModem> {
        val modem = newModem(visible)
        return DemoEngine(modem, ownerId = 12_061L) to modem
    }

    private fun withFix(e: DemoEngine) {
        e.onFix(30.2741, 120.1551, GnssSource.phone, hdop = 2.0, fixAgeSec = 1.0)
    }

    @Test
    fun `cancel frame is a distinct message type the platform can tell apart`() = runBlocking {
        val (e, _) = engine()
        withFix(e)
        val sos = e.triggerSos(nowSec = 1_795_200_000L, cellOk = false)
        val out = e.cancelSos(nowSec = 1_795_200_001L, cellOk = false)

        val d = decodeSosFrame(hexToBytes(out.cancelFrameHex))
        assertEquals(
            SosFrame.MSG_SOS_CANCEL,
            d.msgType,
            "撤销必须是另一种报文类型，不然平台只会把它当成又一次求救",
        )
        assertFalse(sos.frameHex == out.cancelFrameHex, "SOS 和撤销不该是同一帧")
    }

    @Test
    fun `cancel before handover takes the alarm down`() = runBlocking {
        val (e, _) = engine()
        withFix(e)
        val sos = e.triggerSos(nowSec = 1_795_200_000L, cellOk = false)
        assertEquals(SatResult.COMPLETE, sos.result, "这条测试的前提是过境窗口内直接 ACK")

        val out = e.cancelSos(nowSec = 1_795_200_001L, cellOk = false)
        assertTrue(out.accepted)
        assertFalse(out.alreadyHanded, "星上已确认的报文不该被算成已交给模组")
        assertEquals(emptyList<String>(), e.pending, "撤销之后 App 队列里不该还留着那条 SOS")
        assertEquals(SosLevel.normal, e.snapshot().sos.level, "没交出去的撤销要真的把告警收回去")
    }

    @Test
    fun `cancel after handover admits it may be too late`() = runBlocking {
        val (e, modem) = engine(visible = false)
        withFix(e)
        // 语句级/真机初期那一路：只支持排队，模组"收下"不等于星上收到
        modem.supportsImmediateSend = false
        val sos = e.triggerSos(nowSec = 1_795_200_000L, cellOk = false)
        assertEquals(SatPath.QUEUED, sos.via)
        assertTrue(sos.send.accepted, "前提是模组把这条收进了它自己的队列")

        val out = e.cancelSos(nowSec = 1_795_200_001L, cellOk = false)
        assertTrue(out.alreadyHanded, "模组收下过就该标出来")

        val line = SosCopy.cancelLine(out.send, out.alreadyHanded)
        assertTrue(line.contains("不保证"), "这种情况的话必须留余地：$line")
        assertFalse(line.contains("已取消"), "撤销不保证生效时不许出现「已取消」：$line")
        SosCopy.BANNED.forEach { banned -> assertFalse(line.contains(banned), "禁用句「$banned」出现在：$line") }
    }

    @Test
    fun `failed cancel says it failed instead of implying safety`() = runBlocking {
        val (e, modem) = engine()
        withFix(e)
        e.triggerSos(nowSec = 1_795_200_000L, cellOk = false)
        modem.reachable = false

        val out = e.cancelSos(nowSec = 1_795_200_001L, cellOk = false)
        assertFalse(out.accepted)
        val line = SosCopy.cancelLine(out.send, out.alreadyHanded)
        assertTrue(line.contains("没送出去"), "终端不回话时要说没送出去：$line")
        assertTrue(line.contains("再试"), "并且要给出下一步动作：$line")
    }

    @Test
    fun `usage reads the caps instead of hardcoding the quota`() = runBlocking {
        val (e, modem) = engine()
        modem.capsOverride = ModuleCaps.worstKnown(cardId = "89860000000000000001")
        e.onCellStatus(CellStatus(hasInternetCapability = true, validated = true, signalLevel = 4), 0L)
        e.onTick(0L)
        val u = e.usage()

        assertEquals("89860000000000000001", u.cardId, "卡号、等级、额度都得是现场读到的那一份")
        assertEquals(modem.capsOverride.model, u.model)
        assertEquals(modem.capsOverride.lengthLevel, u.level)
        assertTrue(u.capacity > 0, "额度必须读出来 —— 写死 50 在换型号那天就变成假数据")
        assertEquals(u.slotsLeft + u.handedOver, u.capacity)
        assertTrue(u.pressure in 0f..1f)
        assertNotNull(u.budget, "读不到等级也要给最坏档，别让界面显示 0 字节")
        assertFalse(u.invalidCard)
    }

    @Test
    fun `invalid card shows up as unusable quota`() = runBlocking {
        val (e, modem) = engine()
        // 〔规 §3.1.4 注1〕卫星号码回 "1" 就是无效卡，这时候连额度都不能信
        modem.capsOverride = ModuleCaps.worstKnown()
        e.onTick(0L)

        assertTrue(e.usage().invalidCard, "无效卡必须在用量页上说出来，不能照报一个额度")
    }

    @Test
    fun `queue pressure rises when a queued SOS is handed to the module`() = runBlocking {
        val (e, modem) = engine(visible = false)
        withFix(e)
        modem.supportsImmediateSend = false
        assertEquals(0f, e.usage().pressure, "刚开机不该有水位")

        // 排队那一路是双写（E.10 #8）：模组收下之后 App 仍留一份盯梢，所以额度上算"占位"
        e.triggerSos(nowSec = 1_795_200_000L, cellOk = false)
        val u = e.usage()

        assertEquals(1, u.handedOver, "SOS 交给模组后应在用量页上占一个槽：$u")
        assertEquals(0, u.waiting, "双写的那份盯梢副本算在已交模组里，不重复占待补发")
        assertTrue(u.pressure > 0f)
        assertEquals(u.capacity - 1, u.slotsLeft)
    }
}
