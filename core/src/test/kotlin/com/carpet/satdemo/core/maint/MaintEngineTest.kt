package com.carpet.satdemo.core.maint

import com.carpet.satdemo.core.vehicle.Reading
import com.carpet.satdemo.core.vehicle.ReadingBoard
import com.carpet.satdemo.core.vehicle.ReadingKey
import com.carpet.satdemo.core.vehicle.TIRE_KEYS
import com.carpet.satdemo.core.vehicle.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 规则引擎。**这一份钉的不是算得对不对，是"每条建议都说得出自己凭什么"**：
 *  1. 没有读数的建议构造不出来（[Suggestion] 里那道 require，比注释里的约定可靠）；
 *  2. 缺读数时那条规则**整条消失**，而不是变成一行「数据不足」占着界面 ——
 *     一条说不准的建议会把同屏真建议的信任一起拉下去；
 *  3. 「示意」那个标由读数自己的来源决定，不由演示开关决定。接上传感器它自己就该没。
 */
class MaintEngineTest {

    private val now = 1_795_200_400_000L

    /** 判据表和手机上那份一致：标定与间隔现在都还没有手册资料 */
    private val c = Criteria(
        tireNominal = Param(2.5, ValueSource.Nominal),
        tireDrop = Param(0.2, ValueSource.Nominal),
        serviceEveryKm = Param(10_000.0, ValueSource.Manual),
        serviceEveryDays = Param(365.0, ValueSource.Manual),
        filterEveryDays = Param(365.0, ValueSource.Manual),
        idleDays = Param(7.0, ValueSource.Manual),
        quotaRemainRatio = Param(0.20, ValueSource.Chosen),
    )

    private fun r(key: ReadingKey, v: Double, src: ValueSource = ValueSource.Stub) =
        Reading(key, v, src, now)

    /** 一帧"所有规则都该开口"的车况 */
    private fun full(src: ValueSource = ValueSource.Stub) = ReadingBoard(
        listOf(
            r(ReadingKey.OdometerKm, 23_417.0, src),
            r(ReadingKey.LastServiceKm, 13_600.0, ValueSource.User),
            r(ReadingKey.LastServiceDays, 380.0, ValueSource.User),
            r(ReadingKey.FilterDays, 400.0, ValueSource.User),
            r(ReadingKey.IdleDays, 9.0, ValueSource.Observed),
            r(ReadingKey.QuotaCapacity, 500.0, ValueSource.Device),
            r(ReadingKey.QuotaWaiting, 480.0, ValueSource.Device),
            r(ReadingKey.HasFirstAidKit, 0.0, ValueSource.User),
            r(ReadingKey.HasChild, 0.0, ValueSource.User),
        ) + TIRE_KEYS.map { r(it, 2.2, src) },
    )

    @Test
    fun `every suggestion carries the readings it was computed from`() {
        for (sg in MaintEngine.suggest(full(), c)) {
            assertTrue(sg.evidence.isNotEmpty(), "${sg.rule} 没有读数就不该出现")
            assertNotNull(sg.judgement.source, "${sg.rule} 的判据要能说清是谁定的")
            assertTrue(sg.headline.isNotBlank() && sg.detail.isNotBlank(), "${sg.rule} 得说得出做什么和为什么")
        }
    }

    /** 这条是产品主张的代码形态：写成注释里的约定，赶工那天最先失效 */
    @Test
    fun `a suggestion without evidence cannot be constructed`() {
        assertFailsWith<IllegalArgumentException> {
            Suggestion(
                rule = "made.up", code = 9, headline = "换四条胎", detail = "随便说说",
                judgement = Judgement("没有判据", ValueSource.Manual),
                evidence = emptyList(), severity = Severity.Due,
            )
        }
    }

    @Test
    fun `no suggestion at all when the car is fine`() {
        val quiet = ReadingBoard(
            listOf(
                r(ReadingKey.OdometerKm, 20_000.0),
                r(ReadingKey.LastServiceKm, 19_000.0, ValueSource.User),
                r(ReadingKey.LastServiceDays, 30.0, ValueSource.User),
                r(ReadingKey.FilterDays, 40.0, ValueSource.User),
                r(ReadingKey.IdleDays, 1.0, ValueSource.Observed),
                r(ReadingKey.QuotaCapacity, 500.0, ValueSource.Device),
                r(ReadingKey.QuotaWaiting, 10.0, ValueSource.Device),
                r(ReadingKey.HasFirstAidKit, 1.0, ValueSource.User),
                r(ReadingKey.HasChild, 0.0, ValueSource.User),
            ) + TIRE_KEYS.map { r(it, 2.5) },
        )
        assertEquals(emptyList(), MaintEngine.suggest(quiet, c))
    }

    @Test
    fun `missing tire readings drops the whole rule instead of a data-not-enough line`() {
        val noTires = ReadingBoard(
            listOf(
                r(ReadingKey.OdometerKm, 23_417.0),
                r(ReadingKey.LastServiceKm, 13_600.0, ValueSource.User),
                r(ReadingKey.FilterDays, 400.0, ValueSource.User),
            ),
        )
        val out = MaintEngine.suggest(noTires, c)
        assertTrue(out.none { it.rule == "tire.low" }, "没测到胎压就不该有补气这条")
        assertTrue(out.none { it.detail.contains("数据不足") })
    }

    @Test
    fun `a tire twice as far below nominal is due, a small gap is only soon`() {
        fun tires(vararg p: Double) = ReadingBoard(
            TIRE_KEYS.mapIndexed { i, k -> r(k, p[i]) },
        )
        assertEquals(Severity.Soon, MaintEngine.suggest(tires(2.25, 2.5, 2.5, 2.5), c).single().severity)
        assertEquals(Severity.Due, MaintEngine.suggest(tires(2.0, 2.5, 2.5, 2.5), c).single().severity)
    }

