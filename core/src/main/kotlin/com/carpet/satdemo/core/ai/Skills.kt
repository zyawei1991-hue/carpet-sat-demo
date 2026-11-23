package com.carpet.satdemo.core.ai

import com.carpet.satdemo.core.protocol.SatPhase

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
    /**
     * 权限不够时的那句话。默认 null = 沿用 [handle]（SOS 这类 handle 本身不出网、
     * 只是解释"我不替你按"的技能，被拦时说同一句正合适）。
     * **出网的技能必须给这一条**：被拦的那条连取数都不许做 —— 没开「读云端」就
     * 拿定位去问第三方，等于权限档白设。
     * 放在 [handle] 前面是为了让各技能的尾随 lambda 仍落在 [handle] 上。
     */
    val deniedLine: ((SkillContext) -> String)? = null,
    val handle: (SkillContext) -> SkillResult,
)

class SkillRegistry(skills: List<Skill>) {
    private val list = skills.toList()

    val all: List<Skill> get() = list

    /** 短词先剔除：「车」会命中「什么车都能开吗」这种句子，所以命中要按关键词长度降序判，长句优先 */
    fun match(text: String): Skill? {
        val q = text.lowercase()
        return list
            .filter { s -> s.keywords.any { hit(q, it.lowercase()) } }
            .maxByOrNull { s -> s.keywords.filter { hit(q, it.lowercase()) }.maxOfOrNull { it.length } ?: 0 }
    }

    fun byId(id: String): Skill? = list.firstOrNull { it.id == id }
}

/** 明说"不要提"的那几种说法。只在关键词前面那一小段里找，不做分词 —— 表里写什么就判什么 */
private val NEGATIONS = setOf("别问", "不问", "别说", "不说", "不要")

/** 关键词前面看多远。「别问我天气」里那个"我"要跨得过去 */
private const val NEGATION_WINDOW = 6

/** 否定管不到分句另一头：「别说网络，卫星网络到底通不通」后半句是真在问。空格不算断句 */
private const val CLAUSE_BREAK = "，。！？、；：,.!?;:"

/**
 * 关键词这次出现算不算数：同一小句里、它在前面被"别问/不说"这类说法否定过，那一次出现就作废。
 *
 * 为什么值得这么一刀：没有它，「别问我天气」「不要说额度的事」会把技能拉起来，
 * 而技能会一本正经地答天气/报额度 —— 用户刚说过不要，AI 反而追着说，那比答不出来更糟。
 * **只判关键词前面**：「冷不冷」「连不上」里的"不"是词的一部分，不走这条。
 * 同一句里另一次没被否定的出现照样算（「别说网络，卫星网络到底通不通」问的还是链路）。
 */
private fun hit(q: String, kw: String): Boolean {
    var from = 0
    while (true) {
        val i = q.indexOf(kw, from)
        if (i < 0) return false
        val before = q.substring(maxOf(0, i - NEGATION_WINDOW), i).takeLastWhile { it !in CLAUSE_BREAK }
        // 「要不要查额度」是在问，不是"不要"；先把它抹掉再判否定
        val probe = before.replace("要不要", "")
        if (NEGATIONS.none { probe.contains(it) }) return true
        from = i + 1
    }
}

/** 已经锁上星、正在窗口里的相位。这些时候"距下次过顶"这个数已经没有意义了 */
private val IN_WINDOW = setOf(SatPhase.locked, SatPhase.tx_pending, SatPhase.tx_ok,
                              SatPhase.tx_fail, SatPhase.tx_no_ack)

/** SkillContext 里相位是线上的字符串（协议键名），比对枚举前先解回来；解不回来就当不知道 */
private fun phaseOf(raw: String?): SatPhase? = SatPhase.entries.firstOrNull { it.name == raw }

/**
 * 相位给人读的那一个词。**和圆屏固件 app/main/app_main.c 里那张 PHASE_CN 逐条同字** ——
 * 同一件事在屏上和手机里说出两种口径，展台上的观众只会信错的那个。
 * 「tx_ok」只说"卫星已收到"，不说"发送成功"：后者在 [com.carpet.satdemo.core.link.SosCopy.BANNED] 里，
 * 卫星 ACK 不等于平台受理（红线一）。
 *
 * 当 enum 用而不是继续拼字符串，是为了让"新加一个相位忘了配文案"在编译期就红，
 * 而不是在屏上出现一个没人认识的键名（#31，2026-11-02 实拍「当前相位 idle」直接上了屏）
 */
private fun phaseCn(p: SatPhase?): String = when (p) {
    null -> "未知"
    SatPhase.idle -> "待命"
    SatPhase.searching -> "对星中"
    SatPhase.locked -> "已锁定"
    SatPhase.tx_pending -> "正在发送"
    SatPhase.tx_ok -> "卫星已收到"
    SatPhase.tx_fail -> "没发出去"
    SatPhase.tx_no_ack -> "没等到回执"
}

/**
 * 「下一颗卫星什么时候经过」那半句。**窗口内 secondsToNextPass 就是 0**，照字面念成了"还有 0 秒"，
 * 用户会以为星正在头顶，而其实我们已经锁住了 —— 那时候该说的是"现在就能发"。
 * 空串表示这事现在没得说（还没算出过境时间），由调用方决定兜底话。
 *
 * 措辞不说过境/过顶（owner 2026-11-19：要翻译成用户能看懂的话）：
 * 车主听得懂的是"卫星什么时候经过、那时候能不能发"，不是"窗口""过顶"这种行话。
 */
