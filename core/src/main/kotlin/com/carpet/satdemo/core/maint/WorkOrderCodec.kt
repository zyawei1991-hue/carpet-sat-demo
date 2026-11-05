package com.carpet.satdemo.core.maint

import com.carpet.satdemo.core.protocol.MessageBudget

/**
 * 工单码表。
 *
 * 这张表存在的理由只有一个：**发出去的是码，不是话**。L1 档按最坏口径一片只剩 24 字节，
 * 一条中文清单少说 150 字节，得拆 7 片，而分片是演示现场最容易掉的一环。
 * 所以人读的那份全文留在手机上，星上只走这 21 字节的索引。
 *
 * 代价要说清楚：接收方必须拿着同一张表才解得出来，**所以它是我们的彩排台，不是真门店**。
 * 界面上不许出现"已发送给门店"，写的必须是「已发出 · 接收方：演示接收台」。
 */
object MaintCodes {
    val names: Map<Int, String> = mapOf(
        1 to "补气到标定胎压",
        2 to "做常规保养",
        3 to "换空调滤芯",
        4 to "长期停放，一周开一次",
        5 to "加卫星额度包",
        6 to "配车载应急包",
    )

    fun name(code: Int): String = names[code] ?: "未知项 $code"
}

data class WorkItem(val code: Int, val severity: Severity)

data class WorkOrder(
    val year: Int,
    val month: Int,
    val day: Int,
    /** 车架号后 6 位。**刻意只放后 6 位**：里程 + 完整 VIN 是可识别信息，工单不需要那么多 */
    val vin6: String,
    val odometerKm: Int,
    val items: List<WorkItem>,
) {
    init {
        require(vin6.length == 6 && vin6.all { it.isDigit() }) { "VIN 后 6 位要是纯数字：$vin6" }
        require(items.size <= WorkOrderCodec.MAX_ITEMS) { "一帧装不下 ${items.size} 项" }
        require(odometerKm in 0..MAX_ODO) { "里程超出 4 字节：$odometerKm" }
    }

    companion object {
        const val MAX_ODO = 999_999
    }
}

/** 解出来的那一半 —— 没有措辞，措辞在码表里 */
data class DecodedOrder(
    val year: Int,
    val month: Int,
    val day: Int,
    val vin6: String,
    val odometerKm: Int,
    val items: List<WorkItem>,
)

object WorkOrderCodec {

    /** 帧类型。SOS 那套 EXT 骨架不改，只多这一个取值 */
    const val TAG = 0x03
    const val VERSION = 0x01

    /** tag + ver + vin6(BCD) + 里程 + 日期 + 8 个槽位 */
    const val BYTES = 21
    const val MAX_ITEMS = 8

    private const val ODO_OFF = 5
    private const val DATE_OFF = 9
    private const val ITEMS_OFF = 13

    fun encode(o: WorkOrder): ByteArray {
        val b = ByteArray(BYTES)
        b[0] = TAG.toByte()
        b[1] = VERSION.toByte()
        for (i in 0 until 3) {
            val hi = o.vin6[i * 2] - '0'
            val lo = o.vin6[i * 2 + 1] - '0'
            b[2 + i] = ((hi shl 4) or lo).toByte()
        }
        b[ODO_OFF] = (o.odometerKm and 0xFF).toByte()
        b[ODO_OFF + 1] = ((o.odometerKm shr 8) and 0xFF).toByte()
        b[ODO_OFF + 2] = ((o.odometerKm shr 16) and 0xFF).toByte()
        b[ODO_OFF + 3] = ((o.odometerKm shr 24) and 0xFF).toByte()
        b[DATE_OFF] = (o.year and 0xFF).toByte()
        b[DATE_OFF + 1] = ((o.year shr 8) and 0xFF).toByte()
        b[DATE_OFF + 2] = o.month.toByte()
        b[DATE_OFF + 3] = o.day.toByte()
        // 低 6 位是码，高 2 位是严重度。0 是空槽，所以码表从 1 起
        for (i in 0 until MAX_ITEMS) {
            val item = o.items.getOrNull(i)
            b[ITEMS_OFF + i] = if (item == null) 0 else ((item.severity.ordinal shl 6) or item.code).toByte()
        }
        return b
    }

    fun decode(b: ByteArray): DecodedOrder {
        require(b.size == BYTES) { "工单帧固定 $BYTES 字节，收到 ${b.size}" }
        require(b[0].toInt() == TAG) { "这不是工单帧：tag=0x%02X".format(b[0]) }
        require(b[1].toInt() == VERSION) { "工单版本不认识：v${b[1]}" }
        val vin = (0 until 3).joinToString("") {
            val v = b[2 + it].toInt() and 0xFF
            "${v shr 4}${v and 0x0F}"
        }
        val odo = (b[ODO_OFF].toInt() and 0xFF) or
            ((b[ODO_OFF + 1].toInt() and 0xFF) shl 8) or
            ((b[ODO_OFF + 2].toInt() and 0xFF) shl 16) or
            ((b[ODO_OFF + 3].toInt() and 0xFF) shl 24)
        val items = (0 until MAX_ITEMS).map { b[ITEMS_OFF + it].toInt() and 0xFF }
            .filter { it != 0 }
            .map { WorkItem(code = it and 0x3F, severity = Severity.entries[it shr 6]) }
        return DecodedOrder(
            year = (b[DATE_OFF].toInt() and 0xFF) or ((b[DATE_OFF + 1].toInt() and 0xFF) shl 8),
            month = b[DATE_OFF + 2].toInt() and 0xFF,
            day = b[DATE_OFF + 3].toInt() and 0xFF,
            vin6 = vin,
            odometerKm = odo,
            items = items,
        )
    }

    /** 一帧装得下吗。**用真预算算，不许在这里出现常数** */
    fun fits(budget: MessageBudget): Boolean = BYTES <= budget.chunkBytes

    /** 给彩排台和界面用的人读还原 */
    fun describe(d: DecodedOrder): String =
        "%04d-%02d-%02d · VIN…%s · %,d km · ${d.items.size} 项".format(d.year, d.month, d.day, d.vin6, d.odometerKm) +
            "\n" + d.items.joinToString("\n") { "· ${MaintCodes.name(it.code)}（${it.severity.label}）" }
}
