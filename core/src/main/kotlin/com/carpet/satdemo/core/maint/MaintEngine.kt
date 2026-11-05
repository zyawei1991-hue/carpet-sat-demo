package com.carpet.satdemo.core.maint

import com.carpet.satdemo.core.vehicle.Reading
import com.carpet.satdemo.core.vehicle.ReadingBoard
import com.carpet.satdemo.core.vehicle.ReadingKey
import com.carpet.satdemo.core.vehicle.TIRE_KEYS
import com.carpet.satdemo.core.vehicle.ValueSource

enum class Severity(val label: String) {
    Due("到点"), Soon("临期"), Note("知会"),
}

/** 一个判据数值 + 它自己的来源。胎压标定 2.5 是示意的还是 B 柱上抄下来的，在这里分开记 */
data class Param(val value: Double, val source: ValueSource)

/** 界面上那行小字：判据本身 */
data class Judgement(val text: String, val source: ValueSource)

/**
 * 一条建议。
 *
 * **`evidence` 为空构造不出来** —— 这条在产品上是主张（"回溯不到读数的话不许出现在界面上"），
 * 在代码里就是下面那道 `require`。写成注释里的约定，赶工那天最先失效；写成 require，
 * 单元测试和演示现场都会当场叫。
 */
data class Suggestion(
    val rule: String,
    val code: Int,
    val headline: String,
    val detail: String,
    val judgement: Judgement,
    val evidence: List<Reading>,
    val severity: Severity,
    /** 这条属于哪个场景页。家人页只捞带 kids 的那条，见 docs/DECISIONS.md D11 */
    val sceneKey: String? = null,
) {
    init {
        require(evidence.isNotEmpty()) { "$rule 没有读数就不许出建议：$headline" }
        require(code in 1..63) { "$rule 的工单码要塞进 6 位：$code" }
    }

    /** 建议本身打不打标：掺了假读数就照最不真的那一档说 */
    val badge: String? get() = evidence.firstOrNull { !it.source.real }?.source?.badge
}

/** 判据表。第一版全部是示意值，来源标会一路跟着它们，直到 §11 ◆1 那份手册进来 */
data class Criteria(
    val tireNominal: Param,
    /** 比标定低多少开始提醒 */
    val tireDrop: Param,
    val serviceEveryKm: Param,
    val serviceEveryDays: Param,
    val filterEveryDays: Param,
    val idleDays: Param,
    /** 额度还剩多少比例就提醒加包 */
    val quotaRemainRatio: Param,
) {
    /**
     * 这张判据表自己打的标。**和读数的标是两条独立的轴**：OBD 接上传胎压变真了，
     * 比对的标定值还是示意的，两个标得同时挂在界面上。手册资料进来那天这一句自己变 null。
     */
    val badge: String?
        get() = listOf(
            tireNominal, tireDrop, serviceEveryKm, serviceEveryDays, filterEveryDays, idleDays, quotaRemainRatio,
        ).firstOrNull { !it.source.real }?.source?.badge
}

/**
 * 该做的事。规则引擎，全本地，没有网络调用。
 *
 * 六条规则都是"缺读数就整条返回 null"，不是"返回一条说不准的建议"。
 * 排序按严重度、再按工单码 —— **不按我们写代码的顺序**，否则加一条新规则会打乱车主已经习惯的那一屏。
 */
object MaintEngine {

    fun suggest(board: ReadingBoard, c: Criteria): List<Suggestion> =
        listOfNotNull(
            tireLow(board, c),
            serviceDue(board, c),
            cabinFilter(board, c),
            idleWeek(board, c),
            quotaPack(board, c),
            firstAidKit(board, c),
        ).sortedWith(compareBy({ it.severity.ordinal }, { it.code }))

    /** #1 胎压偏低。只报读数与标定差，不下诊断结论 —— 我们没测胎面 */
    private fun tireLow(board: ReadingBoard, c: Criteria): Suggestion? {
        if (board.missing(TIRE_KEYS)) return null
        val nominal = c.tireNominal.value
        val drop = c.tireDrop.value
        val lows = TIRE_KEYS.mapNotNull { board.of(it) }.filter { nominal - it.value >= drop }
        if (lows.isEmpty()) return null
        val worst = lows.maxByOrNull { nominal - it.value }!!
        val gap = nominal - worst.value
        return Suggestion(
            rule = "tire.low",
            code = 1,
            headline = "补气到 %.1f bar".format(nominal),
            detail = "${worst.key.label} %.1f bar，比标定低 %.1f".format(worst.value, gap),
            judgement = Judgement("标定 %.1f bar，低 %.1f 以上提醒".format(nominal, drop), c.tireNominal.source),
            evidence = lows,
            severity = if (gap >= drop * 2) Severity.Due else Severity.Soon,
        )
    }

