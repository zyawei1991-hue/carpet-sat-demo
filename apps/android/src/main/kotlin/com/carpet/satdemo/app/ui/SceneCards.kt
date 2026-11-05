package com.carpet.satdemo.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.carpet.satdemo.app.AppRuntime
import com.carpet.satdemo.app.MaintStore
import com.carpet.satdemo.core.devices.Device
import com.carpet.satdemo.core.devices.DeviceLink
import com.carpet.satdemo.core.maint.Severity
import com.carpet.satdemo.core.maint.Suggestion
import com.carpet.satdemo.core.maint.WorkOrderCodec
import com.carpet.satdemo.core.vehicle.ReadingBoard
import com.carpet.satdemo.core.vehicle.ReadingKey
import com.carpet.satdemo.core.vehicle.TIRE_KEYS
import com.carpet.satdemo.core.devices.Tone as CoreTone
import com.carpet.satdemo.core.screen.ScreenLinkServer
import kotlin.math.roundToInt

/**
 * 用车养车 / 亲子 / 健康 / 数字分身 四个场景包的卡片。
 *
 * **这一整份文件里的数字都不是测出来的**，是场景壳的占位。之所以现在就画出来：
 * 前装车到底开放哪些数据还没定（§9 的外部依赖），而"卡片长什么样、要哪几个字段"
 * 是我们可以先定下来的东西 —— 等答复的时候改的是取数，不是版式。
 *
 * 改的就是取数 —— 这是字面意思。车辆那几张卡现在读的是 [CardScope.board] 那一帧读数，
 * 每条目带着它自己的来源档，「示意」那个标是读数自己说出来的（[InkCard.mock]），
 * 不是卡片画上去的。接上 OBD/TPMS 那天，桩自己消失，版式一行不动。
 */

@Composable
fun VehicleStatusCard(s: CardScope) {
    val b = s.board()
    val parked = s.status.snapshot?.sentinel?.armed == true
    val keys = listOf(ReadingKey.RangeKm, ReadingKey.FuelFraction, ReadingKey.OdometerKm) + TIRE_KEYS
    // 打不打标由这一组读数的来源决定：掺了桩就标，全真就自己把标摘掉
    InkCard(icon = MIcons.vehicle, mock = b.badge(keys) != null) {
        CardTitle("整车状态", trailing = if (parked) "哨兵布防中" else "熄火停车")
        ValueRow("剩余续航", b.text(ReadingKey.RangeKm, "%.0f km"))
        ValueRow("油量", b.of(ReadingKey.FuelFraction)?.let { fuelLine(it.value) })
        ValueRow("胎压", b.tireLine())
        // `%.0f` 不是笔误：读数是 Double，`%,d` 会在运行时抛 IllegalFormatConversionException，
        // 而这一行只在**有数**的时候才走到 —— 空态截图永远拍不到它
        ValueRow("累计里程", b.text(ReadingKey.OdometerKm, "%,.0f km"))
    }
}

/** 油量说成四分之几，不说 0.75 —— 车主在加油站对的是表针刻度，不是小数 */
private fun fuelLine(fraction: Double): String {
    val n = (fraction * 4).roundToInt().coerceIn(0, 4)
    return if (n == 4) "满箱" else if (n == 0) "见底" else "$n/4 箱"
}

/** 四轮胎压一行报完。分开四行会让这张卡只剩下胎压这一件事 */
private fun ReadingBoard.tireLine(): String? {
    val ts = TIRE_KEYS.mapNotNull { of(it) }
    if (ts.isEmpty()) return null
    return ts.joinToString(" / ") { "%.1f".format(it.value) } + if (ts.size == 4) " bar" else " bar（缺 ${4 - ts.size} 只）"
}

/** 一条读数按格式念出来。没有就是没有，不补一个看起来像数的 0 */
private fun ReadingBoard.text(key: ReadingKey, fmt: String): String? =
    of(key)?.let { fmt.format(it.value) }

