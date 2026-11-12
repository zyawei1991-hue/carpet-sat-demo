package com.carpet.satdemo.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carpet.satdemo.app.AppRuntime
import com.carpet.satdemo.app.ScreenVoice
import com.carpet.satdemo.app.VoiceTone
import com.carpet.satdemo.core.ai.ReplySource
import com.carpet.satdemo.core.ai.Turn
import com.carpet.satdemo.core.ai.TurnFrom
import com.carpet.satdemo.core.devices.DeviceLink
import com.carpet.satdemo.core.protocol.DialogOrigin
import com.carpet.satdemo.core.protocol.ScreenPage
import com.carpet.satdemo.core.runtime.DemoRuntime
import com.carpet.satdemo.app.ui.viewmodel.AssistantUiState
import com.carpet.satdemo.app.ui.viewmodel.DockUiState
import com.carpet.satdemo.app.ui.viewmodel.AskBarUiState
import com.carpet.satdemo.app.ui.viewmodel.HomeUiState
import com.carpet.satdemo.app.ui.viewmodel.HomeViewModel
import com.carpet.satdemo.app.ui.viewmodel.homeVm

/**
 * 首页（V1.10 按算出来的层级重排）。
 *
 * 上一版这里有两个 34sp 的大数块（圆屏、对话）和六张同一档的卡 —— **两个重心等于没有重心**。
 * 现在一屏仍只有一张 [Level.Raised]（对话）。圆屏那张是平卡，但它里面是一块 [ScreenMirror]：
 * **强调档只有一张，画面不必让给文字** —— 那枚圆画的就是这一秒推给屏的那一帧，
 * 卫星仍是那条兜底栏，工程长说明一律收进 [CollapsableNote]，不再摊在正文里。
 *
 * 三件事没动，因为它们不是排版问题：**SOS 长按入口不许藏**、**每条回答逐条标来源**、
 * **卫星那两张卡展开后仍是同一张 [RenderCard]**（手机和圆屏不许各解释一遍同一个状态）。
 */
@Composable
fun HomeShell(app: AppRuntime, status: DemoRuntime.Status, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val scope = CardScope(app, status)
    val vm = homeVm(app)
    val state by vm.uiState.collectAsState()
    var editing by rememberSaveable { mutableStateOf(false) }
    var satOpen by rememberSaveable { mutableStateOf(false) }
    var moreCards by rememberSaveable { mutableStateOf(false) }

    // 首帧 status==Loading：先不画。这一帧两张卡都没有内容，画出来就是一闪白
    val ready = state as? HomeUiState.Ready ?: return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Grid.Page)
            .padding(top = Grid.x12, bottom = Grid.x24),
        verticalArrangement = Arrangement.spacedBy(Grid.Group),
    ) {
        HomeRail(app, status, ready.guardOn)
        AssistantHero(app, vm, ready.assistant)
        // ② AI 主理人之下是**当下上下文**：圆屏自己报上来的那一页，对应的卡提到卡片流最前面。
        // 屏在车辆页 = 车里有人正在看它，那一张就是现在最要紧的事
        val pinned = contextKey(status.screenPage)
        if (pinned != null) ContextPin(scope, pinned)
        DockStatusCard(status, ready.dock, onManage = { vm.openDeviceCenter() })
        SatelliteStrip(app, status, open = satOpen, onToggle = { satOpen = !satOpen }, cancelLine = ready.cancelLine)
        // 那两张卡默认收着。兜底条上那句"展开完整链路卡"必须真能点开，否则它是一句谎
        AnimatedVisibility(visible = satOpen, enter = Motion.expandIn(reduce), exit = Motion.expandOut(reduce)) {
            Column(verticalArrangement = Arrangement.spacedBy(Grid.Card)) {
                RenderCard("link", scope)
                RenderCard("pass", scope)
            }
        }

        SceneSwitcher(app)
        // 过境卡由上面那条兜底栏给，卡片流里不能再出现第二次。
        // 一上来摊十张同权重的卡就是"乱"的本体，所以默认只出前两张
        CardStream(if (moreCards) ready.stream else ready.stream.take(2), scope)
        // 展开与编辑合成一行。之前是上下两条同样式的蓝字（"展开其余 5 张卡"、"编辑卡片流"），
        // 加上场景切换器那句"更多场景（2）"，首屏底部一共三条 builder 口径的链接 ——
        // 那是我们自己排卡片用的工具，车主不需要每次开 App 都看见
        if (ready.stream.size > 2) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { moreCards = !moreCards }
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(horizontal = Grid.x8, vertical = Grid.x4),
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
                    if (moreCards) "收起" else "展开另外 ${ready.stream.size - 2} 项",
                    style = Type.Caption,
                    color = Ink.Sat,
                )
                Box(modifier = Modifier.weight(1f))
                // **排列只在卡片流展开后才给**。上一版这一行左"再看 4 张卡"右"排列这些卡"，
                // 两个动作性质不同（一个是看，一个是改）却同权重同行，而收起态里那四张卡根本不在屏上 ——
                // 排一个看不见的列表，本身就是个奇怪的产品
                if (moreCards) {
                    Box(
                        modifier = Modifier
                            .clickable { editing = !editing }
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (editing) "完成" else "调整顺序",
                            style = Type.Caption,
                            color = Ink.TextLow,
                        )
                    }
                }
            }
        }
        if (editing) CardManager(app, ready.managed, ready.hidden)
    }
}

