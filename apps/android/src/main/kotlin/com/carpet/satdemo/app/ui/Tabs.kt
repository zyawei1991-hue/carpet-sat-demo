package com.carpet.satdemo.app.ui

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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.carpet.satdemo.app.AppRuntime
import com.carpet.satdemo.core.ai.PermissionTier
import com.carpet.satdemo.core.ai.TurnScene
import com.carpet.satdemo.core.runtime.DemoRuntime

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
 *  - **动作挪到页尾**：那三个按钮一个都没接（要车机总线），摆在标题下面就是页面第一眼看
 *    见的三颗主行动 —— 而它们按下去只会在日志里写一句"还没接"。
 *  - **不再横向滚**：三个词装得下 360dp，横向滚动那条手势是"你以为还有第四个"的错觉来源。
 *
 * 顶上那根 [SceneAsk] 是后来加的：这一页问车的事不该先切回首页。它只吃这一页的对话，
 * 记录仍是那一份（`TurnScene`）。
 */
@Composable
fun VehicleTab(app: AppRuntime, status: DemoRuntime.Status, modifier: Modifier = Modifier) {
    val scope = CardScope(app, status)
    Page("车辆", "用车养车 · 卫星兜底", modifier = modifier) {
        SceneAsk(app, TurnScene.vehicle, status.screenPage, "问一句这辆车", listOf("车在哪", "下次过境", "围栏开着吗"))
        CardStream(app.orderedCards(Scene.Vehicle), scope, grouped = true)
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
    val keys = listOf(
        "dock",
        "fence", "position", "sos",
        "kids.control", "kids.growth", "kids.ai",
        "health.vitals", "health.checkin", "health.agent",
        "share.objects",
    )
    Page("家人", "亲子陪伴 · 健康助手 · 共享对象", modifier = modifier) {
        // 「娃在屏上说了啥」这一问读的就是那份记录：圆屏在亲子页时说的话已经归到这一串里
        SceneAsk(app, TurnScene.family, status.screenPage, "问一句家里", listOf("娃在屏上说了啥", "额度还剩几条", "圆屏怎么连"))
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
    val flags by app.flags.collectAsState()
    Page("我的", "设备 · 订阅 · 隐私", modifier = modifier) {
        RenderCard("device.manager", scope)
        InkCard(icon = MIcons.task) {
            CardTitle("运行与服务")
            ToggleRow(
                "守护服务",
                status.running,
                "关掉后圆屏会断开，锁屏时也不再报位置",
            ) { on -> app.setGuard(on) }
            ToggleRow("允许圆屏接入", flags.acceptScreens, "开着才能把新圆屏配对进来；关掉后陌生设备一律拒绝") {
                app.setFlag(flags.copy(acceptScreens = it))
            }
            ToggleRow("演示模式", flags.demoMode, "把等待和过境都缩到几分钟，演示时一次看完") {
                app.setFlag(flags.copy(demoMode = it))
            }
        }
        InkCard(icon = MIcons.guard) {
            CardTitle("守护开关")
            ToggleRow(
                "哨兵模式",
                status.snapshot?.sentinel?.armed == true,
                "车停稳一段时间就报个平安，动过就留一个轨迹点") { app.setSentinel(it) }
            ToggleRow(
                "电子围栏：西湖景区",
                status.snapshot?.fence?.active == true,
                "车开出这个范围就用卫星发一条，不用等手机信号") { app.setFence(it, "西湖景区") }
        }
        AssistantSettings(app)
        RenderCard("usage", scope)
        RenderCard("subscription", scope)
        RenderCard("privacy", scope)
        Button(
            onClick = onEngineer,
            colors = ButtonDefaults.buttonColors(containerColor = Ink.Track, contentColor = Ink.TextMid),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("开发者模式") }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, hint: String, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
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
@Composable
private fun AssistantSettings(app: AppRuntime) {
    val endpoint by app.aiEndpoint.collectAsState()
    val granted by app.aiGranted.collectAsState()
    val rows = remember(granted) { app.aiSkills() }
    InkCard(icon = MIcons.agent, tone = if (endpoint.configured) Tone.Sat else Tone.Neutral) {
        // 端点与密钥不再摆出来（V1.18）：出包时就配好了，车主没有需要填的东西
        CardTitle("它能帮到什么程度", trailing = if (endpoint.configured) endpoint.model else "只用手上的信息回答")
        // 档位标题必须大过它管的三枚芯片。之前这里用 11sp 的 SectionLabel，
        // 而下面的芯片是 12.5sp —— 屏幕上是小字在领读，标题在当注脚
        SectionHead("放手交给它多少", "现在停在「${granted.label}」")
        // 五档一行装不下，横向滚的话最后一枚「不可逆动作」只剩半个词 —— 这是权限档，
        // 切掉的那半恰恰是最该看清的一档。折成 3+2，一枚都不缺字
        ChipBand(PermissionTier.entries.map { t ->
            ChipSpec(t.label, selected = t === granted) { app.setAiGranted(t) }
        })
        Text(
            "「发求救」它不会自己发，只会提醒你长按那颗按钮。",
            style = Type.Caption,
            color = Ink.TextLow,
        )
    }
    InkCard(icon = MIcons.task) {
        CardTitle("这 ${rows.size} 件事它能做吗", trailing = "${rows.count { it.allowed }} 件可以做")
        rows.forEach { r ->
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
    }
}
