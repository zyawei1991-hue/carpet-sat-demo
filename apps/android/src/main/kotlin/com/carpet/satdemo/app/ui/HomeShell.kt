package com.carpet.satdemo.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * 首页（V1.7 重排）。
 *
 * **两个主区：圆屏连接 + 主理人对话。卫星链路降成一条兜底横条。**
 * 这不是审美问题，是产品逻辑问题 —— 之前首页把卫星当主角，是因为 demo 是从那条链路长出来的；
 * 用户每天真正会碰的是"那块屏连没连上"和"我问它一句它答得对不对"。
 *
 * 唯一没降的是 SOS 长按入口：安全件藏进二级页等于没有。它在兜底条里占一格，
 * 按下去仍然是三段倒计时、松手可取消。
 */
@Composable
fun HomeShell(app: AppRuntime, status: DemoRuntime.Status, modifier: Modifier = Modifier) {
    val scene by app.scene.collectAsState()
    val scope = CardScope(app, status)
    var editing by remember { mutableStateOf(false) }
    // 卫星那两张卡默认收着。兜底条上那句"完整卡在上面"必须真能点开，否则它是一句谎
    var satOpen by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Grid.x16, vertical = Grid.x12)
            .padding(bottom = Grid.x32),
        verticalArrangement = Arrangement.spacedBy(Grid.x16),
    ) {
        DockHero(app, status)
        AssistantHero(app)
        SatelliteStrip(app, status, open = satOpen, onToggle = { satOpen = !satOpen })
        AnimatedVisibility(
            visible = satOpen,
            enter = fadeIn(tween(Motion.Fast)) + expandVertically(tween(Motion.Normal)),
            exit = fadeOut(tween(Motion.Fast)) + shrinkVertically(tween(Motion.Normal)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Grid.x12)) {
                RenderCard("link", scope)
                RenderCard("pass", scope)
            }
        }

        // 场景切换器往下挪：它是"换一批卡片"的开关，不该压在两个主区上面抢第一屏
        SceneSwitcher(app)
        // 过境卡由上面那条兜底栏给，卡片流里不能再出现第二次：
        // 首页同屏两张讲同一件事的卡，就是"信息太乱"最实在的那一种
        CardStream(app.orderedCards(scene).filterNot { it == "pass" }, scope)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (editing) "完成" else "编辑卡片流",
                style = Type.Caption,
                color = Ink.Sat,
                modifier = Modifier.clickable { editing = !editing },
            )
            Text("SOS 不参与排序", style = Type.Caption, color = Ink.TextLow)
        }
        if (editing) CardManager(app, app.managedCards(scene))
    }
}

/** 主区一：圆屏。**这块屏能不能用，第一屏就要能操作完，不许跳到「我的」去开开关** */
@Composable
private fun DockHero(app: AppRuntime, st: DemoRuntime.Status) {
    val flags by app.flags.collectAsState()
    val screens = app.devices.collectAsState().value.filter { it.kind == DeviceKind.DisplayScreen }
    val allowed = app.allowedScreens()
    var draft by rememberSaveable { mutableStateOf("") }
    val online = st.clients > 0
    val tone = if (online) Tone.Ok else if (flags.acceptScreens) Tone.Wait else Tone.Neutral

    // 这张卡不额外挂图标：HeroStat 自己带一个 48dp 的，同左边界再叠一个是重复
    InkCard(tone = tone) {
        CardTitle(
            "圆屏",
            trailing = when {
                st.serverError != null -> "接入点起不来"
                st.running -> "服务运行中"
                else -> "服务未启动"
            },
            icon = null,
        )
        HeroStat(
            icon = if (online) MIcons.dock else MIcons.pairing,
            value = "${st.clients}",
            unit = "台在线",
            caption = if (online) "已经连上，屏上跟着手机这一帧走"
            else "还没连上。先把下面那个接入开关放开，再把它那串 screen_id 加进白名单",
            tone = tone,
        )
        ValueRow(
            "接入点",
            st.serverError?.let { "起不来：$it" } ?: st.localIp?.let { "ws://$it:${ScreenLinkServer.PORT}" },
            mono = true,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.padding(end = Grid.x12)) {
                Text("允许展示端接入", style = Type.Body)
                Text("默认关。白名单之外的一律拒", style = Type.Caption, color = Ink.TextLow)
            }
            Switch(checked = flags.acceptScreens, onCheckedChange = { app.setFlag(flags.copy(acceptScreens = it)) })
        }
        if (allowed.isNotEmpty()) {
            SectionLabel("白名单（点一下移出，并立刻断开）")
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Grid.x8),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("screen_id", style = Type.Caption) },
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
                colors = ButtonDefaults.buttonColors(containerColor = Ink.Sat, contentColor = Color.White),
            ) { Text("加入") }
        }
        Text(
            "圆屏自己是客户端、手机是服务端，所以配对就是这三步：放开接入 → 加白名单 → 圆屏填这个地址。" +
                "现在还没有\"自动发现\"：扫描结果的形状得等真固件上报了 hello 才能定，先编一个协议到货一定对不上。",
            style = Type.Caption,
            color = Ink.TextLow,
        )
    }
}

