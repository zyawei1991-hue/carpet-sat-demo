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
     * 契约 v3：加 `ui.card = maint`（`docs/MAINTENANCE.md` M3）、`hello.screen`（面板几何，
     * 产品架构 §4 实现要求 #4），以及答句的语音通道 `command{cmd:"audio_chunk"}`。
     *
     * **v1 的连接仍然全部可用**：state / event / command / ack 的形状一个字段都没改，
     * 所以这里收 1、2、3 三个版本，协商看 `hello.protocols`（不看帧上的 `v` —— 出站帧
     * 因为 encodeDefaults=false 根本不写 `v`，把它当依据会在真固件上猜错）。
     * 差别只有几条：v2 才发 `dialog`，v3 才发 `audio_chunk`，**v4 才收 `mic_chunk`**（上行，
     * 屏把采好的 PCM 推给手机转写 —— 这也是为什么 v4 是入站那一侧的版本：
     * 收这帧的闸在 [ScreenLinkServer] 的入站路径，不在 broadcast 的逐连接过滤里）。
     * 服务端按连接逐个过滤。
     *
     * 音频走 `command` 而不是新的 `t`，是因为固件对**解不开的 t 直接关连接**（那边
     * `handle_frame` 的最后一条分支），而不认识的 cmd 只是忽略。一条新帧类型会让任何
     * 没升到 v3 的屏当场掉线，而"掉线"在现场永远被当成 Wi-Fi 抖。
     */
    const val CONTRACT_VERSION = 4
    val SUPPORTED_VERSIONS: Set<Int> = setOf(1, 2, 3, 4)

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

    /**
     * 发给这条连接之前，按它的版本把这一帧改回它画得出来的样子。
     *
     * `dialog` 和 `audio_chunk` 是**整帧**新增，逐连接过滤帧类型就够了；`ui.card = maint` 不是 ——
     * 它骑在 v1 就有的 `state` 帧里，过滤帧类型过滤不到它。`docs/MAINTENANCE.md` 验收判据 #4
     * 要的是「没申报 protocols 的连接永远收不到 maint」，所以这里把它降回 `status`。
     * 老固件那张 `UI_CARD` 表查不到 maint 时画的是「—」，那是"手机上换了卡、屏上一根横杠"，
     * 比退回总览难看得多也更难解释。
     */
    fun forVersion(msg: ScreenMessage, ver: Int): ScreenMessage {
        // maint 是 v3 加的那一档，判据跟着它的引入版本走，**不能**写成 >= CONTRACT_VERSION：
        // 那样每抬一次主版本，上一代的屏就被错误降级一次（v3 屏收到 status，"该做的事"一行凭空消失）
        if (ver >= 3) return msg
        val state = msg.state ?: return msg
        val ui = state.ui ?: return msg
        if (ui.card != UiCard.maint) return msg
        return msg.copy(state = state.copy(ui = ui.copy(card = UiCard.status)))
    }

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

/**
 * 屏 → 手机的上行动作。
 *
 * **这里没有 `card_set`，是故意的**：切卡那三条（[card_next] / [card_auto] / [poke]）都只是
 * **请求**，画哪一张仍由手机算出来写进 `ui.card`。定卡权一旦下放到屏，同一辆车就会在手机和屏上
 * 讲两件事，而 §6.1 那套文案红线只约束得过网关这一路。请求制还留了一个拒绝对：
 * SOS 正在走的时候 [card_next] 会被顶回"呼救还没走完"，屏上那张卫星卡切不走。
 *
 * - [card_next]：左右滑。`args.dir` = +1 往后一张、-1 往前一张。
 * - [card_auto]：把定卡权交回 [com.carpet.satdemo.core.screen.CardPolicy] 那条优先序（手动钉住之后的出口）。
 * - [poke]：单击。手机换一张脸并可以答一句 —— "互动表情"这条链的起点。
 */
@Serializable
enum class CommandType {
    sos_press, sos_cancel, track_pull, set_demo_mode, set_fence, reboot_ui,
    card_next, card_auto, poke, page_report,

