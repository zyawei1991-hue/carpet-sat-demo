package com.carpet.satdemo.core.modem

import com.carpet.satdemo.core.protocol.GeodeticPosition
import kotlin.math.abs

/**
 * TPI 周期任务（E.10 #5）。
 *
 * 方向和我们原来的直觉**相反**：〔规 §3.1.7〕要求手机把 UNIX 秒 + 度分经纬度**喂进模组**，
 * 上电配一次、之后 1~10 分钟一次。三个后果都在这份实现里：
 *  1. 模组复位/掉电即丢这份位置，所以"推过一次"不是一劳永逸，得有周期；
 *  2. **经纬度直接影响下行能不能收到** —— 推歪了等于自己把平台下行关掉；
 *  3. 陈旧度必须变成一个能上屏的状态（〔E.5〕「位置已过期 N 分钟，卫星可能投递失败」），
 *     否则现场只会看到"平台回不来"这条没有线索的现象。
 *
 * 坐标全程走 [GeodeticPosition]（带字段名的类型），不用裸 `(double, double)` ——
 * SDK 的 `updateLocation(经, 纬)` 和语句里的 `(纬, 经)` 顺序正好相反，
 * 传反了编译不报错，要到外场才发现定位在地球对面。
 */
class TpiTracker(
    private val modem: SatelliteModem,
    private val clock: MonoClock = MonoClock { System.currentTimeMillis() },
    /** 〔规 §3.1.7〕建议 1~10 分钟。传超界值会被钳回来，不报错 —— 配置写错不该把链路刷爆 */
    intervalMs: Long = DEFAULT_INTERVAL_MS,
    /** 超过这个年龄就判陈旧。默认取模组侧那个 15 分钟口径（〔E.5〕） */
    private val staleAfterMs: Long = SentenceModem.TPI_STALE_DEFAULT_MS,
    /** 位移超过它就立刻补推，不等周期。0.001° ≈ 111 m，够滤掉 GPS 抖动又抓得住真的挪车 */
    private val moveThresholdDeg: Double = 0.001,
    private val log: (String) -> Unit = {},
) {

    val intervalMs: Long = intervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)

    enum class State {
        /** 一个位置源都还没有：模组那边是空的，下行基本收不到 */
        NO_SOURCE,

        /** 刚推过，还在有效期内 */
        FRESH,

        /** 到点了/挪车了，这一轮该推 */
        DUE,

        /** 升级被写锁挡着，没推成 —— 陈旧度继续涨，要上屏 */
        BLOCKED,

        /** 上一次推了但模组没回显 */
        REJECTED,

        /** 推出去的位置已经老到不能再信 */
        STALE,
    }

    data class Status(
        val state: State,
        /** 距上次**成功**推送多少毫秒；从没成功过为 null */
        val ageMs: Long?,
        val position: GeodeticPosition?,
        val intervalMs: Long,
    )

    @Volatile
    private var latest: GeodeticPosition? = null

    private var pushed: GeodeticPosition? = null
    private var pushedAtMs: Long? = null
    private var rejectedAtMs: Long? = null

    /** 位置源。手机 GPS 和 GT1005 GNSS 都汇到这里，谁新用谁 */
    fun onPosition(position: GeodeticPosition) {
        latest = position
    }

    /** 十进制度入口，只做转换不做判断 —— 保证外部没法绕过带名字的类型 */
    fun onPosition(latDeg: Double, lonDeg: Double) = onPosition(GeodeticPosition.of(latDeg, lonDeg))

    fun status(): Status {
        val now = clock.nowMs()
        val pos = latest
        val age = pushedAtMs?.let { now - it }
        return Status(
            state = when {
                pos == null -> State.NO_SOURCE
                // 升级排第一：这时候"位置推不进去"是用户能做的唯一解释，比"过期 N 分钟"更可动作
                modem.gate.isUpgrading -> State.BLOCKED
                // 被拒排在"从没推成"前面：这时候模组那边是真没位置，光说"该推了"会把人引开
                rejectedAtMs != null -> State.REJECTED
                age == null -> State.DUE
                age > staleAfterMs -> State.STALE
                due(pos, now) -> State.DUE
                else -> State.FRESH
            },
            ageMs = age,
            position = pos,
            intervalMs = intervalMs,
        )
    }

    /** 位置已经老到不能再信 —— §4 那行「位置已过期 N 分钟」就看这个 */
    val isStale: Boolean
        get() = status().state.let { it == State.STALE || it == State.NO_SOURCE }

    /** 过期了多少秒，给文案用；没过期或从没成功推送过返回 null */
    fun staleAgeSec(): Long? = status().let { s ->
        val age = s.ageMs ?: return null
        if (age <= staleAfterMs) null else age / 1000
    }

    /**
     * 到周期/该补推时真的推一条出去。调用方（App 的 1Hz 循环、演示驱动器）在协程里调。
     * 不该推的时候直接返回当前状态，不产生任何写入。
     */
    suspend fun tick(): Status {
        val now = clock.nowMs()
        val pos = latest ?: return Status(State.NO_SOURCE, null, null, intervalMs)
        if (!due(pos, now)) return status()

        val ok = runCatching { modem.updateLocation(pos) }.getOrDefault(false)
        if (ok) {
            pushed = pos
            pushedAtMs = now
            rejectedAtMs = null
            log("TPI 已推送 %.5f, %.5f".format(pos.latitude.degrees, pos.longitude.degrees))
        } else {
            rejectedAtMs = now
            log(if (modem.gate.isUpgrading) "TPI 被升级写锁挡下" else "TPI 未写出去（链路未开、档位未就绪或模组没应答）")
        }
        return status()
    }

    private fun due(pos: GeodeticPosition, now: Long): Boolean {
        val last = pushed ?: return true
        val age = now - (pushedAtMs ?: 0)
        val moved = abs(pos.latitude.degrees - last.latitude.degrees) > moveThresholdDeg ||
            abs(pos.longitude.degrees - last.longitude.degrees) > moveThresholdDeg
        // 被拒过就短间隔重试，别死等一个完整周期 —— 模组那边可能是升级刚结束
        val retry = rejectedAtMs != null && now - rejectedAtMs!! >= RETRY_BACKOFF_MS
        return moved || age >= intervalMs || retry
    }

    companion object {
        /** 〔规 §3.1.7〕"1~10 分钟"。下面三个数是它的字面区间 */
        const val MIN_INTERVAL_MS = 60_000L
        const val MAX_INTERVAL_MS = 10 * 60_000L
        const val DEFAULT_INTERVAL_MS = 5 * 60_000L

        /** 推失败后的补试间隔。比最短周期短，比 1 秒 tick 长，不至于刷屏 */
        const val RETRY_BACKOFF_MS = 20_000L
    }
}
