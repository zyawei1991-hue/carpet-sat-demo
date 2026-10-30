package com.carpet.satdemo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.carpet.satdemo.app.AppRuntime
import com.carpet.satdemo.core.ephemeris.EphState
import com.carpet.satdemo.core.link.LinkPhase
import com.carpet.satdemo.core.link.SosCopy
import com.carpet.satdemo.core.protocol.GnssSource
import com.carpet.satdemo.core.protocol.SatPhase
import com.carpet.satdemo.core.protocol.SatState
import com.carpet.satdemo.core.protocol.SosLevel

/**
 * 卫星守护这一路的卡片。**它们只读 StatePayload，自己不产生任何判断** ——
 * 同一份 payload 也是推给圆屏的那一份（§5.2 最后一条：两边说法必须一致）。
 */

private const val DAY_S = 86_400L

/** 环按十分钟一圈画，倒计时超过这个视野就只报时间不报比例 */
private const val HORIZON_S = 600L

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
    LinkPhase.CELL -> "蜂窝在线，卫星待命（展示端 $clients 台）"
    LinkPhase.PROBE -> "蜂窝已判死，正用一条心跳探卫星能不能通"
    LinkPhase.SAT -> "已切卫星链路，蜂窝不再参与上报"
    LinkPhase.RECOVER -> "蜂窝已恢复，正在补发离线期间攒下的报文"
}

internal fun minuteSecond(sec: Long): String {
    val m = sec / 60
    val r = sec % 60
    return if (m > 0) "$m 分 $r 秒" else "$r 秒"
}

