package com.carpet.satdemo.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
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
    // 一屏一个重心：流里**第一张**标了 hero 的卡升一档，其余一律 [Level.Plain]。
    // 只有第一张 —— 注册表里两张卡同屏时谁当主角由顺序决定，而顺序是用户排的，不是页面钦定的
    val hero = keys.firstOrNull { Cards.registry.isHero(it) && Cards.registry.resolve(it, scope) != null }
    if (!grouped) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Grid.Card),
        ) {
            keys.forEach { key -> RenderCard(key, scope, hero = key == hero) }
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
                live.filter { Cards.registry.group(it) === g }.forEach { RenderCard(it, scope, hero = it == hero) }
            }
        }
    }
}

/**
 * 场景的可用动作。这些动作全都还要靠车机总线或账号侧接口才能真做，
 * 所以现在按下去只如实说没接 —— 一个看着能按、按了什么都没发生的按钮，
 * 比没有这个按钮更糟。
 *
 * **用 Material 的 OutlinedButton，不用自己拼的方块**：自拼的 clickable 方块没有按压态、
 * 没有焦点态、没有涟漪，用户按下去只有字变了颜色，那正是"质感差"的来处。描边（而非实底）
 * 把它标成次要动作：三颗都还没接的东西不该长得像本页的主行动。
 *
 * **不横向滚**：三个词在任何一档视口都装得下，而横向滚动会让人以为后面还有第四个。
 * 最小高度 48dp —— Material 的触摸目标下限，点得着比它长得瘦重要。
 */
@Composable
fun SceneActions(actions: List<String>, app: AppRuntime) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Grid.x8),
    ) {
        actions.forEach { a ->
            OutlinedButton(
                onClick = { app.say("「$a」还没接：需要车辆总线或账号通道") },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                shape = RoundedCornerShape(Round.Chip),
                border = BorderStroke(1.dp, Ink.Outline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink.TextHigh, containerColor = Ink.Sunk),
                contentPadding = PaddingValues(horizontal = Grid.x8, vertical = Grid.x4),
            ) {
                Text(a, style = Type.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    MiniAction("上移") { app.moveCard(key, up = true) }
                    MiniAction(if (hidden.contains(key)) "显示" else "隐藏") {
                        app.toggleCard(key, !hidden.contains(key))
                    }
                }
            }
        }
    }
}

/**
 * 一个文字动作，但**手指区域给到 48dp**。之前这里是一枚 12.5sp 的字直接 clickable，
 * 实际可点面积约 30dp —— Material 的下限是 48dp，够不着的控件比看不见的控件更容易被骂"难用"。
 * clickable 写在 defaultMinSize 外面：这样撑开的空白也在触摸区里，而不是只有那几个字。
 */
@Composable
private fun MiniAction(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .padding(horizontal = Grid.x4),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Type.Caption, color = Ink.Sat, maxLines = 1)
    }
}
