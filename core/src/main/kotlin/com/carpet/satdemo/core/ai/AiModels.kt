package com.carpet.satdemo.core.ai

import kotlinx.serialization.Serializable

/**
 * AI 网关的数据面（§8 近期四件事 #4）。
 *
 * 这一层存在的理由只有一条：**对话不能等于「接一个大模型」**。这个产品里「车到哪了」「还剩几条额度」
 * 「现在能发 SOS 吗」的正确答案来自我们自己的状态机，不是模型的记忆 —— 模型负责把已有的事实说成人话，
 * 不负责发明事实。所以路由在前、取数在中、生成在最后，权限拦在网关上。
 */

/**
 * 权限五档。Skill 自己声明"我做完这件事要动到哪一档"，网关拿当前授予档比 ——
 * **默认只给 [PermissionTier.ReadLocal]**，出网和不可逆动作必须显式开（§6 隐私档同一条思路：取更严的）。
 */
enum class PermissionTier(val rank: Int, val label: String) {
    /** 只读 App 内已有的状态，不碰设备也不出网 */
    ReadLocal(0, "只读本机"),

    /** 读云端（星历、天气、账号） */
    ReadCloud(1, "读云端"),

    /** 往设备写：圆屏内容、模组参数 */
    WriteDevice(2, "写设备"),

    /** 出网发消息：卫星报文、IM */
    NetworkOut(3, "出网发送"),

    /** 不可逆：SOS、远程熄火、扣费 */
    Irreversible(4, "不可逆动作"),
    ;

    companion object {
        fun allows(granted: PermissionTier, needed: PermissionTier) = needed.rank <= granted.rank
    }
}

/**
 * 一次对话要能看到的事实。**由调用方（App / 圆屏）从自己的状态机填**，网关不认识 DemoRuntime，
 * 这样这一层在 JVM 上就能测完，也避免 AI 层反过来拖住链路层。
 *
 * 每个字段都允许 null —— null 的意思是"这条现在没有真数据源"，Skill 必须把它当成
 * 「说不出口」而不是「编一个」。
 */
@Serializable
data class SkillContext(
    val scene: String = "未知",
    val phase: String? = null,
    val secondsToNextPass: Int? = null,
    val passWindowSec: Int? = null,
    val moduleOnline: Boolean? = null,
    val gnssLat: Double? = null,
    val gnssLon: Double? = null,
    val queueWaiting: Int? = null,
    val queueCapacity: Int? = null,
    val handedOver: Int? = null,
    val phoneBattery: Int? = null,
    val ephemerisFresh: Boolean? = null,
    val fenceActive: Boolean? = null,
    val fenceInside: Boolean? = null,
    val screensOnline: Int? = null,
    val aiEndpointConfigured: Boolean = false,
)

/** Skill 的取数结果。[fields] 是给模型的结构化上下文，也是日志与单测要比对的东西 */
@Serializable
data class SkillResult(
    val skillId: String,
    val intent: String,
    val fields: Map<String, String> = emptyMap(),
    /** 我们已经能确定说出口的那句话。没有真源时它是空状态话术，不是猜测 */
    val line: String? = null,
    /** 这条结论里的数字是不是桩 —— 桩必须一路带到界面上，不能在网关里被洗成事实 */
    val mocked: Boolean = false,
    val blocked: PermissionTier? = null,
)

/** 一次问答的完整回执。[source] 决定界面上要不要打「未接真源」那个标 */
data class AiReply(
    val text: String,
    val skill: String?,
    val source: ReplySource,
    val mocked: Boolean = false,
    val proactive: Boolean = false,
)

enum class ReplySource {
    /** 从我们自己的状态机直接答出来的，没经过模型 */
    LocalRule,

    /** 真模型端点生成的 */
    Model,

    /** 该走模型但端点没配，退回本地话术 —— 这条必须让界面看得出来 */
    LocalFallback,

    /** 权限没开，网关拦下了 */
    Denied,
    ;

    val live: Boolean get() = this == Model
}
