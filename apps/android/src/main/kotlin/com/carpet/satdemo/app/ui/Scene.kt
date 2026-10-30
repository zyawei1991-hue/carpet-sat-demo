package com.carpet.satdemo.app.ui

import androidx.compose.runtime.Composable
import com.carpet.satdemo.app.AppRuntime
import com.carpet.satdemo.core.runtime.DemoRuntime

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

    /** AI 主理人这条链路（路由 / 权限 / 主动扫描）。**模型接不接是另一件事**，界面上逐条标来源 */
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
    Open(0, "全量上传"),
    Guarded(1, "只上传必要字段"),
    Strict(2, "本地留存，仅 SOS 出网"),
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
            cards = listOf("sos", "link", "pass", "terminal", "vehicle.status", "vehicle.energy", "fence", "queue", "usage", "position", "vehicle.find"),
            actions = listOf("寻车", "远程熄火", "上报车况"),
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
            cards = listOf("sos", "pass", "position", "tpi", "queue", "usage", "sentinel", "terminal"),
            actions = listOf("上报轨迹", "静默上报"),
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
            cards = listOf("fence", "kids.control", "kids.growth", "kids.ai", "position", "dock", "sos"),
            actions = listOf("设围栏", "限时", "呼叫家长"),
            input = InputMode.DockSideKey,
            basePrivacy = PrivacyTier.Strict,
        ),
    ),

    /** 健康助手：体征 + 打卡 + 主理人。体征设备到货前全是示意 */
    Health(
        "健康助手",
        "体征 · 打卡 · 主理人",
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
    }
}

/**
 * 卡片要读的两样东西：当前这一帧状态（真数据）和 App 运行时（动作）。
 * 故意不给它 Context —— 卡片拿到 Context 就会开始自己连设备，
 * §5.3 那条"连接层在场景之外"当天就破了。
 */
class CardScope(val app: AppRuntime, val status: DemoRuntime.Status) {
    val snap get() = status.snapshot

    fun avail(c: Capability): Availability = app.availability(c)

    /** 卡片直接用它决定要不要打「示意数据」那个徽标 */
    fun mock(c: Capability): Boolean = avail(c) == Availability.Mocked
}

/**
 * 卡片描述符。**注册表是唯一入口**：场景只写 key 的顺序，不认识任何具体卡片，
 * 也不为"这张卡在这个场景里没有"写分支。
 */
data class CardDescriptor(
    val key: String,
    val title: String,
    val needs: Set<Capability>,
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

    val all: List<CardDescriptor> get() = byKey.values.toList()
}