/** 主区二：主理人对话。**没配模型端点时它只按本机状态作答，并把这一条写在脸上** */
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

    InkCard(icon = MIcons.agent, tone = if (endpoint.configured) Tone.Sat else Tone.Wait) {
        CardTitle(
            "主理人",
            trailing = if (endpoint.configured) "远端模型 ${endpoint.model}" else "本机规则 · 未接模型",
        )
        // 对话的宿主在手机，圆屏是它的耳朵和嘴。这一行说死"两边同一份记录"这件事：
        // 屏上说的话会出现在这里，这里答的话会上屏 —— 而不是两块屏各存一份再对齐
        Text(
            if (app.screenDialog) "圆屏也能问：屏上说的话会出现在这里，这里答的话会上屏"
            else "圆屏还不能对话（固件没申报契约 v2）。这里说的每一条，屏上看不到",
            style = Type.Caption,
            color = if (app.screenDialog) Ink.Ok else Ink.TextLow,
        )
        chat.takeLast(4).forEach { line -> Bubble(line) }

        AnimatedVisibility(
            visible = !endpoint.configured,
            enter = fadeIn(tween(Motion.Fast)) + expandVertically(tween(Motion.Normal)),
            exit = fadeOut(tween(Motion.Fast)) + shrinkVertically(tween(Motion.Normal)),
        ) {
            Text(
                "现在每一句回答都来自手机自己的状态：数字是真的，但说话的是规则不是模型。" +
                    "要接真模型，把 OpenAI 兼容端点填进来。",
                style = Type.Caption,
                color = Ink.Wait,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Grid.x8)) {
            listOf("链路", "过境", "额度", "圆屏配对", "车在哪").forEach { q ->
                Box(
                    modifier = Modifier
                        .background(Ink.Track, RoundedCornerShape(Grid.x8))
                        .clickable { app.askAi(q) }
                        .padding(horizontal = Grid.x12, vertical = Grid.x8),
                ) { Text(q, style = Type.Caption, color = Ink.TextMid) }
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
            // 语音这条链路在圆屏那侧（板载双麦 + ES7210），手机只做入口：按下去必须说清还没接
            IconButton(onClick = { app.runtime.log("语音输入还没接：等圆屏固件那条链路（附录 A.3）") }) {
                Icon(MIcons.mic, contentDescription = "语音（未接）", tint = Ink.TextLow)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "权限档：${granted.label}。要出网或放开不可逆动作，去「我的 → 主理人与模型」调",
                style = Type.Caption,
                color = Ink.TextLow,
            )
            Text(
                if (configuring) "收起" else "接模型",
                style = Type.Caption,
                color = Ink.Sat,
                modifier = Modifier.clickable { configuring = !configuring },
            )
        }
        AnimatedVisibility(visible = configuring) {
            Column(verticalArrangement = Arrangement.spacedBy(Grid.x8)) {
                FieldRow("Base URL", url) { url = it }
                FieldRow("API Key", key) { key = it }
                FieldRow("模型名", model) { model = it }
                Text(
                    "只认 OpenAI 兼容的 /chat/completions。三项填齐才算配上；填一半我们会当作没配，" +
                        "宁可乐观地退回本机规则，也不让首屏转 12 秒圈。",
                    style = Type.Caption,
                    color = Ink.TextLow,
                )
                Button(
                    onClick = { app.setAiEndpoint(url, key, model) },
                    colors = ButtonDefaults.buttonColors(containerColor = Ink.Sat, contentColor = Color.White),
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
private fun Bubble(line: Turn) {
    val mine = line.from == TurnFrom.Me
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                // 气泡底色必须比卡片底更亮一档：之前那条用 Ink.Card 的九成不透明度，
                // 画在卡片自己上就等于没画，用户读不出"这两行是它说的、那两行是我说的"
                .background(if (mine) Ink.Sat.copy(alpha = 0.16f) else Ink.Track, RoundedCornerShape(Grid.x12))
                .padding(horizontal = Grid.x12, vertical = Grid.x8),
            verticalArrangement = Arrangement.spacedBy(Grid.x4),
        ) {
            // 圆屏起的那一轮，问和答都要标。车主在手机上看到这条时想知道的是
            // "刚才是不是有人站在车那边跟屏说话"，这个只标一次读不出来
            if (line.via == DialogOrigin.screen) {
                Row(horizontalArrangement = Arrangement.spacedBy(Grid.x4), verticalAlignment = Alignment.CenterVertically) {
                    Icon(MIcons.dock, contentDescription = null, tint = Ink.Wait, modifier = Modifier.size(13.dp))
                    Text("圆屏", style = Type.Caption, color = Ink.Wait)
                }
            }
            Text(line.text, style = Type.Body)
            if (!mine) {
                Row(horizontalArrangement = Arrangement.spacedBy(Grid.x8), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when {
                            !line.ours -> MIcons.voice
                            line.source.live -> MIcons.model
                            line.proactive -> MIcons.agent
                            else -> MIcons.ledger
                        },
                        contentDescription = null,
                        tint = Ink.TextLow,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(sourceLabel(line), style = Type.Caption, color = if (line.ours) Ink.TextLow else Ink.Wait)
                    if (line.skill != null) Text("· ${line.skill}", style = Type.Caption, color = Ink.TextLow)
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
 * 卫星兜底条。**降的是位置，不是能力**：相位、过顶倒计时、SOS 长按三样一个不少。
 * 完整的链路卡与过境卡默认收着，点那行字才展开 —— 展开的是同一张 [RenderCard]，
 * 这里不重复实现，否则手机和圆屏会各自解释一遍同一个状态（§5.2 最后一条）。
 */
@Composable
private fun SatelliteStrip(app: AppRuntime, st: DemoRuntime.Status, open: Boolean, onToggle: () -> Unit) {
    val sat = st.snapshot?.sat
    val phase = sat?.phase?.name ?: "—"
    val left = sat?.nextPassInS
    val moduleOff = sat != null && !sat.moduleOk
    val tone = when {
        moduleOff -> Tone.Alert
        left == null -> Tone.Wait
        left <= 0 -> Tone.Ok
        else -> Tone.Sat
    }
    val arm by app.arm.collectAsState()
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
                        moduleOff -> "GT1005 不在线，心跳先攒在队列里"
                        left == null -> "还没有可用的过境时间"
                        left <= 0 -> "窗口开着，现在就能发"
                        else -> "距下次过顶 ${left}s"
                    },
                    style = Type.Body,
                )
                Text(
                    if (open) "收起完整链路卡" else "展开：链路卡与过境卡",
                    style = Type.Caption,
                    color = Ink.Sat,
                    modifier = Modifier.clickable { onToggle() },
                )
            }
            // 这一格里不再放第二个图标（卡片左上角已经有卫星了），放的是"还要等多久"那个数：
            // 兜底条要能在扫一眼时回答一个问题，而这个问题是时间
            AnimatedContent(targetState = big, transitionSpec = { Motion.slideForward(true) }, label = "strip") { t ->
                Text(t, style = Type.Hero.copy(color = tone.color))
            }
        }
        HoldToFire(
            onDown = { app.pressDownSos() },
            onUp = { app.releaseSos() },
            label = if (arm.active) "松手即取消（${(arm.leftMs / 1000).toInt() + 1}）" else "长按 3 秒发 SOS",
            compact = true,
        )
        val cancel by app.cancelLine.collectAsState()
        cancel?.let { Text(it, style = Type.Caption, color = Ink.Wait) }
    }
}