    /**
     * v3 的两条**下行**指令之一（其余全是屏→手机）：
     *
     * - [audio_chunk]：答句的语音分块推给屏。屏拿到文字就念（现有那条带），
     *   拿到这块就接着放 —— 文字和语音是两帧，所以 TTS 慢、失败、或者根本没接，
     *   都不会把答句扣在手机里。
     * - [wake]：把熄了屏的圆屏点亮。屏那边落的是 **POWER 键同一条路**
     *   （熄屏清掉 + 面板起来 + Wi-Fi 立刻重连，不等拨号退避）。
     *   **不回 ack、不重试、不排队**：屏真醒了下一步会自己推 `state` / `page_report`，
     *   那才是证据；发出去 3 秒这条连接没动静就当没叫醒，要说话，别静默。
     *   硬前提是那条 WebSocket 还活着 —— 屏在电池上熄屏满 2 分钟会 nap（关 Wi-Fi），
     *   那一刻这一帧送不到，只有按 POWER 叫得醒；所以它只在屏的设置页
     *   「熄屏后保持联网」开着时才成立。发送时机见 `docs/APP-SCREEN-CONNECT.md` §10。
     *
     * 为什么两条都塞在 `command` 里而不是新起一个 `t`：见 [ScreenCodec.CONTRACT_VERSION]。
     * 固件解不开陌生的 `t` 是**直接关连接**，收到不认识的 `cmd` 只是丢这一帧 ——
     * 后者才是加性修订该有的失败方式。
     */
    audio_chunk, wake,

    /**
     * v4 的**上行**指令（其余上行全是"请求"，这条是数据）：屏把按住说话那一段采好的
     * 16k/单声道/s16le 裸 PCM 按 [audio_chunk] 同一套 args（`turn_id/seq/total/rate/b64`）
     * 一口气推给手机，手机拼齐、转写（`SiliconFlowAsr.transcribe`），再照 v3 那条下行把
     * `dialog{dir:"up"}`（屏该显示的转写）和 `dialog{dir:"down"}`（答句）送回去。
     * `turn_id` 由屏在按下的那一刻生成（`m-` 前缀），手机**不许另起一个** —— 拼段、去重、
     * 答句配对全靠它。上限 8 秒 = 100 帧 = 256 KB PCM，超出整段拒收。
     *
     * 加这一枚的同时 `CONTRACT_VERSION` 必须同版抬到 4：屏那道闸现在认的是
     * **帧外层的 `v` ≥ 4 且 ack.protocols ∋ 4 两个都点头**，只报 4 而解不了的版本
     * 实测 `armed=0` 一条上行都不发 —— 申报和实现分家比不申报更危险
     * （`protocol/screen-link.md` §上行音频通道）。
     */
    mic_chunk,
}

/**
 * 圆屏页面树上**会上报给手机**的那几层。
 *
 * 分权是这么切的：**哪一页由屏自己定**（触摸驱动，不经手机往返），
 * **这一页里说什么内容由手机定**（还是 [UiState.card] 那一帧）。
 * 这张表存在的唯一理由是手机得知道屏实际在哪儿 —— 首页那块镜像要是写着「屏在状态盘」
 * 而玻璃上正在放孩子的录音，观众先怀疑的是屏坏了，而我们一直在防的就是这种分叉。
 *
 * [face]/[picker] 是 L0/L1（Muse 那张脸、场景选择），其余五页是 L2 场景页。
 * **L3 功能页不上报**：屏里第三层是屏自己的排页，手机上没有对应的说法，
 * 每深一层多报一层，换来的是每一次滑动能让两侧各说一遍话。
 *
 * 刻意没有 `health`：屏上画不出那一页。枚举里先给一个对面渲染不出来的值，
 * 换来的只有演示现场一次切不过去（和 [UiCard] 里不放 `image` 同一条理由）。
 */
@Serializable
enum class ScreenPage { face, picker, vehicle, kids, avatar, clock, gallery, photo }