/** 寻车：最后已知位置是**真**的（来自手机 GNSS），只有"车位记忆"那部分是桩 */
@Composable
fun VehicleFindCard(s: CardScope) {
    val fix = s.snap?.gnss?.takeIf { !(it.lat == 0.0 && it.lon == 0.0 && it.fixAgeS == null) }
    // 图标走 CardTitle 的内联那一只：标题行右边有个 48dp 的动作，标题被撑到垂直居中，
    // 左边栏里那枚小图标就会比标题高半行（截图里"寻车"正是这个毛病）。内联的这只和标题同一条基线带
    InkCard(mock = s.mock(Capability.VehicleBus)) {
        CardTitle(
            "寻车",
            icon = MIcons.parking,
            trailing = "记一次车位",
            trailingAction = { s.app.say("还没接车辆总线，这一次车位没存下来") },
        )
        // **没定位就不摆两行 `--`**：那两行说的和下面那句"还没有定位"是同一件事，
        // 说三遍不只啰嗦，还会让卡片看着像填不满的表（真机照片里"终端电量 --"是同一条 bug）
        ValueRow("车位编号", "B2-073")
        if (fix != null) {
            ValueRow("记录时间", fix.fixAgeS?.let { "%.0f 秒前".format(it) })
            // 经纬度是"复制进地图"用的，不是给人读的：排最后、降一档字号，不跟上面两行抢
            Text("经纬度 %.5f, %.5f".format(fix.lat, fix.lon), style = Type.Mono, color = Ink.TextLow)
        } else {
            Text("还没有定位，寻车只能靠上一次记下的车位", style = Type.Caption, color = Ink.TextLow)
        }
    }
}

/**
 * 「该做的事」。**这不是推荐流**：每一条都说得出自己是哪两条读数算出来的、判据是谁定的。
 *
 * 三行一行都不能少，因为它们各回答一个不同的问题：
 *  第一行 = 做什么；第二行 = 为什么现在（读数 + 判据）；第三行 = 这两样各是真是假。
 * 少第二行它退回成一句广告，少第三行就等于我们把桩数据摆成实测。
 *
 * **缺读数的规则整条不出现**（[ReadingBoard] 那条），所以这里没有「数据不足」那种占位行：
 * 一条说不准的建议会把同屏真建议的信任一起拉下去，而少一行只是少一行。
 */
@Composable
fun VehicleServiceCard(s: CardScope) {
    val maint = s.app.maint
    val scene by s.app.scene.collectAsState()
    val selected by maint.selected.collectAsState()
    val mine = run {
        val all = maint.suggest(s.board())
        // 视图按页（D11）：车辆页是全集，亲子页只留挂着孩子那一条
        if (scene == Scene.Kids) all.filter { it.sceneKey == "kids" } else all
    }
    val picked = mine.filter { selected.contains(it.code) }
    val tone = when (mine.firstOrNull()?.severity) {
        Severity.Due -> Tone.Alert
        Severity.Soon -> Tone.Wait
        else -> Tone.Neutral
    }

    InkCard(icon = MIcons.task, tone = tone, mock = mine.any { it.badge != null }) {
        CardTitle("该做的事", trailing = if (mine.isEmpty()) null else "${mine.size} 项")
        if (mine.isEmpty()) {
            Text(
                if (scene == Scene.Kids) "还没有轮到孩子的那几项。把「车上有孩子」打开，空调滤芯会提前提醒"
                else "现在没有到点的事。判据还没接手册，所以这句话只说明读数没越线",
                style = Type.Caption,
                color = Ink.TextLow,
            )
        }
        mine.forEach { sg ->
            SuggestionRow(sg, selected.contains(sg.code)) { maint.toggle(sg.code) }
        }

        MaintFold(
            "生成工单",
            if (picked.isEmpty()) "勾中的才进工单" else "已勾 ${picked.size} 项",
        ) { OrderSection(s, maint, picked) }

        MaintFold("判据是谁定的", maint.criteria.badge ?: "判据已接手册") {
            TextInputRow(
                placeholder = "上次保养时里程 · 现在 %,d km".format(maint.lastServiceKm()),
            ) { v -> maint.setLastServiceKm(v); s.app.say("记下了：上次保养在 %,d km".format(v)) }
            TextInputRow(
                placeholder = "上次保养距今 · 现在 %d 天".format(maint.lastServiceDays()),
            ) { v -> maint.setLastServiceDays(v); s.app.say("记下了：上次保养是 $v 天前") }
            TextInputRow(
                placeholder = "上次换空调滤芯 · 现在 %d 天".format(maint.filterDays()),
            ) { v -> maint.setFilterDays(v); s.app.say("记下了：滤芯是 $v 天前换的") }
            ToggleBoolRow("车上有急救包", maint.hasKit()) {
                maint.setHasKit(it)
                s.app.say(if (it) "记下了：车上有应急包" else "记下了：车上没有应急包")
            }
            ToggleBoolRow("车上有孩子", maint.hasChild()) {
                maint.setHasChild(it)
                s.app.say(if (it) "记下了：车上有孩子，滤芯那条会提前提醒" else "记下了：车里不带孩子")
            }
            Text(
                "间隔和标定胎压还等手册（保养 10,000 km / 12 个月、标定 2.5 bar），所以这几条判据现在都带着标",
                style = Type.Caption,
                color = Ink.Wait,
            )
        }
    }
}

