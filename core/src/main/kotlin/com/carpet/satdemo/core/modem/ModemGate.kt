package com.carpet.satdemo.core.modem

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

/**
 * 升级期间的**全局写锁**（E.10 #6）。
 *
 * 依据是〔SDK文 §9-2〕那句"如果当前正在更新模组任务，所有数据发送和自定义任务都不可用"，
 * 以及〔SDK文 §4.1〕`CustomError.INITIALIZE_ERROR` 的定义里明确包含"模组正在升级"这一种。
 * 也就是说这不是我们加的保险，是厂商给的语义：**升级期间写进去的东西会被当错误丢掉**。
 *
 * 为什么不让普通发送"等着"：一次模组升级是分钟级，而卫星过境窗口也只有分钟级，
 * 排队等 flash 写完再发，等于把整扇窗口赌掉。所以这里的选择是**立刻拒绝**，
 * 由调用方落回 App 侧持久队列（〔规 §3.1.13〕模组那 50 条槽位不该被白占）。
 */
class ModemGate(private val onBlocked: (String) -> Unit = {}) {

    private val io = Mutex()

    /** 立旗用原子引用，`write` 的快路径因此不用挂起就能读到 */
    private val upgrade = AtomicReference<UpgradeState?>()

    /** [pct] 是厂商 `updateSOC` 的 0~99 进度，99 之后由 [runUpgrade] 自己收尾 */
    data class UpgradeState(val startedAtMs: Long, val pct: Int)

    val isUpgrading: Boolean get() = upgrade.get() != null

    /** 当前升级进度；没在升级返回 null */
    val progressPct: Int? get() = upgrade.get()?.pct

    /**
     * 普通写入（TPI、SFQ、查询）。升级期间返回 null 表示**一个字都没写出去**，
     * 调用方必须把它当成"没发"，不能当成"发了没人认"。
     */
    suspend fun <T> write(label: String, block: suspend () -> T): T? {
        if (isUpgrading) {
            onBlocked(label)
            return null
        }
        return io.withLock {
            // 二次检查：在等锁的这段时间里可能刚好有人开始升级
            if (isUpgrading) {
                onBlocked(label)
                null
            } else {
                block()
            }
        }
    }

    /**
     * 跑一次固件升级。顺序是**先立旗、再拿锁**：
     * 反过来的话会留下"已经开始 flash、上一条 SFQ 还在往里写"的窗口。
     * 已经在升级时返回 false，绝不套第二个升级。
     */
    suspend fun <T> runUpgrade(startedAtMs: Long, body: suspend (progress: (Int) -> Unit) -> T): T? {
        if (!upgrade.compareAndSet(null, UpgradeState(startedAtMs, 0))) return null
        try {
            return io.withLock { body { pct -> upgrade.set(UpgradeState(startedAtMs, pct.coerceIn(0, 99))) } }
        } finally {
            upgrade.set(null)
        }
    }

    companion object {
        /** 没有升级概念的替身实现（场景层 [SimulatedModem]）用这个：永远空闲、永远放行 */
        val IDLE = ModemGate()
    }
}
