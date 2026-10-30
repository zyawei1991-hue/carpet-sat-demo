package com.carpet.satdemo.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.carpet.satdemo.app.AppRuntime

/**
 * App 装配层。Tab 数量固定四个（§5.1）：**加场景不加 Tab**，靠 Tab1 顶部的场景切换器换卡片集合。
 *
 * 工程模式是第四个 Tab 里的一个入口，不占第五个位置 —— 它给彩排和救场用，
 * 不该出现在产品导航里。
 *
 * 换页动画只有一档：轻微上推 + 淡入淡出。做重了会盖住内容本身的变化，
 * 用户在四个页面之间来回点的时候，动画应该让他觉得"是同一摞卡片换了个摆法"。
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
            Column {
                // SOS 常驻位。**不做成悬浮胶囊**：悬浮那颗在「我的」这一页正好压在开关上
                // （Mate60 截图），而这一页每一行的可点控件都贴着右边缘。
                // 放在导航栏上面一格，四个页面都看得见、按得到，且永远不挡内容。
                SosSlot(app)
                NavigationBar(containerColor = Ink.Card, tonalElevation = 0.dp) {
                    Tabs.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = i == tab,
                            onClick = { app.setTab(i) },
                            icon = { Icon(t.icon, contentDescription = t.label, modifier = Modifier.size(22.dp)) },
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
            }
        },
    ) { insets ->
        Box(modifier = Modifier.padding(insets)) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    (slideInVertically(tween(Motion.Normal)) { it / 12 } + fadeIn(tween(Motion.Fast)))
                        .togetherWith(slideOutVertically(tween(Motion.Normal)) { -it / 12 } + fadeOut(tween(Motion.Fast)))
                },
                label = "tab",
            ) { t ->
                when (t) {
                    0 -> HomeShell(app, status)
                    1 -> VehicleTab(app, status)
                    2 -> FamilyTab(app, status)
                    else -> MeTab(app, status, onEngineer = { app.setFlag(flags.copy(engineering = true)) })
                }
            }
        }
    }
}

/**
 * 导航栏上面那一格：常驻的 SOS 入口。
 *
 * 四个产品页共用这一格 —— SOS 不该取决于"当前恰好停在哪个 Tab"。
 * 工程模式页在 [CarpetRoot] 开头就 return 了，走不到这里。
 */
@Composable
private fun SosSlot(app: AppRuntime) {
    Box(
        modifier = Modifier.fillMaxWidth().background(Ink.Deep).padding(horizontal = Grid.Page, vertical = Grid.x8),
        contentAlignment = Alignment.CenterEnd,
    ) { SosFab(app) }
}

private data class TabSpec(
    val label: String,
    val icon: ImageVector,
)

/** 导航图标全部用官方 Material 图标集（material-icons-extended，Apache-2.0），不再自绘 */
private val Tabs = listOf(
    TabSpec("首页", MIcons.navHome),
    TabSpec("车辆", MIcons.navVehicle),
    TabSpec("家人", MIcons.navFamily),
    TabSpec("我的", MIcons.navMe),
)