/**
 * 顶部状态栏：一眼回答"这两台今天能不能用"。
 * **它不是装饰** —— 之前这三个数散在三张卡里，用户要滚到第二、三屏才知道屏没连上。
 *
 * **没数据一律 [Tone.Neutral]（空心圈），不许再用琥珀凑一个"等待"。**
 * 上一版这三格是 未连 / -- / 没跑 三个实心黄点，整条栏看着像三处故障；
 * 而"还没连上"其实是个中性事实，只有"正在等一个已知会来的东西"才配得上 Wait。
 */
@Composable
private fun HomeRail(app: AppRuntime, st: DemoRuntime.Status, guardOn: Boolean) {
    val sat = st.snapshot?.sat
    val left = sat?.nextPassInS
    val dockTone = when {
        st.serverError != null -> Tone.Alert
        st.clients > 0 -> Tone.Ok
        else -> Tone.Neutral
    }
    val satTone = when {
        sat != null && !sat.moduleOk -> Tone.Alert
        left == null -> Tone.Neutral
        left <= 0 -> Tone.Ok
        else -> Tone.Sat
    }
    StatusRail(
        listOf(
            RailItem("圆屏", if (st.clients > 0) "${st.clients} 台" else "未连", dockTone),
            RailItem(
                "卫星",
                when {
                    sat?.moduleOk == false -> "离线"
                    left == null -> "未取"
                    left <= 0 -> "开窗"
                    // **倒计时不在这里出现**。上一版同一个 `561s` 一屏写三遍：这条栏一次、
                    // 兜底卡正文一次、兜底卡右下角那个 24sp 大数再一次。
                    // 这条栏的本分是"这台今天能不能用"，那是状态不是读数 —— 数字留给兜底卡那一处
                    else -> null
                },
                satTone,
            ),
            RailItem("守护", if (guardOn) "在跑" else "未开", if (guardOn) Tone.Ok else Tone.Neutral),
        ),
    )
    // SOS 不在这条里。它原来钉在这行的右端，理由是"不滚动就能按到"—— 判断对，位置错：
    // 384dp（Mate60）上三枚芯片 + 一颗钮装不下，被挤的只能是那颗钮，全页最要紧的入口
    // 被拆成两行竖排。现在它在底部导航栏正中（[SosDial]），四个页面都在，也不挡任何内容
}

