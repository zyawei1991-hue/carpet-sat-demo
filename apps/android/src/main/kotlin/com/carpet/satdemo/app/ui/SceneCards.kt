package com.carpet.satdemo.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
        CardTitle("整车状态", trailing = if (parked) "哨兵布防中" else "熄火停车")
        ValueRow("剩余续航", "412 km")
        ValueRow("油量", "3/4 箱")
        ValueRow("胎压", "2.4 / 2.4 / 2.3 / 2.5 bar")
        ValueRow("累计里程", "23,417 km")
    }
}

/** 寻车：最后已知位置是**真**的（来自手机 GNSS），只有"车位记忆"那部分是桩 */
@Composable
fun VehicleFindCard(s: CardScope) {
    val fix = s.snap?.gnss?.takeIf { !(it.lat == 0.0 && it.lon == 0.0 && it.fixAgeS == null) }
    // 图标走 CardTitle 的内联那一只：标题行右边有个 48dp 的动作，标题被撑到垂直居中，
    // 左边栏里那枚小图标就会比标题高半行（截图里"寻车"正是这个毛病）。内联的这只和标题同一条基线带
    InkCard(mock = s.mock(Capability.VehicleBus)) {
        CardTitle(
            "寻车",
            icon = MIcons.parking,
            trailing = "记一次车位",
            trailingAction = { s.app.say("还没接车辆总线，这一次车位没存下来") },
        )
        // **没定位就不摆两行 `--`**：那两行说的和下面那句"还没有定位"是同一件事，
        // 说三遍不只啰嗦，还会让卡片看着像填不满的表（真机照片里"终端电量 --"是同一条 bug）
        ValueRow("车位编号", "B2-073")
        if (fix != null) {
            ValueRow("记录时间", fix.fixAgeS?.let { "%.0f 秒前".format(it) })
            // 经纬度是"复制进地图"用的，不是给人读的：排最后、降一档字号，不跟上面两行抢
            Text("经纬度 %.5f, %.5f".format(fix.lat, fix.lon), style = Type.Mono, color = Ink.TextLow)
        } else {
            Text("还没有定位，寻车只能靠上一次记下的车位", style = Type.Caption, color = Ink.TextLow)
        }
    }
}

@Composable
fun VehicleServiceCard(s: CardScope) {
    InkCard(icon = MIcons.ticket, mock = s.mock(Capability.VehicleBus)) {
        CardTitle("养车工单")
        RowWork("保养", "还有 830 km", Tone.Wait)
        RowWork("轮胎换位", "已逾期 12 天", Tone.Alert)
        RowWork("年检", "明年 3 月", Tone.Neutral)
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
        // 这三项加起来是 100%，所以是一根条的三段。原来画成三根独立进度条，
        // 第二第三根在截图里看着像画坏了的空槽
        StackedBar(listOf(78 to Ink.Ok, 14 to Ink.Wait, 8 to Ink.Track))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Grid.x12),
        ) {
            LegendDot(Ink.Ok, "驱动", "78%")
            LegendDot(Ink.Wait, "空调", "14%")
            LegendDot(Ink.Track, "待机", "8%")
        }
        ValueRow("平均电耗", "15.8 kWh/100km")
    }
}

@Composable
fun KidsControlCard(s: CardScope) {
    InkCard(icon = MIcons.kid, mock = s.mock(Capability.ChildDevice)) {
        CardTitle("亲子管控", trailing = "设备：项圈")
        ValueRow("今日屏幕时长", "37 分钟 / 上限 60")
        ValueRow("内容白名单", "故事、英语、汉字")
        ValueRow("夜间禁用", "21:30 – 06:30")
        Text(
            "规则在家长手机上定，孩子那边没有「取消」",
            style = Type.Caption,
            color = Ink.TextLow,
        )
    }
}

@Composable
fun KidsGrowthCard(s: CardScope) {
    InkCard(icon = MIcons.growth, mock = s.mock(Capability.ChildDevice)) {
        CardTitle("成长记录")
        ValueRow("今天新词", "5 个")
        ValueRow("连续打卡", "12 天")
        ValueRow("上次录音", "昨天 19:04")
        Text("记录默认留在这台手机上，云端只存摘要", style = Type.Caption, color = Ink.TextLow)
    }
}

@Composable
fun KidsAiCard(s: CardScope) {
    InkCard(icon = MIcons.model, mock = s.mock(Capability.AiAgent)) {
        CardTitle("AI 育儿", trailing = "只摘要，不判断")
        Text("「今天孩子主动问了 3 次『为什么』，集中在机械类。」", style = Type.Body)
        Text("「建议：把睡前故事换成齿轮主题的绘本。」", style = Type.Body)
        Text(
            "AI 不给孩子下结论，也不替家长处罚",
            style = Type.Caption,
            color = Ink.TextLow,
        )
    }
}

