package com.carpet.satdemo.core.protocol

import com.carpet.satdemo.core.ephemeris.EphState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 展示端 <-> App 消息模型。字段和 wire 上的 snake_case 一一对应，
 * Kotlin 侧保持 camelCase 习惯。契约变更以 protocol/screen-link.schema.json 为准。
 */
object ScreenCodec {
    /**
     * 契约 v2：加了 `dialog` 通道（对话记录以手机为单一事实源，圆屏是耳朵和嘴）。
     *
     * **v1 的连接仍然全部可用**：state / event / command / ack 的形状一个字段都没改，
     * 所以这里收 1 和 2 两个版本，协商看 `hello.protocols`（不看帧上的 `v` —— 出站帧
     * 因为 encodeDefaults=false 根本不写 `v`，把它当依据会在真固件上猜错）。
     * 唯一的差别是 v2 才发 `dialog`，服务端按连接逐个过滤。
     */
    const val CONTRACT_VERSION = 2
    val SUPPORTED_VERSIONS: Set<Int> = setOf(1, 2)

    /**
     * 这条连接能不能收对话。取双方都支持的最高版。
     *
     * 空/缺失一律按 v1：**宁可不说话，也不把对话发给一个画不出对话的固件**
     * （取更严的那头，和 §6 隐私档同一条思路）。
     */
    fun negotiated(protocols: List<Int>?): Int =
        protocols?.filter { it in SUPPORTED_VERSIONS }?.maxOrNull() ?: 1

    /**
     * 这固件我们到底能不能对话。**没申报 = 能**（v1 的 state/event 形状一个字段没改，
     * 老固件照样画得出状态，只是不能说话）；申报了但全是我们不认的版本才是真不匹配，
     * 那种情况要当场拒绝并回 `ack{error:"proto_mismatch"}`，别让它以为连上了。
     */
    fun compatible(protocols: List<Int>?): Boolean =
        protocols == null || protocols.any { it in SUPPORTED_VERSIONS }

    val json: Json = Json {
        // kotlinx 1.3 起默认不写出 null 字段，正好是我们要的紧凑 wire
        encodeDefaults = false
        ignoreUnknownKeys = true
    }
}

@Serializable
enum class MsgType { hello, bye, state, event, command, ack, dialog }

@Serializable
enum class DockType { none, desk_stand, car_mount, collar, gym_belt, toy }

@Serializable
enum class SatPhase { idle, searching, locked, tx_pending, tx_ok, tx_fail, tx_no_ack }

@Serializable
enum class LinkPrimary { cell, sat }

@Serializable
enum class GnssSource { phone, gt1005, fused }

@Serializable
enum class SosLevel { normal, armed, pending, sent, acked }

@Serializable
enum class EventKind {
    sos_triggered, sos_acked, sos_failed, sos_cancelled,
    fence_enter, fence_exit,
    sentinel_motion,
    link_switched, sat_visible,
    downlink_msg,
    track_reset,
}

@Serializable
enum class CommandType { sos_press, sos_cancel, track_pull, set_demo_mode, set_fence, reboot_ui }

/** 对话通道。[up]=屏上说的话（要手机回答），[down]=手机说的话（要在屏上显示） */
@Serializable
enum class DialogDir { up, down }

/** 这一轮是谁先起的。记录里必须分得出来 —— 车主想知道"刚才是不是有人在车那边说话" */
@Serializable
enum class DialogOrigin { screen, phone }

/**
 * 一句话是从哪儿来的。**这条决定界面上打不打「未接真源」那个标，不许省**。
 * [xiaozhi_cloud] 是小智官方云全包那条路线的产物：话不是我们的网关生成的，
 * 落到记录和屏幕之前仍要过同一道文案闸（见 [com.carpet.satdemo.core.ai.AiGateway.guard]）。
 */
@Serializable
enum class DialogSource { asr, local_rule, model, local_fallback, denied, xiaozhi_cloud }

@Serializable
data class DialogPayload(
    /** 一轮问答一个 id。上行和下行共用它，所以手机能把"问"和"答"钉在同一轮上，也天然去重 */
    @SerialName("turn_id") val turnId: String,
    val dir: DialogDir,
    val text: String,
    val source: DialogSource = DialogSource.local_rule,
    val skill: String? = null,
    val origin: DialogOrigin = DialogOrigin.screen,
)

