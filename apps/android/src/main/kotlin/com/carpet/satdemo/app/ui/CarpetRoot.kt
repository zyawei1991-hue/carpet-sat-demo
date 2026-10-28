package com.carpet.satdemo.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.carpet.satdemo.app.AppRuntime

/**
 * App 装配层。Tab 数量固定四个（§5.1）：**加场景不加 Tab**，靠 Tab1 顶部的场景切换器换卡片集合。
 *
 * 工程模式是第四个 Tab 里的一个入口，不占第五个位置 —— 它给彩排和救场用，
 * 不该出现在产品导航里。
 */
@Composable
fun CarpetRoot(app: AppRuntime) {
    val status by app.status.collectAsState()
    val flags by app.flags.collectAsState()

    if (flags.engineering) {
        EngineerScreen(app, onBack = { app.setFlag(flags.copy(engineering = false)) })
        return
    }

    val tab by app.tab.collectAsState()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Ink.Deep,
        bottomBar = {
            NavigationBar(containerColor = Ink.Card, tonalElevation = 0.dp) {
                Tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = i == tab,
                        onClick = { app.setTab(i) },
                        icon = { Glyph(t.key, if (i == tab) Ink.TextHigh else Ink.TextLow) },
                        label = { Text(t.label, style = Type.Caption) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Ink.TextHigh,
                            selectedTextColor = Ink.TextHigh,
                            unselectedIconColor = Ink.TextLow,
                            unselectedTextColor = Ink.TextLow,
                            indicatorColor = Ink.Track,
                        ),
                    )
                }
            }
        },
    ) { insets ->
        Box(modifier = Modifier.padding(insets)) {
            when (tab) {
                0 -> SceneShell(app, status)
                1 -> VehicleTab(app, status)
                2 -> FamilyTab(app, status)
                else -> MeTab(app, status, onEngineer = { app.setFlag(flags.copy(engineering = true)) })
            }
        }
    }
}

private data class TabSpec(val key: String, val label: String)

private val Tabs = listOf(
    TabSpec("home", "首页"),
    TabSpec("vehicle", "车辆"),
    TabSpec("family", "家人"),
    TabSpec("me", "我的"),
)

/**
 * 导航图标自己画，不引图标库（§5.2 第 4 条：形要有语义）。
 * 环=守护台在转，框=车辆，两个点=家人，点+框=我的。
 * 颜色只走灰阶 —— 语义色留给状态，导航图标一染色，"蓝=卫星链路"就不纯净了。
 */
@Composable
private fun Glyph(key: String, color: Color) {
    Canvas(modifier = Modifier.size(24.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f
        val line = Stroke(width = 2.dp.toPx())
        when (key) {
            "home" -> drawCircle(color = color, radius = r * 0.7f, center = c, style = line)
            "vehicle" -> drawRoundRect(
                color = color,
                topLeft = Offset(c.x - r * 0.8f, c.y - r * 0.5f),
                size = Size(r * 1.6f, r * 1.0f),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                style = line,
            )
            "family" -> {
                drawCircle(color = color, radius = r * 0.28f, center = Offset(c.x - r * 0.35f, c.y))
                drawCircle(color = color, radius = r * 0.28f, center = Offset(c.x + r * 0.35f, c.y))
            }
            else -> {
                drawCircle(color = color, radius = r * 0.28f, center = Offset(c.x, c.y - r * 0.4f))
                drawRoundRect(
                    color = color,
                    topLeft = Offset(c.x - r * 0.45f, c.y + r * 0.05f),
                    size = Size(r * 0.9f, r * 0.6f),
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                    style = line,
                )
            }
        }
    }
}
