package com.carpet.satdemo.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 展示端 <-> App 消息模型。字段和 wire 上的 snake_case 一一对应，
 * Kotlin 侧保持 camelCase 习惯。契约变更以 protocol/screen-link.schema.json 为准。
 */
object ScreenCodec {
    const val CONTRACT_VERSION = 1

    val json: Json = Json {
        // kotlinx 1.3 起默认不写出 null 字段，正好是我们要的紧凑 wire
        encodeDefaults = false
        ignoreUnknownKeys = true
    }
}

@Serializable
enum class MsgType { hello, bye, state, event, command, ack }

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
    sos_triggered, sos_acked, sos_failed,
    fence_enter, fence_exit,
    sentinel_motion,
    link_switched, sat_visible,
    downlink_msg,
    track_reset,
}

@Serializable
enum class CommandType { sos_press, track_pull, set_demo_mode, set_fence, reboot_ui }

@Serializable
data class HelloPayload(
    @SerialName("screen_id") val screenId: String,
    val fw: String,
    @SerialName("dock_type") val dockType: DockType = DockType.none,
    @SerialName("wifi_rssi") val wifiRssi: Int? = null,
    @SerialName("battery_mv") val batteryMv: Int? = null,
    val protocols: List<Int> = listOf(ScreenCodec.CONTRACT_VERSION),
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
     * 距上次成功推送 TPI 多少秒（E.5）。模组那边的位置是**我们喂给它的**，
     * 它过期就代表下行可能收不到 —— 这不再是可省的遥测。
     */
    @SerialName("tpi_age_s") val tpiAgeS: Long? = null,
    /** TPI 陈旧到不能信。展示端据此打「位置已过期 N 分钟，卫星可能投递失败」。null = 这条链路没有 TPI 概念（比如场景层替身） */
    @SerialName("tpi_stale") val tpiStale: Boolean? = null,
    /** 模组固件升级进度 0~99；没在升级为 null。升级期间所有发送都被写锁挡下（E.10 #6） */
    @SerialName("upgrade_pct") val upgradePct: Int? = null,
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
            MsgType.bye -> null
        }
        val key = when (t) {
            MsgType.bye -> null
            else -> t.name
        }
        require(v == ScreenCodec.CONTRACT_VERSION) { "unsupported contract version $v" }
        require(seq >= 0) { "seq must be >= 0, got $seq" }
        if (key != null) require(required != null) { "t=$t requires payload '$key'" }
    }

    companion object {
        fun decode(text: String): ScreenMessage = ScreenCodec.json.decodeFromString(serializer(), text)
    }

    fun encode(): String = ScreenCodec.json.encodeToString(serializer(), this)
}