/**
 * 首页唯一的重心：AI 助手对话。**只有这一张卡用 [Level.Raised]**（更亮的底 + 一根描边）。
 *
 * 它排在圆屏配对上面，因为"我问它一句它答得对不对"是每天发生频率最高的动作，配对是一次性的事。
 *
 * **这一版把它从"表单"改回"对话窗"**：原来它自上而下挂了六段 ——
 * 标题、"圆屏还没接通"、"还没有对话"、快捷词、输入框、"权限档：只读本机 / 接模型"。
 * 前两句是负状态说明，最后一句是我们内部的档位与网关口径，三者都在替用户说话，
 * 却没有一句是车主想知道的。现在卡里只剩三样：**问、答、输入**。
 * 空态交给输入框的占位文，档位和端点收进右上角那枚齿轮。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssistantHero(app: AppRuntime, vm: HomeViewModel, s: AssistantUiState) {
    val reduce = LocalReduceMotion.current
    var draft by rememberSaveable { mutableStateOf("") }
    var configuring by rememberSaveable { mutableStateOf(false) }
    var voiceAdvanced by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    val listenState by vm.listenState.collectAsState()

    // 这张卡不给图标：它的标题行右边有一枚 48dp 的齿轮，标题被那行撑到垂直居中，
    // 左边再挂一枚 20dp 的小图标就只对齐到行的顶边 —— 截图里"AI 助手"比图标低半行，就是这半行的错位。
    // 首屏这张本来就是主角，不需要图标再告诉用户它是谁
    InkCard(level = Level.Raised, tone = if (s.endpoint.configured) Tone.Sat else Tone.Neutral) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Grid.x8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("AI 助手", style = Type.Title, modifier = Modifier.weight(1f))
            // 未接模型时不再把"本机规则 · 未接模型"打在标题上：那是工程状态，不是给车主看的信息，
            // 而且它挂在首屏最显眼那一行，等于每次打开 App 先看见一句"这个功能还没做完"
            if (s.endpoint.configured) Text(s.endpoint.model, style = Type.Caption, color = Ink.TextLow)
            IconButton(onClick = { configuring = !configuring }) {
                Icon(MIcons.settings, contentDescription = "AI 助手设置", tint = Ink.TextLow, modifier = Modifier.size(18.dp))
            }
        }

        // 模型没答上来的**具体原因**要摆出来，而不是让车主对着"未接模型"猜。
        // 地址写错（/responses、多一层 /v1）、密钥失效、网络到不了 —— 三种下一步完全不同
        s.error?.let { err ->
            Text(
                "AI 这次没答上来：$err",
                style = Type.Caption,
                color = Ink.Alert,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (s.probing) Text("正在问 AI…", style = Type.Caption, color = Ink.Wait)

        // 只显示最近 3 条，但**这几行不能是死字**：想翻更早的就点一下。
        // 上一版这句只是陈述"共 N 条"，用户既看不到其余的，也没有地方可点
        val shown = if (historyOpen) s.chat else s.chat.takeLast(3)
        shown.forEach { line -> Bubble(app, line, s.endpoint.configured) }
        // 发出去之后屏上必须立刻有东西：这条回答还在路上。
        // 没有它，用户只能再点一次发送 —— 而这里明确不排队，多点的那次会被挡回来，两边都以为自己错了
        AnimatedVisibility(visible = s.thinking, enter = Motion.expandIn(reduce), exit = Motion.expandOut(reduce)) {
            ThinkingBubble()
        }
        if (s.chat.size > 3) {
            GhostAction(if (historyOpen) "只看最近几句" else "看更早的 ${s.chat.size - 3} 句") {
                historyOpen = !historyOpen
            }
        }
        // 只有真接通了才说这句。它是一条能力，不是一句故障报告
        if (s.screenDialog && s.chat.isEmpty()) {
            Text("圆屏上说的话也会出现在这里", style = Type.Caption, color = Ink.Ok)
        }

        InkField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = "问一句",
            onSubmit = { if (draft.isNotBlank()) { vm.ask(draft); draft = "" } },
            trailing = {
                IconButton(onClick = { if (draft.isNotBlank()) { vm.ask(draft); draft = "" } }) {
                    Icon(MIcons.send, contentDescription = "发送", tint = Ink.Sat)
                }
                VoiceMicButton(
                    app = app,
                    listening = listenState.first,
                    transcribing = listenState.second,
                )
            },
        )

        // **折行，不横滚**：字号 1.3 时这条带在 384dp 上装不下五枚，横滚的话最后一枚只剩一个"车"字，
        // 而快捷问句是这一屏最该被看见的东西 —— 看不见就等于没有。和场景切换器同一个处理
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Grid.x8),
            verticalArrangement = Arrangement.spacedBy(Grid.x8),
        ) {
            // 每一句都得留着一个技能关键词（信号 / 过境 / 额度 / 圆屏 / 车在哪），
            // 否则点了这枚问句反而绕过技能、去问模型
            listOf("有信号吗", "下次过境是什么时候", "额度还剩多少", "圆屏怎么配对", "车在哪").forEach { q ->
                // 走同一枚 [Chip]：这里原来是手写的一枚 33dp 高的方块，而它恰恰是首页最该被点中的东西。
                // 复用原语顺带解决了两件事 —— 触摸区到 48dp，按压态和场景切换器那几枚保持一致
                Chip(q, selected = false) { vm.ask(q) }
            }
        }

        AnimatedVisibility(visible = configuring, enter = Motion.expandIn(reduce), exit = Motion.expandOut(reduce)) {
            Column(verticalArrangement = Arrangement.spacedBy(Grid.x8)) {
                // 端点在出包时就定了（V1.18），这里不再有地址/密钥/模型名三格：
                // 产品界面不该出现要用户抄一长串字符的东西。自检在工程模式里，给现场排障用
                ValueRow("它能做什么", s.granted.label)
                // 车主只关心两件事：谁在念、在哪念。三档声音差别很大，但不需要名词解释谁是谁
                ValueRow(
                    "答句声音",
                    when (s.source) {
                        "azure" -> "晓伊 · 手机和圆屏同一个声音"
                        "edge" -> "晓伊 · 手机和圆屏同一个声音"
                        "baidu" -> "备用网络声音（晓伊没连上时用）"
                        else -> s.engine ?: "手机自带语音"
                    },
                )
                s.voiceIssue?.let { issue ->
                    Text(issue, style = Type.Caption, color = Ink.Wait, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                if (s.screenDialog && s.screenProtocol in 1..2) {
                    Text(
                        "这块屏还不会自己出声，声音先放在手机上；屏升级之后会自动跟过去",
                        style = Type.Caption,
                        color = Ink.TextLow,
                    )
                }
                // 同一个引擎，音高抬一点就从"播报"变成"在跟你说话"。这三档是现场能立刻听出差别的
                Text("语气", style = Type.Caption, color = Ink.TextLow)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Grid.x8),
                    verticalArrangement = Arrangement.spacedBy(Grid.x8),
                ) {
                    VoiceTone.all.forEach { t ->
                        Chip(t.label, selected = t.id == s.tone.id) { vm.setTone(t) }
                    }
                }
                // 手机起头的那轮在哪念由这里定；屏上起头的轮次只有屏念，不受这项影响
                Text("由哪个设备发声（手机上起头的话）", style = Type.Caption, color = Ink.TextLow)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Grid.x8),
                    verticalArrangement = Arrangement.spacedBy(Grid.x8),
                ) {
                    ScreenVoice.ScreenDevice.entries.forEach { d ->
                        Chip(d.label, selected = d == s.answerDevice) { vm.setAnswerDevice(d) }
                    }
                }
                GhostAction(if (s.previewing) "正在念…" else "试听一句") {
                    if (!s.previewing) vm.previewVoice()
                }
                // 换引擎、换人声是现场调理的活，不是日常动作：点开才出现，不点就只是一行字
                GhostAction(if (voiceAdvanced) "收起换声音" else "换一个声音") { voiceAdvanced = !voiceAdvanced }
                AnimatedVisibility(
                    visible = voiceAdvanced,
                    enter = Motion.expandIn(reduce),
                    exit = Motion.expandOut(reduce),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(Grid.x8)) {
                        if (s.engines.size > 1) {
                            Text("手机里的语音引擎", style = Type.Caption, color = Ink.TextLow)
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Grid.x8),
                                verticalArrangement = Arrangement.spacedBy(Grid.x8),
                            ) {
                                s.engines.forEach { e ->
                                    Chip(e.label, selected = e.id == s.engineChoice) { vm.setEngine(e.id) }
                                }
                                Chip("系统默认", selected = s.engineChoice == null) { vm.setEngine(null) }
                            }
                        } else {
                            Text(
                                "手机上只找到 ${s.engines.firstOrNull()?.label ?: "一个语音"}；想换个声音可以装华为或讯飞的中文语音",
                                style = Type.Caption,
                                color = Ink.TextLow,
                            )
                        }
                        if (s.faces.size > 1) {
                            Text("这个引擎里的其他声音", style = Type.Caption, color = Ink.TextLow)
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Grid.x8),
                                verticalArrangement = Arrangement.spacedBy(Grid.x8),
                            ) {
                                s.faces.take(8).forEach { f ->
                                    Chip(f.label, selected = f.name == s.faceChoice) { vm.setFace(f.name) }
                                }
                                Chip("默认", selected = s.faceChoice == null) { vm.setFace(null) }
                            }
                        }
                    }
                }
                GhostAction("看它现在能做什么") { vm.openSkills() }
            }
        }
    }
}

/**
 * 车辆页、家人页顶上那一根**就地发问条**：一个输入框、三枚这一页自己的快捷问句、这一页最近两条对话。
 *
 * 为什么不复用首页那张 [AssistantHero]：那张是首屏主角（三行对话 + 五枚快捷问 + 一枚齿轮），
 * 四个 Tab 里各摆一张一样的大卡就是每页都在抢主角 —— 上一轮说的"乱"有一半在这儿。
 * 这一根只做"能问"这一件事，并且**只列这一页的那串对话**：
 * 记录全产品只有一份（[com.carpet.satdemo.core.ai.ConversationLog]），这里按 [scene] 取的是视图。
 *
 * [page] 是屏自己报上来的那一页（`page_report`）。换算得上就说一句，换算不上什么都不说 ——
 * 这句是"这条线此刻通了"的凭据，不是一段功能介绍。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SceneAsk(
    app: AppRuntime,
    s: AskBarUiState,
    screenOnThisPage: Boolean,
    placeholder: String,
    prompts: List<String>,
    onAsk: (String) -> Unit,
) {
    val reduce = LocalReduceMotion.current
    var draft by rememberSaveable { mutableStateOf("") }
    val ask = { q: String -> if (q.isNotBlank()) { onAsk(q); draft = "" } }

    InkCard(level = Level.Group) {
        // **和首页那张同一个上下序**：先看见这一页已经说过的话，再看见输入框，最后一排快捷问句。
        // 之前把输入框摆在最上面，四个 Tab 之间"对话在上、输入在下"这一条就断了 —— 那是"乱"的一种
        s.turns.forEach { Bubble(app, it, s.modelConfigured) }
        AnimatedVisibility(visible = s.waiting, enter = Motion.expandIn(reduce), exit = Motion.expandOut(reduce)) {
            ThinkingBubble()
        }
        if (screenOnThisPage) {
            Text("圆屏此刻就在这一页 · 它说的话落在这串里", style = Type.Caption, color = Ink.Ok)
        }
        InkField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = placeholder,
            onSubmit = { ask(draft) },
            trailing = {
                IconButton(onClick = { ask(draft) }) {
                    Icon(MIcons.send, contentDescription = "发送", tint = Ink.Sat)
                }
            },
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Grid.x8),
            verticalArrangement = Arrangement.spacedBy(Grid.x8),
        ) {
            prompts.forEach { q -> Chip(q, selected = false) { ask(q) } }
        }
    }
}

/**
 * 输入框。**用实心底，不用描边框**：Material 的 OutlinedTextField 会在卡里再画一个框，
 * 而卡片本身已经是一个框 —— 框套框是"乱"的直接来源之一。
 * 占位文就是这一屏的操作说明，所以不再另写一行"还没有对话，问一句"。
 */
