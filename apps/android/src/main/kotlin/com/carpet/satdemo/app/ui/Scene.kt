package com.carpet.satdemo.app.ui

import androidx.compose.runtime.Composable
import com.carpet.satdemo.app.AppRuntime
import com.carpet.satdemo.core.ai.AiEndpoint
import com.carpet.satdemo.core.ai.Turn
import com.carpet.satdemo.core.devices.Device
import com.carpet.satdemo.core.runtime.DemoRuntime
import com.carpet.satdemo.core.vehicle.ReadingBoard
import kotlinx.coroutines.flow.StateFlow

/**
 * 一件事有没有真数据源。§5.3 硬要求 #3 的三档说法：
 *  - [Live] 有真源，卡片照实画；
 *  - [Mocked] 只有演示桩，卡片照画但打「示意数据」徽标 —— **前装车开不开放数据还没定，
 *    把桩数据画成实测比少一张卡片更糟**；
 *  - [Absent] 没这个能力，卡片**不渲染**，也不给它写空状态页。
 */
enum class Availability { Live, Mocked, Absent }

/** 卡片声明自己需要什么能力；由 [AppRuntime.availability] 决定它现在是什么档 */
enum class Capability {
    SatelliteLink,
    Ephemeris,
    PositionSource,
    Geofence,
    Sentinel,
    Dock,

    /** AI 助手这条链路（路由 / 权限 / 主动扫描）。**模型接不接是另一件事**，界面上逐条标来源 */
    Assistant,
    VehicleBus,
    ChildDevice,
    HealthBand,
    AiAgent,
    Account,
}

/** 输入方式。圆屏那边是旋钮+侧键+语音，手机是触摸；场景得知道自己能指望哪种 */
enum class InputMode { Touch, TouchAndVoice, DockKnob, DockSideKey }

/**
 * 隐私档位。**生效档位 = 底座默认和家长设置里更严的那个**（§6 第 3 条），
 * 所以这里给每个档位一个序号，取 max 而不是取"最后设置的那个"。
 */
enum class PrivacyTier(val severity: Int, val label: String) {
    Open(0, "什么都传"),
    Guarded(1, "只传必要的"),
    Strict(2, "只存在手机里，求救才发出"),
    ;

    companion object {
        fun effective(base: PrivacyTier, parental: PrivacyTier): PrivacyTier =
            if (base.severity >= parental.severity) base else parental
    }
}

/**
 * 一个场景 = 主卡 + 卡片顺序 + 可用动作 + 输入方式 + 隐私档位。
 * 切场景就是换一个这个对象：**不重建导航栈、不重新登录、不重连设备**。
 */
data class SceneProfile(
    val headline: String,
    val cards: List<String>,
    val actions: List<String>,
    val input: InputMode,
    val basePrivacy: PrivacyTier,
)

enum class Scene(val label: String, val subtitle: String, val profile: SceneProfile) {
    /** 车载守护：用车养车 + 卫星兜底。V1.7 起主卡是**圆屏这块屏**，不是链路 —— 车里天天看的是它 */
    Vehicle(
        "车载守护",
        "用车养车 · 卫星兜底",
        SceneProfile(
            headline = "dock",
            // **顺序按"车主先看什么"，不按"我们后写的哪张卡"**：
            // 上一版是 sos/link/pass/terminal 打头，一个写着"车辆"的页，第一屏讲的是卫星管道。
            // usage 也从这一串里拿掉了 —— 它在「我的」里已经画了一次，一页两张同名重卡是最直接的"乱"。
            // position 同理：它报的是"手机现在在哪"，而车辆页要的是"车在哪"，那是寻车那张卡的事；
            // 原始经纬度和 HDOP 留在「户外无网」那一串里，徒步的人才需要自己核对坐标
            // service 紧跟 status：车主看完"车现在怎么样"，下一句要的是"所以现在该做什么"
            cards = listOf("sos", "link", "vehicle.status", "vehicle.service", "vehicle.energy", "vehicle.find", "fence", "pass", "terminal", "queue", "preset"),
            actions = listOf("寻车", "远程熄火", "报一下车况"),
            input = InputMode.TouchAndVoice,
            basePrivacy = PrivacyTier.Guarded,
        ),
    ),

