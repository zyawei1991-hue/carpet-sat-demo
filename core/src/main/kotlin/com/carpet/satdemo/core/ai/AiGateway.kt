package com.carpet.satdemo.core.ai

import com.carpet.satdemo.core.link.SosCopy

/**
 * 网关：**路由 → 取数 → 权限判定 → 生成**。顺序不许换。
 *
 * 取数在生成之前，是因为模型拿到的必须是已经算好的事实；权限在两者之间，是因为
 * 拦下的那条连上下文都不该发给模型（SOS 那种句子发出去就等于把意图交给了别人的服务器）。
 */
class AiGateway(
    val skills: SkillRegistry = BuiltinSkills.registry(),
    private val provider: AiProvider = LocalRuleProvider(),
    initialGranted: PermissionTier = PermissionTier.ReadLocal,
    private val log: (String) -> Unit = {},
) {
    /** 授予档。默认只读本机 —— 出网和不可逆动作要在设置里明确开（§6 第 3 条同一条思路：取更严的） */
    @Volatile
    var granted: PermissionTier = initialGranted
        private set

    private val turns = ArrayDeque<ChatTurn>()

    val history: List<ChatTurn> get() = turns.toList()

    /** 界面要能逐条说"这条现在准不准"，所以判定得能被外面问一句 */
    fun can(skillId: String): Boolean = skills.byId(skillId)?.let { PermissionTier.allows(granted, it.needs) } ?: false

    fun setGranted(tier: PermissionTier) {
        if (tier == granted) return
        granted = tier
        log("AI 权限档 → ${tier.label}")
    }

    suspend fun ask(question: String, ctx: SkillContext): AiReply {
        val q = question.trim()
        if (q.isEmpty()) return AiReply("想说点什么？", skill = null, source = ReplySource.LocalRule)

        val skill = skills.match(q)
        if (skill == null) {
            // 没命中关键词也要能答：走模型，但事实表里只有状态机给得出的东西
            val facts = summarize(ctx)
            val out = if (provider.live) provider.complete(facts, history.toList(), q) else null
            return record(
                if (out != null) AiReply(guard(out), skill = null, source = ReplySource.Model)
                else AiReply(NO_MATCH, skill = null, source = ReplySource.LocalFallback),
                q,
            )
        }

        if (!PermissionTier.allows(granted, skill.needs)) {
            log("拦截 ${skill.id}：需要「${skill.needs.label}」，当前只到「${granted.label}」")
            return record(
                AiReply(
                    skill.handle(ctx).line ?: "这条我不做。",
                    skill = skill.id,
                    source = ReplySource.Denied,
                    mocked = skill.handle(ctx).mocked,
                ),
                q,
            )
        }

        val result = skill.handle(ctx)
        val facts = buildString {
            append("技能 ").append(skill.id).append("（").append(result.intent).append("）\n")
            result.fields.forEach { (k, v) -> append("· ").append(k).append("=").append(v).append('\n') }
        }
        val model = if (provider.live) provider.complete(facts + summarize(ctx), history.toList(), q) else null
        return record(
            AiReply(
                text = model?.let { guard(it) } ?: result.line ?: "这条我现在说不了。",
                skill = skill.id,
                source = if (model != null) ReplySource.Model else if (provider.live) ReplySource.LocalFallback else ReplySource.LocalRule,
                mocked = result.mocked,
            ),
            q,
        )
    }

    private fun record(reply: AiReply, question: String): AiReply {
        turns.addLast(ChatTurn("user", question))
        turns.addLast(ChatTurn("assistant", reply.text))
        while (turns.size > MAX_TURNS) turns.removeFirst()
        return reply
    }

    /** 主动播报也进历史，否则用户会看到 AI 突然开口而前面没有来由 */
    fun recordProactive(text: String, skill: String?): AiReply {
        turns.addLast(ChatTurn("assistant", text))
        while (turns.size > MAX_TURNS) turns.removeFirst()
        return AiReply(text, skill = skill, source = ReplySource.LocalRule, proactive = true)
    }

    /**
     * 模型输出的文案红线。**红线不只约束我们写的模板，也约束别人的模型**：
     * 出现 [SosCopy.BANNED] 里任何一句就整条换成不越界说法 —— 一句"救援已在路上"的代价我们担不起。
     */
    private fun guard(text: String): String {
        val hit = SosCopy.BANNED.firstOrNull { text.contains(it) }
        if (hit == null) return text
        log("模型输出踩线（$hit），已替换：${text.take(80)}")
        return "这句话我不能替平台说 —— 我只能确认卫星这一侧的状态。"
    }

    companion object {
        private const val MAX_TURNS = 20

        private const val NO_MATCH =
            "这件事我现在没有对应的数据口径，不能凭印象答。可以说「链路」「过境」「额度」「圆屏配对」「车在哪」。"

        /** 发给模型的事实表。null 一律写成「未知」，不留给模型自由发挥的空位 */
        fun summarize(c: SkillContext): String = buildString {
            append("场景=").append(c.scene)
            append(" 相位=").append(c.phase ?: "未知")
            append(" 距下次过顶=").append(c.secondsToNextPass?.toString() ?: "未知").append("秒")
            append(" 终端=").append(when (c.moduleOnline) { true -> "在线"; false -> "离线"; null -> "未知" })
            append(" 待发=").append(c.queueWaiting?.toString() ?: "未知")
            append(" 槽位=").append(c.queueCapacity?.toString() ?: "未知")
            append(" 已交核心网=").append(c.handedOver?.toString() ?: "未知")
            append(" 定位=").append(if (c.gnssLat != null && c.gnssLon != null) "%.5f,%.5f".format(c.gnssLat, c.gnssLon) else "无")
            append(" 围栏=").append(if (c.fenceActive == true) (if (c.fenceInside == true) "在内" else "越界") else "未开")
            append(" 在线圆屏=").append(c.screensOnline?.toString() ?: "未知").append("台")
            append(" 手机电量=").append(c.phoneBattery?.toString() ?: "未知").append("%")
        }
    }
}