internal fun passSentence(c: SkillContext): String = when {
    phaseOf(c.phase)?.let { it in IN_WINDOW } == true -> "卫星正在头顶，现在就能发。"
    c.secondsToNextPass == null -> ""
    c.secondsToNextPass <= 0 -> "卫星已经到位，现在就能发。"
    else -> "还要 ${humanSpan(c.secondsToNextPass)}，下一颗卫星经过时才能发。"
}

/** 秒数说成人话：180 秒说成"3 分钟"，90 秒说成"1 分 30 秒" —— 没人会去心算 90 秒是多久 */
internal fun humanSpan(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return when {
        m <= 0 -> "$s 秒"
        s == 0 -> "$m 分钟"
        else -> "$m 分 $s 秒"
    }
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
                // 「网络」「信号」两个单字级词太宽，任何一句都命中；留带卫星的，外加首页那颗
                // 「有信号吗」的整句（demo 的黄金路径，收了会把建议词变成答不上的那种）
                keywords = listOf("链路", "卫星网络", "有网", "卫星信号", "有信号吗", "现在能发吗", "能不能发"),
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
                    line = "当前相位 ${phaseCn(phaseOf(c.phase))}，$module。" + passSentence(c),
                )
            },

            Skill(
                id = "pass.window",
                title = "卫星什么时候来",
                // keywords 仍留着「过境/过顶/窗口」：车主可能还是这么问，路由要接得住；
                // 改的只是**说出去的话**，不是**听得懂的话**
                keywords = listOf("过境", "过顶", "窗口", "什么时候能发", "卫星", "什么时候来"),
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
                            "卫星位置数据好几天没更新了，什么时候经过会算不准。连网更新一次就好 —— 卫星还在，只是不知道它几点到。"
                        else -> passSentence(c).ifEmpty { "现在还说不准卫星什么时候经过。" } +
                            (c.passWindowSec?.takeIf { it > 0 }?.let { "经过时能发 ${humanSpan(it)}。" } ?: "")
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

            // §7：问天气。出网（Open-Meteo，无 key）须显式开「读云端」，没开就诚实说去哪开，不编。
            // 取数在 handle 里当场做（Weather 自带 30 分钟缓存），结果写进 fields 给模型压成一句话；
            // 没定位/没网回的都是"没拿到"这个事实本身 —— 数字只能来自 Open-Meteo 的返回
            Skill(
                id = "weather",
                title = "明天天气",
                keywords = listOf("天气", "下雨", "气温", "冷不冷", "热不热", "冷吗", "热吗", "带伞", "多少度"),
                needs = PermissionTier.ReadCloud,
                deniedLine = { "天气要出网查，得先在设置里把 AI 权限开到「读云端」—— 开之前我不拿你的定位去问外面。" },
            ) { c ->
                val lat = c.gnssLat
                val lon = c.gnssLon
                if (lat == null || lon == null) {
                    SkillResult(
                        skillId = "weather",
                        intent = "天气查询",
                        fields = mapOf("located" to "false"),
                        line = "现在没有定位，天气报不准 —— 我不猜城市。",
                    )
                } else {
                    val f = Weather.tomorrow(lat, lon, System.currentTimeMillis())
                    if (f == null) {
                        SkillResult(
                            skillId = "weather",
                            intent = "天气查询",
                            fields = mapOf("located" to "true", "fetched" to "false"),
                            line = "天气这会儿没取到（没网或者服务没回），等一会儿再问。",
                        )
                    } else {
                        SkillResult(
                            skillId = "weather",
                            intent = "天气查询",
                            fields = mapOf(
                                "located" to "true",
                                "fetched" to "true",
                                "tomorrow_weather" to f.codeCn,
                                "tomorrow_tmax_c" to f.tMaxC.toString(),
                                "tomorrow_tmin_c" to f.tMinC.toString(),
                                "tomorrow_precip_prob" to f.precipProb.toString(),
                            ),
                            // 模型在时这句会被按现有口径重说一遍；模型不在时技能话术本身就是完整答案
                            line = "明天${f.codeCn}，${f.tMinC.toInt()}~${f.tMaxC.toInt()}℃" +
                                (if (f.precipProb >= 30) "，降水概率 ${f.precipProb}%，记得带伞。" else "，降水概率 ${f.precipProb}%。"),
                        )
                    }
                }
            },

            // 亲子页那一问：「娃在屏上说了啥」。**它读的是手机那份对话记录** ——
            // 屏在亲子页时说的话会落到手机的家人页，scene 接上之后这一问才有东西可念。
            // 只念原话、不加评价：那些话不是我们生成的，替它总结一句就等于替那句话负责。
            Skill(
                id = "kids.recap",
                title = "屏上说了啥",
                // 「家里」曾经也在这张表里：它把「家里还剩多少额度」抢成了屏对话回看。
                // 屏上那几个关键词（说了啥/屏上/孩子/娃）已经够区分，宽的那个只留在歧义上赢
                keywords = listOf("说了啥", "说了什么", "屏上", "孩子", "娃", "听了什么"),
                needs = PermissionTier.ReadLocal,
            ) { c ->
                val said = c.screenTalk
                SkillResult(
                    skillId = "kids.recap",
                    intent = "圆屏对话回看",
                    fields = mapOf("count" to said.size.toString()),
                    line = when {
                        said.isEmpty() && c.screensOnline == 0 -> "圆屏现在没连着，也没有说话记录。"
                        said.isEmpty() -> "圆屏上还没有说过话。"
                        else -> "屏上最近有 ${said.size} 句：" + said.joinToString("、") { "「$it」" }
                    },
                )
            },
        ),
    )
}