    /** #2 常规保养到期。里程和时间两条都算，先到为准 */
    private fun serviceDue(board: ReadingBoard, c: Criteria): Suggestion? {
        val odo = board.of(ReadingKey.OdometerKm) ?: return null
        val lastKm = board.of(ReadingKey.LastServiceKm) ?: return null
        if (odo.value < lastKm.value) return null      // 填反了就不说话，不猜哪个对
        val since = odo.value - lastKm.value
        val days = board.value(ReadingKey.LastServiceDays)
        val byKm = since >= c.serviceEveryKm.value
        val byTime = days != null && days >= c.serviceEveryDays.value
        if (!byKm && !byTime && since < c.serviceEveryKm.value * 0.95) return null
        return Suggestion(
            rule = "service.due",
            code = 2,
            headline = "做常规保养",
            // 第二行只说读数，阈值归第三行那句判据：两句都写「手册 10,000 km」是同一件事说两遍
            detail = "距上次 %,d km".format(since.toInt()),
            judgement = Judgement(
                // 判据存的是天，念出来的是月：把 365 直接印成「365 个月」是错的单位，而这一行是"出处"那一行
                "手册间隔 %,d km 或 %d 个月，先到为准".format(
                    c.serviceEveryKm.value.toInt(), (c.serviceEveryDays.value / 30).toInt(),
                ), c.serviceEveryKm.source,
            ),
            evidence = listOf(odo, lastKm),
            severity = if (byKm || byTime) Severity.Due else Severity.Soon,
        )
    }

    /** #3 空调滤芯。车上有孩子时上调一档 —— 这是唯一一条跨场景的联动 */
    private fun cabinFilter(board: ReadingBoard, c: Criteria): Suggestion? {
        val days = board.of(ReadingKey.FilterDays) ?: return null
        if (days.value < c.filterEveryDays.value * 0.9) return null
        val kid = board.value(ReadingKey.HasChild) == 1.0
        val due = days.value >= c.filterEveryDays.value
        return Suggestion(
            rule = "cabin.filter",
            code = 3,
            headline = "换空调滤芯",
            detail = "上次换是 %,d 天前".format(days.value.toInt()),
            judgement = Judgement(
                "手册 %d 个月，车上有孩子建议提前".format((c.filterEveryDays.value / 30).toInt()),
                c.filterEveryDays.source,
            ),
            evidence = listOf(days),
            severity = when {
                due && kid -> Severity.Due
                due || kid -> Severity.Soon
                else -> Severity.Note
            },
            sceneKey = if (kid) "kids" else null,
        )
    }

    /** #4 长期停放。这条会被 OBD 盒子漏电污染，所以采购要求里写了必须有 ACC 休眠 */
    private fun idleWeek(board: ReadingBoard, c: Criteria): Suggestion? {
        val idle = board.of(ReadingKey.IdleDays) ?: return null
        if (idle.value < c.idleDays.value) return null
        return Suggestion(
            rule = "idle.week",
            code = 4,
            // 六个字：原文「一周开一次，十分钟」在字号 1.3 那一档断成「十分 / 钟」，一行装不下的事就别写成一行
            headline = "每周开十分钟",
            detail = "已经 %,d 天没动车".format(idle.value.toInt()),
            judgement = Judgement("连续 %d 天未行驶".format(c.idleDays.value.toInt()), c.idleDays.source),
            evidence = listOf(idle),
            severity = Severity.Note,
        )
    }

    /** #5 卫星额度包。六条里唯一一条读数本来是真的的 —— 额度是终端型号口径报上来的 */
    private fun quotaPack(board: ReadingBoard, c: Criteria): Suggestion? {
        val cap = board.value(ReadingKey.QuotaCapacity) ?: return null
        val waiting = board.value(ReadingKey.QuotaWaiting) ?: return null
        if (cap <= 0.0) return null      // 没读到型号就不说，"说不准"不是建议
        val remain = (cap - waiting) / cap
        if (remain > c.quotaRemainRatio.value) return null
        return Suggestion(
            rule = "quota.pack",
            code = 5,
            headline = "加卫星额度包",
            detail = "槽位 %,d 条，待发 %,d 条，剩 %.0f%%".format(cap.toInt(), waiting.toInt(), remain * 100),
            judgement = Judgement("剩不到 %.0f%% 提醒".format(c.quotaRemainRatio.value * 100), c.quotaRemainRatio.source),
            evidence = listOfNotNull(board.of(ReadingKey.QuotaCapacity), board.of(ReadingKey.QuotaWaiting)),
            severity = Severity.Soon,
        )
    }

    /** #6 车载应急包。不是推销：呼救发得出去、车上没有能用的东西，这是产品自己的缺环 */
    private fun firstAidKit(board: ReadingBoard, c: Criteria): Suggestion? {
        val kit = board.of(ReadingKey.HasFirstAidKit) ?: return null
        if (kit.value != 0.0) return null
        return Suggestion(
            rule = "kit.none",
            code = 6,
            headline = "车上配一个应急包",
            detail = "你填的是「车上没有急救包」",
            judgement = Judgement("呼救发得出去，车上得有能用的东西", ValueSource.User),
            evidence = listOf(kit),
            severity = Severity.Note,
        )
    }
}
