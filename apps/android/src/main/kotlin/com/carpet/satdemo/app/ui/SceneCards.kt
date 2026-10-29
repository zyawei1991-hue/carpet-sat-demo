package com.carpet.satdemo.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.carpet.satdemo.app.AppRuntime
import com.carpet.satdemo.core.devices.Device
import com.carpet.satdemo.core.devices.DeviceLink
import com.carpet.satdemo.core.devices.Tone as CoreTone
import com.carpet.satdemo.core.screen.ScreenLinkServer

/**
 * 用车养车 / 亲子 / 健康 / 数字分身 四个场景包的卡片。
 *
 * **这一整份文件里的数字都不是测出来的**，是场景壳的占位。之所以现在就画出来：
 * 前装车到底开放哪些数据还没定（§9 的外部依赖），而"卡片长什么样、要哪几个字段"
 * 是我们可以先定下来的东西 —— 等答复的时候改的是取数，不是版式。
 * 每张卡都靠 [InkCard.mock] 打上「示意数据」，观众不会把桩读成实测。
 */

@Composable
fun VehicleStatusCard(s: CardScope) {
    val parked = s.status.snapshot?.sentinel?.armed == true
    InkCard(icon = MIcons.vehicle, mock = s.mock(Capability.VehicleBus)) {
        CardTitle("车况", trailing = if (parked) "哨兵布防中" else "熄火停车")
        ValueRow("剩余续航", "412 km")
        ValueRow("油量", "3/4")
        ValueRow("胎压", "2.4 / 2.4 / 2.3 / 2.5 bar")
        ValueRow("累计里程", "23,417 km")
        Text(
            "这些字段来自前装 CAN 的假设口径。真接上车以后取值口径要按车型再对一遍",
            style = Type.Caption,
            color = Ink.TextLow,
        )
    }
}

/** 寻车：最后已知位置是**真**的（来自手机 GNSS），只有"车位记忆"那部分是桩 */
@Composable
fun VehicleFindCard(s: CardScope) {
    val g = s.snap?.gnss
    val hasFix = g != null && !(g.lat == 0.0 && g.lon == 0.0 && g.fixAgeS == null)
    InkCard(icon = MIcons.parking, mock = s.mock(Capability.VehicleBus)) {
        CardTitle("寻车", trailing = "记一次车位")
        ValueRow(
            "最后已知位置",
            if (hasFix) "%.5f, %.5f".format(g!!.lat, g.lon) else null,
            mono = true,
        )
        ValueRow("记录时间", g?.fixAgeS?.let { "%.0f 秒前".format(it) })
        ValueRow("车位编号", "B2-073（示意）")
        Text(
            if (hasFix) "熄火那一瞬的位置由手机侧记下，不需要车机配合" else "还没有定位，寻车只能靠上一次记下的车位",
            style = Type.Caption,
            color = Ink.TextLow,
        )
    }
}

@Composable
fun VehicleServiceCard(s: CardScope) {
    InkCard(icon = MIcons.ticket, mock = s.mock(Capability.VehicleBus)) {
        CardTitle("养车工单")
        RowWork("保养", "还有 830 km", Tone.Wait)
        RowWork("轮胎换位", "已逾期 12 天", Tone.Alert)
        RowWork("年检", "明年 3 月", Tone.Neutral)
        Text("工单来自桩数据。真做的时候这里是 OBD 故障码 + 4S 店的排期接口", style = Type.Caption, color = Ink.TextLow)
    }
}

@Composable
private fun RowWork(name: String, state: String, tone: Tone) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(name, style = Type.Body)
        Text(state, style = Type.Caption.copy(color = if (tone === Tone.Neutral) Ink.TextMid else tone.color))
    }
}

