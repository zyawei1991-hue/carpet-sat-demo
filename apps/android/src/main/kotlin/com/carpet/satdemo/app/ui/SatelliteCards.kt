package com.carpet.satdemo.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carpet.satdemo.app.AppRuntime
import com.carpet.satdemo.core.ephemeris.EphState
import com.carpet.satdemo.core.link.LinkPhase
import com.carpet.satdemo.core.link.SosCopy
import com.carpet.satdemo.core.protocol.GnssSource
import com.carpet.satdemo.core.protocol.AccessService
import com.carpet.satdemo.core.protocol.SatPhase
import com.carpet.satdemo.core.protocol.SatState
import com.carpet.satdemo.core.protocol.SosLevel

/**
 * 卫星守护这一路的卡片。**它们只读 StatePayload，自己不产生任何判断** ——
 * 同一份 payload 也是推给圆屏的那一份（§5.2 最后一条：两边说法必须一致）。
 */

private const val DAY_S = 86_400L

/**
 * 倒计时秒数。负数或超过一天的一律当"不知道"：
 * core 那边 `nextPassInS?.toInt()` 截过 Long，"永远在窗口内"这种哨兵值会变成垃圾数，
 * 而屏上打出一个垃圾倒计时，比打 "--" 骗人骗得更彻底。
 */
private fun SatState.countdown(): Long? = nextPassInS?.toLong()?.takeIf { it in 0..DAY_S }

private fun phaseTone(p: LinkPhase): Tone = when (p) {
    LinkPhase.CELL -> Tone.Ok
    LinkPhase.RECOVER -> Tone.Wait
    LinkPhase.PROBE -> Tone.Wait
    LinkPhase.SAT -> Tone.Sat
}

private fun phaseLine(p: LinkPhase, clients: Int): String = when (p) {
    LinkPhase.CELL -> "蜂窝在线，卫星待命（圆屏 $clients 台）"
    LinkPhase.PROBE -> "蜂窝已经不通，正用卫星试一条探活消息"
    LinkPhase.SAT -> "已切卫星链路，蜂窝不再参与上报"
    LinkPhase.RECOVER -> "蜂窝已恢复，正在补发离线期间攒下的消息"
}

/** 那一行大字。枚举原名（CELL/PROBE）是线格式里的词，不该出现在车主屏幕上 */
private fun linkWord(p: LinkPhase): String = when (p) {
    LinkPhase.CELL -> "蜂窝"
    LinkPhase.PROBE -> "探活中"
    LinkPhase.SAT -> "卫星"
    LinkPhase.RECOVER -> "补发中"
}

/** 卫星那一侧的相位。同样只念人话：tx_no_ack 这种串只有我们自己看得懂 */
internal fun phaseLabel(p: SatPhase): String = when (p) {
    SatPhase.idle -> "空闲"
    SatPhase.searching -> "搜星中"
    SatPhase.locked -> "已锁定"
    SatPhase.tx_pending -> "排队中"
    SatPhase.tx_ok -> "卫星已收到"
    SatPhase.tx_fail -> "发送失败"
    SatPhase.tx_no_ack -> "星上没确认"
}

internal fun minuteSecond(sec: Long): String {
    val m = sec / 60
    val r = sec % 60
    return if (m > 0) "$m 分 $r 秒" else "$r 秒"
}

/**
 * 钟表写法。**大图元旁边那一串数字用它，不用 [minuteSecond]**：
 * "9 分 21 秒"是 6 个汉字宽，24sp 摆在 104dp 的圆里量不出来，于是折行、"秒"字被裁到描边上
 * —— 那是真机照片里最难看的一处。"9:21" 四个等宽字符怎么都不会折。
 */
private fun clock(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%d:%02d".format(m, s)
    }
}

/** 图例里的时间只到"分"：精确秒数已经在那个大数字上了，这里再说一遍就是抢位置 */
private fun minuteOnly(sec: Long): String = if (sec >= 60) "${sec / 60} 分" else "${sec} 秒"

