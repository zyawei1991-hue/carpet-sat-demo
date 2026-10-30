package com.carpet.satdemo.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.carpet.satdemo.app.AppRuntime
import com.carpet.satdemo.core.ai.ReplySource
import com.carpet.satdemo.core.ai.Turn
import com.carpet.satdemo.core.ai.TurnFrom
import com.carpet.satdemo.core.devices.DeviceKind
import com.carpet.satdemo.core.devices.DeviceLink
import com.carpet.satdemo.core.protocol.DialogOrigin
import com.carpet.satdemo.core.runtime.DemoRuntime
import com.carpet.satdemo.core.screen.ScreenLinkServer

/**
 * 首页（V1.10 按算出来的层级重排）。
 *
 * 上一版这里有两个 34sp 的大数块（圆屏、对话）和六张同一档的卡 —— **两个重心等于没有重心**。
 * 现在一屏只留一个 [Level.Raised]（对话），圆屏降成一组行 [InkGroup]，卫星仍是那条兜底栏，
 * 工程长说明一律收进 [CollapsableNote]，不再摊在正文里。
 *
 * 三件事没动，因为它们不是排版问题：**SOS 长按入口不许藏**、**每条回答逐条标来源**、
 * **卫星那两张卡展开后仍是同一张 [RenderCard]**（手机和圆屏不许各解释一遍同一个状态）。
 */
@Composable
fun HomeShell(app: AppRuntime, status: DemoRuntime.Status, modifier: Modifier = Modifier) {
    val scene by app.scene.collectAsState()
    val scope = CardScope(app, status)
    var editing by rememberSaveable { mutableStateOf(false) }
    var satOpen by rememberSaveable { mutableStateOf(false) }
    var moreCards by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Grid.Page)
            .padding(top = Grid.x12, bottom = Grid.x24),
        verticalArrangement = Arrangement.spacedBy(Grid.Group),
    ) {
        HomeRail(app, status)
        AssistantHero(app)
        DockGroup(app, status)
        SatelliteStrip(app, status, open = satOpen, onToggle = { satOpen = !satOpen })
        // 那两张卡默认收着。兜底条上那句"展开完整链路卡"必须真能点开，否则它是一句谎
        AnimatedVisibility(visible = satOpen, enter = Motion.expandIn, exit = Motion.expandOut) {
            Column(verticalArrangement = Arrangement.spacedBy(Grid.Card)) {
                RenderCard("link", scope)
                RenderCard("pass", scope)
            }
        }

        SceneSwitcher(app)
        // 过境卡由上面那条兜底栏给，卡片流里不能再出现第二次。
        // 一上来摊十张同权重的卡就是"乱"的本体，所以默认只出前三张
        val stream = app.orderedCards(scene).filterNot { it == "pass" }
        CardStream(if (moreCards) stream else stream.take(3), scope)
        if (stream.size > 3) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { moreCards = !moreCards }
                    .padding(vertical = Grid.x8),
                horizontalArrangement = Arrangement.spacedBy(Grid.x8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (moreCards) MIcons.collapse else MIcons.expand,
                    contentDescription = null,
                    tint = Ink.Sat,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    if (moreCards) "收起卡片流" else "展开其余 ${stream.size - 3} 张卡",
                    style = Type.Caption,
                    color = Ink.Sat,
                )
            }
        }
        Text(
            if (editing) "完成" else "编辑卡片流",
            style = Type.Caption,
            color = Ink.Sat,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { editing = !editing }
                .padding(vertical = Grid.x8),
        )
        if (editing) CardManager(app, app.managedCards(scene))
    }
}

/**
 * 顶部状态条：一眼回答"这两台今天能不能用"。
 * **它不是装饰** —— 之前这三个数散在三张卡里，用户要滚到第二、三屏才知道屏没连上。
 */
@Composable
private fun HomeRail(app: AppRuntime, st: DemoRuntime.Status) {
    val sat = st.snapshot?.sat
    val left = sat?.nextPassInS
    val dockTone = when {
        st.serverError != null -> Tone.Alert
        st.clients > 0 -> Tone.Ok
        else -> Tone.Wait
    }
    val satTone = when {
        sat != null && !sat.moduleOk -> Tone.Alert
        left == null -> Tone.Wait
        left <= 0 -> Tone.Ok
        else -> Tone.Sat
    }
    StatusRail(
        listOf(
            RailItem("圆屏", if (st.clients > 0) "${st.clients} 台" else "未连", dockTone),
            RailItem(
                "卫星",
                if (sat?.moduleOk == false) "离线" else left?.let { if (it <= 0) "开窗" else "${it}s" } ?: "--",
                satTone,
            ),
            RailItem("守护", if (app.guardPinned) "在跑" else "没跑", if (app.guardPinned) Tone.Ok else Tone.Wait),
        ),
    )
    // SOS 不在这条里。它原来钉在这行的右端，理由是"不滚动就能按到"—— 判断对，位置错：
    // 384dp（Mate60）上三枚芯片 + 一颗钮装不下，被挤的只能是那颗钮，全页最要紧的入口
    // 被拆成两行竖排。现在它在底部导航栏正中（[SosDial]），四个页面都在，也不挡任何内容
}

