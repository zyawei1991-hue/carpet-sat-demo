package com.carpet.satdemo.core.queue

import com.carpet.satdemo.core.modem.MonoClock
import com.carpet.satdemo.core.protocol.ModuleCaps
import com.carpet.satdemo.core.protocol.ModuleModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * E.10 #8 的队列口径。〔规 §3.1.12 注 / §3.1.13 注〕给了两套差着一个数量级的型号：
 * GM1001 = 500 条 / FLASH 掉电保存 / **没有超时**，GM1003 = 50 条 / RAM 掉电丢失 / 4 分钟未发出即丢。
 * 这里钉的是"同一件事在两个型号上必须走相反方向"的那几条 —— 写死常数就一定会有一半是错的。
 */
class OutboxTest {

    private class Clock(var now: Long = 1_795_200_000_000L) : MonoClock {        override fun nowMs(): Long = now
        fun advance(ms: Long) {
            now += ms
        }
    }

    private fun caps(model: ModuleModel) = ModuleCaps.worstKnown().copy(model = model)

    private fun box(model: ModuleModel, clock: Clock = Clock()) = Outbox(caps(model), clock)

    @Test
    fun `容量按型号注入，不是常数`() {
        val c = Clock()
        val small = box(ModuleModel.GM1003, c)
        val big = box(ModuleModel.GM1001, c)
        repeat(51) { i ->
            val hex = "T%03d".format(i)
            small.offer(hex, OutboxClass.Telemetry)
            big.offer(hex, OutboxClass.Telemetry)
        }
        assertEquals(50, small.size, "GM1003 只有 50 个槽")
        assertEquals(51, big.size, "GM1001 有 500 个，第 51 条排得下")
        assertEquals(0, big.dropped)
        assertEquals(1, small.dropped)
        assertEquals("T001", small.hexes.first(), "该挤掉的是最老的那条心跳，不是最新的")
    }

    @Test
    fun `队满时 SOS 挤掉心跳，心跳挤不进一队列的 SOS`() {
        val c = Clock()
        val q = box(ModuleModel.GM1003, c)
        repeat(50) { i -> q.offer("S%03d".format(i), OutboxClass.Emergency) }

        assertEquals(Outbox.Verdict.RefusedFull, q.offer("HEARTBEAT", OutboxClass.Telemetry))
        assertEquals(50, q.size, "拒收不能把已有的任何一条弄丢")
        assertEquals(1, q.dropped, "但也不能假装没丢 —— 屏上要看得见这一条")

        // 撤销 SOS 也是 Emergency 档：满了就替换最老的那条，宁可旧的不发
        assertEquals(Outbox.Verdict.AcceptedAfterEvict, q.offer("S999", OutboxClass.Emergency))
        assertEquals(50, q.size)
        assertEquals("S001", q.hexes.first(), "S000 被新的这条挤掉了")
    }

    @Test
    fun `出队按要紧程度排，不按到队先后`() {
        val c = Clock()
        val q = box(ModuleModel.GM1001, c)
        q.offer("H1", OutboxClass.Telemetry); c.advance(1)
        q.offer("A1", OutboxClass.Alarm); c.advance(1)
        q.offer("S1", OutboxClass.Emergency)
        assertEquals(listOf("S1", "A1", "H1"), q.hexes, "越界告警和 SOS 都要排在心跳前面")
    }

    @Test
    fun `同一条报文不占两个槽`() {
        val c = Clock()
        val q = box(ModuleModel.GM1003, c)
        assertEquals(Outbox.Verdict.Accepted, q.offer("H1", OutboxClass.Telemetry))
        assertEquals(Outbox.Verdict.RefusedDuplicate, q.offer("H1", OutboxClass.Telemetry))
        assertEquals(1, q.size, "同一分钟里位置电量都没变，帧是完全一样的，堆两条只是白占额度")
    }

    @Test
    fun `交给模组不等于送达 - GM1003 四分钟没发出去 App 自己拿回来`() {
        val c = Clock()
        val q = box(ModuleModel.GM1003, c)
        q.offer("S1", OutboxClass.Emergency)
        q.markHanded("S1", c.now)
        assertEquals(0, q.size, "屏上那个数字该降下来")
        assertEquals(1, q.handedSize, "App 这份盯梢副本不能跟着降")

        q.sweep(c.now + FOUR_MIN - 1)
        assertEquals(0, q.size, "差一秒都还不到模组丢的时候，抢着重推只会占额度")

        // 模组**没有**报告丢弃。真机上它会不会给那条 SFI 还没确认（§9 #14），所以只能按型号猜
        q.sweep(c.now + FOUR_MIN)
        assertEquals(1, q.size, "不猜就等于让 SOS 静默消失（E.6）")
        assertEquals(0, q.handedSize)
        assertEquals(1, q.reclaimed)
        assertEquals(1, q.peek(c.now).first().requeuedTimes)
    }

