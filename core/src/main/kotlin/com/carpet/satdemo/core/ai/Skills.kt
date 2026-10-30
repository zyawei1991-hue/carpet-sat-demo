package com.carpet.satdemo.core.ai

/**
 * Skill 注册表：**关键词触发 + 结构化 JSON + 权限分档**（digivita 那条已经跑通的形状，附录 D.2）。
 *
 * 为什么不直接用模型做意图识别：模型识别错了没人知道，而且同一个问题明天会路由到别的 skill 上。
 * 关键词表是**可读可改可测**的，错了能在单测里钉住。模型只负责最后那句话怎么说。
 */
data class Skill(
    val id: String,
    val title: String,
    /** 命中即路由。全小写比对，中文关键词不做分词 —— 表里写什么就找什么 */
    val keywords: List<String>,
    val needs: PermissionTier,
    val handle: (SkillContext) -> SkillResult,
)

class SkillRegistry(skills: List<Skill>) {
    private val list = skills.toList()

    val all: List<Skill> get() = list

    /** 短词先剔除：「车」会命中「什么车都能开吗」这种句子，所以命中要按关键词长度降序判，长句优先 */
    fun match(text: String): Skill? {
        val q = text.lowercase()
        return list
            .filter { s -> s.keywords.any { q.contains(it.lowercase()) } }
            .maxByOrNull { s -> s.keywords.filter { q.contains(it.lowercase()) }.maxOfOrNull { it.length } ?: 0 }
    }

    fun byId(id: String): Skill? = list.firstOrNull { it.id == id }
}

/** 已经锁上星、正在窗口里的相位。这些时候"距下次过顶"这个数已经没有意义了 */
private val IN_WINDOW = setOf("locked", "tx_pending", "tx_ok", "tx_fail", "tx_no_ack")

/**
 * 「下一次过顶」那半句。**窗口内 secondsToNextPass 就是 0**，照字面念成了"还有 0 秒"，
 * 用户会以为星正在头顶，而其实我们已经锁住了 —— 那时候该说的是"正在窗口内"。
 * 空串表示这事现在没得说（还没算出过境时间），由调用方决定兜底话。
 */
internal fun passSentence(c: SkillContext): String = when {
    c.phase in IN_WINDOW -> "正在过境窗口内。"
    c.secondsToNextPass == null -> ""
    c.secondsToNextPass <= 0 -> "窗口已经打开。"
    else -> "下一次过顶还有 ${c.secondsToNextPass} 秒。"
}

/**
 * 内置技能。每条都只说**自己那格数据里真有的东西**；
 * 没数据源的一律回"这条我现在说不了"，并且 `mocked=false` —— 桩不是 mock，桩是**不答**。
 */
object BuiltinSkills {