/**
 * 首页唯一的重心：主理人对话。**只有这一张卡用 [Level.Raised]**（更亮的底 + 一根描边）。
 *
 * 它排在圆屏配对上面，因为"我问它一句它答得对不对"是每天发生频率最高的动作，配对是一次性的事。
 */
@Composable
private fun AssistantHero(app: AppRuntime) {
    val chat = app.chat.collectAsState().value
    val endpoint by app.aiEndpoint.collectAsState()
    val granted by app.aiGranted.collectAsState()
    var draft by rememberSaveable { mutableStateOf("") }
    var configuring by rememberSaveable { mutableStateOf(false) }
    var url by rememberSaveable { mutableStateOf(endpoint.baseUrl) }
    var key by rememberSaveable { mutableStateOf(endpoint.apiKey) }
    var model by rememberSaveable { mutableStateOf(endpoint.model) }

    InkCard(level = Level.Raised, icon = MIcons.agent, tone = if (endpoint.configured) Tone.Sat else Tone.Wait) {
        CardTitle(
            "主理人",
            trailing = if (endpoint.configured) "远端模型 ${endpoint.model}" else "本机规则 · 未接模型",
        )
        // 对话的宿主在手机，圆屏是它的耳朵和嘴。这一行说死"两边同一份记录"这件事：
        // 屏上说的话会出现在这里，这里答的话会上屏 —— 而不是两块屏各存一份再对齐
        Text(
            if (app.screenDialog) "圆屏也能问：屏上说的话会出现在这里，这里答的话会上屏"
            else "圆屏还没接通：这里说的每一条，屏上看不到",
            style = Type.Caption,
            color = if (app.screenDialog) Ink.Ok else Ink.TextLow,
        )

        if (chat.isEmpty()) Text("还没有对话。问一句，或者点下面那几个词。", style = Type.Caption, color = Ink.TextLow)
        chat.takeLast(3).forEach { line -> Bubble(app, line) }
        if (chat.size > 3) Text("共 ${chat.size} 条，只显示最近 3 条", style = Type.Caption, color = Ink.TextLow)

        // **横向可滚，不许折行**：Row 不会换行，装不下的那颗只能把字竖着拆开
        // （截图里"车/在/哪"排成三行，是这一版最难看的一处）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Grid.x8),
        ) {
            listOf("链路", "过境", "额度", "圆屏配对", "车在哪").forEach { q ->
                Box(
                    modifier = Modifier
                        .background(Ink.Track, RoundedCornerShape(Round.Chip))
                        .clickable { app.askAi(q) }
                        .padding(horizontal = Grid.x12, vertical = Grid.x8),
                ) { Text(q, style = Type.Caption, color = Ink.TextHigh) }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Grid.x8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("问一句", style = Type.Caption) },
                    singleLine = true,
                    textStyle = Type.Body,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Ink.Sat,
                        unfocusedBorderColor = Ink.Track,
                        focusedTextColor = Ink.TextHigh,
                        unfocusedTextColor = Ink.TextHigh,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { app.askAi(draft); draft = "" }),
                )
            }
            IconButton(onClick = { app.askAi(draft); draft = "" }) {
                Icon(MIcons.send, contentDescription = "发送", tint = Ink.Sat)
            }
            // 语音这条链路在圆屏那侧（板载双麦），手机只做入口：按下去必须说清还没接
            IconButton(onClick = { app.runtime.log("语音输入还没接：麦克风在圆屏那一侧") }) {
                Icon(MIcons.mic, contentDescription = "语音（未接）", tint = Ink.TextMid)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("权限档：${granted.label}", style = Type.Caption, color = Ink.TextLow)
            Text(
                if (configuring) "收起" else "接模型",
                style = Type.Caption,
                color = Ink.Sat,
                modifier = Modifier.clickable { configuring = !configuring },
            )
        }
        AnimatedVisibility(visible = configuring, enter = Motion.expandIn, exit = Motion.expandOut) {
            Column(verticalArrangement = Arrangement.spacedBy(Grid.x8)) {
                FieldRow("Base URL", url) { url = it }
                FieldRow("API Key", key) { key = it }
                FieldRow("模型名", model) { model = it }
                Button(
                    onClick = { app.setAiEndpoint(url, key, model) },
                    colors = ButtonDefaults.buttonColors(containerColor = Ink.Sat, contentColor = Ink.Deep),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存并重连") }
            }
        }
    }
}