/**
 * 方位角 → 车主看得懂的方向。数字给的是精确值，汉字给的是"我该往哪边转车身"，
 * 两个都要：只写 45° 得先想一下东北是多少度（§5.2 第 4 条：形和数都要能读）。
 */
private fun dirName(az: Double): String = when (((az + 22.5) % 360.0).toInt() / 45) {
    0 -> "正北"
    1 -> "东北"
    2 -> "正东"
    3 -> "东南"
    4 -> "正南"
    5 -> "西南"
    6 -> "正西"
    else -> "西北"
}

/** 链路主卡。首页首屏那张"状态优先"的卡就是它（§5.1） */
@Composable
fun LinkCard(s: CardScope) {
    val st = s.status
    val snap = s.snap
    val tone = phaseTone(st.phase)
    InkCard(icon = MIcons.signal, tone = tone, emphasis = true) {
        CardTitle("链路", trailing = st.localIp?.let { "接入点 $it" })
        Text(linkWord(st.phase), style = Type.Hero.copy(color = tone.color))
        Text(phaseLine(st.phase, st.clients), style = Type.Body)
        if (snap == null) {
            // 三条灰条，不是"稍等一下"那一行字。标签先给全、值位留同高的条，
            // 第一条状态落地时这张卡一行都不位移 —— 而且"还没到"从此和"取不到"长得不一样
            ValueRow("主路", null, warming = true)
            ValueRow("卫星终端", null, warming = true)
            ValueRow("载噪比", null, warming = true)
        } else {
            ValueRow("主路", if (snap.link.primary.name == "cell") "蜂窝" else "卫星", if (snap.link.cellOk) Tone.Ok else Tone.Wait)
            ValueRow(
                "卫星终端",
                if (snap.sat.moduleOk) "在线" else "离线",
                if (snap.sat.moduleOk) Tone.Ok else Tone.Alert,
            )
            ValueRow("载噪比", snap.sat.cnr?.let { "%.1f dB".format(it) })
        }
    }
}

/**
 * SOS 卡。状态那行字**只能来自 [SosCopy]**（§6.1 红线一），
 * 而卫星 ACK 和平台回执在这张卡上是两件事，不许并成一句。
 *
 * 触发方式是**长按 3 秒、分三段**，不是单击：这颗钮长在首屏，兜里蹭一下就触发一次求救，
 * 比按不出来严重得多。三段是为了让手指知道"还剩多少"，光一个环在震动时看不出来。
 * 松手即取消，取消那件事必须在钮上写明白 —— 隐藏在中断动作里的取消等于没有取消。
 */