@Composable
internal fun InkField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSubmit: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    // 发送（和首页那枚麦克风）画在实心底**里面**。以前它们站在盒子外面的卡片底色上，
    // 一个控件读成两块 —— Mate 60 实拍里"箭头孤零零飘在右边"就是这个
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Ink.Sunk, RoundedCornerShape(Round.Chip)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = Type.Body.copy(color = Ink.TextHigh),
            cursorBrush = SolidColor(Ink.Sat),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSubmit() }),
            decorationBox = { inner ->
                Box(modifier = Modifier.padding(horizontal = Grid.x12, vertical = Grid.x8)) {
                    // 占位文和敲进去的字同一档字号。原来是 Caption 配 Body，
                    // 第一个字敲下去整行会跳一下大小
                    if (value.isEmpty()) Text(placeholder, style = Type.Body, color = Ink.TextLow)
                    inner()
                }
            },
        )
        trailing()
    }
}

/**
 * 输入框那枚麦克风：**按住说话，松手出题**。
 *
 * 链路全部在 [com.carpet.satdemo.app.AppRuntime] 里（录音 → 硅基流动转写 → askAi），
 * 这里只管三件事：按下/松开的手势、按住时的红色状态、没权限时替用户去要一次。
 *
 * 没配 key（`siliconflow.asrKey` 空着打的包）或转写中，按钮退回灰档 —— 它得和
 * "语音（未接）"那版长得一样，只是现在按下去有真话说了。
 */