@Serializable
data class HelloPayload(
    @SerialName("screen_id") val screenId: String,
    val fw: String,
    @SerialName("dock_type") val dockType: DockType = DockType.none,
    @SerialName("wifi_rssi") val wifiRssi: Int? = null,
    @SerialName("battery_mv") val batteryMv: Int? = null,
    /**
     * 固件声明它认的契约版本。null = 固件压根没这一项（第一版小智固件就是这样），
     * 按 v1 处理：state/event 的形状一个字段都没改，它照样画得出。
     * **默认值不能是当前版本** —— 漏报就被当成最新档，服务端会往一个画不出对话的固件上推 dialog。
     */
    val protocols: List<Int>? = null,
)

@Serializable
data class LinkState(
    val primary: LinkPrimary,
    @SerialName("cell_ok") val cellOk: Boolean,
    @SerialName("cell_dbm") val cellDbm: Int? = null,
    @SerialName("sat_ok") val satOk: Boolean,
)

@Serializable
data class SatState(
    val phase: SatPhase,
    @SerialName("sat_name") val satName: String? = null,
    val cnr: Double? = null,
    /** null = 星历缓存不可用 */
    @SerialName("next_pass_in_s") val nextPassInS: Int? = null,
    @SerialName("module_battery") val moduleBattery: Int? = null,
    val fw: String? = null,
    /**
     * GT1005 本身在不在。掉线和「在窗口外没锁到星」是两件事：
     * 前者是硬件没了，展示端要说"终端离线"，后者才是"等过顶"。
     */
    @SerialName("module_ok") val moduleOk: Boolean = true,
    /** App 侧攒着待补发的条数。掉线期间这个数字一直涨，就是"一条不能丢"的凭据 */
    @SerialName("queue_pending") val queuePending: Int = 0,
    /**
     * 因为队列满了被挤掉/拒收的条数（E.10 #8）。容量按模组型号注入（GM1001 500 / GM1003 50），
     * 所以这个数字非零是**可能**发生的：展示端要把它画出来，而不是假装"一条没丢"。null = 没有队列概念
     */
    @SerialName("queue_dropped") val queueDropped: Int? = null,
    /**
     * 队列容量，同样是型号口径（E.10 #8）。展示端画"N/额度"才有紧迫感 ——
     * 光说"待补发 47 条"没人知道这离 50 条的上限只差 3 条。
     */
    @SerialName("queue_capacity") val queueCapacity: Int? = null,
    /**
     * 距上次成功推送 TPI 多少秒（E.5）。模组那边的位置是**我们喂给它的**，
     * 它过期就代表下行可能收不到 —— 这不再是可省的遥测。
     */
    @SerialName("tpi_age_s") val tpiAgeS: Long? = null,
    /** TPI 陈旧到不能信。展示端据此打「位置已过期 N 分钟，卫星可能投递失败」。null = 这条链路没有 TPI 概念（比如场景层替身） */
    @SerialName("tpi_stale") val tpiStale: Boolean? = null,
    /** 模组固件升级进度 0~99；没在升级为 null。升级期间所有发送都被写锁挡下（E.10 #6） */
    @SerialName("upgrade_pct") val upgradePct: Int? = null,
    /**
     * 星历算得出来吗（E.10 #7）。和"锁没锁到星"是两件事：
     * 星历没了只是**不知道**什么时候有窗，天上周期的窗口照样在。
     */
    @SerialName("eph_state") val ephState: EphState? = null,
    /**
     * 星历不可用时那行人话。文案必须由 App 出、**不给固件自己编** ——
     * 〔E.9〕最容易犯的错就是把 `NETWORK_ERROR` 显示成"无卫星信号"，
     * 用户会举着天线在空旷处等，而真正该做的是连一次网。
     */
    @SerialName("eph_fail") val ephFail: String? = null,
    /**
     * 上一次星历**算成功**的时刻（epoch 毫秒）。失败块里那句"上次成功是 09:14"用它。
     * null = 从没算成功过，这时屏上只能说"还没算出过窗口"，不许拿别的字段凑一个时间。
     */
    @SerialName("eph_ok_at_ms") val ephOkAtMs: Long? = null,
    /** 当前（或下个）窗口长度，秒。厂商给的 endTime-startTime，比"还有 N 秒"能用的地方多 */
    @SerialName("next_pass_dur_s") val nextPassDurS: Int? = null,
    /** 这次过境的最大仰角，度。低仰角那颗容易被楼和山体挡掉 */
    @SerialName("next_pass_max_el") val nextPassMaxEl: Double? = null,
    /** 该把车头/天线朝哪边：正北 0° 顺时针。窗口内是插值出来的瞬时方位角 */
    @SerialName("aim_az") val aimAz: Double? = null,
)