@Composable
fun VehicleEnergyCard(s: CardScope) {
    InkCard(icon = MIcons.battery, mock = s.mock(Capability.VehicleBus)) {
        CardTitle("能耗", trailing = "近 30 天")
        Column(verticalArrangement = Arrangement.spacedBy(Grid.x4)) {
            Text("驱动 78% · 空调 14% · 待机 8%", style = Type.Body)
            ProgressBar(0.78f, Tone.Ok)
            ProgressBar(0.14f, Tone.Wait)
            ProgressBar(0.08f, Tone.Neutral)
        }
        ValueRow("平均电耗", "15.8 kWh/100km")
    }
}

@Composable
fun KidsControlCard(s: CardScope) {
    InkCard(icon = MIcons.kid, mock = s.mock(Capability.ChildDevice)) {
        CardTitle("亲子管控", trailing = "底座：项圈")
        ValueRow("今日屏幕时长", "37 分钟 / 上限 60")
        ValueRow("内容白名单", "故事、英语、汉字（示意）")
        ValueRow("夜间禁用", "21:30 – 06:30")
        Text(
            "管控规则在家长手机上定，圆屏侧只读。孩子那边没有\"取消\"这个按钮",
            style = Type.Caption,
            color = Ink.TextLow,
        )
    }
}

@Composable
fun KidsGrowthCard(s: CardScope) {
    InkCard(icon = MIcons.growth, mock = s.mock(Capability.ChildDevice)) {
        CardTitle("成长记录")
        ValueRow("今天新词", "5 个（示意）")
        ValueRow("连续打卡", "12 天")
        ValueRow("上次录音", "昨天 19:04")
        Text("记录默认留在本地，云端只存摘要 —— 这是隐私档位 Strict 的要求（§6）", style = Type.Caption, color = Ink.TextLow)
    }
}

@Composable
fun KidsAiCard(s: CardScope) {
    InkCard(icon = MIcons.model, mock = s.mock(Capability.AiAgent)) {
        CardTitle("AI 育儿", trailing = "只摘要，不判断")
        Text("「今天孩子主动问了 3 次『为什么』，集中在机械类。」", style = Type.Body)
        Text("「建议：把睡前故事换成齿轮主题的绘本。」", style = Type.Body)
        Text(
            "AI 在这里不给孩子下结论，也不替家长做处罚。任何涉及评价孩子的输出都要能追溯到原文",
            style = Type.Caption,
            color = Ink.TextLow,
        )
    }
}

@Composable
fun HealthVitalsCard(s: CardScope) {
    InkCard(icon = MIcons.health, mock = s.mock(Capability.HealthBand)) {
        CardTitle("体征", trailing = "心率带未接")
        ValueRow("心率", "72 bpm（示意）")
        ValueRow("体温", "36.5 ℃（示意）")
        ValueRow("上次测量", "8 分钟前")
        Text(
            "体征超阈值走卫星发一条，不等蜂窝。到货前这一张全是桩，数字别当依据",
            style = Type.Caption,
            color = Ink.Wait,
        )
    }
}

@Composable
fun HealthCheckinCard(s: CardScope) {
    InkCard(icon = MIcons.checkin, mock = s.mock(Capability.HealthBand)) {
        CardTitle("今日打卡")
        RowWork("血压", "08:12 已完成", Tone.Ok)
        RowWork("用药", "21:00 待完成", Tone.Wait)
        RowWork("步数", "4,120 / 6,000", Tone.Neutral)
    }
}

@Composable
fun HealthAgentCard(s: CardScope) {
    InkCard(icon = MIcons.agent, mock = s.mock(Capability.AiAgent)) {
        CardTitle("健康主理人")
        Text("「用药还差一次。要不要现在提醒？」", style = Type.Body)
        Text("动作：提醒一次 / 今天不提醒 / 转给家人", style = Type.Caption)
        Text("主理人的每一条建议都要能被家长一键关掉，且关掉之后不再重试", style = Type.Caption, color = Ink.TextLow)
    }
}