@Composable
private fun VoiceMicButton(app: AppRuntime, listening: Boolean, transcribing: Boolean) {
    val ctx = LocalContext.current
    // 权限弹窗关掉之后（不管给没给）重算一次"能不能直接录"。
    var permTick by rememberSaveable { mutableStateOf(0) }
    val hasMic = remember(permTick) { app.micPermissionGranted(ctx) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permTick++
        if (!it) app.say("没给录音权限，语音输入用不了")
    }

    val enabled = app.voiceInputConfigured && !transcribing
    val tint = when {
        transcribing -> Ink.Wait
        listening -> Ink.Alert
        else -> Ink.TextMid
    }

    val holdModifier = Modifier.pointerInput(enabled, hasMic) {
        if (!enabled) return@pointerInput
        awaitPointerEventScope {
            while (true) {
                // 按下即录；没权限时这一下改成去要权限，不要静默
                awaitFirstDown(requireUnconsumed = false)
                if (!hasMic) {
                    launcher.launch(android.Manifest.permission.RECORD_AUDIO)
                } else {
                    val started = app.startVoiceInput()
                    // 等抬起（或手势取消），再把这段送出去
                    var cancelled = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val change = ev.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        if (ev.changes.any { it.isConsumed }) { cancelled = true; break }
                    }
                    if (started) {
                        if (cancelled) app.cancelVoiceInput() else app.stopVoiceInput()
                    }
                }
                // 抬起后等一个"全松手"的稳定点，防止连击
                waitForUpOrCancellation()
            }
        }
    }

    Box(
        modifier = if (listening) {
            Modifier.background(Ink.Alert.copy(alpha = Ink.TINT), RoundedCornerShape(Round.Chip))
        } else {
            Modifier
        },
    ) {
        IconButton(onClick = { if (!enabled) app.say("语音输入还没接：等 key 配上再按") }, modifier = holdModifier) {
            Icon(
                MIcons.mic,
                contentDescription = when {
                    transcribing -> "转写中"
                    listening -> "松开结束"
                    else -> "按住说话"
                },
                tint = tint,
            )
        }
    }
}

