package com.carpet.satdemo.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 这两个向量是 `edge_tts` 7.3.1 的 `generate_sec_ms_gec_v2(unix_ts)` 亲手算出来的。
 * 算法移植最怕"看着对、差一位"，所以钉死两个时间点：一个早期、一个近期。
 */
class EdgeTokenTest {
    @Test
    fun matchesUpstreamVectors() {
        assertEquals(
            "2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2",
            EdgeToken.generate(1_700_000_000L),
        )
        assertEquals(
            "B029A7426816F097-4579D112DC47FC3BF432B499C0A282F427C659474AF9BF5FA33CE2C003F9E9C2",
            EdgeToken.generate(1_750_000_000L),
        )
    }

    /** 令牌是 5 分钟一档：同一档内换秒数，值必须一模一样（否则每句都要重握手） */
    @Test
    fun quantizesToFiveMinuteBuckets() {
        val base = 1_750_000_000L
        val bucketStart = base - base % 300
        assertEquals(EdgeToken.generate(base), EdgeToken.generate(bucketStart))
        assertEquals(EdgeToken.generate(base), EdgeToken.generate(bucketStart + 299))
        assertTrue(EdgeToken.generate(base) != EdgeToken.generate(bucketStart + 300))
    }

    @Test
    fun shapeIsSixteenDashSixtyFourUpperHex() {
        val token = EdgeToken.generate(1_760_000_000L)
        val parts = token.split('-')
        assertEquals(2, parts.size)
        assertEquals(16, parts[0].length)
        assertEquals(64, parts[1].length)
        val hex = Regex("[0-9A-F]+")
        assertTrue(hex.matches(parts[0]), "CS64 半段只允许大写十六进制：${parts[0]}")
        assertTrue(hex.matches(parts[1]), "摘要半段只允许大写十六进制：${parts[1]}")
    }
}