/**
 * 圆屏**当前那一页里**此刻该说哪件事。**这一层仍由手机定，固件不许自己挑内容**
 * —— 同一个 ui 缺失时它退回 [status]，那一档也是这里的一员，不是代码里的隐式默认。
 *
 * 它从「整块屏全屏画哪一张」降级成了「这一页里画哪一条」：页由屏定（见 [ScreenPage]），
 * 话仍由手机定。SOS 现在还是走这一档（策略把 sat 卡钉在最优先），
 * 而商定的那层**全屏抢占**（`SOS 确认 / 平台回执 / 越界` 三类 `event` 盖住任何一页）还没接线 ——
 * 手机端 `EventKind` 里那几条早就有了，缺的是屏那侧的 `on_event` 落地。
 *
 * 这里刻意没有 `image`：契约传不动一张照片（见 protocol/screen-link.md 待办那条）。
 *
 * `maint` 是 v3 加的（`docs/MAINTENANCE.md` M3）：**一行该做的事，不带数字、不带判据**。
 * 1.75" 那一页装不下"读数 + 为什么"两行，而"为什么"是手机的事。内容的决定权仍在
 * [com.carpet.satdemo.core.screen.CardPolicy] —— 屏上出现一条手机没推过的建议，
 * 「每句话都有出处」这条主张当场就没了，所以固件不许自己算推荐、也不许自己挑哪一条。
 */
@Serializable
enum class UiCard { status, sat, dialog, emotion, clock, maint }

/**
 * 这块屏当下「穿着」的那张脸。它不是 [UiCard.emotion] 专用字段：
 * 对话卡把脸画在文字上方、表情卡把它铺满整屏，读的是同一个值，
 * 所以 App 改一次就两边都跟着变。屏上的脸是矢量现画的，不下图。
 */
@Serializable
enum class UiEmotion { neutral, happy, excited, sad, worried, sleepy, thinking }

/** 对话通道。[up]=屏上说的话（要手机回答），[down]=手机说的话（要在屏上显示） */
@Serializable
enum class DialogDir { up, down }

/** 这一轮是谁先起的。记录里必须分得出来 —— 车主想知道"刚才是不是有人在车那边说话" */
@Serializable
enum class DialogOrigin { screen, phone }

/**
 * 一句话是从哪儿来的。**这条决定界面上打不打「未接真源」那个标，不许省**。
 * [xiaozhi_cloud] 是小智官方云全包那条路线的产物，[muse_cloud] 是换了 Muse 底座之后
 * 屏本机那条 ASR→云→TTS 的链 —— 两档同一种话：**不是我们的网关生成的**。
 * 落到记录和屏幕之前仍要过同一道文案闸（见 [com.carpet.satdemo.core.ai.AiGateway.guard]）。
 */
@Serializable
enum class DialogSource { asr, local_rule, model, local_fallback, denied, xiaozhi_cloud, muse_cloud;

    /**
     * 屏那侧的模型替我们说完的。三条规矩挂在它身上：只入账、不触发技能、不回镜。
     * 写成谓词而不是在两处各列一遍名字，是为了下一档云进来时不会只改一处。
     */
    val foreignCloud: Boolean get() = this == xiaozhi_cloud || this == muse_cloud
}

