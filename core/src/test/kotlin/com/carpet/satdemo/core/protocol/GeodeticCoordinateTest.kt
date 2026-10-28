package com.carpet.satdemo.core.protocol

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 度分转换的测试值全部来自〔规 §3.1.7〕对字段宽度的定义：
 * 纬度 `ddmm.mmmm`、经度 `dddmm.mmmm`，且"前面的 0 也将被传输"。
 * 这里错的代价是"位置发到模组那偏了几十公里"，属于外场才发现的那类 bug。
 */
class GeodeticCoordinateTest {

    @Test
    fun `杭州坐标按规范宽度发出`() {
        val p = GeodeticPosition.of(latDeg = 30.2741, lonDeg = 120.1551)
        assertEquals("3016.4460", p.latitude.field)
        // 120°09.3060' —— 分的那个前导零不能丢
        assertEquals("12009.3060", p.longitude.field)
        assertEquals(listOf("3016.4460", "N", "12009.3060", "E"), p.tpiFields)
    }

    @Test
    fun `南纬西经给出 S 和 W 并且字段里不带负号`() {
        val p = GeodeticPosition.of(latDeg = -33.8688, lonDeg = -70.6693)
        assertEquals("3352.1280", p.latitude.field)
        assertEquals("S", p.latitude.hemisphere)
        assertEquals("07040.1580", p.longitude.field)
        assertEquals("W", p.longitude.hemisphere)
    }

    @Test
    fun `低纬度补零到两位`() {
        assertEquals("0005.4000", GeodeticLatitude(0.09).field)
        assertEquals("00005.4000", GeodeticLongitude(0.09).field)
    }

    @Test
    fun `十进制度往返误差不超过一厘米`() {
        for (lat in listOf(-89.9, -30.2741, 0.0, 0.0001, 30.2741, 60.0, 89.9999)) {
            for (lon in listOf(-179.9, -70.6693, 0.0, 8.5, 120.1551, 179.9999)) {
                val p = GeodeticPosition.of(lat, lon)
                val back = GeodeticPosition.fromTpiFields(
                    p.latitude.field, p.latitude.hemisphere,
                    p.longitude.field, p.longitude.hemisphere,
                )
                assertNotNull(back, "$lat,$lon 应该能解回来")
                // 4 位分 = 0.0001' ≈ 0.18 m，留一点余量
                kotlin.test.assertTrue(
                    kotlin.math.abs(back!!.latitude.degrees - lat) < 2e-6 &&
                        kotlin.math.abs(back.longitude.degrees - lon) < 2e-6,
                    "往返漂移过大: ($lat,$lon) -> $back",
                )
            }
        }
    }

    @Test
    fun `分进位到度不能发出六十分钟`() {
        // 29.9999999°：分四舍五入到 60.0000，必须进位成 3000.0000 而不是 2960.0000
        assertEquals("3000.0000", GeodeticLatitude(29.9999999).field)
    }

    @Test
    fun `中文 locale 下也不会把小数点写成逗号`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            // 这里的关键不是真的会出逗号（我们用的是 Locale.ROOT），
            // 而是**任何一次改动把 format 的 locale 拿掉就会坏**，所以钉住
            assertEquals("3016.4460", GeodeticPosition.of(30.2741, 120.1551).latitude.field)
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `越界与非法字段`() {
        assertFailsWith<IllegalArgumentException> { GeodeticLatitude(91.0) }
        assertFailsWith<IllegalArgumentException> { GeodeticLongitude(181.0) }
        assertFailsWith<IllegalArgumentException> { GeodeticLatitude(Double.NaN) }
        // 分钟 >= 60 的字段是坏数据，不猜
        assertNull(GeodeticPosition.fromTpiFields("3060.0000", "N", "12009.3060", "E"))
        assertNull(GeodeticPosition.fromTpiFields("30", "N", "12009.3060", "E"))
    }
}