@Serializable
data class GnssState(
    val lat: Double,
    val lon: Double,
    @SerialName("alt_m") val altM: Double? = null,
    val hdop: Double? = null,
    val source: GnssSource,
    @SerialName("fix_age_s") val fixAgeS: Double? = null,
)

@Serializable
data class FenceState(
    val active: Boolean,
    val inside: Boolean,
    val name: String? = null,
    /** 到边界的距离，出界为负 */
    @SerialName("dist_m") val distM: Double? = null,
)

@Serializable
data class SosState(
    val level: SosLevel,
    @SerialName("since_ts") val sinceTs: Long? = null,
    val attempts: Int? = null,
    /**
     * **卫星**有没有应答。true 不等于平台收到（〔规 §3.1.4 注6〕），
     * 所以文案里"已回执"只能由 level==acked 触发，acked 只由平台回执置位。
     */
    @SerialName("sat_ack") val satAck: Boolean? = null,
    /** 失败成因（no_reply / queue_full / …）。给圆屏那行小字用，正常时为 null */
    val fail: String? = null,
)

@Serializable
data class SentinelState(
    val armed: Boolean = false,
    @SerialName("last_motion_ts") val lastMotionTs: Long? = null,
    @SerialName("drift_m") val driftM: Double? = null,
)

@Serializable
data class StatePayload(
    val link: LinkState,
    val sat: SatState,
    val gnss: GnssState,
    val fence: FenceState,
    val sos: SosState,
    val sentinel: SentinelState? = null,
    @SerialName("phone_battery") val phoneBattery: Int? = null,
    @SerialName("demo_mode") val demoMode: Boolean = false,
)

@Serializable
data class EventPayload(
    val kind: EventKind,
    @SerialName("event_id") val eventId: String,
    val payload: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
    /** 展示端收到时若已过期则丢弃且不弹提示 */
    @SerialName("ttl_ms") val ttlMs: Long? = null,
)

@Serializable
data class CommandPayload(
    val cmd: CommandType,
    val args: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
)

@Serializable
data class AckPayload(
    @SerialName("for_seq") val forSeq: Long,
    /** 确认的是哪条 event。会话类 ack（hello 的回执）没有它 */
    @SerialName("for_event_id") val forEventId: String? = null,
    /** 非空表示拒绝，目前只有 proto_mismatch */
    val error: String? = null,
)

@Serializable
data class ScreenMessage(
    val v: Int = ScreenCodec.CONTRACT_VERSION,
    val t: MsgType,
    val seq: Long,
    val ts: Long? = null,
    val hello: HelloPayload? = null,
    val state: StatePayload? = null,
    val event: EventPayload? = null,
    val command: CommandPayload? = null,
    val ack: AckPayload? = null,
    val dialog: DialogPayload? = null,
) {
    init {
        // 镜像 schema 里的 allOf 条件：t 决定哪个负载必填。
        // 违反即抛，让两端在单测阶段就炸，而不是运行时静默发错。
        val required: Any? = when (t) {
            MsgType.hello -> hello
            MsgType.state -> state
            MsgType.event -> event
            MsgType.command -> command
            MsgType.ack -> ack
            MsgType.dialog -> dialog
            MsgType.bye -> null
        }
        val key = when (t) {
            MsgType.bye -> null
            else -> t.name
        }
        require(v in ScreenCodec.SUPPORTED_VERSIONS) { "unsupported contract version $v" }
        require(seq >= 0) { "seq must be >= 0, got $seq" }
        if (key != null) require(required != null) { "t=$t requires payload '$key'" }
    }

    companion object {
        fun decode(text: String): ScreenMessage = ScreenCodec.json.decodeFromString(serializer(), text)
    }

    fun encode(): String = ScreenCodec.json.encodeToString(serializer(), this)
}
