package com.carpet.satdemo.app.ui

import androidx.compose.runtime.Composable

/**
 * 卡片注册表。**场景只认 key，不认卡片**；卡片也不认场景。
 * 加一个场景 = 在 [Scene] 里多列一串 key；加一张卡 = 在这里多注册一行。两边互不动。
 */
object Cards {
    val registry = CardRegistry(
        listOf(
            // 卫星守护：数据全部来自 StatePayload / DemoRuntime，没有一张卡可以自己编数
            CardDescriptor("sos", "紧急求助", setOf(Capability.SatelliteLink)) { SosCard(it) },
            CardDescriptor("link", "链路", setOf(Capability.SatelliteLink)) { LinkCard(it) },
            CardDescriptor("pass", "下次过境", setOf(Capability.Ephemeris)) { PassWindowCard(it) },
            CardDescriptor("terminal", "卫星终端", setOf(Capability.SatelliteLink)) { TerminalCard(it) },
            CardDescriptor("queue", "待发队列", setOf(Capability.SatelliteLink)) { QueueCard(it) },
            CardDescriptor("usage", "卫星用量", setOf(Capability.SatelliteLink)) { UsageCard(it) },
            CardDescriptor("tpi", "终端位置", setOf(Capability.SatelliteLink)) { TpiCard(it) },
            CardDescriptor("position", "位置", setOf(Capability.PositionSource)) { PositionCard(it) },
            CardDescriptor("fence", "电子围栏", setOf(Capability.Geofence)) { FenceCard(it) },
            CardDescriptor("sentinel", "哨兵模式", setOf(Capability.Sentinel)) { SentinelCard(it) },
            CardDescriptor("dock", "展示端", setOf(Capability.Dock)) { DockCard(it) },

            // 用车养车
            CardDescriptor("vehicle.status", "车况", setOf(Capability.VehicleBus)) { VehicleStatusCard(it) },
            CardDescriptor("vehicle.find", "寻车", setOf(Capability.VehicleBus)) { VehicleFindCard(it) },
            CardDescriptor("vehicle.service", "养车工单", setOf(Capability.VehicleBus)) { VehicleServiceCard(it) },
            CardDescriptor("vehicle.energy", "能耗", setOf(Capability.VehicleBus)) { VehicleEnergyCard(it) },

            // 亲子
            CardDescriptor("kids.control", "亲子管控", setOf(Capability.ChildDevice)) { KidsControlCard(it) },
            CardDescriptor("kids.growth", "成长记录", setOf(Capability.ChildDevice)) { KidsGrowthCard(it) },
            CardDescriptor("kids.ai", "AI 育儿", setOf(Capability.AiAgent)) { KidsAiCard(it) },

            // 健康
            CardDescriptor("health.vitals", "体征", setOf(Capability.HealthBand)) { HealthVitalsCard(it) },
            CardDescriptor("health.checkin", "打卡", setOf(Capability.HealthBand)) { HealthCheckinCard(it) },
            CardDescriptor("health.agent", "健康主理人", setOf(Capability.AiAgent)) { HealthAgentCard(it) },

            // 综合场景
            CardDescriptor("social.avatar", "数字分身", setOf(Capability.AiAgent)) { AvatarCard(it) },
            CardDescriptor("social.a2a", "A2A 社交", setOf(Capability.AiAgent)) { A2aCard(it) },
            CardDescriptor("share.objects", "共享对象", setOf(Capability.Account)) { ShareObjectsCard(it) },

            // 我的
            CardDescriptor("device.manager", "设备与底座", setOf(Capability.Dock)) { DeviceManagerCard(it) },
            CardDescriptor("subscription", "订阅与流量包", setOf(Capability.Account)) { SubscriptionCard(it) },
            CardDescriptor("privacy", "隐私中心", setOf(Capability.Account)) { PrivacyCard(it) },
        ),
    )
}

/** 卡片是不是该出现，只看这一句 */
@Composable
fun RenderCard(key: String, scope: CardScope) {
    val d = Cards.registry.resolve(key, scope) ?: return
    d.body(scope)
}
