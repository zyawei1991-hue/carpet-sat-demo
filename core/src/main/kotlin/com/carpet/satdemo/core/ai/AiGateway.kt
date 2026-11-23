package com.carpet.satdemo.core.ai

import com.carpet.satdemo.core.link.SosCopy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** §5.1-2：技能话术「先答」要等多久。模型超过这个数还没吐第一个字，本机那句就先开口 */
    private val localFirstMs: Long = 1_500,
    /** §5.2：会话窗口。同一个人格隔过这个窗再问，算新一轮 —— 旧话不陪跑（owner 11-15 拍：20 秒内算同一段对话） */
    private val sessionWindowMs: Long = 20_000,
) {
    /** 授予档。默认只读本机 —— 出网和不可逆动作要在设置里明确开（§6 第 3 条同一条思路：取更严的） */
    @Volatile
    var granted: PermissionTier = initialGranted
        private set

    /** 人格各一条历史（§5.2）：亲子页陪聊的话不该接在车载守护的历史后面被喂给模型 */
    private class Track {
        val turns = ArrayDeque<ChatTurn>()
        var lastMs = 0L
    }

    private val tracks = Persona.entries.associateWith { Track() }

    /** 发给模型的按人格取（[historyFor]）；这条是排障总视图，拼接顺序按人格声明序 */
    val history: List<ChatTurn> get() = tracks.values.flatMap { it.turns.toList() }

    fun historyFor(persona: Persona): List<ChatTurn> = tracks.getValue(persona).turns.toList()

    /** 会话窗口在这一刀收：窗一过先清这条人格的历史，别人格的不动 */
    private fun trackFor(persona: Persona): Track = tracks.getValue(persona).also { t ->
        if (t.turns.isNotEmpty() && t.lastMs > 0L && nowMs() - t.lastMs > sessionWindowMs) {
            t.turns.clear()
            log("会话窗口过了 ${sessionWindowMs}ms，清掉 ${persona.name} 的历史")
        }
    }

    /**
     * 上一次真模型为什么没答上来。设置页要把它原样摆出来 ——
     * 「未接模型」是状态，用户还得知道下一步该改地址、换密钥还是换网络。
     */
    val lastModelError: String? get() = provider.lastError

    /** 界面要能逐条说"这条现在准不准"，所以判定得能被外面问一句 */
    fun can(skillId: String): Boolean = skills.byId(skillId)?.let { PermissionTier.allows(granted, it.needs) } ?: false

    fun setGranted(tier: PermissionTier) {
        if (tier == granted) return
        granted = tier
        log("AI 权限档 → ${tier.label}")
    }

    /**
     * [onSentence]：**流式每凑齐一句回调一次**（已逐句过 [guard]）。整段答案照旧从返回值走，
     * 记录、历史、回镜都不提前用这一句 —— 提前交出去的只有声音。不传就是非流式，行为同旧。
     *
     * [onLocalFirst]：§5.1-2「先答后补」。技能有本机话术、模型等了 [localFirstMs] 还没出第一个字时，
     * 这句本机话术交出去让屏幕先开口；模型后面回来照常用 [onSentence]/返回值补充。
     * 交出去的那句在返回值上带 [AiReply.answeredEarly]（仅当模型最终没回来），宿主入账但别再说第二遍。
     */
    suspend fun ask(
        question: String,
        ctx: SkillContext,
        scene: TurnScene? = null,
        onSentence: (String) -> Unit = {},
        onLocalFirst: (AiReply) -> Unit = {},
    ): AiReply {
        val q = question.trim()
        if (q.isEmpty()) return AiReply("想说点什么？", skill = null, source = ReplySource.LocalRule)
        val persona = Persona.of(scene)

        val skill = skills.match(q)
        if (skill == null) {
            // 没命中关键词也要能答：走模型，但事实表里只有状态机给得出的东西。
            // 事实表按人格取材 —— 亲子/助手态不喂卫星，模型手里没卫星料就绕不回卫星
            val facts = summarize(ctx, persona)
            val out = model(facts, q, persona, onSentence = onSentence)
            return record(
                if (out != null) AiReply(guard(out), skill = null, source = ReplySource.Model)
                else AiReply(noMatch(persona), skill = null, source = ReplySource.LocalFallback),
                q,
                persona,
            )
        }

        if (!PermissionTier.allows(granted, skill.needs)) {
            log("拦截 ${skill.id}：需要「${skill.needs.label}」，当前只到「${granted.label}」")
            // 被拦的技能不跑 handle —— handle 是取数，出网的技能没拿到授权就不能出网
            val line = skill.deniedLine?.invoke(ctx) ?: withContext(Dispatchers.IO) { skill.handle(ctx) }.line ?: "这条我不做。"
            return record(
                AiReply(
                    line,
                    skill = skill.id,
                    source = ReplySource.Denied,
                ),
                q,
                persona,
            )
        }

        // handle 是当场取数：天气那一路是同步 HTTP（缓存过期时要真的等一个往返）。
        // 调用方在 Main 上，摊在主线程里会直接 NetworkOnMainThreadException，
        // 又被 Weather 的 runCatching 吞掉 —— 手机上天气就永远取不到，彩排台却一切正常
        val result = withContext(Dispatchers.IO) { skill.handle(ctx) }
        val facts = buildString {
            append("技能 ").append(skill.id).append("（").append(result.intent).append("）\n")
            result.fields.forEach { (k, v) -> append("· ").append(k).append("=").append(v).append('\n') }
        }
        // 没回来时这行是唯一说得清「屏上那句到底是谁写的」的证据：不打的话，
        // 现场看到的就是「明明配了大模型，念出来的却是本机话术」，而且没有任何地方报错
        //
        // §5.1-2 先答后补：到点模型一个字没出，本机那句就先开口。两条纪律：
        // 定时器**不许用 withTimeout** —— 那会取消在飞的 HTTP，等于把"模型后面补充"一起掐死；
        // 流式只要吐过一句就不抢嘴 —— 模型的话已经让屏幕开口了，再插一句本机话术是双嘴红线。
        val spokeAny = AtomicBoolean(false)
        val earlyFired = AtomicBoolean(false)
        val streamGate: (String) -> Unit = { s -> spokeAny.set(true); onSentence(s) }
        val out: String? = coroutineScope {
            val job = if (result.line != null && provider.live && localFirstMs > 0) launch {
                delay(localFirstMs)
                if (!spokeAny.get()) {
                    earlyFired.set(true)
                    onLocalFirst(AiReply(guard(result.line), skill = skill.id, source = ReplySource.LocalRule))
                }
            } else null
            val answer = model(
                facts + summarize(ctx, persona), q, persona,
                fallbackNote = "用技能自己的话术兜底", onSentence = streamGate,
            )
            job?.cancel()
            answer
        }
        return record(
            AiReply(
                text = out?.let { guard(it) } ?: result.line ?: "这条我现在说不了。",
                skill = skill.id,
                source = if (out != null) ReplySource.Model else if (provider.live) ReplySource.LocalFallback else ReplySource.LocalRule,
                mocked = result.mocked,
                // 模型这回没回来、本机那句又已经先答过：这一条只是重播，宿主入账但不许再开口
                answeredEarly = earlyFired.get() && out == null,
            ),
            q,
            persona,
        )
    }

    private fun record(reply: AiReply, question: String, persona: Persona): AiReply {
        // 历史按人格分道（§5.2），入哪条道、下次从哪条道取，都跟这轮的人格走
        val t = trackFor(persona)
        t.turns.addLast(ChatTurn("user", question))
        t.turns.addLast(ChatTurn("assistant", reply.text))
        while (t.turns.size > MAX_TURNS) t.turns.removeFirst()
        t.lastMs = nowMs()
        return reply
    }

    /**
     * 主动播报**不进模型历史**。它进的是 [ConversationLog]（用户时间线），不是这里。
     *
     * 为什么分开：心跳最短的冷却 60 秒，一条提醒就是历史里的一轮，发给模型只取最后 6 条 ——
     * 连着几条「围栏越界」「额度快满」就把用户真正问过的那几轮挤出窗口，
     * 模型接着答的时候前面没有来由，答非所问。忙时那句「我在想上一条」走的也是这条，
     * 它更不该出现在下一轮的事实里。
     */
    fun recordProactive(text: String, skill: String?): AiReply =
        AiReply(text, skill = skill, source = ReplySource.LocalRule, proactive = true)

    /**
     * 真模型那一趟。两个调用点只差兜底那句的措辞，所以失败日志在这里统一带上耗时和原因。
     * 没有这行，"屏上这句到底是模型写的还是本机话术"在日志里就分不出来（同 2026-11-06 那条）。
     */
    private suspend fun model(
        facts: String,
        question: String,
        persona: Persona,
        fallbackNote: String? = null,
        onSentence: (String) -> Unit = {},
    ): String? {
        if (!provider.live) return null
        val t0 = nowMs()
        // 首句时刻在 IO 线程上写、协程恢复后在别的线程读：用一个数说清，不靠可见性运气
        val firstMs = AtomicLong(-1L)
        // 发给模型的历史只取这一轮人格那一条道（§5.2）；会话窗口也在这里收
        val out = provider.completeStream(facts, trackFor(persona).turns.toList(), question, persona) { s ->
            if (firstMs.get() < 0) firstMs.set(nowMs() - t0)
            // 红线逐句过：这一句马上要合成开口，等不到整段回来再把关
            onSentence(guard(s))
        }
        val ms = nowMs() - t0
        if (out == null) {
            log(
                "${provider.label} 这句没回来（${provider.lastError ?: "原因未知"}），用时 $ms ms" +
                    (fallbackNote?.let { "，$it" } ?: ""),
            )
        } else {
            val head = if (firstMs.get() >= 0) "，首句 ${firstMs.get()} ms" else ""
            log("${provider.label} 用时 $ms ms$head")
        }
        return out
    }

    /**
     * 模型输出的文案红线。**红线不只约束我们写的模板，也约束别人的模型**：
     * 出现 [SosCopy.BANNED] 里任何一句就整条换成不越界说法 —— 一句"救援已在路上"的代价我们担不起。
     *
     * public 是有意的：[ConversationLog] 要拿它当入站那道闸。小智云那一路的话不是这个网关生成的，
     * 但它同样要上屏、同样会永久留在车主的记录里，所以同样得过这一道。
     */
    fun guard(text: String): String {
        val hit = SosCopy.BANNED.firstOrNull { text.contains(it) }
        if (hit == null) return text
        log("模型输出踩线（$hit），已替换：${text.take(80)}")
        return "这句话我不能替平台说 —— 我只能确认卫星这一侧的状态。"
    }

    /** 亲子和助手态不谈卫星 —— 兜底话术也分场景，别说"可以说链路"给人指到卫星上去 */
    private fun noMatch(persona: Persona): String = when (persona) {
        Persona.Guardian -> NO_MATCH
        else -> "这件事我现在没有数据，不能凭印象答。天气、聊天、讲故事都可以问我。"
    }

    companion object {
        private const val MAX_TURNS = 20

        private const val NO_MATCH =
            "这件事我现在没有数据，不能凭印象答。可以问「链路」「卫星什么时候来」「额度」「圆屏配对」「车在哪」。"

        /** 发给模型的事实表。null 一律写成「未知」，不留给模型自由发挥的空位。
         *  卫星/链路那一组只在守护人格下进表（owner 11-15：对话别全绕着卫星） */
        fun summarize(c: SkillContext, persona: Persona = Persona.Guardian): String = buildString {
            append("场景=").append(c.scene)
            if (persona.satelliteFacts) {
                append(" 相位=").append(c.phase ?: "未知")
                append(" 距下次过顶=").append(c.secondsToNextPass?.toString() ?: "未知").append("秒")
                append(" 终端=").append(when (c.moduleOnline) { true -> "在线"; false -> "离线"; null -> "未知" })
                append(" 待发=").append(c.queueWaiting?.toString() ?: "未知")
                append(" 槽位=").append(c.queueCapacity?.toString() ?: "未知")
                append(" 已交核心网=").append(c.handedOver?.toString() ?: "未知")
                append(" 围栏=").append(if (c.fenceActive == true) (if (c.fenceInside == true) "在内" else "越界") else "未开")
            }
            append(" 定位=").append(if (c.gnssLat != null && c.gnssLon != null) "%.5f,%.5f".format(c.gnssLat, c.gnssLon) else "无")
            if (persona.satelliteFacts) {
                append(" 在线圆屏=").append(c.screensOnline?.toString() ?: "未知").append("台")
            }
            append(" 手机电量=").append(c.phoneBattery?.toString() ?: "未知").append("%")
            // 屏上那几句原话必须一起给模型：不给的话"娃在屏上说了啥"到了真模型那一侧只能编，
            // 而编出来的那句会以"远端模型"的名义永久留在车主的对话记录里
            if (c.screenTalk.isNotEmpty()) append(" 屏上说过=").append(c.screenTalk.joinToString(" | "))
        }
    }
}