/** 链路主卡。首页首屏那张"状态优先"的卡就是它（§5.1） */
@Composable
fun LinkCard(s: CardScope) {
    val st = s.status
    val snap = s.snap
    val tone = phaseTone(st.phase)
    InkCard(icon = MIcons.signal, tone = tone, emphasis = true) {
        CardTitle("链路", trailing = st.localIp?.let { "接入点 $it" })
        Text(st.phase.name, style = Type.Hero.copy(color = tone.color))
        Text(phaseLine(st.phase, st.clients), style = Type.Body)
        if (snap == null) {
            Text("还没有第一帧状态，等 1Hz 那一圈跑完", style = Type.Caption)
        } else {
            ValueRow("主路", if (snap.link.primary.name == "cell") "蜂窝" else "卫星", if (snap.link.cellOk) Tone.Ok else Tone.Wait)
            ValueRow(
                "卫星终端",
                if (snap.sat.moduleOk) "GT1005 在线" else "GT1005 离线",
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
            // 按钮长在卡里而不是长在场景动作条上：Kids / Health / Social 三个场景的动作里没有 SOS，
            // 而这张卡是钉在首屏的 —— 求救不能依赖"当前场景恰好配了这个动作"（§5.1）
            HoldToFire(
                onDown = { app.pressDownSos() },
                onUp = { app.releaseSos() },
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
 * 按住才生效的那颗钮。**没用 Material 的 Button**：Button 自带 clickable，
 * 会和长按的手势抢事件，抢输的那一方会把"按住"变成"点一下就没了"。
 * 求救钮宁可样式土一点，也不能在关键时刻不按手势办事。
 *
 * `detectTapGestures(onPress = …)` + `tryAwaitRelease()` 是这条路唯一不留歧义的写法：
 * 抬手返回 true，被父级滚动抢走返回 false —— 两种都要走到 onUp，
 * 因为"没发出去"和"被页面滚动打断"对用户是同一件事：松手了，什么都没发生。
 */
@Composable
internal fun HoldToFire(
    onDown: () -> Unit,
    onUp: () -> Unit,
    label: String = "长按 3 秒触发 SOS",
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    /** 卡片里铺满一行；顶部状态条那种要的是"一枚能按的胶囊"，那里传 false */
    fill: Boolean = true,
) {
    Box(
        modifier = modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier)
            .background(Ink.Alert, RoundedCornerShape(Grid.x12))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onDown()
                        tryAwaitRelease()
                        onUp()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = if (compact) Type.Body.copy(fontWeight = FontWeight.SemiBold) else Type.Hero,
            color = Color.White,
            modifier = Modifier.padding(vertical = if (compact) Grid.x12 else Grid.x16),
        )
    }
}

/**
 * 过境窗口卡。环=剩余，弧线=窗口占这一轮的比例，指北针=该朝哪边。
 *
 * 星历算不出来时那行人话必须上屏（E.9 / E.10 #7）：`NETWORK_ERROR` 的意思是
 * "没星历也没缓存"，该做的是连一次网；写成"没卫星信号"，用户会举着天线在空旷处白等。
 */
@Composable
fun PassWindowCard(s: CardScope) {
    val sat = s.snap?.sat
    val left = sat?.countdown()
    val dur = (sat?.nextPassDurS ?: 0).toLong()
    val maxEl = sat?.nextPassMaxEl
    val ephOk = sat?.ephState == EphState.SUCCESS
    InkCard(icon = MIcons.pass, tone = Tone.Sat, mock = s.mock(Capability.Ephemeris)) {
        CardTitle("下次过境", trailing = sat?.satName?.let { "锁定 $it" })
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CountdownRing(
                fraction = left?.let { 1f - (it.toFloat() / HORIZON_S).coerceIn(0f, 1f) } ?: 0f,
                center = left?.let { minuteSecond(it) } ?: "--",
                tone = if (sat?.phase == SatPhase.locked) Tone.Ok else Tone.Sat,
                sub = if (left == null) "时间未知" else "剩余",
            )
            Column(modifier = Modifier.padding(start = Grid.x16)) {
                ValueRow("窗口长度", if (dur > 0) minuteSecond(dur) else null)
                ValueRow("最大仰角", maxEl?.let { "%.0f°".format(it) })
                ValueRow(
                    "仰角判断",
                    maxEl?.let { if (it < 20) "偏低，楼和山体容易挡" else "够用" },
                    if (maxEl != null && maxEl < 20) Tone.Wait else Tone.Neutral,
                )
            }
        }
        PassArc(
            windowFraction = if (left == null || dur <= 0 || left + dur <= 0L) 0f
            else (dur.toFloat() / (left + dur)).coerceIn(0f, 1f),
            progressInWindow = if (left != null && left <= 0L) 0.5f else null,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("天线/车头朝向", style = Type.Caption)
                AimDial(sat?.aimAz)
            }
            Text(
                when {
                    sat?.ephState == null -> "没接星历源"
                    ephOk -> "星历可用"
                    else -> sat.ephFail ?: "星历不可用（${sat.ephState}）"
                },
                style = Type.Caption,
                color = if (ephOk) Ink.Ok else Ink.Wait,
            )
        }
    }
}

/** GT1005 终端卡。升级进度画在这张卡上，因为它决定那排按钮能不能按（E.10 #6） */
@Composable
fun TerminalCard(s: CardScope) {
    val sat = s.snap?.sat
    val pct = sat?.upgradePct
    InkCard(icon = MIcons.hardware, tone = if (sat?.moduleOk == false) Tone.Alert else Tone.Neutral) {
        CardTitle("卫星终端", trailing = sat?.fw?.let { "fw $it" })
        Text(
            when {
                sat == null -> "还没读到第一帧状态"
                !sat.moduleOk -> "GT1005 离线：终端没供电或链路断了，报文只能先留在手机这边"
                pct != null -> "终端正在升级固件，这一段时间内所有发送都会被挡下"
                else -> "终端就绪"
            },
            style = Type.Body,
            color = when {
                sat == null -> Ink.TextMid
                !sat.moduleOk -> Ink.Alert
                pct != null -> Ink.Wait
                else -> Ink.Ok
            },
        )
        if (pct != null) ProgressBar(pct / 100f)
        ValueRow("状态", sat?.phase?.name)
        ValueRow("终端电量", sat?.moduleBattery?.let { "$it%" })
    }
}

/** 补发队列卡。数字和额度一起画（E.10 #8：光说"待补发 47 条"没人知道离 50 只差 3 条） */
@Composable
fun QueueCard(s: CardScope) {
    val sat = s.snap?.sat
    val pending = sat?.queuePending ?: 0
    val dropped = sat?.queueDropped
    InkCard(icon = MIcons.queue, tone = if (dropped != null) Tone.Alert else if (pending > 0) Tone.Wait else Tone.Ok) {
        CardTitle("待发队列", trailing = "点=条数")
        QueueDots(pending, sat?.queueCapacity, if (pending > 0) Tone.Wait else Tone.Ok)
        Text(
            when {
                dropped != null -> "有 $dropped 条因为超时或队列满被挤掉，已重新排队"
                pending > 0 -> "蜂窝断了也不丢：这些会在 RECOVER 里补发出去"
                else -> "没有欠账"
            },
            style = Type.Caption,
            color = if (dropped != null) Ink.Alert else Ink.TextMid,
        )
    }
}

/**
 * TPI 位置推送卡。
 *
 * 措辞上限就是"最近一次推送于 N 秒前"：〔码〕实测 `updateLocation()` 返回 void、
 * SDK 又没有 TPI 监听器，**这条路不可观测**（§9 #17），
 * 所以这里不许写"位置已同步"，哪怕底层刚返回 true。
 */
@Composable
fun TpiCard(s: CardScope) {
    val sat = s.snap?.sat
    val age = sat?.tpiAgeS
    val stale = sat?.tpiStale == true
    InkCard(icon = MIcons.terminal, tone = if (stale) Tone.Alert else Tone.Ok) {
        CardTitle("终端里的位置（TPI）", trailing = "模组只认我们推的这份")
        Text(
            when {
                age == null -> "这条链路还没推过位置"
                stale -> "位置已过期 ${minuteSecond(age)}，卫星可能投递失败"
                else -> "最近一次推送于 ${minuteSecond(age)}前"
            },
            style = Type.Body,
            color = if (stale) Ink.Alert else Ink.TextHigh,
        )
        Text(
            "推送结果不可回执：SDK 的 updateLocation 没有返回值，所以这里只报时间不报成功",
            style = Type.Caption,
            color = Ink.TextLow,
        )
    }
}

/** 位置卡。没定位就画 --，不画 0,0 —— core 里 GnssState 的默认值正是 0,0 */
@Composable
fun PositionCard(s: CardScope) {
    val g = s.snap?.gnss
    val hasFix = g != null && !(g.lat == 0.0 && g.lon == 0.0 && g.fixAgeS == null)
    InkCard(icon = MIcons.position, tone = if (hasFix) Tone.Ok else Tone.Wait) {
        CardTitle("位置", trailing = g?.let { sourceLabel(it.source) })
        ValueRow("经纬度", if (hasFix && g != null) "%.5f, %.5f".format(g.lat, g.lon) else null, mono = true)
        ValueRow("精度 HDOP", g?.hdop?.let { "%.1f".format(it) }, mono = true)
        ValueRow(
            "新鲜度",
            g?.fixAgeS?.let { if (it > 30) "%.0f 秒前（已过期）".format(it) else "%.0f 秒前".format(it) },
        )
        Text(
            if (hasFix) "定位一进来就交给 TPI 任务，够新就不再推"
            else "还没有定位。进屋、车里、高楼之间会这样；SOS 仍会发，但会带上「定位已过期」的标记",
            style = Type.Caption,
            color = Ink.TextLow,
        )
    }
}

private fun sourceLabel(src: GnssSource): String = when (src) {
    GnssSource.phone -> "手机 GPS"
    GnssSource.gt1005 -> "GT1005 GNSS"
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
                d == null -> if (f.inside) "已在围栏内（距离判定还没接：需要一条轨迹进来）" else "已越界"
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
        ValueRow("上次运动", sn?.lastMotionTs?.let { "$it" }, mono = true)
        ValueRow("位移", sn?.driftM?.let { "%.0f 米".format(it) })
    }
}

