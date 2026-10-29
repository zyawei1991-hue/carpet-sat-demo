package com.carpet.satdemo.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.carpet.satdemo.app.AppRuntime
import com.carpet.satdemo.core.runtime.DemoRuntime

/**
 * 场景外壳（§5.3）。**换场景就是换一个 [SceneProfile]：不重建导航栈、不重新登录、不重连设备**，
 * 因为连接握在 [AppRuntime] 上，这一屏只是换一批卡片。
 */

@Composable
fun SceneSwitcher(app: AppRuntime, modifier: Modifier = Modifier) {
    val current by app.scene.collectAsState()
    var more by remember { mutableStateOf(false) }
    val shown = if (more) Scene.entries.toList() else Scene.quick
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Grid.x12)) {
        SectionHead(current.label, current.subtitle + " · 底座默认隐私 " + current.profile.basePrivacy.label)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Grid.x8),
        ) {
            shown.forEach { sc -> Chip(sc.label, selected = sc === current) { app.sceneOn(sc) } }
        }
        Text(
            if (more) "收起场景" else "更多场景（${Scene.entries.size - Scene.quick.size}）",
            style = Type.Caption,
            color = Ink.Sat,
            modifier = Modifier.clickable { more = !more },
        )
    }
}

/** 卡片流。缺能力的卡在这里直接被跳过，不画占位也不报错（§5.3 硬要求 #3） */
@Composable
fun CardStream(keys: List<String>, scope: CardScope, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Grid.Card),
    ) {
        keys.forEach { key -> RenderCard(key, scope) }
    }
}

/**
 * 场景的可用动作。**只有 SOS 是真接的**，其余按下去只记一行日志说"没接" ——
 * 一个看着能按、按了什么都没发生的按钮，比没有这个按钮更糟。
 */
@Composable
fun SceneActions(actions: List<String>, app: AppRuntime) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Grid.x8),
    ) {
        actions.take(3).forEach { a ->
            if (a.contains("SOS")) {
                Button(
                    onClick = { app.pressSos() },
                    colors = ButtonDefaults.buttonColors(containerColor = Ink.Alert, contentColor = Color.White),
                ) { Text(a) }
            } else {
                Button(
                    onClick = { app.runtime.log("「$a」还没接：需要车辆总线或账号通道") },
                    colors = ButtonDefaults.buttonColors(containerColor = Ink.Track, contentColor = Ink.TextMid),
                ) { Text(a) }
            }
        }
    }
}

/** 卡片流的管理：隐藏 + 上移。§5.1 里"可排序、可隐藏"就落在这两件事上 */
@Composable
fun CardManager(app: AppRuntime, keys: List<String>) {
    val hidden by app.hiddenCards.collectAsState()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Grid.x4),
    ) {
        keys.forEach { key ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(Cards.registry.title(key), style = Type.Body)
                Row(horizontalArrangement = Arrangement.spacedBy(Grid.x8)) {
                    Text(
                        "上移",
                        style = Type.Caption,
                        color = Ink.Sat,
                        modifier = Modifier.clickable { app.moveCard(key, up = true) },
                    )
                    Text(
                        if (hidden.contains(key)) "显示" else "隐藏",
                        style = Type.Caption,
                        color = Ink.Sat,
                        modifier = Modifier.clickable { app.toggleCard(key, !hidden.contains(key)) },
                    )
                }
            }
        }
    }
}
