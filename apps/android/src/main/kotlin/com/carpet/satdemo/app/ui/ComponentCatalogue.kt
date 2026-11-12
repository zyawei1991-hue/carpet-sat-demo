package com.carpet.satdemo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.carpet.satdemo.app.AppRuntime

/**
 * 组件目录（工程模式）。**给的是这套界面真正的原料，不是截图**：
 *
 *  - **卡片目录**：遍历 [Cards.registry]（26 张注册卡），每张先报 key / 分组 / 需要的能力 /
 *    能力现档（Live·示意·缺），再把**真身**画出来 —— 同一份 `body`，产品页看到什么这里就看到什么。
 *    加卡删卡这里自动跟着变，因为它读的是注册表，不是手抄的清单。
 *  - **原语样例**：InkCard 三档、RowLine、ChipBand、StatusRail、倒计时环这些基础件，
 *    一件一件摆出来并写明用途 —— 这是设计语言（§5.2）的活文档。
 */
@Composable
fun ComponentCatalogue(app: AppRuntime, status: com.carpet.satdemo.core.runtime.DemoRuntime.Status) {
    val scope = remember(status) { CardScope(app, status) }

    SectionHead("卡片注册表", "共 ${Cards.registry.all.size} 张 · 按 CardGroup 分组 · 能力缺的照画并标注")
    CardGroup.entries.forEach { g ->
        val cards = Cards.registry.all.filter { it.group == g }
        if (cards.isEmpty()) return@forEach
        Text(
            g.label.ifEmpty { "（不分组）" },
            style = Type.Overline,
            modifier = Modifier.padding(top = Grid.x8),
        )
        cards.forEach { d -> CatalogueCard(d, scope) }
    }

    Gap(12)
    SectionHead("原语样例", "Primitives.kt 里的基础件，一件一个最小示例")
    PrimitivesGallery()
}

@Composable
private fun CatalogueCard(d: CardDescriptor, scope: CardScope) {
    val needsText = d.needs.joinToString(" · ") { CAP_LABEL[it] ?: it.name }
    val missing = d.needs.filter { scope.avail(it) == Availability.Absent }
    val mocked = d.needs.filter { scope.avail(it) == Availability.Mocked }
    InkCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Grid.x8)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.title, style = Type.Title, modifier = Modifier.weight(1f))
                if (d.hero) Badge("本场景主卡", Tone.Sat)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Grid.x8)) {
                Text(d.key, style = Type.Mono, modifier = Modifier.weight(1f, fill = false))
                if (needsText.isNotEmpty()) Text("需要：$needsText", style = Type.Caption, color = Ink.TextLow)
            }
            if (missing.isNotEmpty()) {
                Text("缺能力，产品页不渲染：${missing.joinToString("、") { CAP_LABEL[it] ?: it.name }}",
                    style = Type.Caption, color = Ink.Alert)
            } else if (mocked.isNotEmpty()) {
                Text("示意档：${mocked.joinToString("、") { CAP_LABEL[it] ?: it.name }}",
                    style = Type.Caption, color = Ink.Wait)
            }
        }
    }
    // 目录不判 hero（一屏一个的规则只属于 CardStream），全部按普通档画
    d.body(scope)
}

private val CAP_LABEL: Map<Capability, String> = mapOf(
    Capability.SatelliteLink to "卫星链路",
    Capability.Ephemeris to "星历",
    Capability.PositionSource to "定位",
    Capability.Geofence to "围栏",
    Capability.Sentinel to "哨兵",
    Capability.Dock to "底座",
    Capability.Assistant to "助手链路",
    Capability.VehicleBus to "车机总线",
    Capability.ChildDevice to "孩子设备",
    Capability.HealthBand to "体征手环",
    Capability.AiAgent to "A2A",
    Capability.Account to "账号",
)