@Composable
private fun OrderSection(s: CardScope, maint: MaintStore, picked: List<Suggestion>) {
    if (picked.isEmpty()) {
        Text("勾上面的行，勾中的才进工单", style = Type.Caption, color = Ink.TextLow)
        return
    }
    val order = maint.order(picked)
    val bytes = WorkOrderCodec.encode(order)
    val clip = LocalClipboardManager.current
    Text(
        WorkOrderCodec.describe(WorkOrderCodec.decode(bytes)),
        style = Type.Mono,
        color = Ink.TextMid,
    )
    Text(
        "%d 字节 · %s".format(bytes.size, if (WorkOrderCodec.fits(s.app.usage().budget)) "一帧装得下" else "装不进一帧"),
        style = Type.Caption,
        color = Ink.TextLow,
    )
    // 两句实话：车架号得接上 OBD 才读得到；上行是 M2，还没接，所以这一帧现在只在手机里
    if (!maint.vinReal) Text("车架号是编的：接上 OBD 盒子才读得到后 6 位", style = Type.Caption, color = Ink.Wait)
    val base by s.app.scene.collectAsState()
    val choice by s.app.privacyChoice.collectAsState()
    Text(
        if (maint.canSendOut(base.profile.basePrivacy, choice)) {
            "卫星上行还没接。这一帧现在只在手机里，复制出去对码"
        } else {
            "生效隐私档是本地留存：工单只存手机，不上行"
        },
        style = Type.Caption,
        color = Ink.TextLow,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Grid.x8)) {
        GhostAction("复制全文") {
            clip.setText(AnnotatedString(WorkOrderCodec.describe(WorkOrderCodec.decode(bytes))))
            s.app.say("工单复制好了")
        }
        GhostAction("清空勾选") { maint.clearSelected() }
    }
}

@Composable
private fun SuggestionRow(sg: Suggestion, checked: Boolean, onToggle: () -> Unit) {
    val tone = when (sg.severity) {
        Severity.Due -> Tone.Alert
        Severity.Soon -> Tone.Wait
        Severity.Note -> Tone.Neutral
    }
    // 整行可点：勾选框只有 40dp，让人在手机上瞄准一个小方块点十次是设计者的失败
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .defaultMinSize(minHeight = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(Grid.x8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        // weight(1f) 不是好看：没有它，右边那枚「到点」按自身宽度量，长说明会直接压在它上面
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Grid.x4)) {
            // chip 与标题同一行、顶部对齐：居中压在四行小字旁边，字号 1.3 时它像属于第二行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Grid.x8),
                verticalAlignment = Alignment.Top,
            ) {
                Text(sg.headline, style = Type.Body, color = Ink.TextHigh, modifier = Modifier.weight(1f))
                Badge(sg.severity.label, tone)
            }
            Text("${sg.detail} · 判据 ${sg.judgement.text}", style = Type.Caption, color = Ink.TextMid)
            // 两个标一行一个：并成一句在 1.3 字号下必然断在「未接 / 手册」中间，把出处拆成半句
            listOfNotNull(sg.badge, sg.judgement.source.badge).distinct().forEach { mark ->
                Text(mark, style = Type.Overline, color = Ink.Wait)
            }
        }
    }
}