@Composable
private fun FieldRow(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, style = Type.Caption) },
        singleLine = true,
        textStyle = Type.Mono,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Ink.Sat,
            unfocusedBorderColor = Ink.Track,
            focusedTextColor = Ink.TextHigh,
            unfocusedTextColor = Ink.TextHigh,
        ),
    )
}

/** 一条对话。左右对齐就够，不做头像、不做气泡尾巴 —— 那两个都会把一屏能看的条数压掉一条 */
@Composable
private fun Bubble(app: AppRuntime, line: Turn) {
    val mine = line.from == TurnFrom.Me
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                // 气泡底必须比卡片底再亮一档：画成同色就等于没画，用户读不出哪两行是它说的
                .background(if (mine) Ink.Sat.copy(alpha = Ink.TINT) else Ink.Track, RoundedCornerShape(Round.Card))
                .padding(horizontal = Grid.x12, vertical = Grid.Inner),
            verticalArrangement = Arrangement.spacedBy(Grid.x4),
        ) {
            // 圆屏起的那一轮，问和答都要标。车主想知道的是"刚才是不是有人站在车那边跟屏说话"
            if (line.via == DialogOrigin.screen) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Grid.x4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(MIcons.dock, contentDescription = null, tint = Ink.Wait, modifier = Modifier.size(13.dp))
                    Text("圆屏", style = Type.Caption, color = Ink.Wait)
                }
            }
            Text(line.text, style = Type.Body)
            if (!mine) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Grid.x8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        when {
                            !line.ours -> MIcons.voice
                            line.source.live -> MIcons.model
                            line.proactive -> MIcons.agent
                            else -> MIcons.ledger
                        },
                        contentDescription = null,
                        tint = Ink.TextMid,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(sourceLabel(line), style = Type.Caption, color = if (line.ours) Ink.TextLow else Ink.Wait)
                    // 写的是技能名（"圆屏怎么连"），不是注册表 id（"dock.pair"）——
                    // 后者是我们内部的键名，车主看见只会看见一个键名
                    line.skill?.let { Text("· ${app.skillTitle(it)}", style = Type.Caption, color = Ink.TextLow) }
                }
            }
        }
    }
}

/**
 * 来源必须逐条标，不能整块标一次。**"本机规则"和"远端模型"混在一屏里不写清楚，
 * 观众就会把规则模板当成接了大模型** —— 这比承认没接要贵得多。
 */
private fun sourceLabel(line: Turn): String = when {
    // 小智官方云那一路：话不是我们的网关生成的，界面上必须单独一句话说明，
    // 而不是混进"远端模型"里 —— 那会让人以为这句话也过了我们的红线
    line.source == ReplySource.XiaozhiCloud -> "小智云 · 未过网关"
    line.proactive -> "主动提醒"
    line.source == ReplySource.Model -> "远端模型"
    line.source == ReplySource.Denied -> "权限拦截"
    line.source == ReplySource.LocalFallback -> "未接模型 · 规则作答"
    else -> "本机状态直答"
}

/**
 * 圆屏配对。**从"一张带大数的卡"降成一组行** —— 配对是一次性动作，不该占首页重心；
 * 但它必须能在这一屏做完（不许跳去「我的」开开关），所以开关、白名单、配对输入框都在组里。
 */
