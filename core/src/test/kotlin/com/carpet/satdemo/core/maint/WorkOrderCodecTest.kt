package com.carpet.satdemo.core.maint

import com.carpet.satdemo.core.protocol.MessageBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 那一帧 21 字节的工单。
 *
 * 这里钉的是**发出去的东西自己解得回来**：星上走的只有索引码，
 * 全文留在手机里。所以一旦编解码两边对不上，界面上"已发出"和接收台"收到 3 项"
 * 就是两件事 —— 而这两件事在演示现场没法互相核对。
 */
class WorkOrderCodecTest {

    private val order = WorkOrder(
        year = 2026, month = 11, day = 5,
        vin6 = "882137", odometerKm = 23_417,
        items = listOf(WorkItem(1, Severity.Due), WorkItem(2, Severity.Soon), WorkItem(6, Severity.Note)),
    )

    @Test
    fun `the frame is 21 bytes and fits the smallest budget`() {
        val bytes = WorkOrderCodec.encode(order)
        assertEquals(WorkOrderCodec.BYTES, bytes.size)
        // **用真预算算，不许在这里出现常数**：等级换成 5（1900）时这条不该跟着改
        assertTrue(WorkOrderCodec.fits(MessageBudget.WORST_KNOWN), "L1 最坏口径下装不进一片，这套编码就不成立")
    }

    @Test
    fun `what we send is what the console gets back`() {
        val d = WorkOrderCodec.decode(WorkOrderCodec.encode(order))
        assertEquals(order.year, d.year)
        assertEquals(order.month, d.month)
        assertEquals(order.day, d.day)
        assertEquals(order.vin6, d.vin6)
        assertEquals(order.odometerKm, d.odometerKm)
        assertEquals(order.items, d.items)
    }

    @Test
    fun `empty slots do not decode as items`() {
        val one = order.copy(items = listOf(WorkItem(3, Severity.Note)))
        val d = WorkOrderCodec.decode(WorkOrderCodec.encode(one))
        assertEquals(1, d.items.size, "八个槽里空着的那几格不是待办项")
        assertEquals(3, d.items.single().code)
    }

    @Test
    fun `a frame that is not a work order is refused, not guessed`() {
        val bytes = WorkOrderCodec.encode(order)
        bytes[0] = 0x02                            // SOS 那套骨架的 tag
        assertFailsWith<IllegalArgumentException> { WorkOrderCodec.decode(bytes) }
        assertFailsWith<IllegalArgumentException> { WorkOrderCodec.decode(bytes.copyOfRange(0, 12)) }
        val badVersion = WorkOrderCodec.encode(order).also { it[1] = 0x09 }
        assertFailsWith<IllegalArgumentException> { WorkOrderCodec.decode(badVersion) }
    }

    @Test
    fun `every rule the engine can raise has a code and a name`() {
        // 码表和规则是一体两面：加了规则忘了加码，接收台会念出"未知项 0"
        assertEquals(setOf(1, 2, 3, 4, 5, 6), MaintCodes.names.keys)
        for ((code, name) in MaintCodes.names) assertEquals(name, MaintCodes.name(code))
        assertEquals("未知项 9", MaintCodes.name(9))
    }

    @Test
    fun `the frame does not grow with the wording`() {
        // 全文留在手机里，星上只走索引码。这条钉的是"改成发文本"这个决定的代价还在账上：
        // 措辞再长，帧也还是 21 字节；一旦有人把建议原文塞进载荷，L1 那一档立刻装不下
        val full = order.copy(items = (1..WorkOrderCodec.MAX_ITEMS).map { WorkItem(it, Severity.Note) })
        val bytes = WorkOrderCodec.encode(full)
        assertEquals(WorkOrderCodec.BYTES, bytes.size)
        assertTrue(bytes.size <= MessageBudget.WORST_KNOWN.chunkBytes)
        assertEquals(WorkOrderCodec.MAX_ITEMS, WorkOrderCodec.decode(bytes).items.size)
    }

    @Test
    fun `a work order that cannot fit the frame is refused at construction`() {
        assertFailsWith<IllegalArgumentException> {
            order.copy(items = (1..9).map { WorkItem(it.coerceAtMost(6), Severity.Note) })
        }
        assertFailsWith<IllegalArgumentException> { order.copy(vin6 = "88213a") }
        assertFailsWith<IllegalArgumentException> { order.copy(odometerKm = 1_234_567) }
    }

    @Test
    fun `describe reads back as something a person can check`() {
        val text = WorkOrderCodec.describe(WorkOrderCodec.decode(WorkOrderCodec.encode(order)))
        assertTrue(text.contains("2026-11-05"), text)
        assertTrue(text.contains("VIN…882137"), text)
        assertTrue(text.contains(MaintCodes.name(1)), text)
        assertTrue(text.contains("到点"), text)
    }
}
