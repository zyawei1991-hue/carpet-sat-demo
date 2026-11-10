package com.carpet.satdemo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * 卡片属于页面上的哪一段。**由卡片自己声明，不由页面声明** ——
 * 页面写死分组的话，用户在"排列这些卡"里换的序就会被页面覆盖掉，两边走到两套事实。
 *
 * 这一列是为车辆页加的：它原来是一串 11 张同权重方块，其中 8 张讲的是卫星管道，
 * 车主点开一个写着"车辆"的页，看到的却不是车。分组不新增内容，只是把"这半页在讲同一件事"
 * 这件事写回屏幕上。
 */
enum class CardGroup(val label: String, val note: String?) {
    /** 不分组：跟着前面的内容走，不另起一段 */
    Inline("", null),
    /** 越界、静止、求救 —— 三件事都是"出事了怎么办"，所以是一组 */
    Guard("守护", null),
    Vehicle("车况", null),
    Satellite("卫星兜底", "手机没信号的时候才轮到它"),
    /**
     * 亲子与健康分开。原来这两堆共用一个 `Family("家人")`，于是家人页分段等于没分：
     * 管控、成长、AI 育儿、体征、打卡、AI 助手六张卡挂在同一个标题下面，
     * 而家长找"今天新词"和找"血压"根本不是一件事。
     */
    Kids("亲子陪伴", null),
    Health("健康", null),
    Share("共享对象", null),
    Account("订阅与隐私", null),
}

/**
 * 卡片注册表。**场景只认 key，不认卡片**；卡片也不认场景。
 * 加一个场景 = 在 [Scene] 里多列一串 key；加一张卡 = 在这里多注册一行。两边互不动。
 */
object Cards {
    val registry = CardRegistry(
        listOf(
            // 卫星守护：数据全部来自 StatePayload / DemoRuntime，没有一张卡可以自己编数
            CardDescriptor("sos", "紧急求助", CardGroup.Guard, setOf(Capability.SatelliteLink)) { SosCard(it) },
            CardDescriptor("link", "链路", CardGroup.Inline, setOf(Capability.SatelliteLink)) { LinkCard(it) },
            CardDescriptor("pass", "下次过境", CardGroup.Satellite, setOf(Capability.Ephemeris)) { PassWindowCard(it) },
            CardDescriptor("terminal", "卫星终端", CardGroup.Satellite, setOf(Capability.SatelliteLink)) { TerminalCard(it) },
            CardDescriptor("queue", "待发消息", CardGroup.Satellite, setOf(Capability.SatelliteLink)) { QueueCard(it) },
            CardDescriptor("usage", "卫星用量", CardGroup.Satellite, setOf(Capability.SatelliteLink)) { UsageCard(it) },
            CardDescriptor("tpi", "终端位置", CardGroup.Satellite, setOf(Capability.SatelliteLink)) { TpiCard(it) },
            CardDescriptor("position", "位置", CardGroup.Guard, setOf(Capability.PositionSource)) { PositionCard(it) },
            CardDescriptor("fence", "电子围栏", CardGroup.Guard, setOf(Capability.Geofence)) { FenceCard(it) },
            CardDescriptor("sentinel", "哨兵模式", CardGroup.Guard, setOf(Capability.Sentinel)) { SentinelCard(it) },
            CardDescriptor("dock", "圆屏", CardGroup.Inline, setOf(Capability.Dock)) { DockCard(it) },

            // 用车养车
            CardDescriptor("vehicle.status", "整车状态", CardGroup.Vehicle, setOf(Capability.VehicleBus), hero = true) { VehicleStatusCard(it) },
            CardDescriptor("vehicle.find", "寻车", CardGroup.Vehicle, setOf(Capability.VehicleBus)) { VehicleFindCard(it) },
            CardDescriptor("vehicle.service", "该做的事", CardGroup.Vehicle, setOf(Capability.VehicleBus)) { VehicleServiceCard(it) },
            CardDescriptor("vehicle.energy", "能耗", CardGroup.Vehicle, setOf(Capability.VehicleBus)) { VehicleEnergyCard(it) },

            // 亲子
            CardDescriptor("kids.control", "亲子管控", CardGroup.Kids, setOf(Capability.ChildDevice)) { KidsControlCard(it) },
            CardDescriptor("kids.growth", "成长记录", CardGroup.Kids, setOf(Capability.ChildDevice)) { KidsGrowthCard(it) },
            CardDescriptor("kids.ai", "AI 育儿", CardGroup.Kids, setOf(Capability.AiAgent)) { KidsAiCard(it) },

            // 健康
            CardDescriptor("health.vitals", "体征", CardGroup.Health, setOf(Capability.HealthBand), hero = true) { HealthVitalsCard(it) },
            CardDescriptor("health.checkin", "打卡", CardGroup.Health, setOf(Capability.HealthBand)) { HealthCheckinCard(it) },
            CardDescriptor("health.agent", "健康主理人", CardGroup.Health, setOf(Capability.AiAgent)) { HealthAgentCard(it) },

            // 综合场景
            CardDescriptor("social.avatar", "数字分身", CardGroup.Inline, setOf(Capability.AiAgent)) { AvatarCard(it) },
            CardDescriptor("social.a2a", "A2A 社交", CardGroup.Inline, setOf(Capability.AiAgent)) { A2aCard(it) },
            CardDescriptor("share.objects", "共享对象", CardGroup.Share, setOf(Capability.Account)) { ShareObjectsCard(it) },

            // 我的
            CardDescriptor("device.manager", "我的设备", CardGroup.Inline, setOf(Capability.Dock)) { DeviceManagerCard(it) },
            CardDescriptor("subscription", "订阅与流量包", CardGroup.Inline, setOf(Capability.Account)) { SubscriptionCard(it) },
            CardDescriptor("privacy", "隐私中心", CardGroup.Inline, setOf(Capability.Account)) { PrivacyCard(it) },
        ),
    )
}

/** 卡片是不是该出现，只看这一句 */
@Composable
fun RenderCard(key: String, scope: CardScope, hero: Boolean = false) {
    val d = Cards.registry.resolve(key, scope) ?: return
    if (!hero) d.body(scope)
    // 强调档不在卡片自己手里：一屏只有一张能被标成 hero，由 [CardStream] 决定是谁，再从这个孔注进去。
    // 写在卡片里会变成"每张卡都声明自己最重要"，那正是上一版一摞同权重方块的成因
    else CompositionLocalProvider(LocalCardLevel provides Level.Raised) { d.body(scope) }
}
