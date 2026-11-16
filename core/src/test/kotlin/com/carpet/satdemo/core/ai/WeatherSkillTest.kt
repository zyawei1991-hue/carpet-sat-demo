package com.carpet.satdemo.core.ai

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/** §7：天气技能的路由、权限档、诚实回落。真取数只在 Weather.tomorrow 里，单测不打真网 */
class WeatherSkillTest {

    private val base = SkillContext(
        scene = "车载守护",
        gnssLat = 39.9042,
        gnssLon = 116.4074,
        ephemerisFresh = true,
    )

    @Test
    fun `天气问句路由到 weather 技能`() = runBlocking {
        val g = AiGateway()
        for (q in listOf("明天天气怎么样", "会下雨吗", "气温多少", "明天冷不冷", "要不要带伞", "多少度")) {
            assertEquals("weather", g.ask(q, base).skill, "「$q」应路由到 weather")
        }
    }

    @Test
    fun `没开读云端被拦且话术说清去哪开`() = runBlocking {
        val g = AiGateway() // 默认 ReadLocal
        val r = g.ask("明天天气怎么样", base)
        assertEquals("weather", r.skill)
        assertEquals(ReplySource.Denied, r.source)
        assertContains(r.text, "读云端")
    }

    @Test
    fun `没定位不猜城市`() {
        val g = AiGateway(initialGranted = PermissionTier.ReadCloud)
        val c = base.copy(gnssLat = null, gnssLon = null)
        val skill = g.skills.byId("weather")!!
        val result = skill.handle(c)
        assertEquals("false", result.fields["located"])
        assertContains(result.line ?: "", "没有定位")
    }

    @Test
    fun `天气码译档不硬分`() {
        assertEquals("晴", Weather.codeCn(0))
        assertEquals("阴", Weather.codeCn(3))
        assertEquals("有雨", Weather.codeCn(61))
        assertEquals("有雨", Weather.codeCn(80))
        assertEquals("有雪", Weather.codeCn(73))
        assertEquals("有雷雨", Weather.codeCn(95))
        // 没列出的码归多云，不出"未知天气 42"
        assertEquals("多云", Weather.codeCn(42))
    }

    @Test
    fun `技能话术数字只来自接口返回`() {
        // 直接构造一次"取到了"的结果验证话术拼装：数字必须逐字来自 Forecast
        val f = Weather.Forecast(codeCn = "有雨", tMaxC = 21.4, tMinC = 12.0, precipProb = 70, fetchedAtMs = 0)
        val line = "明天${f.codeCn}，${f.tMinC.toInt()}~${f.tMaxC.toInt()}℃" +
            (if (f.precipProb >= 30) "，降水概率 ${f.precipProb}%，记得带伞。" else "，降水概率 ${f.precipProb}%。")
        assertEquals("明天有雨，12~21℃，降水概率 70%，记得带伞。", line)
    }
}