/** 展示端（圆屏）接入卡。这一张全是真数据：连了几台、接入点是什么 */
@Composable
fun DockCard(s: CardScope) {
    val st = s.status
    InkCard(icon = MIcons.dock, tone = if (st.clients > 0) Tone.Ok else Tone.Wait) {
        CardTitle("展示端", trailing = "契约 v1")
        Text(
            if (st.clients > 0) "${st.clients} 台圆屏在线，1Hz 收全量状态"
            else "还没有圆屏连上来。手机和圆屏要在同一个 Wi-Fi，接入点 ws://${st.localIp ?: "—"}",
            style = Type.Body,
        )
        ValueRow("接入点", st.localIp?.let { "ws://$it" }, mono = true)
        Text(
            "圆屏和 App 渲染同一份 StatePayload，所以两边看到的说法一样",
            style = Type.Caption,
            color = Ink.TextLow,
        )
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
            Text("这张卡没就绪（ICI 回 1）。额度、等级都不可信，先查卡是否插反", style = Type.Body, color = Ink.Alert)
        }
        ValueRow("长度等级", u.level?.let { "等级 ${it.level} · ${it.maxBytes} B" }, mono = true)
        ValueRow("单条可用载荷", "${u.budget.dataFieldBytes} B（按最坏口径折算）")
        ValueRow("业务类型", u.service?.name, mono = true)
        ValueRow("卡号", u.cardId?.takeIf { it != "1" }, mono = true)
        ProgressBar(u.pressure, if (u.pressure >= 0.9f) Tone.Alert else Tone.Wait)
        ValueRow("已交终端 / 额度", if (u.capacity > 0) "${u.handedOver} / ${u.capacity}" else "${u.handedOver} / 未知")
        QueueDots(u.waiting, u.capacity.takeIf { it > 0 }, Tone.Sat)
        ValueRow("App 侧丢弃", if (u.dropped > 0) "${u.dropped} 条" else "0", if (u.dropped > 0) Tone.Alert else Tone.Neutral)
        ValueRow("过境后收回", "${u.reclaimed} 条")
        Text(
            if (u.model == null) "还没读到模组型号，所以额度总数空着 —— 空着比写一个猜出来的数好"
            else "额度按型号口径算，这个分母〔§9 #12〕还没和厂商确认；「已交出去」的报文算占位，不代表已经发走",
            style = Type.Caption,
            color = Ink.TextLow,
        )
    }
}