    @Test
    fun `service comes due by whichever of mileage and months arrives first`() {
        val byMileage = ReadingBoard(
            listOf(r(ReadingKey.OdometerKm, 26_000.0), r(ReadingKey.LastServiceKm, 15_000.0, ValueSource.User), r(ReadingKey.LastServiceDays, 100.0, ValueSource.User)),
        )
        val byTime = ReadingBoard(
            listOf(r(ReadingKey.OdometerKm, 16_000.0), r(ReadingKey.LastServiceKm, 15_000.0, ValueSource.User), r(ReadingKey.LastServiceDays, 400.0, ValueSource.User)),
        )
        assertEquals(Severity.Due, MaintEngine.suggest(byMileage, c).single().severity)
        assertEquals("service.due", MaintEngine.suggest(byTime, c).single().rule)
        assertTrue(MaintEngine.suggest(byMileage, c).single().detail.startsWith("距上次 11"), "里程那条要说的是跑了多少，不是总里程")
    }

    /**
     * 判据存的是**天**，界面上念的是**月**。这一条是被截图逼出来的：
     * 第一版把 365 直接印成「手册间隔 10,000 km 或 365 个月」，而「判据」那一行正是这一卡
     * 存在的理由 —— 单位写错的出处行，比没有出处更糟。
     */
    @Test
    fun `the judgement line converts days to months instead of printing a raw day count`() {
        val board = ReadingBoard(
            listOf(r(ReadingKey.OdometerKm, 26_000.0), r(ReadingKey.LastServiceKm, 15_000.0, ValueSource.User), r(ReadingKey.FilterDays, 400.0, ValueSource.User)),
        )
        val out = MaintEngine.suggest(board, c)
        val service = out.first { it.rule == "service.due" }.judgement.text
        val filter = out.first { it.rule == "cabin.filter" }.judgement.text
        assertTrue(service.contains("12 个月"), "保养判据要念成 12 个月：$service")
        assertTrue(filter.contains("12 个月"), "滤芯判据要念成 12 个月：$filter")
        assertTrue(!service.contains("365") && !filter.contains("365"), "判据行里不许出现裸的天数：$service ｜ $filter")
    }

    @Test
    fun `an odometer below the entered service mileage says nothing`() {
        // 车主填错了就不猜哪个对。猜出来的建议是把自己的数编进车主的车况里
        val flipped = ReadingBoard(
            listOf(r(ReadingKey.OdometerKm, 10_000.0), r(ReadingKey.LastServiceKm, 23_000.0, ValueSource.User)),
        )
        assertTrue(MaintEngine.suggest(flipped, c).none { it.rule == "service.due" })
    }

    @Test
    fun `a child on board pulls the cabin filter forward and tags it for the family page`() {
        val base = listOf(
            r(ReadingKey.FilterDays, 350.0, ValueSource.User),
            r(ReadingKey.HasChild, 1.0, ValueSource.User),
        )
        val sg = MaintEngine.suggest(ReadingBoard(base), c).single()
        assertEquals("cabin.filter", sg.rule)
        assertEquals("kids", sg.sceneKey)
        assertFalse(sg.severity == Severity.Note, "孩子常坐的车，滤芯临期不该只当知会")
    }

    @Test
    fun `quota rule stays silent until the terminal reports a capacity`() {
        val noCard = ReadingBoard(listOf(r(ReadingKey.IdleDays, 30.0, ValueSource.Observed)))
        assertTrue(MaintEngine.suggest(noCard, c).none { it.rule == "quota.pack" })
        val low = ReadingBoard(
            listOf(r(ReadingKey.QuotaCapacity, 500.0, ValueSource.Device), r(ReadingKey.QuotaWaiting, 490.0, ValueSource.Device)),
        )
        assertNotNull(MaintEngine.suggest(low, c).singleOrNull { it.rule == "quota.pack" })
    }

    @Test
    fun `badge follows the reading, not the demo switch`() {
        // 桩胎压：建议带「示意数据」。换成设备读数：那个标自己没了，判据的标还在
        val stubbed = MaintEngine.suggest(full(ValueSource.Stub), c).single { it.rule == "tire.low" }
        assertEquals("示意数据 · 未接真源", stubbed.badge)
        val real = MaintEngine.suggest(full(ValueSource.Device), c).single { it.rule == "tire.low" }
        assertNull(real.badge, "接上真源还带着示意标，就是长期说谎")
        // 两条轴各自独立：读数是假的，判据也是假的，界面上得同时看得见
        assertEquals("示意判据 · 未接手册", c.badge)
    }

    @Test
    fun `asked and answer sorted by severity, not by the order rules were written`() {
        val out = MaintEngine.suggest(full(), c)
        val key = { sg: Suggestion -> sg.severity.ordinal * 100 + sg.code }
        assertEquals(out.map(key).sorted(), out.map(key), "先按严重度、再按工单码。加一条新规则不该打乱车主已经习惯的那一屏")
        assertEquals(Severity.Due, out.first().severity)
        assertEquals(6, out.size, "六条规则在满帧下都该开口")
    }

    @Test
    fun `an answer the owner gave is not stub data`() {
        // 「你填的」是真的问出来的，不是测出来的 —— 但它绝不能被打成桩，
        // 否则车主下一次就不填了，而我们恰恰最需要他填的那几项
        val sg = MaintEngine.suggest(full(ValueSource.Device), c).single { it.rule == "kit.none" }
        assertNull(sg.badge)
        assertEquals("你填的", sg.judgement.source.badge)
    }
}
