package com.carpet.satdemo.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import com.carpet.satdemo.app.AppRuntime
import com.carpet.satdemo.core.runtime.DemoRuntime

/**
 * 场景外壳（§5.3）。**换场景就是换一个 [SceneProfile]：不重建导航栈、不重新登录、不重连设备**，
 * 因为连接握在 [AppRuntime] 上，这一屏只是换一批卡片。
 */

@Composable
fun SceneSwitcher(app: AppRuntime, modifier: Modifier = Modifier) {
    val current by app.scene.collectAsState()
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Grid.x8)) {
        // 五个全排在一条横向可滚的带上，不再"先摆三个 + 更多场景（2）"：
        // 那一行折叠让首屏多出一条只有我们自己在乎的链接，而滚动条本来就装得下五个。
        // **上面不再重复写一遍场景名** —— 选中那枚是实底蓝，它自己就是标题，
        // 标题行再念一次就是同一屏两个重心（截图里"车载守护"出现过两次）
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Grid.x8),
        ) {
            Scene.entries.toList().forEach { sc -> Chip(sc.label, selected = sc === current) { app.sceneOn(sc) } }
        }
        // 副标题只留场景自己那句话。"默认隐私：只上传必要字段"是我们内部的档位口径，
        // 挂在标题下面，用户读到的是"这行在跟我讲规则"，不是"现在是什么模式"
        Text(current.subtitle, style = Type.Caption, color = Ink.TextLow)
    }
}

/**
 * 卡片流。缺能力的卡在这里直接被跳过，不画占位也不报错（§5.3 硬要求 #3）。
 *
 * `grouped = true` 时按 [CardGroup] 分段，每段一个 [SectionHead]。**分组只重排"段"，
 * 段内仍是传进来的顺序** —— 那串 key 是用户在"排列这些卡"里排出来的，页面不许把它按自己的
 * 表覆盖掉（否则同一个设置在两个页上说法不一样）。段的顺序取每组第一次出现的先后。
 */
@Composable
fun CardStream(keys: List<String>, scope: CardScope, modifier: Modifier = Modifier, grouped: Boolean = false) {
    if (!grouped) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Grid.Card),
        ) {
            keys.forEach { key -> RenderCard(key, scope) }
        }
        return
    }
    // 先滤掉没能力的卡：一段里没有一张能画的卡，就不该留一个孤零零的标题
    val live = keys.filter { Cards.registry.resolve(it, scope) != null }
    val order = mutableListOf<CardGroup>()
    live.forEach { if (!order.contains(Cards.registry.group(it))) order += Cards.registry.group(it) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Grid.x16),
    ) {
        order.forEach { g ->
            Column(verticalArrangement = Arrangement.spacedBy(Grid.Card)) {
                if (g !== CardGroup.Inline) SectionHead(g.label, g.note)
                live.filter { Cards.registry.group(it) === g }.forEach { RenderCard(it, scope) }
            }
        }
    }
}

/**
 * 场景的可用动作。这些动作全都还要靠车机总线或账号侧接口才能真做，
 * 所以现在按下去只如实说没接 —— 一个看着能按、按了什么都没发生的按钮，
 * 比没有这个按钮更糟。
 *
 * **画成小字块，不画成三颗大按钮**：三颗等高按钮摆在页首，视觉上就是本页的"主行动"，
 * 可它们一个都没接。降级成文字块之后，它读起来是"这个场景会做的事"，而不是"快点我"。
 *
 * **不横向滚**：三个词在任何一档视口都装得下，而横向滚动会让人以为后面还有第四个。
 * 高度给到 44dp —— 它是可点的，点得着比它长得瘦重要。
 */
@Composable
fun SceneActions(actions: List<String>, app: AppRuntime) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Grid.x8),
    ) {
        actions.forEach { a ->
            // 等宽 + 描边：三颗只填 Ink.Sunk 的方块贴在黑底上，读出来是"页面坏了一块"，不是"能按"
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .background(Ink.Raise, RoundedCornerShape(Round.Chip))
                    .border(1.dp, Ink.Outline, RoundedCornerShape(Round.Chip))
                    .clickable { app.runtime.log("「$a」还没接：需要车辆总线或账号通道") },
                contentAlignment = Alignment.Center,
            ) { Text(a, style = Type.Body, maxLines = 1) }
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