@Composable
fun SosCard(s: CardScope) {
    val app = s.app
    val snap = s.snap
    val arm by app.arm.collectAsState()
    val cancelLine by app.cancelLine.collectAsState()
    val send = app.runtime.engine.sosSend
    val platformAcked = snap?.sos?.level == SosLevel.acked
    val level = snap?.sos?.level
    val fired = level == SosLevel.sent || level == SosLevel.pending || level == SosLevel.acked
    val tone = when {
        platformAcked -> Tone.Ok
        level == SosLevel.pending -> Tone.Alert
        level == SosLevel.sent -> Tone.Wait
        else -> Tone.Neutral
    }
    InkCard(icon = MIcons.sos, tone = tone, emphasis = true) {
        CardTitle("紧急求助", trailing = snap?.sos?.attempts?.let { "已尝试 $it 次" })
        Text(
            when (level) {
                SosLevel.acked -> "平台已回执"
                SosLevel.sent -> "已交给卫星，等平台回执"
                SosLevel.pending -> "还没确认"
                SosLevel.armed -> "待命中"
                else -> "未触发"
            },
            style = Type.Hero,
            color = if (tone === Tone.Neutral) Ink.TextHigh else tone.color,
        )
        // 没按过就不该有"这次怎么样"那一句：engine 里的 sosSend 有初始值，
        // 直接念出来会让首屏一上来就挂一行失败结论
        if (level == null || level == SosLevel.normal) {
            Text("还没触发过。按住下面那颗钮 3 秒，或长按圆屏侧键", style = Type.Body, color = Ink.TextMid)
        } else {
            Text(SosCopy.statusLine(send, snap?.sat?.countdown(), platformAcked), style = Type.Body, color = Ink.TextMid)
        }
        // 撤销的结果单独一行，和上面那句并存：那两件事说的是两个报文，并成一句就分不清撤销赶上了没有
        cancelLine?.let { Text(it, style = Type.Body, color = Ink.Wait) }

        if (arm.active) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CountdownRing(
                    // 环=剩余（§5.2 第 4 条）。传"已经过了多少"会让倒计时看起来在变大
                    fraction = 1f - arm.fraction,
                    center = ((arm.leftMs + 999) / 1000).toString(),
                    tone = Tone.Alert,
                    sub = "松手即取消",
                )
                Column(verticalArrangement = Arrangement.spacedBy(Grid.x8)) {
                    ArmSegments(arm.segment)
                    Text(
                        "已按住 ${(AppRuntime.ARM_MS - arm.leftMs) / AppRuntime.SEG_MS} 秒",
                        style = Type.Caption,
                    )
                }
            }
        } else {
            // 这张卡里不再画第二颗长按钮：导航栏正中那颗（[SosDial]）每一页都在，
            // 同一屏两颗 SOS，出事时人只会先按错一个。这张卡管的是"那颗按下去之后是什么状态"
            Text(
                "按住底部中间那颗红色 SOS，三秒才生效，中途松手就取消",
                style = Type.Caption,
                color = Ink.TextLow,
            )
        }
        if (fired) {
            Button(
                onClick = { app.cancelSos() },
                colors = ButtonDefaults.buttonColors(containerColor = Ink.Track, contentColor = Ink.TextHigh),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("撤销这次 SOS") }
        }
        Text(
            "圆屏侧键长按同样能触发。没确认时换到开阔处再按一次，比等下次过境快",
            style = Type.Caption,
            color = Ink.TextLow,
        )
    }
}

/** 三段进度。第一段亮 = 已经按住 1 秒，三段全亮 = 报文已经交给队列 */
@Composable
private fun ArmSegments(done: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(Grid.x8)) {
        repeat(3) { i ->
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(Grid.x8)
                    .background(
                        if (i < done) Ink.Alert else Ink.Track,
                        RoundedCornerShape(Grid.x4),
                    ),
            )
        }
    }
}

/**
 * 底部导航栏正中那颗 SOS 圆钮：按住三秒才生效，中途松手就取消。
 *
 * **没用 Material 的 Button**：Button 自带 clickable，会和长按的手势抢事件，
 * 抢输的那一方把"按住"变成"点一下就没了"。求救钮宁可样式土一点，
 * 也不能在关键时刻不按手势办事。
 *
 * `detectTapGestures(onPress = …)` + `tryAwaitRelease()` 是这条路唯一不留歧义的写法：
 * 抬手返回 true，被父级抢走返回 false —— 两种都要走到 onUp，因为"没发出去"和
 * "被页面滚动打断"对用户是同一件事：松手了，什么都没发生。
 * 挂进导航栏之后这件事基本不会发生了 —— 它不在可滚的 Column 里。
 */
