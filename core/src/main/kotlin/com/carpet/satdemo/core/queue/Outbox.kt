package com.carpet.satdemo.core.queue

import com.carpet.satdemo.core.modem.MonoClock
import com.carpet.satdemo.core.protocol.ModuleCaps
import com.carpet.satdemo.core.protocol.ModuleModel

/**
 * 补发队列（E.10 #8）。
 *
 * 这个文件存在的理由是〔规 §3.1.12 注 / §3.1.13 注〕那两套数值差着一个数量级的口径：
 * GM1001 是 **500 条 / 掉电保存 / 没有超时**，GM1003 是 **50 条 / 掉电丢失 / 存够 4 分钟没发出去就丢**。
 * "App 里那份待补发队列该留多久、什么时候重推"完全由型号决定，写成常数就有一半概率是错的。
 *
 * 更要紧的是**双写**这件事：模组 `SFQ` 回"收下"并不代表送达，只代表它进了模组那个队列。
 * 所以 App 那份不能一收到 accepted 就删 ——
 * 删了之后模组偷偷丢掉（GM1003 的 4 分钟）也没人补，SOS 会静默消失（E.6）。
 * 于是队列分两层：
 *  - [waiting] 还没交给模组的，屏上那个"待补发 N 条"就是这个数
 *  - [handed]  交出去了、模组还没发掉的，App 留着当"盯梢"副本，按型号口径决定要不要重推
 */

/** 报文在补发队列里的身份。priority 越大越可以先被挤掉 */
enum class OutboxClass(val priority: Int) {
    /** SOS 与它的撤销 */
    Emergency(0),

    /** 围栏越界、哨兵告警 —— 要排在心跳前面 */
    Alarm(1),

    /** 心跳、遥测。队满时先从这一档丢 */
    Telemetry(2),
}

/**
 * 队列口径。全部从 [ModuleCaps] 推出来，没读到能力档时用 [UNKNOWN]（= 最坏档），
 * 读到之后 [Outbox.adopt] 换成真档 —— 结构不变，只是取值。
 */
data class QueuePolicy(
    /** 模组 SFQ 槽位数，同时也是 App 侧 waiting 的上限：超过这个数再堆，模组一时半会儿也吞不下 */
    val capacity: Int,
    /** 交给模组后多久没发出去就该当作被丢掉了；GM1001 没有超时机制，所以是 null */
    val requeueAfterHandedMs: Long?,
    /** 模组掉电后它那份队列还在不在。不在就得整体重推，在就绝不能重推（重推就是重复 SOS） */
    val persistsAcrossPowerLoss: Boolean,
    /** SFQ 有没有优先级字段。没有的型号只能靠 App 侧的出队顺序表达优先级 */
    val supportsPriority: Boolean,
) {
    companion object {
        /** 没确认之前的兜底档：按 GM1003 那套最不利的来 */
        val UNKNOWN = from(null)

        fun from(caps: ModuleCaps?): QueuePolicy {
            val model = caps?.model ?: ModuleModel.WORST_KNOWN
            return QueuePolicy(
                capacity = model.queueCapacity,
                requeueAfterHandedMs = model.dropUnsentAfterMs,
                persistsAcrossPowerLoss = model.persistsAcrossPowerLoss,
                supportsPriority = model.supportsPriority,
            )
        }
    }
}

/**
 * App 侧的补发队列。纯内存 + 注入时钟，没有 Android 依赖，单测里直接演"离线 40 分钟"。
 *
 * 真机上 [waiting] 那层要落盘（手机自己重启也不能丢），落盘的是**内容**不是口径 ——
 * 口径每次上电从 `ICI` 重新读。
 */