/**
 * 「这条还在路上」的那口气。
 *
 * 形状直接用回答气泡的形状，里面放两条灰条：位置、宽度、底色都和真回答一致，
 * 回答落地时只是把条换成字，**整块不往下跳**。
 * 不加闪烁、不加转圈的点 —— 它要说的是"等着，有结果"，不是"程序在动"。
 */
@Composable
internal fun ThinkingBubble(modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(Ink.Track, RoundedCornerShape(Round.Card))
                .padding(horizontal = Grid.x12, vertical = Grid.Inner),
            verticalArrangement = Arrangement.spacedBy(Grid.x4),
        ) {
            SkeletonBar(width = 132.dp, color = Ink.TextLow)
            SkeletonBar(width = 76.dp, color = Ink.TextLow)
        }
    }
}

/** 一条对话。左右对齐就够，不做头像、不做气泡尾巴 —— 那两个都会把一屏能看的条数压掉一条 */
@Composable
internal fun Bubble(app: AppRuntime, line: Turn, modelConfigured: Boolean) {
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
                    Text(
                        sourceLabel(line, modelConfigured),
                        style = Type.Caption,
                        color = if (line.ours) Ink.TextLow else Ink.Wait,
                    )
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
 *
 * [modelConfigured] 是端点当前是否填齐：`LocalFallback` 两种情况都会出现 ——
 * 没配（真没接）和配了但这次没答上来（密钥/地址/网络）。上一版一律写成「未接模型」，
 * 用户明明填过端点、每次都收到这一句，唯一能得出的结论是"App 没认我填的东西"。
 */
private fun sourceLabel(line: Turn, modelConfigured: Boolean): String = when {
    // 屏或小智自己答的话，没有经过手机这一关：这句话必须自己站一排，
    // 和 AI 回答混在一起说，就等于替它担保了
    line.source == ReplySource.MuseCloud -> "屏上直接答的 · 没经手机把关"
    line.source == ReplySource.XiaozhiCloud -> "小智云直接答的 · 没经手机把关"
    line.proactive -> "我主动提醒的"
    line.source == ReplySource.Model -> "AI 回答"
    line.source == ReplySource.Denied -> "这一步没被允许"
    // 规则答的话一定要和模型答的分开说，否则观众会把模板当成大模型
    line.source == ReplySource.LocalFallback ->
        if (modelConfigured) "手机按规则答的 · 模型这次没接上" else "手机按规则答的"
    else -> "手机自己查的"
}

/**
 * 屏报上来的那一页 → 该置顶的那张卡。**这是三层架构里"当下上下文"那条的落点**：
 * 圆屏在车辆页，说明车那边此刻有人在看它，车辆域就上浮到首页卡片流最前面；
 * 屏在亲子页同理。屏没报页（null / face / clock 这些不属于任何一个域）就什么都不置顶 ——
 * 硬猜一个"大概在车里"比不猜更糟。
 */
private fun contextKey(page: ScreenPage?): String? = when (page) {
    ScreenPage.vehicle -> "vehicle.status"
    ScreenPage.kids -> "fence"
    else -> null
}

/**
 * 上下文置顶卡：把屏此刻正在看的那一张卡提前画出来。**还是同一张 [RenderCard]**，
 * 不另做实现 —— 置顶是排版的权力，不是另写一遍状态的理由（§5.2 最后一条）。
 */
@Composable
private fun ContextPin(scope: CardScope, key: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Grid.x4)) {
        Text(
            "圆屏此刻在看" + Cards.registry.title(key),
            style = Type.Caption,
            color = Ink.TextLow,
        )
        RenderCard(key, scope)
    }
}