@Composable
fun SosDial(app: AppRuntime, modifier: Modifier = Modifier) {
    val arm by app.arm.collectAsState()
    val haptics = LocalHapticFeedback.current
    // 三段各震一下：手指数得出"还剩几段"，比在震动的车里盯着数字看可靠。
    // LaunchedEffect 认的是段号，一秒一跳的那种抖动一次都没有
    LaunchedEffect(arm.segment) {
        if (arm.segment > 0) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    // 外面这一圈只在按住时存在：平时导航栏里就是一颗红钮，不预先占位、不常驻发光
    Box(modifier = modifier.size(68.dp), contentAlignment = Alignment.Center) {
        if (arm.active) SosArmRing(arm.fraction)
        Box(
            modifier = Modifier
                .size(56.dp)
                .shadow(10.dp, CircleShape)
                .background(Ink.Alert, CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            app.pressDownSos()
                            tryAwaitRelease()
                            app.releaseSos()
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                // 按下去之后钮上就是剩下的秒数：教"松手能取消"这件事，不必另外写一行字
                if (arm.active) "${arm.leftMs / 1000 + 1}" else "SOS",
                style = Type.Body.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/**
 * 按住 SOS 时红钮外圈那根进度弧：白色、4dp、从十二点顺时针走满三秒。
 *
 * 白色而不是状态色 —— 这一圈只回答"还剩多少"，给它任何语义色都会和"红=出事"抢含义（§5.2 第 2 条）。
 * 单独抽出来还有一层用：真机上"按住三秒"这个状态靠等待是拍不稳的，
 * 抽成纯参数的图元，截图 harness 就能把 0.33 和 1.0 两档摆在一起量宽窄、量它会不会切进导航栏。
 */
@Composable
internal fun SosArmRing(fraction: Float, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val swept by animateFloatAsState(fraction.coerceIn(0f, 1f), Motion.tweenSpec(reduce), label = "sosArm")
    Canvas(modifier = modifier.size(68.dp)) {
        val w = 4.dp.toPx()
        drawArc(
            color = Color.White,
            startAngle = -90f,
            sweepAngle = 360f * swept,
            useCenter = false,
            style = Stroke(width = w, cap = StrokeCap.Round),
            topLeft = Offset(w / 2f, w / 2f),
            size = Size(size.width - w, size.height - w),
        )
    }
}

/**
 *
 * 星历算不出来时那行人话必须上屏（E.9 / E.10 #7）：`NETWORK_ERROR` 的意思是
 * "没星历也没缓存"，该做的是连一次网；写成"没卫星信号"，用户会举着天线在空旷处白等。
 *
 * **没数就不画那几个图元。** 上一版没星历时照样画 104dp 的环、56dp 的弧、72dp 的指北针，
 * 一张四百多 dp 高的卡里只有三个 `--` 和一句"还没取到星历" —— 车辆页最难看的就是这张。
 * 图元是给数据准备的容器，没数据时它退成一块 [BlockError]：一句话说清哪种算不出来，
 * 加上"上次成功是几点"和一个真会重算的「重试」。
 *
 * **有数的时候这张卡也整个换过版式**（真机照片：它一个人占了 55% 的视口）：
 *  - 104dp 的倒计时环 → 去掉。十分钟视野里"还剩 9 分"只有 6% 的绿弧，读出来是"仪表坏了"，
 *    可它想说的只是一个时间。环形那种"整圈就是全部"的语义留给 SOS 那三秒（[SosCard]）。
 *  - 56dp 的弧线 → 换成一根 [StackedBar]。同一件事横条 8dp 就说完，还能挂两条图例；
 *    弧线那 56dp 里一大截是空轨道，占着位置不回答问题。
 *  - 三行 ValueRow（窗口长度 / 最大仰角 / 仰角判断）→ 合成一句。它们讲的是同一件事
 *    （"这轮窗口好不好用"），拆三行就成了一张三行的表。
 *  - 指北针 72dp → 56dp，度数挪到盘旁边那句话里（旧版那根针正压着自己的标签）。
 *
 * 四百多 dp 降到一百六十上下，四个信息一个没少。
 */
@Composable
fun PassWindowCard(s: CardScope) {
    val reduce = LocalReduceMotion.current
    val sat = s.snap?.sat
    val left = sat?.countdown()
    val dur = (sat?.nextPassDurS ?: 0).toLong()
    val maxEl = sat?.nextPassMaxEl
    val ephOk = sat?.ephState == EphState.SUCCESS
    val mock = s.mock(Capability.Ephemeris)
    // 首帧还没到：这是"还没到"，不是"取不到"，画骨架不画错误块。
    // 版式就是真数据那一版的形状（一个大数字 + 一行说明 + 一根条），落地时一个字都不位移
    if (s.snap == null) {
        InkCard(icon = MIcons.pass, tone = Tone.Neutral) {
            CardTitle("下次过境")
            SkeletonBar(width = 112.dp, height = 28.dp)
            SkeletonBar(width = 188.dp)
        }
        return
    }
    if (left == null && maxEl == null) {
        val retrying = s.app.ephRetrying.collectAsState().value
        // 没定位时不给「重试」：星历重算第一步就是拿位置，位置没有，按十次也是这句。
        // 那条路要走定位，不是走星历
        val canRetry = sat?.ephState != null && sat.ephState != EphState.NO_FIX
        InkCard(icon = MIcons.pass, tone = Tone.Neutral, mock = mock) {
            CardTitle("下次过境")
            BlockError(
                reason = when {
                    sat?.ephState == null -> "这一屏没有星历源，算不出窗口"
                    ephOk -> "星历可用，但这一轮还没排上窗口"
                    else -> sat?.ephFail ?: "星历暂时算不出来：先连一次网，不必举着天线等"
                },
                lastOkAtMs = sat?.ephOkAtMs,
                busy = retrying,
                onRetry = if (canRetry) {
                    { s.app.retryEphemeris() }
                } else {
                    null
                },
            )
        }
        return
    }
    val inWindow = left != null && left <= 0L
    val tone = if (sat?.phase == SatPhase.locked || inWindow) Tone.Ok else Tone.Sat
    val c = tone.color
    InkCard(icon = MIcons.pass, tone = tone, mock = mock) {
        CardTitle("下次过境", trailing = sat?.satName?.let { "锁定 $it" })
        // 第一行只回答"还要等多久"这一个问题：数字等宽（1Hz 跳秒不抖行），后面跟一句人话
        if (left == null) {
            // 窗口算出来了、进入时间没算出来：这是真状态，不许画一个 0:00 让人以为现在就能发
            Text("这一轮窗口已知，进入时间还在算", style = Type.Body, color = Ink.TextMid)
        } else {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(Grid.x8),
            ) {
                AnimatedContent(
                    targetState = if (inWindow) "现在" else clock(left),
                    transitionSpec = { Motion.slideForward(true, reduce) },
                    label = "pass",
                ) { t ->
                    Text(t, style = Type.Numeric.copy(color = c))
                }
                Text(
                    if (inWindow) "窗口开着，能直接发" else "后进入窗口",
                    style = Type.Body,
                    color = Ink.TextMid,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
        // 等待 : 窗口 = 从现在到窗口结束这一整段时间的两段
        if (left != null && !inWindow && dur > 0) {
            StackedBar(listOf(left.toInt().coerceAtLeast(1) to Ink.Track, dur.toInt() to c))
            Row(horizontalArrangement = Arrangement.spacedBy(Grid.x16)) {
                LegendDot(Ink.Track, "还要等", minuteOnly(left))
                LegendDot(c, "窗口能发", minuteOnly(dur))
            }
        }
        // 仰角和"够不够用"合成一句：分开写的话没人会分别读它们
        maxEl?.let { el ->
            Text(
                if (el < 20) "最大仰角 %.0f°，偏低：楼和山体容易挡".format(el)
                else "最大仰角 %.0f°，够用".format(el),
                style = Type.Caption,
                color = if (el < 20) Ink.Wait else Ink.TextMid,
            )
        }
        // 指北针只在真算得出方向时画。之前没方位也画一个空圆写着"无方位"，
        // 那是把"我们不知道"画成一个待填的图形，用户会去转车身找那个不存在的答案
        sat?.aimAz?.let { az ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(Grid.x12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AimDial(az, tone)
                Text(
                    // 度数只在盘里出现一次：旁边那句话再念一遍 45°，就成了同一件事说两遍
                    "车头/天线朝%s".format(dirName(az)),
                    style = Type.Body,
                    color = Ink.TextHigh,
                )
            }
        }
    }
}

/**
 * GT1005 终端卡。
 *
 * **"终端电量"那一行删了**：core 那个字段是写死的 null（`DemoEngine.snapshot()` 里
 * `moduleBattery = null` —— 假模组没电可报，真模组的电量口径〔§9〕也还没确认）。
 * 一行**永远**只会画 `--` 的键值对不是"缺数据"，是"这条根本没有来源"，
 * 挂在卡上就是真机照片里最扎眼的"看着像坏了"。相位挪进标题右侧，整张卡收成两行。
 * 升级进度仍画在这张卡上，因为它决定那排按钮能不能按（E.10 #6）。
 */
@Composable
fun TerminalCard(s: CardScope) {
    val snap = s.snap
    if (snap == null) {
        // 首帧：标题留着、真句那一行垫一条同高的灰条。
        // 空卡上只写"稍等"是没有形状的，用户分不清它是在等还是在坏
        InkCard(icon = MIcons.hardware, tone = Tone.Neutral) {
            CardTitle("卫星终端")
            SkeletonBar(width = 168.dp)
        }
        return
    }
    val sat = snap.sat
    val pct = sat.upgradePct
    InkCard(icon = MIcons.hardware, tone = if (!sat.moduleOk) Tone.Alert else Tone.Neutral) {
        CardTitle("卫星终端", trailing = phaseLabel(sat.phase))
        Text(
            when {
                !sat.moduleOk -> "终端离线：没供电或者线断了，消息只能先留在手机这边"
                pct != null -> "终端正在升级，这段时间所有发送都会被挡下"
                else -> "终端就绪"
            },
            style = Type.Body,
            color = when {
                !sat.moduleOk -> Ink.Alert
                pct != null -> Ink.Wait
                else -> Ink.Ok
            },
        )
        if (pct != null) ProgressBar(pct / 100f)
    }
}

/**
 * 补发队列卡。数字和额度一起画（E.10 #8：光说"待补发 47 条"没人知道离 50 只差 3 条）。
 * 额度用一条水位表示满不满，不再画点阵 —— 47 个点既数不过来，也答不出"还剩多少"。
 */
@Composable
fun QueueCard(s: CardScope) {
    val sat = s.snap?.sat
    val pending = sat?.queuePending ?: 0
    val capacity = sat?.queueCapacity
    val dropped = sat?.queueDropped
    val full = capacity != null && pending >= capacity
    val tone = if (dropped != null || full) Tone.Alert else if (pending > 0) Tone.Wait else Tone.Ok
    InkCard(icon = MIcons.queue, tone = tone) {
        CardTitle("待发队列", trailing = capacity?.let { "$pending / $it 条" } ?: "$pending 条")
        if (capacity != null && capacity > 0 && pending > 0) {
            StackedBar(listOf(pending to tone.color, (capacity - pending).coerceAtLeast(0) to Ink.Track))
        }
        Text(
            when {
                dropped != null -> "有 $dropped 条因为超时或队列满被挤掉，已重新排队"
                full -> "队列满了，新的内容先进不来"
                pending > 0 -> "蜂窝断了也不丢：链路一恢复就补发出去"
                else -> "没有欠账"
            },
            style = Type.Caption,
            color = if (dropped != null || full) Ink.Alert else Ink.TextMid,
        )
    }
}

/**
 * TPI 位置推送卡。**这一张的措辞上限是"最近一次推送于 N 秒前"**：
 * 厂商的 updateLocation() 是 void、又没有 TPI 监听器，推没推进去在应用侧不可观测，
 * 所以这里永远不许写"位置已同步"，哪怕底层刚返回 true。
 */
@Composable
fun TpiCard(s: CardScope) {
    val sat = s.snap?.sat
    val age = sat?.tpiAgeS
    val stale = sat?.tpiStale == true
    InkCard(icon = MIcons.terminal, tone = if (stale) Tone.Alert else Tone.Ok) {
        CardTitle("终端里的位置")
        Text(
            when {
                age == null -> "这条链路还没推过位置"
                stale -> "位置已过期 ${minuteSecond(age)}，卫星可能投递失败"
                else -> "最近一次推送于 ${minuteSecond(age)}前"
            },
            style = Type.Body,
            color = if (stale) Ink.Alert else Ink.TextHigh,
        )
    }
}

/** 位置卡。没定位就一行说清为什么，不画三行 `--` —— 空态不是内容 */
@Composable
fun PositionCard(s: CardScope) {
    val g = s.snap?.gnss
    if (s.snap == null) {
        InkCard(icon = MIcons.position, tone = Tone.Neutral) {
            CardTitle("位置")
            ValueRow("经纬度", null, warming = true)
            ValueRow("精度 HDOP", null, warming = true)
        }
        return
    }
    val hasFix = g != null && !(g.lat == 0.0 && g.lon == 0.0 && g.fixAgeS == null)
    if (!hasFix || g == null) {
        EmptyCard(
            title = "位置",
            icon = MIcons.position,
            hint = "还没有定位。圆屏那块板不带 GPS，位置由手机供；进屋、车里、高楼之间会拿不到",
        )
        return
    }
    InkCard(icon = MIcons.position, tone = Tone.Ok) {
        CardTitle("位置", trailing = sourceLabel(g.source))
        ValueRow("经纬度", "%.5f, %.5f".format(g.lat, g.lon), mono = true)
        ValueRow("精度 HDOP", g.hdop?.let { "%.1f".format(it) }, mono = true)
        ValueRow(
            "新鲜度",
            g.fixAgeS?.let { if (it > 30) "%.0f 秒前（已过期）".format(it) else "%.0f 秒前".format(it) },
        )
    }
}

private fun sourceLabel(src: GnssSource): String = when (src) {
    GnssSource.phone -> "手机 GPS"
    GnssSource.gt1005 -> "终端定位"
    GnssSource.fused -> "手机+终端融合"
}

/** 电子围栏卡。开关是真的，越界距离由调用方喂（core 不引地理库），没喂就不画数 */
@Composable
fun FenceCard(s: CardScope) {
    val f = s.snap?.fence
    val d = f?.distM
    val breached = f != null && f.active && !f.inside
    InkCard(icon = MIcons.fence, tone = if (breached) Tone.Alert else Tone.Neutral) {
        CardTitle("电子围栏", trailing = f?.name ?: "未命名")
        Text(
            when {
                f == null || !f.active -> "围栏没开。开了之后越界会走卫星发一条，不等蜂窝"
                d == null -> if (f.inside) "已在围栏内" else "已越界"
                f.inside -> "在围栏内，离边界 %.0f 米".format(d)
                else -> "已越界 %.0f 米".format(-d)
            },
            style = Type.Body,
            color = if (breached) Ink.Alert else Ink.TextHigh,
        )
    }
}

/** 哨兵模式卡 */
@Composable
fun SentinelCard(s: CardScope) {
    val sn = s.snap?.sentinel
    val armed = sn?.armed == true
    InkCard(icon = MIcons.sentry, tone = if (armed) Tone.Ok else Tone.Neutral, mock = s.mock(Capability.Sentinel)) {
        CardTitle("哨兵模式", trailing = if (armed) "布防中" else "未布防")
        Text(
            if (armed) "布防中：静止超过阈值发一条状态，动了就打一个轨迹点" else "未布防",
            style = Type.Body,
        )
        // 这里原来直接把 epoch 毫秒念上屏（一串 1730…）。那不是"时间"，那是我们内部的数：
        // 车主看得懂的是几点几分
        ValueRow("上次运动", clockTime(sn?.lastMotionTs))
        ValueRow("位移", sn?.driftM?.let { "%.0f 米".format(it) })
    }
}

/** 圆屏接入卡。这一张全是真数据：连了几台、接入点是什么 */
@Composable
fun DockCard(s: CardScope) {
    val st = s.status
    InkCard(icon = MIcons.dock, tone = if (st.clients > 0) Tone.Ok else Tone.Neutral) {
        CardTitle("圆屏", trailing = if (st.clients > 0) "已连接" else "未连接")
        Text(
            if (st.clients > 0) "${st.clients} 台圆屏在线"
            else "还没有圆屏连上来。首页那张「圆屏」卡里配对",
            style = Type.Body,
        )
        ValueRow("接入点", st.localIp?.let { "ws://$it" }, mono = true)
    }
}

/**
 * 卫星用量。**§8 R1 点名要的那一页**：额度水位、长度等级、丢弃数，全从模组现场读。
 *
 * 三个口径必须写在脸上，否则这张卡会在演示时骗人：
 *  - 额度分母按型号给（GM1001 500 / GM1003 50），〔§9 #12〕还没跟厂商确认，所以真读到型号之前这里空着；
 *  - 长度等级只念 ICI 回的那个原始数字，折算成字节要依赖单位口径（〔§9 #16〕），未确认前按最坏的一档算；
 *  - "已交出"不等于"已发出"：交进模组队列就算占了一个槽，那是它会不会被发走我们管不着。
 */
@Composable
fun UsageCard(s: CardScope) {
    val u = s.app.usage()
    val tone = when {
        u.invalidCard -> Tone.Alert
        u.pressure >= 0.9f -> Tone.Alert
        u.dropped > 0 -> Tone.Wait
        else -> Tone.Neutral
    }
    InkCard(icon = MIcons.quota, tone = tone) {
        CardTitle("卫星用量", trailing = u.model?.name)
        if (u.invalidCard) {
            // 无效卡的时候连额度都不能信：那组数是从没就绪的模组里读出来的
            Text("这张卡没就绪。额度、等级都不可信，先查卡是否插反", style = Type.Body, color = Ink.Alert)
        }
        ValueRow("长度等级", u.level?.let { "${it.level} · ${it.maxBytes} B" }, mono = true)
        ValueRow("单条可用载荷", "${u.budget.dataFieldBytes} B（按最保守的一档）")
        ValueRow(
            "回复接收",
            u.service?.let { if (it == AccessService.LONG) "终端自动收" else "要主动查" },
        )
        ValueRow("卡号", u.cardId?.takeIf { it != "1" }, mono = true)
        ProgressBar(u.pressure, if (u.pressure >= 0.9f) Tone.Alert else Tone.Wait)
        ValueRow("已交终端 / 额度", if (u.capacity > 0) "${u.handedOver} / ${u.capacity}" else "${u.handedOver} / 未知")
        ValueRow("还在手机里排队", "${u.waiting} 条")
        ValueRow("手机这边丢弃", if (u.dropped > 0) "${u.dropped} 条" else "0", if (u.dropped > 0) Tone.Alert else Tone.Neutral)
        ValueRow("过境后收回", "${u.reclaimed} 条")
        Text(
            if (u.model == null) "还没读到终端型号，额度总数先空着"
            else "「已交终端」只代表交给了终端，不代表已经发出去",
            style = Type.Caption,
            color = Ink.TextLow,
        )
    }
}