@Composable
fun HealthVitalsCard(s: CardScope) {
    InkCard(icon = MIcons.health, mock = s.mock(Capability.HealthBand)) {
        CardTitle("体征", trailing = "心率带未接")
        ValueRow("心率", "72 bpm")
        ValueRow("体温", "36.5 ℃")
        ValueRow("上次测量", "8 分钟前")
        Text(
            "超阈值走卫星发一条，不等蜂窝",
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
    }
}

@Composable
fun AvatarCard(s: CardScope) {
    InkCard(icon = MIcons.model, mock = s.mock(Capability.AiAgent)) {
        CardTitle("数字分身", trailing = "代答中")
        ValueRow("今日代答", "9 条")
        ValueRow("转真人", "2 条")
        ValueRow("被否决", "1 条")
        Text(
            "每一条代答都会先告诉对方是 AI",
            style = Type.Caption,
            color = Ink.Wait,
        )
    }
}

@Composable
fun A2aCard(s: CardScope) {
    InkCard(icon = MIcons.chat, mock = s.mock(Capability.AiAgent)) {
        CardTitle("分身社交")
        ValueRow("在线对象", "3")
        ValueRow("待授权共享", "位置 · 行程")
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
            "卫星消息额度",
            if (u.capacity > 0) "${u.handedOver} / ${u.capacity} 条" else "还没读到终端型号",
            if (u.pressure >= 0.9f) Tone.Alert else Tone.Neutral,
        )
        ProgressBar(u.pressure, if (u.pressure >= 0.9f) Tone.Alert else Tone.Wait)
        ValueRow("到期", "2026-12-01")
        ValueRow("套餐", "守护版")
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
        TierRow("场景默认", base, null)
        HairLine()
        TierRow("家长设置", picked) { s.app.setPrivacyChoice(it) }
        HairLine()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("生效档位", style = Type.Body, color = Ink.TextMid)
            Badge(eff.label, if (eff === PrivacyTier.Strict) Tone.Ok else Tone.Wait)
        }
    }
}

/**
 * 一档隐私。**不排成"标签 — 芯片 — 说明"三列**：384dp（Mate60）上三列装不下，
 * 最右那一列被压成一字一行的竖排、左边留一整片空白 —— Row 不换行，装不下就只能拆字。
 * 改成上下两行，档位那一行走横向滚动。
 *
 * 只读的那一档（底座默认）不给三枚可点的芯片：它不能选，画成能选的样子是骗人。
 */
@Composable
private fun TierRow(label: String, tier: PrivacyTier, onPick: ((PrivacyTier) -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Grid.x4),
        verticalArrangement = Arrangement.spacedBy(Grid.x8),
    ) {
        Text(label, style = Type.Body, color = Ink.TextMid)
        if (onPick == null) {
            Text(tier.label, style = Type.Caption, color = Ink.TextLow)
        } else {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Grid.x8),
            ) {
                PrivacyTier.entries.forEach { t -> Chip(t.label, selected = t === tier) { onPick(t) } }
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
        CardTitle("我的设备", trailing = if (st.running) "守护运行中" else "守护已停")
        // 这一行必须是"钉住了没"，不是"守护开着没"：权限没批的时候 1Hz 照跑，
        // 但锁屏久了会被系统冻住，报"运行中"就是假话
        ValueRow(
            "常驻",
            when {
                !st.running -> "没跑"
                s.app.guardPinned -> "后台常驻，锁屏也不断"
                else -> "只在 App 开着时跑：定位权限没批，锁屏会停"
            },
        )
        if (list.isEmpty()) {
            Text("还没有设备。开启守护后会自动登记", style = Type.Caption)
        }
        list.forEach { d -> LedgerRow(s.app, d, now) }
        ValueRow("接入点", st.localIp?.let { "ws://$it:${ScreenLinkServer.PORT}" }, mono = true)
        ValueRow("白名单", if (allowed.isEmpty()) "空" else allowed.joinToString())
        // 出厂那个 id 一旦被移出白名单，现场就没有第二台屏能顶上来了，所以这里必须能放回去
        if (!allowed.contains(ScreenLinkServer.DEFAULT_SCREEN_ID)) {
            Button(
                onClick = { s.app.allowScreen(ScreenLinkServer.DEFAULT_SCREEN_ID) },
                colors = ButtonDefaults.buttonColors(containerColor = Ink.Track, contentColor = Ink.TextHigh),
            ) { Text("放回出厂那台圆屏", style = Type.Caption) }
        }
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