    /** 户外无网：蜂窝判死以后靠卫星，倒计时和 TPI 在这里是主角 */
    Offgrid(
        "户外无网",
        "无人区 · 徒步 · 救援",
        SceneProfile(
            headline = "pass",
            cards = listOf("sos", "pass", "position", "tpi", "queue", "preset", "usage", "sentinel", "terminal"),
            actions = listOf("记一条轨迹", "安静地报个位置"),
            input = InputMode.Touch,
            basePrivacy = PrivacyTier.Guarded,
        ),
    ),

    /** 亲子陪伴：围栏和管控是主卡，AI 育儿只做摘要不做判断 */
    Kids(
        "亲子陪伴",
        "AI 育儿 · 安全围栏",
        SceneProfile(
            headline = "fence",
            // vehicle.service 在这一页只出挂着孩子那一条（滤芯）。同一张卡、同一份规则，
            // 换一页换一个视图 —— 见 docs/DECISIONS.md D11，卡片不认场景，只认自己那条 sceneKey
            cards = listOf("fence", "kids.control", "kids.growth", "vehicle.service", "kids.ai", "position", "dock", "sos"),
            actions = listOf("设围栏", "限时", "呼叫家长"),
            input = InputMode.DockSideKey,
            basePrivacy = PrivacyTier.Strict,
        ),
    ),

    /** 健康助手：体征 + 打卡 + AI 助手。体征设备到货前全是示意 */
    Health(
        "健康助手",
        "体征 · 打卡 · AI 助手",
        SceneProfile(
            headline = "health.vitals",
            cards = listOf("health.vitals", "health.checkin", "health.agent", "position", "queue", "sos"),
            actions = listOf("测一次", "异常提醒", "呼叫家人"),
            input = InputMode.TouchAndVoice,
            basePrivacy = PrivacyTier.Strict,
        ),
    ),

    /** 综合场景：数字分身和 A2A 社交。这一档现在只有壳 */
    Social(
        "数字分身",
        "分身 · A2A 社交",
        SceneProfile(
            headline = "social.avatar",
            cards = listOf("social.avatar", "social.a2a", "share.objects", "dock", "position"),
            actions = listOf("让分身回一句", "共享位置", "暂停代答"),
            input = InputMode.DockKnob,
            basePrivacy = PrivacyTier.Strict,
        ),
    ),
    ;

    companion object {
        /** 首页那个切换器只放常切的三个，其余走"更多"。全塞顶上就成了横向滚动的迷宫 */
        val quick: List<Scene> get() = listOf(Vehicle, Offgrid, Kids)

        /**
         * 圆屏 picker 那一页格子 ↔ App 场景的对应（「变装统一」）。
         *
         * 圆屏的场景切换器（屏自己驱动的那棵页树，见 [ScreenPage.picker]）格子上挂的名字
         * 和 App 切换器上的必须讲同一套话：同一场景两端用同一个词（车载/亲子/数字分身），
         * 变装变的是脸和页面皮，**不变的是名字和它这个人** —— 起了名之后两端都用
         * [AppRuntime.petCalled] 称呼，情绪 [UiEmotion] 也由 App 统一下行。
         *
         * 对不上头的三格是有意的，不是没对齐：
         * - `clock` / `gallery` / `photo` 是屏自己的功能页（看钟、看照片），不是场景，
         *   App 不给它们造场景 —— ContextPin 也不会把它们钉到卡片流上。
         * - App 的 [Offgrid] / [Health] 屏上没有对应页：屏的 1.75" 圆面画不下
         *   卫星排队/体征那几串卡，这两个场景的主屏就是手机，屏只继续当脸和嘴。
         *
         * 契约纪律不变：**页由屏定，内容由手机定**。这张表只用于把屏报上来的页
         * 翻译成 App 这边的说法（ContextPin、镜像注脚），不是遥控切页的通道。
         */
        fun fromScreenPage(page: com.carpet.satdemo.core.protocol.ScreenPage): Scene? = when (page) {
            com.carpet.satdemo.core.protocol.ScreenPage.vehicle -> Vehicle
            com.carpet.satdemo.core.protocol.ScreenPage.kids -> Kids
            com.carpet.satdemo.core.protocol.ScreenPage.avatar -> Social
            else -> null
        }
    }
}

