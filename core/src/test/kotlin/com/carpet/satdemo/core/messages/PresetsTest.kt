package com.carpet.satdemo.core.messages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 12 条预设短语是**和合作方对词的口径**（11-20 方案 §2.2 L3，逐字对齐自研 APP）。
 * 这张表一改就是口径一改，所以钉在测试里而不是评审记录里：谁就地润色，CI 当场拦住。
 */
class PresetsTest {

    @Test
    fun `十二条原话逐字在场且分四组`() {
        val texts = Presets.ALL.map { it.text }
        assertEquals(
            listOf(
                "卫星消息测试", "最新情况如何",
                "一切正常，请放心", "正在路上，请放心", "已到达目的地",
                "遇到危险，请求救援", "身体不适，急需救助", "被困住了，急需救援",
                "好的，收到", "好的，在原地等待", "好的，请加快救援", "好的，目前情况可控",
            ),
            texts,
        )
        assertEquals(mapOf(
            PresetGroup.Test to 2, PresetGroup.Report to 3,
            PresetGroup.Rescue to 3, PresetGroup.Reply to 4,
        ), Presets.ALL.groupingBy { it.group }.eachCount())
        assertEquals(12, Presets.ALL.map { it.id }.distinct().size)
        assertEquals(12, Presets.ORDERED_GROUPS.sumOf { Presets.of(it).size })
    }

    @Test
    fun `汉字按三字节记账这是剩余M字节那格的分子`() {
        // "一切正常，请放心" 8 个字全在 3 字节段 → 24 字节。UTF-8 口径变了，卡面上的字节账就得跟着变
        assertEquals(24, Presets.byId("report.ok")?.utf8Bytes)
        assertEquals(3, "，".toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `认短语整句优先包含其次头四字认不出不硬认`() {
        assertEquals("report.ok", Presets.match("一切正常，请放心")?.id)
        assertEquals("report.ok", Presets.match("帮我发一条一切正常，请放心")?.id)
        assertEquals("report.ok", Presets.match("发个一切正常过去")?.id)
        assertNull(Presets.match("嗯"))
        assertNull(Presets.match(""))
    }

    @Test
    fun `求救组文案不含平台承诺`() {
        // SosCopy.BANNED 同一把尺：我们自己的文案不许替平台许诺（"已在路上"这类）
        val banned = listOf("已送达", "已经在路上", "马上到", "一定")
        for (p in Presets.of(PresetGroup.Rescue)) {
            assertTrue(banned.none { p.text.contains(it) }, "${p.id} 的文案越线: ${p.text}")
        }
        assertNotNull(Presets.byId("rescue.danger"))
    }
}
