package com.carpet.satdemo.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import com.carpet.satdemo.app.AppRuntime
import com.carpet.satdemo.app.BuildConfig
import com.carpet.satdemo.core.ai.PermissionTier
import com.carpet.satdemo.core.protocol.ScreenPage
import com.carpet.satdemo.core.runtime.DemoRuntime
import com.carpet.satdemo.app.ui.viewmodel.MeAssistantUi
import com.carpet.satdemo.app.ui.viewmodel.MeUiState
import com.carpet.satdemo.app.ui.viewmodel.MeViewModel
import com.carpet.satdemo.app.ui.viewmodel.familyVm
import com.carpet.satdemo.app.ui.viewmodel.meVm
import com.carpet.satdemo.app.ui.viewmodel.vehicleVm

/**
 * Tab2/3/4 三页。它们和 Tab1 用的是同一个注册表、同一个 CardScope，
 * 差别只在"这一页摆哪几串 key"。**没有第二套卡片实现**，
 * 否则同一状态在两个页面上会说出两种话（§5.2 最后一条）。
 */

@Composable
private fun Page(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Grid.x16, vertical = Grid.x12)
            .padding(bottom = Grid.x24),
        verticalArrangement = Arrangement.spacedBy(Grid.x12),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Grid.x4)) {
            Text(title, style = Type.Hero)
            Text(subtitle, style = Type.Caption)
        }
        content()
    }
}

/**
 * Tab2 车辆：车载守护场景的完整卡片流。切到这里**不重新连接任何设备**，只是换一批卡。
 *
 * 三件事是照着真机照片改的：
 *  - **分组**（[CardStream] grouped）：这一页原来是 11 张同权重方块，其中 8 张讲卫星管道，
 *    标题却写着"车辆"。现在按 车况 / 守护 / 卫星兜底 三段走，车主那一问先有答案。
 *  - **动作挪到页尾**：寻车和车况还没有真实接口，远程熄火已走合作方数字钥匙；
 *    把动作摆在标题下面会让页面第一眼只剩三颗按钮，反而看不见车主真正要看的车况。
 *  - **不再横向滚**：三个词装得下 360dp，横向滚动那条手势是"你以为还有第四个"的错觉来源。
 *
 * 顶上那根 [SceneAsk] 是后来加的：这一页问车的事不该先切回首页。它只吃这一页的对话，
 * 记录仍是那一份（`TurnScene`）。
 */
@Composable
fun VehicleTab(app: AppRuntime, status: DemoRuntime.Status, modifier: Modifier = Modifier) {
    val scope = CardScope(app, status)
    val vm = vehicleVm(app)
    val ask by vm.ask.collectAsState()
    val cards by vm.cards.collectAsState()
    Page("车辆", "用车养车 · 卫星兜底", modifier = modifier) {
        SceneAsk(
            app,
            ask,
            screenOnThisPage = vm.isScreenOnPage(status.screenPage),
            placeholder = "问一句这辆车",
            prompts = listOf("车在哪", "卫星什么时候来", "围栏开着吗"),
            onAsk = vm::ask,
        )
        CardStream(cards, scope, grouped = true)
        SceneActions(app.scene.value.profile.actions, app)
    }
}

/**
 * Tab3 家人：亲子 + 健康 + 共享对象三个场景的卡合在一页（§5.1 里 Tab3 就是这个口径）。
 *
 * **这一页自己列一张表，不再拿两个场景的 cards 拼**。拼出来的结果是十二张同宽同底同圆角的
 * 方块一路排到底，中间没有一句话告诉用户"这几张是一件事"—— 那是全 App 最乱的一屏，
 * 而看这一页的人是家长，恰恰最不该让他自己找。现在按 守护 / 亲子陪伴 / 健康 / 共享对象 四段走。
 *
 * 拼表还带来两张重复：`queue`（待发队列）和车辆页那张同名，`sos` 和首页那颗钮同屏两次。
 * 卫星管道的事留在车辆页讲，这一页只讲家里的事（§5.2「一页两张同名重卡是最直接的乱」）。
 */