/** 卡片内的折叠区。和 [CollapsableNote] 同一个形状，区别是里面装的是控件不是一段话 */
@Composable
private fun MaintFold(label: String, hint: String, content: @Composable ColumnScope.() -> Unit) {
    val reduce = LocalReduceMotion.current
    var open by rememberSaveable(label) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .defaultMinSize(minHeight = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(Grid.x8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (open) MIcons.collapse else MIcons.expand,
                contentDescription = null,
                tint = Ink.TextLow,
                modifier = Modifier.size(14.dp),
            )
            Text(label, style = Type.Caption.copy(color = Ink.Sat))
            Text(hint, style = Type.Caption, color = Ink.TextLow, modifier = Modifier.weight(1f))
        }
        AnimatedVisibility(visible = open, enter = Motion.expandIn(reduce), exit = Motion.expandOut(reduce)) {
            Column(
                modifier = Modifier.padding(top = Grid.x8),
                verticalArrangement = Arrangement.spacedBy(Grid.x8),
                content = content,
            )
        }
    }
}

/**
 * 车主填一个数。**框内实心底、不描边**：卡片已经是一个框，框里再套一个框就是"乱"的来处
 * （和首页那条发问栏同一条理由）。当前生效值写在占位里，所以不用再配一行标签。
 */
@Composable
private fun TextInputRow(placeholder: String, onCommit: (Int) -> Unit) {
    var draft by rememberSaveable(placeholder) { mutableStateOf("") }
    val commit = {
        val v = draft.filter { it.isDigit() }
        if (v.isNotEmpty()) onCommit(v.toInt())
        draft = ""
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Ink.Sunk, RoundedCornerShape(Round.Chip)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = draft,
            onValueChange = { d -> if (d.all { it.isDigit() } && d.length <= 7) draft = d },
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = Type.Body.copy(color = Ink.TextHigh),
            cursorBrush = SolidColor(Ink.Sat),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            decorationBox = { inner ->
                Box(modifier = Modifier.padding(horizontal = Grid.x12, vertical = Grid.x8)) {
                    if (draft.isEmpty()) Text(placeholder, style = Type.Body, color = Ink.TextLow)
                    inner()
                }
            },
        )
        GhostAction("记", onClick = commit)
    }
}

/** 开关量。「车上有孩子」这一条会改掉一条判据，所以它得是个能核对的开关，不是卡片角落的一颗芯片 */
@Composable
private fun ToggleBoolRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .defaultMinSize(minHeight = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = Type.Body, modifier = Modifier.weight(1f).padding(end = Grid.x12))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun VehicleEnergyCard(s: CardScope) {
    InkCard(icon = MIcons.battery, mock = s.mock(Capability.VehicleBus)) {
        CardTitle("能耗", trailing = "近 30 天")
        // 这三项加起来是 100%，所以是一根条的三段。原来画成三根独立进度条，
        // 第二第三根在截图里看着像画坏了的空槽
        StackedBar(listOf(78 to Ink.Ok, 14 to Ink.Wait, 8 to Ink.Track))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Grid.x12),
        ) {
            LegendDot(Ink.Ok, "驱动", "78%")
            LegendDot(Ink.Wait, "空调", "14%")
            LegendDot(Ink.Track, "待机", "8%")
        }
        ValueRow("平均电耗", "15.8 kWh/100km")
    }
}

@Composable
fun KidsControlCard(s: CardScope) {
    InkCard(icon = MIcons.kid, mock = s.mock(Capability.ChildDevice)) {
        CardTitle("亲子管控", trailing = "设备：项圈")
        ValueRow("今日屏幕时长", "37 分钟 / 上限 60")
        ValueRow("内容白名单", "故事、英语、汉字")
        ValueRow("夜间禁用", "21:30 – 06:30")
        Text(
            "规则在家长手机上定，孩子那边没有「取消」",
            style = Type.Caption,
            color = Ink.TextLow,
        )
    }
}