@Composable
fun AvatarCard(s: CardScope) {
    InkCard(icon = MIcons.model, mock = s.mock(Capability.AiAgent)) {
        CardTitle("数字分身", trailing = "代答中")
        ValueRow("今日代答", "9 条（示意）")
        ValueRow("转真人", "2 条")
        ValueRow("被否决", "1 条")
        Text(
            "分身代答必须先亮身份。任何一条被对方发现是AI而没提前说明，这个功能就得下线",
            style = Type.Caption,
            color = Ink.Wait,
        )
    }
}

@Composable
fun A2aCard(s: CardScope) {
    InkCard(icon = MIcons.chat, mock = s.mock(Capability.AiAgent)) {
        CardTitle("A2A 社交")
        ValueRow("在线对象", "3（示意）")
        ValueRow("待授权共享", "位置 · 行程")
        Text("跨端协商的授权粒度到字段，不到 App。这里是壳，协议还没定", style = Type.Caption, color = Ink.TextLow)
    }
}

@Composable
fun ShareObjectsCard(s: CardScope) {
    InkCard(icon = MIcons.share, mock = s.mock(Capability.Account)) {
        CardTitle("共享对象")
        RowWork("妈妈", "位置 · 全程可见", Tone.Ok)
        RowWork("车队群", "位置 · 仅越界时", Tone.Wait)
        RowWork("保险公司", "未授权", Tone.Neutral)
    }
}

/**
 * 订阅与流量包。**资费套餐是桩（等 §11 的 P0 答复），额度这一行不是** ——
 * 它读的是用量页同一个 [AppRuntime.usage]。两张卡对同一个数说出两句话，
 * 是 §5.2 最后一条明令禁止的那种事，所以这里宁可空着也不写 180/300。
 */
@Composable
fun SubscriptionCard(s: CardScope) {
    val u = s.app.usage()
    InkCard(icon = MIcons.plan, mock = s.mock(Capability.Account)) {
        CardTitle("订阅与流量包")
        ValueRow(
            "卫星报文额度",
            if (u.capacity > 0) "${u.handedOver} / ${u.capacity} 条" else "还没读到模组型号",
            if (u.pressure >= 0.9f) Tone.Alert else Tone.Neutral,
        )
        ProgressBar(u.pressure, if (u.pressure >= 0.9f) Tone.Alert else Tone.Wait)
        ValueRow("到期", "2026-12-01（示意）")
        ValueRow("套餐", "守护版（示意）")
        Text("额度和用量页同源，读的是终端现场；套餐与资费都等时空道宇给口径（§11 的 P0）", style = Type.Caption, color = Ink.TextLow)
    }
}

/**
 * 隐私中心。**"生效档位 = 底座默认 与 家长设置 里更严的那个"这条规则是真的**（§6 第 3 条），
 * 两个输入里底座档位是按场景声明的（SceneProfile.basePrivacy），家长这一档由用户在这一屏选。
 */
@Composable
fun PrivacyCard(s: CardScope) {
    val picked = s.app.privacyChoice.collectAsState().value
    val base = s.app.scene.collectAsState().value.profile.basePrivacy
    val eff = PrivacyTier.effective(base, picked)
    InkCard(icon = MIcons.privacy, mock = s.mock(Capability.Account)) {
        CardTitle("隐私中心", trailing = "取更严的那个")
        TierRow("底座默认", base, null)
        TierRow("家长设置", picked) { s.app.setPrivacyChoice(it) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("生效档位", style = Type.Caption)
            Badge(eff.label, if (eff === PrivacyTier.Strict) Tone.Ok else Tone.Wait)
        }
    }
}

@Composable
private fun TierRow(label: String, tier: PrivacyTier, onPick: ((PrivacyTier) -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Type.Caption)
        Row(horizontalArrangement = Arrangement.spacedBy(Grid.x4)) {
            PrivacyTier.entries.forEach { t ->
                val on = t === tier
                Box(
                    modifier = if (onPick == null) Modifier else Modifier.clickable { onPick(t) },
                ) {
                    Badge(t.label, if (on) Tone.Sat else Tone.Neutral)
                }
            }
        }
    }
}