    fun registry(): SkillRegistry = SkillRegistry(
        listOf(
            Skill(
                id = "link.status",
                title = "链路现在什么样",
                keywords = listOf("链路", "网络", "有网", "信号", "现在能发吗", "能不能发"),
                needs = PermissionTier.ReadLocal,
            ) { c ->
                val module = when (c.moduleOnline) {
                    null -> "终端状态未知"
                    true -> "GT1005 在线"
                    false -> "GT1005 离线"
                }
                val f = buildMap {
                    put("phase", c.phase ?: "unknown")
                    put("module_online", c.moduleOnline?.toString() ?: "null")
                }
                SkillResult(
                    skillId = "link.status",
                    intent = "链路状态查询",
                    fields = f,
                    line = "当前相位 ${c.phase ?: "未知"}，$module。" + passSentence(c),
                )
            },

            Skill(
                id = "pass.window",
                title = "下次过境",
                keywords = listOf("过境", "过顶", "窗口", "什么时候能发", "卫星"),
                needs = PermissionTier.ReadLocal,
            ) { c ->
                val s = c.secondsToNextPass
                SkillResult(
                    skillId = "pass.window",
                    intent = "过境窗口查询",
                    fields = mapOf(
                        "seconds_to_next_pass" to (s?.toString() ?: "null"),
                        "window_sec" to (c.passWindowSec?.toString() ?: "null"),
                        "ephemeris_fresh" to (c.ephemerisFresh?.toString() ?: "null"),
                    ),
                    // 星历过期时不能说"没有卫星"，那是两件事（附录 E.9）
                    line = when {
                        c.ephemerisFresh == false ->
                            "星历已经过期，窗口算不准了。联网更新一次才能给时间 —— 这不是说没卫星。"
                        else -> passSentence(c).ifEmpty { "还没有可用的过境时间。" } +
                            (c.passWindowSec?.takeIf { it > 0 }?.let { "窗口大约 $it 秒。" } ?: "")
                    },
                )
            },

            Skill(
                id = "quota.check",
                title = "还剩多少条",
                keywords = listOf("额度", "流量", "多少条", "用完", "剩余"),
                needs = PermissionTier.ReadLocal,
            ) { c ->
                val wait = c.queueWaiting
                SkillResult(
                    skillId = "quota.check",
                    intent = "用量查询",
                    fields = mapOf(
                        "waiting" to (wait?.toString() ?: "null"),
                        "capacity" to (c.queueCapacity?.toString() ?: "null"),
                        "handed_over" to (c.handedOver?.toString() ?: "null"),
                    ),
                    line = when {
                        c.queueCapacity == null || c.queueCapacity <= 0 ->
                            "还没读到终端型号，额度说不准。"
                        else -> "待发 ${wait ?: 0} 条 / 槽位 ${c.queueCapacity} 条，已交核心网 ${c.handedOver ?: 0} 条。"
                    },
                )
            },

            Skill(
                id = "dock.pair",
                title = "圆屏怎么连",
                keywords = listOf("圆屏", "屏幕", "展示端", "配对", "连不上", "connect"),
                needs = PermissionTier.ReadLocal,
            ) { c ->
                SkillResult(
                    skillId = "dock.pair",
                    intent = "设备配对指引",
                    fields = mapOf("screens_online" to (c.screensOnline?.toString() ?: "null")),
                    line = "手机这侧是服务端：首页「圆屏」那一组先放开「允许圆屏接入」，" +
                        "再把圆屏编号加进白名单，圆屏连手机的地址就能出画面。" +
                        (c.screensOnline?.let { "现在在线 $it 台。" } ?: ""),
                )
            },

            Skill(
                id = "device.find",
                title = "车在哪",
                keywords = listOf("车在哪", "停车位置", "寻车", "找车"),
                needs = PermissionTier.ReadLocal,
            ) { c ->
                val has = c.gnssLat != null && c.gnssLon != null
                SkillResult(
                    skillId = "device.find",
                    intent = "位置查询",
                    fields = if (has) mapOf("lat" to c.gnssLat!!.toString(), "lon" to c.gnssLon!!.toString()) else emptyMap(),
                    line = if (has) "最后一次定位在 %.5f, %.5f。".format(c.gnssLat, c.gnssLon)
                    else "现在没有定位源，我不猜坐标。",
                )
            },

            Skill(
                id = "sos.fire",
                title = "发求救",
                keywords = listOf("求救", "sos", "出事了", "救命"),
                needs = PermissionTier.Irreversible,
            ) { c ->
                SkillResult(
                    skillId = "sos.fire",
                    intent = "发起 SOS",
                    fields = mapOf("phase" to (c.phase ?: "unknown")),
                    // 这句话永远是"待确认"，网关在这一档会直接拦住 —— 不可逆动作不由对话触发（§6.1）
                    line = "发 SOS 是不可逆动作，我不会替你在对话里按下去。首屏长按 3 秒，或用圆屏那颗侧键。",
                )
            },

            Skill(
                id = "fence.state",
                title = "围栏状态",
                keywords = listOf("围栏", "越界", "出去了吗", "在区域内"),
                needs = PermissionTier.ReadLocal,
            ) { c ->
                SkillResult(
                    skillId = "fence.state",
                    intent = "围栏查询",
                    fields = mapOf(
                        "active" to (c.fenceActive?.toString() ?: "null"),
                        "inside" to (c.fenceInside?.toString() ?: "null"),
                    ),
                    line = when {
                        c.fenceActive != true -> "围栏没开。要在「我的 → 守护开关」里设一个区域。"
                        c.fenceInside == null -> "围栏开着，但现在没有定位，判不出在不在里面。"
                        else -> if (c.fenceInside) "在围栏内。" else "已经越界了。"
                    },
                )
            },
        ),
    )
}