@Composable
fun FamilyTab(app: AppRuntime, status: DemoRuntime.Status, modifier: Modifier = Modifier) {
    val scope = CardScope(app, status)
    val vm = familyVm(app)
    val ask by vm.ask.collectAsState()
    val keys = listOf(
        "dock",
        "fence", "position", "sos",
        "kids.control", "kids.growth", "kids.ai",
        "health.vitals", "health.checkin", "health.agent",
        "share.objects",
    )
    Page("家人", "亲子陪伴 · 健康助手 · 共享对象", modifier = modifier) {
        // 「娃在屏上说了啥」这一问读的就是那份记录：圆屏在亲子页时说的话已经归到这一串里
        SceneAsk(
            app,
            ask,
            screenOnThisPage = vm.isScreenOnPage(status.screenPage),
            placeholder = "问一句家里",
            prompts = listOf("娃在屏上说了啥", "额度还剩几条", "圆屏怎么连"),
            onAsk = vm::ask,
        )
        CardStream(keys, scope, grouped = true)
    }
}

/** Tab4 我的：设备与底座、订阅、隐私中心，加上真正的产品级开关和工程模式入口 */
@Composable
fun MeTab(
    app: AppRuntime,
    status: DemoRuntime.Status,
    onEngineer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = CardScope(app, status)
    val vm = meVm(app)
    val s by vm.uiState.collectAsState()
    // 首帧 Loading：这一页没有 Loading 骨架，等状态到了再画
    val ready = s as? MeUiState.Ready ?: return
    Page("我的", "设备 · 订阅 · 隐私", modifier = modifier) {
        RenderCard("device.manager", scope)
        InkCard(icon = MIcons.task) {
            CardTitle("运行与服务")
            val guardHaptic = rememberConfirmHaptic()
            ToggleRow(
                "守护服务",
                ready.guardRunning,
                "关掉后圆屏会断开，锁屏时也不再报位置",
            ) { on ->
                if (on) guardHaptic()
                vm.setGuard(on)
            }
            ToggleRow("允许圆屏接入", ready.flags.acceptScreens, "开着才能把新圆屏配对进来；关掉后陌生设备一律拒绝") {
                vm.setAcceptScreens(it)
            }
            ToggleRow("演示模式", ready.flags.demoMode, "把等待和卫星经过都缩到几分钟，演示时一次看完") {
                vm.setDemoMode(it)
            }
        }
        InkCard(icon = MIcons.guard) {
            CardTitle("守护开关")
            // 这三枚开关都会让卫星开始/停止发消息（哨兵平安报、围栏出区报），是有后果的动作：
            // 落下的那一下给触觉确认（UX #27），和 SOS 布防的分段震动同一族
            val confirmHaptic = rememberConfirmHaptic()
            ToggleRow(
                "哨兵模式",
                ready.sentinel,
                "车停稳一段时间就报个平安，动过就留一个轨迹点") {
                confirmHaptic(); vm.setSentinel(it)
            }
            ToggleRow(
                "电子围栏：西湖景区",
                ready.fence,
                "车开出这个范围就用卫星发一条，不用等手机信号") {
                confirmHaptic(); vm.setFence(it, "西湖景区")
            }
            // 遗留车内这一版只做断联：高温要车内温度探头、体征要手环硬件，都没有数据源。
            // 说明里就把这条边界写出来，别让用户以为它在看着温度
            ToggleRow(
                "断联提醒（遗留车内）",
                ready.leaveBehind,
                ready.leaveBehindLine.ifBlank {
                    "带手机走远、车上那台设备没跟上：断链满 60 秒且已离开停车圈就报警，不看温度和体征"
                }) {
                confirmHaptic(); vm.setLeaveBehind(it)
            }
        }
        AssistantSettings(vm, ready.assistant)
        PetNameCard(vm, ready.assistant.petName)
        AlbumPushCard(app, ready.flags.acceptScreens, ready.assistant.petName)
        RenderCard("usage", scope)
        RenderCard("subscription", scope)
        RenderCard("privacy", scope)
        // 设置-关于：owner 11-25 要的版本号核对页，T5 从这里称，不再麻烦人翻工程模式
        var showAbout by rememberSaveable { mutableStateOf(false) }
        Button(
            onClick = { showAbout = !showAbout },
            colors = ButtonDefaults.buttonColors(containerColor = Ink.Track, contentColor = Ink.TextMid),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (showAbout) "收起关于页" else "关于（版本信息）") }
        if (showAbout) AboutBlock()
        Button(
            onClick = onEngineer,
            colors = ButtonDefaults.buttonColors(containerColor = Ink.Track, contentColor = Ink.TextMid),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("开发者模式") }
    }
}

