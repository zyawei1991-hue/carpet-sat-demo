package com.carpet.satdemo.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** ASR 响应解析：不引 JSON 库，手解就得用手测钉住（转写结果直接进对话，解错就问错问题） */
class SiliconFlowAsrTest {

    @Test
    fun `普通转写响应`() {
        assertEquals("有信号吗", SiliconFlowAsr.extractText("""{"text":"有信号吗"}"""))
    }

    @Test
    fun `text 字段带空格和转义`() {
        assertEquals("下次过境\n是什么", SiliconFlowAsr.extractText("""{ "id": "x", "text": "下次过境\n是什么", "m": 1 }"""))
        assertEquals("说\"你好\"", SiliconFlowAsr.extractText("""{"text":"说\"你好\""}"""))
        assertEquals("反斜杠\\一个", SiliconFlowAsr.extractText("""{"text":"反斜杠\\一个"}"""))
    }

    @Test
    fun `缺 text 或不是字符串时返回 null`() {
        assertNull(SiliconFlowAsr.extractText("""{"message":"bad"}"""))
        assertNull(SiliconFlowAsr.extractText("""{"text":null}"""))
        assertNull(SiliconFlowAsr.extractText("not json"))
        assertNull(SiliconFlowAsr.extractText("""{"text":123}"""))
    }
}
