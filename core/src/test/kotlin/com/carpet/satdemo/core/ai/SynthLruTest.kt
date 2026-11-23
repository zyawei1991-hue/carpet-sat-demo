package com.carpet.satdemo.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** §5.1-3 缓存的那半：按字节淘汰。App 侧挂在 android.media 上测不了，淘汰逻辑在这扫 */
class SynthLruTest {

    private fun entry(n: Int) = SynthLru.Entry(ByteArray(n), "edge")

    @Test
    fun `同一句命中 换音色不命中`() {
        val c = SynthLru(1024)
        c.put("额度快满。|1.0|1.0", entry(100))
        assertNotNull(c.get("额度快满。|1.0|1.0"))
        assertNull(c.get("额度快满。|1.2|1.0"))
    }

    @Test
    fun `超上限从最久没用的挤 装得下常用那几句`() {
        val c = SynthLru(250)
        c.put("a", entry(100))
        c.put("b", entry(100))
        c.get("a") // a 又被用了：再挤的时候先走 b
        c.put("c", entry(100))
        assertEquals(200L, c.totalBytes())
        assertNotNull(c.get("a"))
        assertNull(c.get("b"))
        assertNotNull(c.get("c"))
    }

    @Test
    fun `同键重写不算两份`() {
        val c = SynthLru(1024)
        c.put("a", entry(100))
        c.put("a", entry(150))
        assertEquals(150, c.totalBytes())
        assertEquals(1, c.size())
    }

    @Test
    fun `单条比整个缓存还大 收下又立刻挤掉 不越界`() {
        val c = SynthLru(100)
        c.put("big", entry(5_000))
        assertEquals(0L, c.totalBytes())
        assertNull(c.get("big"))
    }
}