@Composable
private fun PrimitivesGallery() {
    var chipSel by remember { mutableIntStateOf(1) }

    InkCard(Modifier.fillMaxWidth()) {
        Text("InkCard 三档：Plain / Raised / Group", style = Type.Title)
        Text("普通卡不描边，强调卡才描；Group 用来托一组行", style = Type.Caption, color = Ink.TextLow)
        Gap(8)
        InkCard(Modifier.fillMaxWidth(), level = Level.Plain) { ValueRow("Plain（默认档）", "和页面拉开 1.33:1") }
        Gap(8)
        InkCard(Modifier.fillMaxWidth(), level = Level.Raised) { ValueRow("Raised（强调档）", "一屏最多一个") }
        Gap(8)
        InkCard(Modifier.fillMaxWidth(), level = Level.Group) { ValueRow("Group（分组背板）", "不单独当卡读") }
    }

    InkCard(Modifier.fillMaxWidth()) {
        Text("InkGroup：组行", style = Type.Title)
        Gap(8)
        InkGroup("服务", trailing = "3 项") {
            RowLine("守护服务", "在跑", tone = Tone.Ok)
            RowLine("圆屏接入", "可对话", tone = Tone.Sat)
            RowLine("蓝牙模组", null)
        }
    }

    InkCard(Modifier.fillMaxWidth()) {
        Text("ValueRow / RowLine：标签—值", style = Type.Title)
        Text("缺数据画 -- 不画 0；warming 画灰条", style = Type.Caption, color = Ink.TextLow)
        Gap(8)
        ValueRow("正常值", "412 km")
        ValueRow("语义色", "过境中", tone = Tone.Sat)
        ValueRow("缺数据", null)
        ValueRow("等宽", "39.9271, 116.3874", mono = true)
        ValueRow("预热中", null, warming = true)
    }

    InkCard(Modifier.fillMaxWidth()) {
        Text("ChipBand：选一档", style = Type.Title)
        Text("每行最多 3 枚，手工分带不折行", style = Type.Caption, color = Ink.TextLow)
        Gap(8)
        ChipBand(listOf("甲", "乙", "丙", "丁").mapIndexed { i, s ->
            ChipSpec(s, selected = chipSel == i, onSelect = { chipSel = i })
        })
    }

    InkCard(Modifier.fillMaxWidth()) {
        Text("StatusRail：顶部状态栏", style = Type.Title)
        Text("一条栏三段；没数据用 Neutral 空心圈", style = Type.Caption, color = Ink.TextLow)
        Gap(8)
        StatusRail(listOf(
            RailItem("链路", "在网", Tone.Ok),
            RailItem("过境", "9:21", Tone.Sat),
            RailItem("圆屏", null, Tone.Neutral),
        ))
    }

    InkCard(Modifier.fillMaxWidth()) {
        Text("徽标与点", style = Type.Title)
        Gap(8)
        Row(horizontalArrangement = Arrangement.spacedBy(Grid.x8), verticalAlignment = Alignment.CenterVertically) {
            Badge("正常", Tone.Ok); Badge("等待", Tone.Wait); Badge("告警", Tone.Alert); MockChip()
        }
        Gap(8)
        Row(horizontalArrangement = Arrangement.spacedBy(Grid.x16), verticalAlignment = Alignment.CenterVertically) {
            Tone.entries.forEach { t -> Row(verticalAlignment = Alignment.CenterVertically) { Dot(t); Gap(4); Text(t.label, style = Type.Caption) } }
        }
    }

    InkCard(Modifier.fillMaxWidth()) {
        Text("倒计时环 / 朝向盘 / 进度条", style = Type.Title)
        Gap(8)
        Row(horizontalArrangement = Arrangement.spacedBy(Grid.x16), verticalAlignment = Alignment.CenterVertically) {
            CountdownRing(0.62f, "9:21", Tone.Sat)
            AimDial(45.0)
        }
        Gap(8)
        ProgressBar(0.4f)
        Gap(8)
        StackedBar(listOf(78 to Ink.Sat, 14 to Ink.Wait, 8 to Ink.Track))
        Gap(4)
        Row(horizontalArrangement = Arrangement.spacedBy(Grid.x12)) {
            LegendDot(Ink.Sat, "驱动", "78%"); LegendDot(Ink.Wait, "空调", "14%"); LegendDot(Ink.Track, "待机", "8%")
        }
    }

    InkCard(Modifier.fillMaxWidth()) {
        Text("占位与异常", style = Type.Title)
        Gap(8)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Grid.x12)) {
            SkeletonBar()
            GhostAction("查看明细", onClick = {})
        }
        Gap(8)
        BlockError(reason = "星历还没回来（示例）", lastOkAtMs = null, onRetry = null)
    }
}