/**
 * 设备与底座。**这一张念的是台账（[com.carpet.satdemo.core.devices.DeviceBus]），不是卡片自己编的状态**：
 * 卫星终端、圆屏、手机传感器各自一行，掉线的那行留在原地写清"最后一次见到是几点"。
 *
 * 台账放在场景之外，所以切场景、切 Tab 都不会让一台设备从页面上消失再出现（§5.3 硬要求 #2）。
 */
@Composable
fun DeviceManagerCard(s: CardScope) {
    val st = s.status
    val list = s.app.devices.collectAsState().value
    val now = System.currentTimeMillis()
    val allowed = s.app.allowedScreens()
    InkCard(icon = MIcons.terminal) {
        CardTitle("设备与底座", trailing = if (st.running) "守护运行中" else "守护已停")
        if (list.isEmpty()) {
            Text("台账还是空的。守护一开就会登记卫星终端，圆屏连上来会多一行", style = Type.Caption)
        }
        list.forEach { d -> LedgerRow(s.app, d, now) }
        ValueRow("接入点", st.localIp?.let { "ws://$it:${ScreenLinkServer.PORT}" }, mono = true)
        ValueRow("白名单", if (allowed.isEmpty()) "空" else allowed.joinToString())
        // 出厂那个 id 一旦被移出白名单，现场就没有第二台屏能顶上来了，所以这里必须能放回去
        if (!allowed.contains(ScreenLinkServer.DEFAULT_SCREEN_ID)) {
            Button(
                onClick = { s.app.allowScreen(ScreenLinkServer.DEFAULT_SCREEN_ID) },
                colors = ButtonDefaults.buttonColors(containerColor = Ink.Track, contentColor = Ink.TextHigh),
            ) { Text("放回出厂屏的 screen_id", style = Type.Caption) }
        }
        Text(
            "工程模式（开关台、线路原文）在下面的入口，演示救场时用",
            style = Type.Caption,
            color = Ink.TextLow,
        )
    }
}

@Composable
private fun LedgerRow(app: AppRuntime, d: Device, now: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(Grid.x4)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(d.title, style = Type.Body)
            Badge(linkLabel(d.link), uiTone(d.tone))
        }
        d.detail.forEach { Text(it, style = Type.Caption, color = Ink.TextMid) }
        when (val l = d.link) {
            is DeviceLink.Offline -> Text(l.why ?: "已离线", style = Type.Caption, color = Ink.Alert)
            is DeviceLink.Degraded -> Text(l.why, style = Type.Caption, color = Ink.Wait)
            is DeviceLink.Online -> Text("在线 ${elapsed(now - l.since)}", style = Type.Caption)
            is DeviceLink.Connecting -> Text("接入中 ${elapsed(now - l.since)}", style = Type.Caption)
            DeviceLink.Absent -> Unit
        }
        if (d.actions.contains("revoke")) {
            Button(
                onClick = { app.revokeScreen(d.title) },
                colors = ButtonDefaults.buttonColors(containerColor = Ink.Track, contentColor = Ink.TextHigh),
            ) { Text("移出白名单并断开", style = Type.Caption) }
        }
    }
}

private fun linkLabel(l: DeviceLink): String = when (l) {
    is DeviceLink.Online -> "在线"
    is DeviceLink.Degraded -> "在线但不健康"
    is DeviceLink.Connecting -> "接入中"
    is DeviceLink.Offline -> "离线"
    DeviceLink.Absent -> "不在场"
}

/** 台账那一行的颜色语义只能从 [Device.tone] 出，卡片这边只做一次枚举换算 */
private fun uiTone(t: CoreTone): Tone = when (t) {
    CoreTone.Ok -> Tone.Ok
    CoreTone.Wait -> Tone.Wait
    CoreTone.Alert -> Tone.Alert
    CoreTone.Neutral -> Tone.Neutral
}

private fun elapsed(ms: Long): String {
    val sec = (ms / 1000).coerceAtLeast(0)
    val m = sec / 60
    return if (m > 0) "$m 分 ${sec % 60} 秒" else "$sec 秒"
}