@Serializable
data class DialogPayload(
    /** 一轮问答一个 id。上行和下行共用它，所以手机能把"问"和"答"钉在同一轮上，也天然去重 */
    @SerialName("turn_id") val turnId: String,
    val dir: DialogDir,
    val text: String,
    val source: DialogSource = DialogSource.local_rule,
    val skill: String? = null,
    val origin: DialogOrigin = DialogOrigin.screen,
    /**
     * 答这句话的同时把屏**挪到那一页**。只允许出现在 `dir=down` 的帧上，
     * 而且只做一件事：换页。它不是一个技能口，也不是 SOS 口 ——
     * 屏收到它就切页，除此之外不执行任何东西，所以"云端代答的一句话替车主按下了呼救"
     * 这类事在这条契约里构造不出来。
     *
     * 为什么要挂在对话帧上而不是新开一种帧：跳页永远发生在"刚答了一句"那一刻，
     * 分开两帧就得再发明一次 turn_id 关联，而对话面已经容忍丢帧 ——
     * **丢了这一条，屏停在原页、手机记录仍在**，这正是我们想要的降级。
     */
    val action: ScreenPage? = null,
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

    /**
     * v3 新增：**这块玻璃到底有多大、是不是圆的、能不能点**（产品架构 §4 实现要求 #4、
     * 附录 A.4 #1）。`dock_type` 描述的是"挂在哪"，解决不了"能放几行字"——
     * 466×466 圆的可用区只有约 0.8 直径，同样一句「补气到 2.5 bar」在方屏上一行、在这块屏上两行。
     *
     * 可空：老固件没这一项 = 手机按自己那台的默认口径排版，**不许当成"没有屏"**。
     * 手机侧只读不写，所以这里不需要"缺了等于不知道"那条必填规矩。
     */
    val screen: ScreenGeom? = null,
)

/** 面板几何。物理像素 + 形状 + 触摸，单位写在字段名外面（ppi 是每英寸像素，不是 dpi）。 */
@Serializable
data class ScreenGeom(
    val w: Int,
    val h: Int,
    val round: Boolean,
    val touch: Boolean,
    val ppi: Int? = null,
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
     *
     * **不能有默认值**：`Json { encodeDefaults = false }` 会把「等于默认值」的字段整个省掉，
     * 而 `= true` 意味着终端健康时这个键根本不上线，屏那边 `bool_of` 找不到键就不动缓存、
     * 开机 memset 成 false —— 于是卫星卡永远画「卫星终端离线 / --:--」，串口一个错都不报
     * （2026-11-02 真机 11 步实拍抓到的）。屏用"缺了等于不知道"读的布尔都必须必填。
     */
    @SerialName("module_ok") val moduleOk: Boolean,
    /**
     * App 侧攒着待补发的条数。掉线期间这个数字一直涨，就是"一条不能丢"的凭据。
     * 同样不能有默认值：屏把"没有这个键"和"队里 0 条"当成两句话（前者是手机还没算），
     * 带默认值时 0 会被省掉，屏就永远显示「待补发 -」。
     */
    @SerialName("queue_pending") val queuePending: Int,
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
data class UiState(
    val card: UiCard,
    val emotion: UiEmotion? = null,
    /**
     * 手机时区相对 UTC 的偏移分钟数（东八区 480）。钟面拿整帧的 [ScreenMessage.ts] 加上它拆时分秒。
     * null = 没给，屏按 UTC 画并在角上标一个小小的 UTC —— 这台设备没有电池-backed RTC，
     * 也不要在固件里猜时区：车里那个网络一断，SNTP 就拿不到，猜出来的表比没有表更误导人。
     */
    @SerialName("tz_min") val tzMin: Int? = null,
    /**
     * **回声，不是指令**：手机把屏自己报上来的那一页原样写回去（见 [ScreenPage]、`page_report`）。
     * 屏不许按这个字段切页 —— 切了就成了手机遥控屏，而我们刚把定页权交出去。
     * 它存在的用处有两个：彩排台和首页镜像能照着同一份读数说话；
     * 屏能看出「手机以为我在 A 页而我已经在 B 页」，那是不一致，不是错误。
     * null = 这一轮屏还没报过页（老固件不会报，重连后也还没补报）。
     */
    val page: ScreenPage? = null,
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
    /**
     * 展示端该画哪张卡。null = 这一帧没定，屏继续画它自己的状态总览 ——
     * 老 App、v1 会话、还有手机刚起来还没算出卡的那几秒都是这个形状，
     * 所以**它缺失绝不能黑屏或报错**。加性字段，不占契约版本。
     */
    val ui: UiState? = null,
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
    /**
     * App 支持的契约版本表。v4 上行（mic_chunk，屏把 PCM 推给手机转写）走"App 先发版"：
     * 屏看到这份表里有 4 才打开上行，四张表这轮不改。
     */
    val protocols: List<Int>? = null,
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