/**
 * 卡片要读的两样东西：当前这一帧状态（真数据）和 App 运行时（动作）。
 * 故意不给它 Context —— 卡片拿到 Context 就会开始自己连设备，
 * §5.3 那条"连接层在场景之外"当天就破了。
 */
class CardScope(val app: AppRuntime, val status: DemoRuntime.Status) {
    val snap get() = status.snapshot

    /** 当前场景。卡片读它不走 `app.scene` 直读：状态入口收拢在这一个类里 */
    val scene: StateFlow<Scene> = app.scene

    /** 家长设置的隐私档。生效档 = 这个和场景底座档取更严（[PrivacyTier.effective]） */
    val privacyChoice: StateFlow<PrivacyTier> = app.privacyChoice

    /** 设备台账。设备中心那张卡读它，切场景/切 Tab 都不会让一台设备消失 */
    val devices: StateFlow<List<Device>> = app.devices

    /** 全局开关位。设备中心那张卡的「允许圆屏接入」读它 */
    val flags: StateFlow<AppRuntime.Flags> = app.flags

    /**
     * 屏上原话：那一份唯一的对话时间线。亲子那几张卡只许**引用**这里的真实句子，
     * 不许现场编一句"漂亮话" —— 编出来的摘要正是 §5.3 最尴尬的那种假真源。
     */
    val chat: StateFlow<List<Turn>> = app.chat

    /** 当前真正配上的模型端点。AI 那张卡「接没接上模型」由它说，不由美术说 */
    val aiEndpoint: StateFlow<AiEndpoint> = app.aiEndpoint

    fun avail(c: Capability): Availability = app.availability(c)

    /** 卡片直接用它决定要不要打「示意数据」那个徽标 */
    fun mock(c: Capability): Boolean = avail(c) == Availability.Mocked

    /**
     * 这一帧车况读数。车辆那几张卡读同一份，**不在卡片里各编各的数** ——
     * 一旦每张卡自己写字符串，"整车主页 412 km、该做的事那条说 380 km"就是迟早的事。
     */
    fun board(): ReadingBoard {
        val u = app.usage()
        return app.maint.board(u.waiting, u.capacity.takeIf { it > 0 }, app.maint.quotaReal)
    }
}

/**
 * 卡片描述符。**注册表是唯一入口**：场景只写 key 的顺序，不认识任何具体卡片，
 * 也不为"这张卡在这个场景里没有"写分支。
 */
data class CardDescriptor(
    val key: String,
    val title: String,
    val group: CardGroup,
    val needs: Set<Capability>,
    /** 这一张是本页的主问题：升一档底 + 一根描边。**一屏只会有一个**，见 [CardStream] */
    val hero: Boolean = false,
    val body: @Composable (CardScope) -> Unit,
)

class CardRegistry(descriptors: List<CardDescriptor>) {
    private val byKey = descriptors.associateBy { it.key }

    /** 没注册或能力不齐就返回 null，调用方跳过它 —— 不报错、不画空状态 */
    fun resolve(key: String, scope: CardScope): CardDescriptor? {
        val d = byKey[key] ?: return null
        return if (d.needs.any { scope.avail(it) == Availability.Absent }) null else d
    }

    fun title(key: String): String = byKey[key]?.title ?: key

    fun group(key: String): CardGroup = byKey[key]?.group ?: CardGroup.Inline

    fun isHero(key: String): Boolean = byKey[key]?.hero == true

    val all: List<CardDescriptor> get() = byKey.values.toList()
}
