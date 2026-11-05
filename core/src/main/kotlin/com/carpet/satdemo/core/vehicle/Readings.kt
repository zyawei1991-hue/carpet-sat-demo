package com.carpet.satdemo.core.vehicle

/**
 * 一条车况读数是**哪来的**。
 *
 * 这一档存在的唯一理由：界面上那个「示意数据 · 未接真源」的标必须由数据自己说，
 * 不能由 `demo_mode` 那个开关说。挂在开关上有两种错法 —— 接了真源还带着标是长期说谎，
 * 演示时手一抖把没接真源的标关掉是事故。
 *
 * **读数轴和判据轴是两条**，各记各的档：OBD 读到的胎压是真的，比对的标定值还是示意的，
 * 界面上两个标都得露出来。合成一个"有没有真数据"的布尔，接上传感器那天就会把判据标一起关掉。
 */
enum class ValueSource(val real: Boolean, val badge: String?) {

    /** 我们为了演示编的值 */
    Stub(false, "示意数据 · 未接真源"),

    /** 车上真接了设备读上来的：OBD 盒子、独立胎压传感器 */
    Device(true, null),

    /** 车厂车联网平台给的。续航 / SOC / 总里程只有这条路是真的 */
    Cloud(true, null),

    /** 从别的读数算出来的。输入是桩，输出就不是真的 */
    Observed(false, "推算值 · 未接真源"),

    /** 车主自己填的。它不是测出来的，但它是问出来的 —— 这两种在界面上必须分得开 */
    User(true, "你填的"),

    /** 标定值（B 柱标签、手册上的额定值），还没拿到真资料 */
    Nominal(false, "示意判据 · 未接手册"),

    /** 保养手册的间隔，还没拿到真资料 */
    Manual(false, "示意判据 · 未接手册"),

    /** 第二阶段：车型养车知识库里的一条，带 docId + version + clause */
    Kb(true, null),

    /**
     * 既不是测出来的、也不是手册说的，是**我们自己定的产品选择**（提醒比例、槽位阈值这类）。
     * 它不需要挂「示意」那个标 —— 但我们也不许把它写成标定值。
     */
    Chosen(true, null),
}

enum class ReadingKey(val label: String, val unit: String) {
    RangeKm("剩余续航", "km"),
    FuelFraction("油量", "箱"),
    TireFl("左前胎压", "bar"),
    TireFr("右前胎压", "bar"),
    TireRl("左后胎压", "bar"),
    TireRr("右后胎压", "bar"),
    OdometerKm("累计里程", "km"),

    /** 连续多少天没动过车。由里程历史算，不是车上直接有的数 */
    IdleDays("连续未行驶", "天"),

    LastServiceKm("上次保养时里程", "km"),
    LastServiceDays("上次保养距今", "天"),
    FilterDays("上次换空调滤芯距今", "天"),
    QuotaWaiting("待发条数", "条"),
    QuotaCapacity("额度槽位", "条"),

    /** 下面两条是 0/1 的开关量，走同一个读数模型是为了让"每条建议都得有依据"这一条只有一种写法 */
    HasFirstAidKit("车上有急救包", ""),
    HasChild("车上有孩子", ""),
    ;

    val isTire: Boolean get() = this == TireFl || this == TireFr || this == TireRl || this == TireRr
}

val TIRE_KEYS = listOf(ReadingKey.TireFl, ReadingKey.TireFr, ReadingKey.TireRl, ReadingKey.TireRr)

data class Reading(
    val key: ReadingKey,
    val value: Double,
    val source: ValueSource,
    /** 什么时候取的。趋势类判据没有这个时间就不能算，不许用 System.currentTimeMillis() 现场补一个 */
    val takenAtMs: Long,
)

/**
 * 一帧车况。缺哪条就是缺哪条 —— **缺读数的规则整条消失，不许它输出"数据不足"占一行**。
 *
 * 这条是刻意的：一条说不准的建议和一条没有的建议，在界面上的代价不一样。
 * 前者会把真建议的信任一起拉下去，后者只是少一行。
 */
class ReadingBoard(readings: List<Reading>) {

    private val byKey: Map<ReadingKey, Reading> = readings.associateBy { it.key }

    fun of(key: ReadingKey): Reading? = byKey[key]

    fun value(key: ReadingKey): Double? = byKey[key]?.value

    fun missing(keys: List<ReadingKey>): Boolean = keys.any { byKey[it] == null }

    fun evidence(keys: List<ReadingKey>): List<Reading> = keys.mapNotNull { byKey[it] }

    /** 这一组里有没有一条是真源读上来的。决定卡片上打不打那个标 */
    fun anyReal(keys: List<ReadingKey>): Boolean = evidence(keys).any { it.source.real }

    /** 这一组读数的来源标：全真就不打，掺了假就照最不真的那一档说 */
    fun badge(keys: List<ReadingKey>): String? {
        val fake = evidence(keys).firstOrNull { !it.source.real } ?: return null
        return fake.source.badge
    }
}