    @Test
    fun `GM1001 没有超时机制，交出去的不能自己拿回来重推`() {
        val c = Clock()
        val q = box(ModuleModel.GM1001, c)
        q.offer("S1", OutboxClass.Emergency)
        q.markHanded("S1", c.now)
        q.sweep(c.now + 60 * 60_000)
        assertEquals(0, q.size, "FLASH 那份会自己等下次过境发出去，重推就是发两条一样的 SOS")
        assertEquals(1, q.handedSize)
    }

    @Test
    fun `掉电重推这件事方向按型号相反`() {
        val ram = box(ModuleModel.GM1003)
        ram.offer("S1", OutboxClass.Emergency)
        ram.markHanded("S1", 0)
        ram.onModulePowerLoss()
        assertEquals(1, ram.size, "RAM 队列掉电即空，不重推那些报文就真没了")

        val flash = box(ModuleModel.GM1001)
        flash.offer("S1", OutboxClass.Emergency)
        flash.markHanded("S1", 0)
        flash.onModulePowerLoss()
        assertEquals(0, flash.size, "FLASH 里那条还躺着，重推等于把同一条 SOS 再发一遍")
        assertEquals(1, flash.handedSize)
    }

    @Test
    fun `确认送达才真的撤掉盯梢`() {
        val c = Clock()
        val q = box(ModuleModel.GM1003, c)
        q.offer("S1", OutboxClass.Emergency)
        q.markHanded("S1", c.now)
        assertTrue(q.confirm("S1"))
        assertEquals(0, q.handedSize)
        q.sweep(c.now + FOUR_MIN)
        assertEquals(0, q.size, "SFI 成功过了还重推，就是让人收到两条一样的求救")
    }

    @Test
    fun `心跳这类遥测交出去就不用盯，SOS 才需要`() {
        val c = Clock()
        val q = box(ModuleModel.GM1003, c)
        q.offer("H1", OutboxClass.Telemetry)
        assertTrue(q.release("H1"))
        assertEquals(0, q.size)
        assertEquals(0, q.handedSize, "下一分钟就有一条新的盖过它，留着只是白占 50 个槽里的一个")
    }

    @Test
    fun `推给模组前先看剩余额度`() {
        val c = Clock()
        val q = box(ModuleModel.GM1003, c)
        assertEquals(50, q.slotsLeft())
        q.offer("S1", OutboxClass.Emergency)
        q.markHanded("S1", c.now)
        assertEquals(49, q.slotsLeft())

        // 数错了也不能让盯梢列表无限涨：超出容量的退回待推
        repeat(50) { i ->
            q.offer("X%02d".format(i), OutboxClass.Emergency)
            q.markHanded("X%02d".format(i), c.now)
        }
        assertEquals(50, q.handedSize, "已交出去的不会超过模组容量")
        assertEquals(0, q.slotsLeft())
        assertTrue(q.size > 0, "多出来的那条要回到待推，不能被丢掉")
    }

    @Test
    fun `读到 ICI 之前按最坏档排队，读到之后只换取值不重排`() {
        val c = Clock()
        val q = Outbox(caps = null, clock = c)
        assertEquals(50, q.policy.capacity, "没读到能力档就按 GM1003 那套最不利的设计")
        repeat(60) { i -> q.offer("T%03d".format(i), OutboxClass.Telemetry) }
        assertEquals(50, q.size)
        assertEquals(10, q.dropped)

        q.adopt(caps(ModuleModel.GM1001))
        assertEquals(500, q.policy.capacity)
        assertEquals(50, q.size, "已经挤掉的不会回来")
        q.offer("NEW", OutboxClass.Telemetry)
        assertEquals(51, q.size, "从现在起不再挤人了")
    }

    @Test
    fun `捡回来的报文按要紧程度重新排位`() {
        val c = Clock()
        val q = box(ModuleModel.GM1001, c)
        q.offer("S1", OutboxClass.Emergency)
        q.markHanded("S1", c.now)
        q.offer("H1", OutboxClass.Telemetry)
        q.reclaim(listOf("S1"))
        assertEquals(listOf("S1", "H1"), q.hexes, "SOS 回来了得排到心跳前面，别让它排在队尾等")
        assertEquals(1, q.reclaimed)
        assertFalse(q.handedSize > 0)
    }
}

/** GM1003 那条：存够 4 分钟没发出去就丢〔规 §3.1.13 注〕 */
private const val FOUR_MIN = 4 * 60_000L