@Composable
fun KidsGrowthCard(s: CardScope) {
    InkCard(icon = MIcons.growth, mock = s.mock(Capability.ChildDevice)) {
        CardTitle("成长记录")
        ValueRow("今天新词", "5 个")
        ValueRow("连续打卡", "12 天")
        ValueRow("上次录音", "昨天 19:04")
        Text("记录默认留在这台手机上，云端只存摘要", style = Type.Caption, color = Ink.TextLow)
    }
}

@Composable
fun KidsAiCard(s: CardScope) {
    InkCard(icon = MIcons.model, mock = s.mock(Capability.AiAgent)) {
        CardTitle("AI 育儿", trailing = "只摘要，不判断")
        Text("「今天孩子主动问了 3 次『为什么』，集中在机械类。」", style = Type.Body)
        Text("「建议：把睡前故事换成齿轮主题的绘本。」", style = Type.Body)
        Text(
            "AI 不给孩子下结论，也不替家长处罚",
            style = Type.Caption,
            color = Ink.TextLow,
        )
    }
}

@Composable
fun HealthVitalsCard(s: CardScope) {
    InkCard(icon = MIcons.health, mock = s.mock(Capability.HealthBand)) {
        CardTitle("体征", trailing = "心率带未接")
        ValueRow("心率", "72 bpm")
        ValueRow("体温", "36.5 ℃")
        ValueRow("上次测量", "8 分钟前")
        Text(
            "超阈值走卫星发一条，不等蜂窝",
            style = Type.Caption,
            color = Ink.Wait,
        )
    }
}

/** 名称 + 状态的一行。打卡和养车那几张示意卡共用这个形状 */
@Composable
private fun RowWork(name: String, state: String, tone: Tone) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(name, style = Type.Body)
        Text(state, style = Type.Caption.copy(color = if (tone === Tone.Neutral) Ink.TextMid else tone.color))
    }
}

@Composable
fun HealthCheckinCard(s: CardScope) {
    InkCard(icon = MIcons.checkin, mock = s.mock(Capability.HealthBand)) {
        CardTitle("今日打卡")
        RowWork("血压", "08:12 已完成", Tone.Ok)
        RowWork("用药", "21:00 待完成", Tone.Wait)
        RowWork("步数", "4,120 / 6,000", Tone.Neutral)
    }
}

@Composable
fun HealthAgentCard(s: CardScope) {
    InkCard(icon = MIcons.agent, mock = s.mock(Capability.AiAgent)) {
        CardTitle("健康主理人")
        Text("「用药还差一次。要不要现在提醒？」", style = Type.Body)
        Text("动作：提醒一次 / 今天不提醒 / 转给家人", style = Type.Caption)
    }
}

@Composable
fun AvatarCard(s: CardScope) {
    InkCard(icon = MIcons.model, mock = s.mock(Capability.AiAgent)) {
        CardTitle("数字分身", trailing = "代答中")
        ValueRow("今日代答", "9 条")
        ValueRow("转真人", "2 条")
        ValueRow("被否决", "1 条")
        Text(
            "每一条代答都会先告诉对方是 AI",
            style = Type.Caption,
            color = Ink.Wait,
        )
    }
}

@Composable
fun A2aCard(s: CardScope) {
    InkCard(icon = MIcons.chat, mock = s.mock(Capability.AiAgent)) {
        CardTitle("分身社交")
        ValueRow("在线对象", "3")
        ValueRow("待授权共享", "位置 · 行程")
    }
}

@Composable
fun ShareObjectsCard(s: CardScope) {
    InkCard(icon = MIcons.share, mock = s.mock(Capability.Account)) {
        CardTitle("共享对象")
        RowWork("妈妈", "位置 · 全程可见", Tone.Ok)
        RowWork("车队群", "位置 · 仅越界时", Tone.Wait)
        RowWork("保险公司", "未授权", Tone.Neutral)
    }
}

