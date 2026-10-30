package com.carpet.satdemo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.carpet.satdemo.app.AppRuntime
import com.carpet.satdemo.core.ai.PermissionTier
import com.carpet.satdemo.core.runtime.DemoRuntime

/**
 * Tab2/3/4 三页。它们和 Tab1 用的是同一个注册表、同一个 CardScope，
 * 差别只在"这一页摆哪几串 key"。**没有第二套卡片实现**，
 * 否则同一状态在两个页面上会说出两种话（§5.2 最后一条）。
 */

@Composable
private fun Page(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Grid.x16, vertical = Grid.x12)
            .padding(bottom = Grid.x32),
        verticalArrangement = Arrangement.spacedBy(Grid.x12),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Grid.x4)) {
            Text(title, style = Type.Hero)
            Text(subtitle, style = Type.Caption)
        }
        content()
    }
}

/** Tab2 车辆：车载守护场景的完整卡片流。切到这里**不重新连接任何设备**，只是换一批卡 */
@Composable
fun VehicleTab(app: AppRuntime, status: DemoRuntime.Status, modifier: Modifier = Modifier) {
    val scope = CardScope(app, status)
    Page("车辆", "用车养车 · 数据通道未接，卡片会打「示意数据」", modifier = modifier) {
        SceneActions(app.scene.value.profile.actions, app)
        CardStream(app.orderedCards(Scene.Vehicle), scope)
    }
}

/** Tab3 家人：亲子 + 健康 + 共享对象三个场景的卡合在一页（§5.1 里 Tab3 就是这个口径） */
@Composable
fun FamilyTab(app: AppRuntime, status: DemoRuntime.Status, modifier: Modifier = Modifier) {
    val scope = CardScope(app, status)
    val keys = (Scene.Kids.profile.cards + Scene.Health.profile.cards + listOf("share.objects")).distinct()
    Page("家人", "亲子陪伴 · 健康助手 · 共享对象", modifier = modifier) {
        CardStream(keys, scope)
    }
}

/** Tab4 我的：设备与底座、订阅、隐私中心，加上真正的产品级开关和工程模式入口 */
@Composable
fun MeTab(
    app: AppRuntime,
    status: DemoRuntime.Status,
    onEngineer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = CardScope(app, status)
    val flags by app.flags.collectAsState()
    Page("我的", "设备与底座 · 订阅与流量包 · 隐私中心", modifier = modifier) {
        RenderCard("device.manager", scope)
        InkCard(icon = MIcons.task) {
            CardTitle("运行与服务")
            ToggleRow(
                "守护服务",
                status.running,
                "常驻通知会钉住进程，锁屏后 1Hz 那一圈照跑；关掉就断开与圆屏的连接",
            ) { on -> app.setGuard(on) }
            ToggleRow("允许展示端接入", flags.acceptScreens, "默认关。演示现场才放开") {
                app.setFlag(flags.copy(acceptScreens = it))
            }
            ToggleRow("演示模式", flags.demoMode, "压缩时间轴，让窗口和队列在几分钟内演完") {
                app.setFlag(flags.copy(demoMode = it))
            }
        }
        InkCard(icon = MIcons.guard) {
            CardTitle("守护开关")
            ToggleRow(
                "哨兵模式",
                status.snapshot?.sentinel?.armed == true,
                "静止超阈值上报，动了打点") { app.setSentinel(it) }
            ToggleRow(
                "电子围栏：西湖景区",
                status.snapshot?.fence?.active == true,
                "越界走卫星发一条，不等蜂窝") { app.setFence(it, "西湖景区") }
        }
        AssistantSettings(app)
        RenderCard("usage", scope)
        RenderCard("subscription", scope)
        RenderCard("privacy", scope)
        Button(
            onClick = onEngineer,
            colors = ButtonDefaults.buttonColors(containerColor = Ink.Track, contentColor = Ink.TextMid),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("工程模式（彩排开关与线路原文）") }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, hint: String, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // weight(1f) 不是好看，是**不压到开关**：没有它，长说明按自身宽度量，
        // 直接盖在右侧 Switch 上（截图 harness 第一次拍出来就看见了这条）
        Column(modifier = Modifier.weight(1f).padding(end = Grid.x12)) {
            Text(title, style = Type.Body)
            Text(hint, style = Type.Caption, color = Ink.TextLow)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * 主理人的"能碰什么"。首页那张卡只管问与答，这一张管权限：
 * **档位逐条列出每条技能当前准不准** —— 只写一句"已放开"的话，用户不知道放开的是什么，
 * 出事时也读不出是哪一条越了档（§6 第 3 条要的是能核对，不是能开关）。
 */
@Composable
private fun AssistantSettings(app: AppRuntime) {
    val endpoint by app.aiEndpoint.collectAsState()
    val granted by app.aiGranted.collectAsState()
    val rows = remember(granted) { app.aiSkills() }
    InkCard(icon = MIcons.agent, tone = if (endpoint.configured) Tone.Sat else Tone.Wait) {
        CardTitle("主理人与模型", trailing = if (endpoint.configured) endpoint.model else "本机规则")
        ValueRow("端点", endpoint.baseUrl.takeIf { it.isNotBlank() })
        ValueRow("密钥", if (endpoint.apiKey.isBlank()) null else "已存本机")
        SectionLabel("权限档：${granted.label}（往右每高一档都更危险）")
        Row(horizontalArrangement = Arrangement.spacedBy(Grid.x8)) {
            PermissionTier.entries.forEach { t ->
                Chip(t.label, selected = t === granted) { app.setAiGranted(t) }
            }
        }
        SectionLabel("它现在会做什么")
        rows.forEach { r ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Grid.x8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(r.title, style = Type.Body, modifier = Modifier.weight(1f))
                Text(r.needs.label, style = Type.Caption, color = Ink.TextLow)
                Badge(if (r.allowed) "准许" else "拦下", if (r.allowed) Tone.Ok else Tone.Wait)
            }
        }
        Text(
            "密钥只写进本机 SharedPreferences，不进日志、不进线路原文窗。「发求救」那一档就算放开，" +
                "网关也只回一句「提示你自己去按」—— 不可逆动作不由对话触发（§6.1）。",
            style = Type.Caption,
            color = Ink.TextLow,
        )
    }
}
