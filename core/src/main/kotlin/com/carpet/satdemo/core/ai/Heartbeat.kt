package com.carpet.satdemo.core.ai

/**
 * Heartbeat 主动扫描（附录 D.1 里 digivita 那条闭环的另一半）。
 *
 * 「主理人」和普通聊天框的区别就在这：它会在**没人问的时候开口**。但一次误报的代价比沉默大得多 ——
 * 所以规则表里每一条都必须同时满足「有真数据源」和「到了阈值」，且带冷却时间，同一条不会连着念。
 */
data class ProactiveTip(val skill: String, val text: String)

/** 一条主动提醒的规则。公开是为了让上层能往 [Heartbeat] 里加自己的规则，而不是只能用内置那张表 */
data class Rule(
    val id: String,
    /** 同一条提醒的冷却。卫星过境这种事 12 分钟一轮，太短会把人念烦 */
    val cooldownMs: Long,
    val fire: (SkillContext) -> ProactiveTip?,
)

class Heartbeat(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val rules: List<Rule> = DEFAULT_RULES,
) {
    private val lastFired = mutableMapOf<String, Long>()

    /** 每 tick 调一次。返回本轮该说的话（可能空，多半是空 —— 空是正常状态，不要为了刷存在感编话） */
    fun scan(ctx: SkillContext): List<ProactiveTip> {
        val t = nowMs()
        val out = mutableListOf<ProactiveTip>()
        rules.forEach { r ->
            if (t - (lastFired[r.id] ?: Long.MIN_VALUE / 2) < r.cooldownMs) return@forEach
            val from = runCatching { r.fire(ctx) }.getOrNull()
            if (from != null) {
                lastFired[r.id] = t
                out += from
            }
        }
        return out
    }

    /** 测试与演示用：把冷却清掉，好演"连着出两条" */
    fun reset() = lastFired.clear()

    companion object {
        val DEFAULT_RULES: List<Rule> = listOf(
            Rule("quota.nearly-full", 10 * 60_000) { c ->
                val w = c.queueWaiting
                val cap = c.queueCapacity
                if (w == null || cap == null || cap <= 0 || w < cap * 0.8) null
                else ProactiveTip("quota.check", "待发队列已经用到 $w/$cap，快满了。过了这个窗口还没发完就要考虑减频。")
            },
            Rule("eph.stale", 30 * 60_000) { c ->
                if (c.ephemerisFresh != false) null
                else ProactiveTip("pass.window", "星历过期了，过境时间算不准。联网更新一次 —— 不是没卫星。")
            },
            Rule("terminal.offline", 5 * 60_000) { c ->
                if (c.moduleOnline != false) null
                else ProactiveTip("link.status", "卫星终端不在线，要发的内容先攒在手机这边，一条不会丢。")
            },
            Rule("fence.breach", 60_000) { c ->
                if (c.fenceActive != true || c.fenceInside != false) null
                else ProactiveTip("fence.state", "已经越界了。这条不等蜂窝，走卫星发。")
            },
            Rule("battery.low", 20 * 60_000) { c ->
                val b = c.phoneBattery
                if (b == null || b > 20) null
                else ProactiveTip("link.status", "手机只剩 $b%，守护服务是吃电的大户 —— 现在插上比较稳。")
            },
            Rule("dock.waiting", 15 * 60_000) { c ->
                if (c.screensOnline != 0) null
                else ProactiveTip("dock.pair", "还没有圆屏连上来。先放开「允许圆屏接入」，再把圆屏编号加进白名单。")
            },
        )
    }
}