/**
 * 订阅与流量包。**资费套餐是桩（等 §11 的 P0 答复），额度这一行不是** ——
 * 它读的是用量页同一个 [AppRuntime.usage]。两张卡对同一个数说出两句话，
 * 是 §5.2 最后一条明令禁止的那种事，所以这里宁可空着也不写 180/300。
 */
@Composable
fun SubscriptionCard(s: CardScope) {
    val u = s.app.usage()
    InkCard(icon = MIcons.plan, mock = s.mock(Capability.Account)) {
        CardTitle("订阅与流量包")
        ValueRow(
            "卫星消息额度",
            if (u.capacity > 0) "${u.handedOver} / ${u.capacity} 条" else "还没读到终端型号",
            if (u.pressure >= 0.9f) Tone.Alert else Tone.Neutral,
        )
        // **一条用满 0 的进度条不是"零"，是一条没人能读懂的横线**。
        // 0/50 那三个字已经把额度说完了，条只在真的开始占用之后才有信息
        if (u.pressure > 0f) ProgressBar(u.pressure, if (u.pressure >= 0.9f) Tone.Alert else Tone.Wait)
        ValueRow("到期", "2026-12-01")
        ValueRow("套餐", "守护版")
    }
}

/**
 * 隐私中心。**"生效档位 = 底座默认 与 家长设置 里更严的那个"这条规则是真的**（§6 第 3 条），
 * 两个输入里底座档位是按场景声明的（SceneProfile.basePrivacy），家长这一档由用户在这一屏选。
 */
@Composable
fun PrivacyCard(s: CardScope) {
    val picked = s.app.privacyChoice.collectAsState().value
    val base = s.app.scene.collectAsState().value.profile.basePrivacy
    val eff = PrivacyTier.effective(base, picked)
    InkCard(icon = MIcons.privacy, mock = s.mock(Capability.Account)) {
        CardTitle("隐私中心", trailing = "取更严的那个")
        TierRow("场景默认", base, null)
        HairLine()
        TierRow("家长设置", picked) { s.app.setPrivacyChoice(it) }
        HairLine()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("生效档位", style = Type.Body, color = Ink.TextMid)
            Badge(eff.label, if (eff === PrivacyTier.Strict) Tone.Ok else Tone.Wait)
        }
    }
}

/**
 * 一档隐私。**不排成"标签 — 芯片 — 说明"三列**：384dp（Mate60）上三列装不下，
 * 最右那一列被压成一字一行的竖排、左边留一整片空白 —— Row 不换行，装不下就只能拆字。
 *
 * **也不能横向滚**。上一版把三列改成"一行 + 横向滚动"，结果第三枚「本地留存，仅 SOS 出网」
 * 被容器边切掉后半截（整页截图里就在「只上传必要字段」右边）—— 而这是隐私档：
 * 用户选的是"我的数据去哪儿"，选项被切一半等于让他猜。改成折行，三枚都在，一枚都不缺字。
 *
 * 只读的那一档（底座默认）不给三枚可点的芯片：它不能选，画成能选的样子是骗人。
 */
@Composable
private fun TierRow(label: String, tier: PrivacyTier, onPick: ((PrivacyTier) -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Grid.x4),
        verticalArrangement = Arrangement.spacedBy(Grid.x8),
    ) {
        Text(label, style = Type.Body, color = Ink.TextMid)
        if (onPick == null) {
            Text(tier.label, style = Type.Caption, color = Ink.TextLow)
        } else {
            // 两枚一行：这三档名字长短差很多（「全量上传」约 74dp，「本地留存，仅 SOS 出网」约 154dp），
            // 三枚挤一行在 360dp 上就超宽 —— 不换行的那一行会把最后一枚切掉半截，
            // 而隐私档被切掉的那半截恰恰是最该看清的一档
            ChipBand(
                PrivacyTier.entries.map { t -> ChipSpec(t.label, selected = t === tier) { onPick(t) } },
                perRow = 2,
            )
        }
    }
}

