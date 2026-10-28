package com.carpet.satdemo.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.carpet.satdemo.app.AppRuntime
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
        InkCard {
            CardTitle("运行与服务")
            ToggleRow(
                "守护服务",
                status.running,
                "关掉会断开与圆屏的连接，App 队列里欠的报文留着",
            ) { on -> if (on) app.runtime.start() else app.runtime.stop() }
            ToggleRow("允许展示端接入", flags.acceptScreens, "默认关。演示现场才放开") {
                app.setFlag(flags.copy(acceptScreens = it))
            }
            ToggleRow("演示模式", flags.demoMode, "压缩时间轴，让窗口和队列在几分钟内演完") {
                app.setFlag(flags.copy(demoMode = it))
            }
        }
        InkCard {
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
        Column(modifier = Modifier.padding(end = Grid.x12)) {
            Text(title, style = Type.Body)
            Text(hint, style = Type.Caption, color = Ink.TextLow)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