@Composable
private fun DockGroup(app: AppRuntime, st: DemoRuntime.Status) {
    val flags by app.flags.collectAsState()
    val screens = app.devices.collectAsState().value.filter { it.kind == DeviceKind.DisplayScreen }
    val allowed = app.allowedScreens()
    var draft by rememberSaveable { mutableStateOf("") }
    // 还没连上时配对摊开：这是这一屏唯一需要用户动手的地方，藏起来等于让人找不到入口
    var open by rememberSaveable { mutableStateOf(st.clients == 0) }
    val online = st.clients > 0

    InkGroup("圆屏", trailing = if (online) "${st.clients} 台在线" else "未连接") {
        RowLine(
            "接入点",
            st.serverError?.let { "起不来：$it" } ?: st.localIp?.let { "ws://$it:${ScreenLinkServer.PORT}" },
            tone = if (st.serverError != null) Tone.Alert else Tone.Neutral,
            mono = true,
        )
        HairLine()
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Grid.x4),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = Grid.x12)) {
                Text("允许圆屏接入", style = Type.Body)
                Text("开起来才能配对；白名单之外一律拒绝", style = Type.Caption, color = Ink.TextLow)
            }
            Switch(checked = flags.acceptScreens, onCheckedChange = { app.setFlag(flags.copy(acceptScreens = it)) })
        }
        if (allowed.isNotEmpty()) {
            HairLine()
            Column(verticalArrangement = Arrangement.spacedBy(Grid.x8)) {
                SectionHead("白名单", "点一下移出，并立刻断开")
                Row(horizontalArrangement = Arrangement.spacedBy(Grid.x8)) {
                    allowed.forEach { id ->
                        // 台账里那台的链接状态才是"现在通不通"；只认白名单会把"填过号但从没来过"画成绿的
                        val link = screens.firstOrNull { it.title == id }?.link
                        Box(modifier = Modifier.clickable { app.revokeScreen(id) }) {
                            Badge(
                                id,
                                when (link) {
                                    is DeviceLink.Online -> Tone.Ok
                                    is DeviceLink.Degraded -> Tone.Wait
                                    is DeviceLink.Connecting -> Tone.Wait
                                    is DeviceLink.Offline -> Tone.Alert
                                    else -> Tone.Neutral
                                },
                            )
                        }
                    }
                }
            }
        }
        if (open) {
            HairLine()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Grid.x8),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("圆屏编号", style = Type.Caption) },
                        singleLine = true,
                        textStyle = Type.Body,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Ink.Sat,
                            unfocusedBorderColor = Ink.Track,
                            focusedTextColor = Ink.TextHigh,
                            unfocusedTextColor = Ink.TextHigh,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (app.pairScreen(draft)) draft = "" }),
                    )
                }
                Button(
                    onClick = { if (app.pairScreen(draft)) draft = "" },
                    colors = ButtonDefaults.buttonColors(containerColor = Ink.Sat, contentColor = Ink.Deep),
                ) { Text("加入") }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(vertical = Grid.x4),
            horizontalArrangement = Arrangement.spacedBy(Grid.x8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (open) MIcons.collapse else MIcons.expand,
                contentDescription = null,
                tint = Ink.Sat,
                modifier = Modifier.size(14.dp),
            )
            Text(if (open) "收起配对" else "配对新的圆屏", style = Type.Caption, color = Ink.Sat)
        }
        CollapsableNote(
            "配对怎么做",
            "手机这边放开接入、把圆屏编号加进白名单，再到圆屏上填上面那个接入点地址。",
        )
    }
}

/**
 * 卫星兜底条。**降的是位置，不是能力**：相位、过顶倒计时、SOS 长按三样一个不少。
 * 完整的链路卡与过境卡默认收着，点那行字才展开 —— 展开的是同一张 [RenderCard]，
 * 这里不重复实现，否则手机和圆屏会各自解释一遍同一个状态（§5.2 最后一条）。
 */
@Composable
private fun SatelliteStrip(app: AppRuntime, st: DemoRuntime.Status, open: Boolean, onToggle: () -> Unit) {
    val sat = st.snapshot?.sat
    val phase = sat?.phase?.let { phaseLabel(it) } ?: "—"
    val left = sat?.nextPassInS
    val moduleOff = sat != null && !sat.moduleOk
    val tone = when {
        moduleOff -> Tone.Alert
        left == null -> Tone.Wait
        left <= 0 -> Tone.Ok
        else -> Tone.Sat
    }
    val big = when {
        moduleOff -> "离线"
        left == null -> "--"
        left <= 0 -> "开窗"
        else -> "${left}s"
    }
    InkCard(icon = MIcons.satellite, tone = tone) {
        CardTitle("卫星兜底", trailing = if (moduleOff) "终端离线" else "相位 $phase")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Grid.x16),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Grid.x4)) {
                Text(
                    when {
                        moduleOff -> "卫星终端没在线，要发的内容先攒在手机这边"
                        left == null -> "还没有可用的过境时间"
                        left <= 0 -> "窗口开着，现在就能发"
                        else -> "距下次过顶 ${left}s"
                    },
                    style = Type.Body,
                )
                Text(
                    if (open) "收起卫星详情" else "展开卫星详情",
                    style = Type.Caption,
                    color = Ink.Sat,
                    modifier = Modifier.clickable { onToggle() },
                )
            }
            // 这一格里不再放第二个图标（卡片左上角已经有卫星了），放的是"还要等多久"那个数：
            // 兜底条要能在扫一眼时回答一个问题，而这个问题是时间。
            // 用 Numeric 而不是 Hero —— 1Hz 跳秒，比例字体会抖行
            AnimatedContent(targetState = big, transitionSpec = { Motion.slideForward(true) }, label = "strip") { t ->
                Text(t, style = Type.Numeric.copy(color = tone.color))
            }
        }
        // 这里不放 SOS：那一颗在底部导航栏正中（[SosDial]），同一页两个入口只会让人按错一个。
        // 撤销那条回显留在原地 —— 它讲的是"刚才按的那下有没有生效"，属于这张卡的账
        val cancel by app.cancelLine.collectAsState()
        cancel?.let { Text(it, style = Type.Caption, color = Ink.Wait) }
    }
}
