package com.carpet.satdemo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.font.FontFamily
import com.carpet.satdemo.app.AppRuntime

/**
 * 工程模式。**这就是原来那个演示控制台，整体搬进来的，能力一条没丢**：
 * 彩排开关、星历源切换、固件升级演示、SOS、事件日志、线路原文窗。
 *
 * 它和产品界面共用同一份状态渲染：那几行字仍然出自 `DemoRuntime.consoleText()`，
 * 和桌面彩排台读的是同一个函数（§5.2）。产品页给人看结论，这一页给人看为什么。
 */
@Composable
fun EngineerScreen(app: AppRuntime, onBack: () -> Unit) {
    val status by app.status.collectAsState()
    val flags by app.flags.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .background(Ink.Deep)
            .padding(Grid.x16),
        verticalArrangement = Arrangement.spacedBy(Grid.x12),
    ) {
        Button(
            onClick = onBack,
            colors = ButtonDefaults.buttonColors(containerColor = Ink.Track, contentColor = Ink.TextMid),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("返回产品界面") }

        // 手机、桌面彩排台、这一页，三处读同一个渲染函数，彩排时看到的才等于装机看到的
        Text(
            app.runtime.consoleText(status),
            style = Type.Mono.copy(color = Ink.TextHigh),
            modifier = Modifier
                .fillMaxWidth()
                .background(Ink.Card, androidx.compose.foundation.shape.RoundedCornerShape(Grid.x8))
                .padding(Grid.x12),
        )

        Text("彩排开关", style = Type.Body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
        EngRow("模拟断网（彩排用）", flags.simulatedOffline) {
            app.setFlag(flags.copy(simulatedOffline = it))
        }
        EngRow("模拟卫星未过顶", flags.noPassWindow) { app.setFlag(flags.copy(noPassWindow = it)) }
        EngRow("模拟 GT1005 掉线", flags.gt1005Offline) { app.setFlag(flags.copy(gt1005Offline = it)) }
        EngRow("星历：用厂商 SDK（重启生效）", app.vendorEph) { app.setVendorEph(it) }

        Button(
            onClick = { app.pressSos() },
            colors = ButtonDefaults.buttonColors(containerColor = Ink.Alert, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("SOS", style = Type.Hero) }

        Button(
            onClick = { if (!status.running) app.runtime.start() else app.runtime.stop() },
            colors = ButtonDefaults.buttonColors(containerColor = Ink.Track, contentColor = Ink.TextHigh),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (status.running) "停止服务" else "启动服务") }

        Button(
            onClick = { app.runUpgrade() },
            colors = ButtonDefaults.buttonColors(containerColor = Ink.Track, contentColor = Ink.Wait),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("演一次固件升级（占住写锁）") }

        EngRow("工程模式日志窗", flags.passthrough) { app.setFlag(flags.copy(passthrough = it)) }

        if (flags.passthrough) {
            Text(app.runtime.engineeringWindowHeader(), style = Type.Mono)
            app.runtime.passthrough.snapshot().forEach { line ->
                Text(line.render(), style = Type.Mono.copy(color = Color.LightGray), maxLines = 1)
            }
        }

        Text("事件日志", style = Type.Body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
        status.log.takeLast(20).forEach { line ->
            Text(line, style = Type.Caption.copy(fontFamily = FontFamily.Monospace))
        }
    }
}

@Composable
private fun EngRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Type.Body)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