/**
 * 圆屏在首页只剩**状态**，配对表单整个搬到「我的 → 设备与底座」。
 *
 * 三层架构里终端层是"呈现发生在哪"，不是首页的一块常驻表单。上一版这块摊开了镜像圆、
 * 白名单、编号输入框、折叠说明 —— 一次性配置动作占了首屏四分之一。
 * 现在首页只回答"屏连着没、此刻显示什么"，要动配置的去设备中心。
 * **但镜像是真镜像**：连着的时候它仍是手机推给屏的那一帧，只是不再配一整卡的操作。
 */
@Composable
private fun DockStatusCard(st: DemoRuntime.Status, s: DockUiState, onManage: () -> Unit) {
    val online = st.clients > 0
    // 圆屏从无到有连上的那一下给触觉确认（UX #27）：演示时人的注意力在路上，
    // "连上了"这件事靠震一下感知，不必回头盯屏幕
    val hadOnline = remember { mutableStateOf(false) }
    val confirmHaptic = rememberConfirmHaptic()
    LaunchedEffect(online) {
        if (online && !hadOnline.value) confirmHaptic()
        hadOnline.value = online
    }
    InkCard(
        icon = MIcons.dock,
        tone = when {
            st.serverError != null -> Tone.Alert
            online -> Tone.Ok
            else -> Tone.Neutral
        },
    ) {
        CardTitle("圆屏", trailing = if (online) "${st.clients} 台在线" else "未连接")
        if (online) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ScreenMirror(
                    snap = st.snapshot,
                    online = st.serverError == null,
                    page = st.screenPage,
                    line = s.screenLine,
                )
            }
            MirrorCaption(
                snap = st.snapshot,
                online = online,
                serverError = st.serverError,
                page = st.screenPage,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            val onlineNames = s.screens.filter { it.link is DeviceLink.Online }
                .joinToString("、") { it.title }
            if (onlineNames.isNotBlank()) Text(onlineNames, style = Type.Body, color = Ink.TextMid)
        } else {
            // 没连上只说该做的那一步：来过没握上手是编号/开关的事，一条 TCP 都没到过是网络的事。
            // 具体怎么配（热点、路由器、AP 隔离）整段说明归设备中心管，首屏不再背这一段
            Text(
                when {
                    st.serverError != null -> "接入服务起不来：${st.serverError}"
                    st.attempts > 0 -> "圆屏来过 ${st.attempts} 次但没握上手，去设备中心检查编号和开关"
                    else -> "还没有圆屏连进来。配对方法在「我的 → 设备与底座」；连上后下滑换场景、点那张脸就能对话"
                },
                style = Type.Body,
                color = Ink.TextMid,
                modifier = Modifier.announceChange(),
            )
        }
        GhostAction("去设备中心管理") { onManage() }
    }
}