/** T5 的正式核对处：版本名 / versionCode / 装机时间 / 最近更新，读装机包自身 */
@Composable
private fun AboutBlock() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val times = remember(ctx) {
        runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            pi.firstInstallTime to pi.lastUpdateTime
        }
    }
    val fmt: (Long) -> String = { t ->
        if (t <= 0L) "取不到" else android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", t).toString()
    }
    val (firstInstall, lastUpdate) = times.getOrDefault(0L to 0L)
    InkCard(icon = MIcons.settings) {
        CardTitle("关于（版本信息）")
        listOf(
            "版本名" to BuildConfig.VERSION_NAME,
            "versionCode" to BuildConfig.VERSION_CODE.toString(),
            "装机时间" to fmt(firstInstall),
            "最近更新" to fmt(lastUpdate),
        ).forEach { (k, v) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = Grid.x4),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(k, style = Type.Body, color = Ink.TextMid)
                Text(v, style = Type.Mono.copy(color = Ink.TextHigh))
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, hint: String, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            // 触摸下限（Material 44dp / 我们的口径 48dp）：两行文字自身只有 ~38dp 高，
            // 没有这一条，说明文字一短整行就矮下去，指头得瞄准标题那一行
            .defaultMinSize(minHeight = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // weight(1f) 不是好看，是**不压到开关**：没有它，长说明按自身宽度量，
        // 直接盖在右侧 Switch 上（截图 harness 第一次拍出来就看见了这条）
        Column(modifier = Modifier.weight(1f).padding(end = Grid.x12)) {
            Text(title, style = Type.Body)
            Text(hint, style = Type.Caption, color = Ink.TextLow)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * AI 助手的"能碰什么"。首页那张卡只管问与答，这一张管权限：
 * **档位逐条列出每条技能当前准不准** —— 只写一句"已放开"的话，用户不知道放开的是什么，
 * 出事时也读不出是哪一条越了档（§6 第 3 条要的是能核对，不是能开关）。
 *
 * **拆成两张卡**。上一版这是一张七百多 dp 高的方块：端点、密钥、五档芯片、七行准入清单、
 * 一句脚注全在一个圆角里 —— 那是五张卡挤在一个描边里，「我的」整页读下来只有七个同宽方块，
 * 没有一处告诉用户哪儿换了一件事。现在「AI 助手与模型」管配置，「它现在会做什么」管清单，
 * 中间那道缝就是这两件事的分界。
 */
/**
 * 起名卡。产品从"工具"变成"关系"的那一步就落在这一张上：
 * 没起名时它是「我的」页最上面的一张 —— 先有名字，后面所有"它怎么怎么样"的文案才有主语；
 * 起完名这张卡退到助手设置下面，变成改名入口，不再抢第一眼。
 */
@Composable
private fun PetNameCard(vm: MeViewModel, current: String) {
    val named = current.isNotBlank()
    var editing by rememberSaveable { mutableStateOf(!named) }
    var draft by rememberSaveable(current) { mutableStateOf(current) }
    InkCard(icon = MIcons.photo, tone = if (named) Tone.Neutral else Tone.Sat) {
        CardTitle(
            if (named) "它的名字" else "给它起个名字",
            trailing = if (named) current else "还没起名",
        )
        if (editing) {
            InkField(
                value = draft,
                onValueChange = { draft = it.take(12) },
                placeholder = "叫它什么好？最多 12 个字",
                onSubmit = {
                    vm.setPetName(draft)
                    editing = false
                },
                trailing = {
                    IconButton(onClick = {
                        vm.setPetName(draft)
                        editing = false
                    }) {
                        Icon(MIcons.send, contentDescription = "就这样叫它", tint = Ink.Sat)
                    }
                },
            )
            Text(
                "起了名，App 和圆屏上都会用它来称呼这个小家伙。",
                style = Type.Caption,
                color = Ink.TextLow,
            )
        } else {
            GhostAction("改个名") { editing = true }
        }
    }
}

/**
 * 相册推图（11-11 清单 §8.3 / B §5）：从手机相册现选一张推给圆屏的相册页。
 * 照片不打包进 APK —— 每一张都是推的那一刻从相册现读现压的。
 * 屏侧 JPEG 解码还在等固件：推过去没反应时先看 App 日志有没有"照片已推给圆屏"，
 * 有 = App 这边通了，屏上不显示是固件还没跟上（老固件静默忽略，不掉线）。
 *
 * 文案走它的视角：给车主的不是"推图成功 N 帧"，是"给它看你拍的照片"。
 * 名字从 [MeAssistantUi.petName] 带进来，起了名就称呼名字，没起名兜"它"。
 */
@Composable
private fun AlbumPushCard(app: AppRuntime, acceptScreens: Boolean, petName: String) {
    // PickVisualMedia 不申请存储权限（Photo Picker 系统组件）；老系统退回 GetContent
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) app.setAlbumPick(uri)
    }
    val called = petName.ifEmpty { "它" }
    InkCard(icon = MIcons.photo) {
        CardTitle(
            "给它看你拍的照片",
            trailing = if (app.runtime.status.value.screenPage == ScreenPage.photo) "$called 正在看相册" else null,
        )
        Text(
            "选一张照片，$called 会立刻看到。照片现选现推，不占手机的体积；$called 翻到相册页时也会把最近这张再看一遍。",
            style = Type.Caption,
            color = Ink.TextLow,
        )
        val screenOnline = app.runtime.status.value.clients > 0
        Button(
            onClick = {
                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            enabled = acceptScreens && screenOnline,
            colors = ButtonDefaults.buttonColors(containerColor = Ink.Track, contentColor = Ink.TextMid),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (screenOnline) "选一张给它看" else "$called 还没连上") }
    }
}

/**
 * 它会做什么（权限 + 技能清单合一）。上一版这是两张卡：「它能帮到什么程度」管档位，
 * 「这 N 件事它能做吗」管逐条准入 —— 同一个问题（"它到底能干什么"）被拆到两个圆角里，
 * 用户得在两张卡之间来回对。现在一张卡讲完：先定放手多少，再看每件事准不准，
 * 拦下的那几条直接说清差在哪一档。端点与密钥不摆出来（V1.18）：出包时就配好了。
 */
@Composable
private fun AssistantSettings(vm: MeViewModel, s: MeAssistantUi) {
    InkCard(icon = MIcons.agent, tone = if (s.endpoint.configured) Tone.Sat else Tone.Neutral) {
        CardTitle("它会做什么", trailing = if (s.endpoint.configured) s.endpoint.model else "只用手上的信息回答")
        // 档位标题必须大过它管的那排芯片。之前这里用 11sp 的 SectionLabel，
        // 而下面的芯片是 12.5sp —— 屏幕上是小字在领读，标题在当注脚
        SectionHead("放手交给它多少", "现在停在「${s.granted.label}」")
        // 五档一行装不下，横向滚的话最后一枚「不可逆动作」只剩半个词 —— 这是权限档，
        // 切掉的那半恰恰是最该看清的一档。折成 3+2，一枚都不缺字
        ChipBand(PermissionTier.entries.map { t ->
            ChipSpec(t.label, selected = t === s.granted) { vm.setGranted(t) }
        })
        s.skills.forEach { r ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Grid.x8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(r.title, style = Type.Body, modifier = Modifier.weight(1f))
                // 放行的那几条不重复标档位：它们要的都≤上面那个当前档，只有拦下的那条需要说清差在哪
                if (!r.allowed) Text("要「${r.needs.label}」", style = Type.Caption, color = Ink.TextLow)
                Badge(if (r.allowed) "可以做" else "被拦下", if (r.allowed) Tone.Ok else Tone.Wait)
            }
        }
        Text(
            "「发求救」它不会自己发，只会提醒你长按那颗按钮。",
            style = Type.Caption,
            color = Ink.TextLow,
        )
    }
}