class Outbox(
    caps: ModuleCaps? = null,
    private val clock: MonoClock = MonoClock { System.currentTimeMillis() },
) {
    var policy: QueuePolicy = QueuePolicy.from(caps)
        private set

    /**
     * 上电会话读到 `ICI` 之后调用。已入队的不动，只改后面的准入与盯梢判定 ——
     * 从坏档换成真档不该让已经攒下的报文重排。
     */
    fun adopt(caps: ModuleCaps?) {
        policy = QueuePolicy.from(caps)
    }

    data class Entry(
        val hex: String,
        val kind: OutboxClass,
        val enqueuedAtMs: Long,
        /** 第一次交给模组的时间；null = 还没交出去过 */
        var handedOverAtMs: Long? = null,
        /** 被模组丢回来过几次。展示端拿它判断"这条到底发了多少回" */
        var requeuedTimes: Int = 0,
    )

    private val waiting = ArrayList<Entry>()
    private val handed = ArrayList<Entry>()

    /** 因为队满被挤掉/拒收的条数。展示端要能看见这个数字在涨 —— 那是"一条不能丢"的反证 */
    var dropped = 0
        private set

    /** 从模组那侧捡回来的条数（超时丢弃或掉电重推） */
    var reclaimed = 0
        private set

    /** 屏上那个数字：还没交给模组的条数 */
    val size: Int get() = waiting.size
    val handedSize: Int get() = handed.size

    /** 测试和日志看的队首顺序（优先级高的在前，同档按入队先后） */
    val hexes: List<String> get() = ordered(waiting).map { it.hex }

    enum class Verdict { Accepted, AcceptedAfterEvict, RefusedFull, RefusedDuplicate }

    /**
     * 入队。满了先挤掉**最不重要的那一条**；如果连最不重要的一条都比新来的要紧，
     * 就明确拒收并计数 —— 宁可让屏上出现"N 条没排进去"，也不能悄悄丢。
     */
    fun offer(hex: String, kind: OutboxClass, nowMs: Long = clock.nowMs()): Verdict {
        if (waiting.any { it.hex == hex } || handed.any { it.hex == hex }) return Verdict.RefusedDuplicate
        if (waiting.size >= policy.capacity) {
            val victim = orderedEvictionOrder().firstOrNull() ?: return Verdict.RefusedFull
            if (victim.kind.priority < kind.priority) {
                dropped++
                return Verdict.RefusedFull
            }
            waiting.remove(victim)
            dropped++
            waiting.add(Entry(hex, kind, nowMs))
            return Verdict.AcceptedAfterEvict
        }
        waiting.add(Entry(hex, kind, nowMs))
        return Verdict.Accepted
    }

    /**
     * 这一轮该往外推的报文。App 每次推一条、模组 `accepted` 了就调 [markHanded]。
     * 一次推几条由调用方定（真机上一条 SFQ 要过一次串口写），但**不能超过模组剩余额度**，
     * 见 [slotsLeft]。
     *
     * `nowMs` 必须显式传：整条链路的时间都来自调用方那个时钟（演示里是假时钟），
     * 这里偷偷读系统时间会让超时判定拿两个不同的时间基相比，测试和真机各错一半。
     */
    fun peek(nowMs: Long): List<Entry> {
        sweep(nowMs)
        return ordered(waiting)
    }

    /**
     * 模组还剩几个槽。GM1003 只有 50 个，交出去没发掉的都占着 ——
     * 不看这个数一直推，模组只能回"缓存已满"，那条报文于是又得回到队首白转一圈。
     */
    fun slotsLeft(): Int = (policy.capacity - handed.size).coerceAtLeast(0)

    /** 模组回"收下"。从待推挪到盯梢，**不删** */
    fun markHanded(hex: String, nowMs: Long = clock.nowMs()) {
        val idx = waiting.indexOfFirst { it.hex == hex }
        if (idx < 0) return
        val e = waiting.removeAt(idx)
        if (e.handedOverAtMs == null) e.handedOverAtMs = nowMs
        handed.add(e)
        // 交出去的总数不该超过模组容量；超了说明我们数错了，把最老的那条退回待推
        while (handed.size > policy.capacity) {
            val oldest = handed.minByOrNull { it.handedOverAtMs ?: it.enqueuedAtMs } ?: break
            handed.remove(oldest)
            waiting.add(0, oldest)
        }
    }

    /**
     * 模组收下了，但这一条**不值得再盯** —— 心跳这类"下一条就把旧的覆盖了"的遥测才这么处理。
     * SOS 走这条路等于把它交给模组的 RAM 去赌（E.6）。
     */
    fun release(hex: String): Boolean = waiting.removeAllWith(hex) != null || handed.removeAllWith(hex) != null

    /**
     * 确认送达（SFI 成功、或平台回执按 owner+tsMin 对上了），盯梢副本可以撤。
     * 撤晚了没有副作用 —— 只是内存多占一条，撤早了才是丢报文。
     */
    fun confirm(hex: String): Boolean = handed.removeAll { it.hex == hex }

    /**
     * 模组主动报告的"我丢了这条"（〔规 §3.1.13 注〕，真机从 SFI 读）。
     * 回到待推队首：SOS 和撤销都排在后面的心跳之前。
     */
    fun reclaim(hexes: List<String>) {
        if (hexes.isEmpty()) return
        hexes.forEach { hex ->
            val e = handed.removeAllWith(hex) ?: waiting.removeAllWith(hex)
                ?: Entry(hex, OutboxClass.Alarm, clock.nowMs())
            e.requeuedTimes++
            e.handedOverAtMs = null
            waiting.add(0, e)
        }
        reclaimed += hexes.size
    }

    /**
     * 模组掉电/复位（含固件升级完成）。要不要整体重推**只看型号**：
     * GM1003 队列在 RAM 里，掉电即空，不重推那些报文就真没了；
     * GM1001 存在 FLASH 里，重推等于把同一条 SOS 再发一遍。
     */
    fun onModulePowerLoss() {
        if (policy.persistsAcrossPowerLoss || handed.isEmpty()) return
        handed.forEach { e ->
            e.handedOverAtMs = null
            waiting.add(e)
        }
        reclaimed += handed.size
        handed.clear()
    }

    /**
     * 盯梢判定，每个 tick 调一次：交出去超过 [QueuePolicy.requeueAfterHandedMs] 还没确认的，
     * 按型号口径模组已经丢了它，拿回队首重排。
     *
     * 这一步不能只靠模组报告 —— GM1003 那条 4 分钟超时是"偷偷丢"，
     * 真机上它会不会给一条 SFI 还没确认（§9 #14）。不猜就等于把 SOS 丢掉。
     */
    fun sweep(nowMs: Long = clock.nowMs()) {
        val timeout = policy.requeueAfterHandedMs ?: return
        val stale = handed.filter { it.handedOverAtMs?.let { t -> nowMs - t >= timeout } == true }
        if (stale.isEmpty()) return
        handed.removeAll(stale)
        stale.forEach { e ->
            e.handedOverAtMs = null
            e.requeuedTimes++
            waiting.add(0, e)
        }
        reclaimed += stale.size
    }

    private fun ordered(list: List<Entry>) =
        list.sortedWith(compareBy({ it.kind.priority }, { it.enqueuedAtMs }))

    /** 最不重要的排前面（优先级数值大 = 不重要；同档丢最老的） */
    private fun orderedEvictionOrder() =
        waiting.sortedWith(compareByDescending<Outbox.Entry> { it.kind.priority }.thenBy { it.enqueuedAtMs })

    private fun ArrayList<Entry>.removeAllWith(hex: String): Entry? {
        val e = firstOrNull { it.hex == hex } ?: return null
        remove(e)
        return e
    }

    /** 工程模式日志窗用：一句话说清两层队列和两个计数 */
    fun summary(): String =
        "待补发 $size 条 / 已交模组 $handedSize 条（额度 ${policy.capacity}）" +
            "，挤掉 $dropped 条，捡回 $reclaimed 条"
}