/**
 * 卫星兜底条。**降的是位置，不是能力**：相位、过顶倒计时、SOS 长按三样一个不少。
 * 完整的链路卡与过境卡默认收着，点那行字才展开 —— 展开的是同一张 [RenderCard]，
 * 这里不重复实现，否则手机和圆屏会各自解释一遍同一个状态（§5.2 最后一条）。
 */
@Composable
private fun SatelliteStrip(
    app: AppRuntime,
    st: DemoRuntime.Status,
    open: Boolean,
    onToggle: () -> Unit,
    cancelLine: String?,
) {
    val reduce = LocalReduceMotion.current
    val sat = st.snapshot?.sat
    val phase = sat?.phase?.let { phaseLabel(it) } ?: "—"
    val left = sat?.nextPassInS
    val moduleOff = sat != null && !sat.moduleOk
    val tone = when {
        moduleOff -> Tone.Alert
        left == null -> Tone.Neutral
        left <= 0 -> Tone.Ok
        else -> Tone.Sat
    }
    val big = when {
        moduleOff || sat == null -> null
        left == null -> null
        left <= 0 -> "开窗"
        else -> "${left}s"
    }
    InkCard(icon = MIcons.satellite, tone = tone) {
        CardTitle(
            "卫星兜底",
            trailing = when {
                moduleOff -> "终端离线"
                sat == null -> null
                else -> "相位 $phase"
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Grid.x16),
            // **顶对齐**。居中那一版把右下角那个大数卡在"窗口开着，现在就能发"和
            // "展开卫星详情"两行中间 —— 它到底是这句的尾巴，还是下面那行的注脚，读不出来。
            // 这一格的大数永远是正文那一句的答案（开窗 / 561s），所以它必须和正文同一条带
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Grid.x4)) {
                Text(
                    // **这句里不再抄一遍那个数**。上一版"距下次过顶 561s"和右下角那个 24sp 的
                    // "561s" 同屏各写一次，加上顶部状态栏就是三次 —— 数只留一处，这句退成它的标签
                    when {
                        moduleOff -> "卫星终端没在线，要发的内容先攒在手机这边"
                        left == null -> "还没取到星历。取到之后这里就是过顶倒计时"
                        left <= 0 -> "窗口开着，现在就能发"
                        else -> "距下次过顶"
                    },
                    style = Type.Body,
                    // 相位突变（尤其"终端离线"和"开窗"）是这一卡唯一的新闻，得能被读出来
                    modifier = Modifier.announceChange(),
                )
                Box(
                    modifier = Modifier
                        .clickable { onToggle() }
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (open) "收起卫星详情" else "展开卫星详情",
                        style = Type.Caption,
                        color = Ink.Sat,
                    )
                }
            }
            // 这一格里不再放第二个图标（卡片左上角已经有卫星了），放的是"还要等多久"那个数：
            // 兜底条要能在扫一眼时回答一个问题，而这个问题是时间。
            // 用 Numeric 而不是 Hero —— 1Hz 跳秒，比例字体会抖行。
            // **没数的时候这一格整个不画**：一个大号灰字"未取"挂在卡角上，
            // 看着像控件坏了，而它想说的只是"还不知道"
            if (big != null) {
                AnimatedContent(targetState = big, transitionSpec = { Motion.slideForward(true, reduce) }, label = "strip") { t ->
                    Text(t, style = Type.Numeric.copy(color = tone.color))
                }
            }
        }
        // 这里不放 SOS：那一颗在底部导航栏正中（[SosDial]），同一页两个入口只会让人按错一个。
        // 撤销那条回显留在原地 —— 它讲的是"刚才按的那下有没有生效"，属于这张卡的账
        cancelLine?.let { Text(it, style = Type.Caption, color = Ink.Wait) }
    }
}
