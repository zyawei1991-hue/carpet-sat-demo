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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
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
        bottomBar = { BottomBar(app, tab) },
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
 * 底部那一格：四个导航位 + 正中间一颗 SOS 圆钮。
 *
 * 为什么在中间而不是悬浮胶囊或导航栏上面一格：这一颗是**常驻安全件**，
 * 悬浮件可以压文字、不可以压控件（在「我的」那一页它正好压住开关行），
 * 而导航栏正中既永远看得见、又永远不挡内容 —— 四个页面共用同一格位置。
 *
 * 中间那颗占一个和导航位等宽的位置，所以 360dp 窄屏上五格都装得下，
 * 不会像原来那样把最要紧的东西挤成竖排。
 */
@Composable
private fun BottomBar(app: AppRuntime, tab: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().height(80.dp).background(Ink.Card),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tabs.forEachIndexed { i, t ->
            if (i == 2) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) { SosDial(app) }
            }
            NavigationBarItem(
                selected = i == tab,
                onClick = { app.setTab(i) },
                modifier = Modifier.weight(1f),
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