/**
 * 设备与底座。**这一张念的是台账（[com.carpet.satdemo.core.devices.DeviceBus]），不是卡片自己编的状态**：
 * 卫星终端、圆屏、手机传感器各自一行，掉线的那行留在原地写清"最后一次见到是几点"。
 *
 * 台账放在场景之外，所以切场景、切 Tab 都不会让一台设备从页面上消失再出现（§5.3 硬要求 #2）。
 */
@Composable
fun DeviceManagerCard(s: CardScope) {
    val st = s.status
    val list = s.app.devices.collectAsState().value
    val now = System.currentTimeMillis()
    val allowed = s.app.allowedScreens()
    InkCard(icon = MIcons.terminal) {
        CardTitle("我的设备", trailing = if (st.running) "守护运行中" else "守护已停")
        // 这一行必须是"钉住了没"，不是"守护开着没"：权限没批的时候 1Hz 照跑，
        // 但锁屏久了会被系统冻住，报"运行中"就是假话
        ValueRow(
            "常驻",
            when {
                !st.running -> "没跑起来"
                s.app.guardPinned -> "后台常驻，锁屏也不断"
                else -> "只在 App 开着时跑：定位权限没批，锁屏会停"
            },
        )
        if (list.isEmpty()) {
            Text("还没有设备。开启守护后会自动登记", style = Type.Caption)
        }
        list.forEach { d -> LedgerRow(s.app, d, now) }
        ValueRow("接入点", st.localIp?.let { "ws://$it:${ScreenLinkServer.PORT}" }, mono = true)
        ValueRow("白名单", if (allowed.isEmpty()) "空" else allowed.joinToString())
        // 出厂那个 id 一旦被移出白名单，现场就没有第二台屏能顶上来了，所以这里必须能放回去
        if (!allowed.contains(ScreenLinkServer.DEFAULT_SCREEN_ID)) {
            Button(
                onClick = { s.app.allowScreen(ScreenLinkServer.DEFAULT_SCREEN_ID) },
                colors = ButtonDefaults.buttonColors(containerColor = Ink.Track, contentColor = Ink.TextHigh),
            ) { Text("放回出厂那台圆屏", style = Type.Caption) }
        }
    }
}

@Composable
private fun LedgerRow(app: AppRuntime, d: Device, now: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(Grid.x4)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(d.title, style = Type.Body)
            Badge(linkLabel(d.link), uiTone(d.tone))
        }
        d.detail.forEach { Text(it, style = Type.Caption, color = Ink.TextMid) }
        when (val l = d.link) {
            is DeviceLink.Offline -> Text(l.why ?: "已离线", style = Type.Caption, color = Ink.Alert)
            is DeviceLink.Degraded -> Text(l.why, style = Type.Caption, color = Ink.Wait)
            is DeviceLink.Online -> Text("在线 ${elapsed(now - l.since)}", style = Type.Caption)
            is DeviceLink.Connecting -> Text("接入中 ${elapsed(now - l.since)}", style = Type.Caption)
            DeviceLink.Absent -> Unit
        }
        if (d.actions.contains("revoke")) {
            Button(
                onClick = { app.revokeScreen(d.title) },
                colors = ButtonDefaults.buttonColors(containerColor = Ink.Track, contentColor = Ink.TextHigh),
            ) { Text("移出白名单并断开", style = Type.Caption) }
        }
    }
}

private fun linkLabel(l: DeviceLink): String = when (l) {
    is DeviceLink.Online -> "在线"
    is DeviceLink.Degraded -> "在线但不健康"
    is DeviceLink.Connecting -> "接入中"
    is DeviceLink.Offline -> "离线"
    DeviceLink.Absent -> "不在场"
}

/** 台账那一行的颜色语义只能从 [Device.tone] 出，卡片这边只做一次枚举换算 */
private fun uiTone(t: CoreTone): Tone = when (t) {
    CoreTone.Ok -> Tone.Ok
    CoreTone.Wait -> Tone.Wait
    CoreTone.Alert -> Tone.Alert
    CoreTone.Neutral -> Tone.Neutral
}

private fun elapsed(ms: Long): String {
    val sec = (ms / 1000).coerceAtLeast(0)
    val m = sec / 60
    return if (m > 0) "$m 分 ${sec % 60} 秒" else "$sec 秒"
}
