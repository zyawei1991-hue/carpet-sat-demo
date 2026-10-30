package com.carpet.satdemo.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carpet.satdemo.core.protocol.LinkPrimary
import com.carpet.satdemo.core.protocol.SatPhase
import com.carpet.satdemo.core.protocol.StatePayload

/**
 * 圆屏镜像：**这一格画的就是手机每 1 Hz 推给屏的那一帧**，不是又写一套 UI。
 *
 * 为什么手机上要能看见屏：这套 demo 的主场景是"圆屏连上了、能跟它说话"，而在此之前
 * 手机上关于屏的全部信息是"1 台在线"四个字 —— 连上了也不知道连上了什么、屏上在显示什么。
 * 屏不在手边（在车上、在支架上）的时候，这一格就是那块屏。
 *
 * 版式抄自展示端那一侧的绘制（`simulator/screen.html` 是同一口径的冒充件）：
 * 外圈链路、内圈卫星相位、圆心一摞状态、底下那段空白是触摸区。
 * **只有字号不跟着缩** —— 466px 缩到 224dp，字要跟着缩就只剩一块好看的黑圆，那就不必读了。
 *
 * 两条刻意的偏离，都不是手滑：
 *  - **颜色走本端这一套 token**（蜂窝绿、卫星蓝、告警红）。屏那侧卫星是琥珀，同语义两个色相，
 *    要统一得连着固件和 §5.2 那张色表一起改，不能在这一格里私加一支琥珀绕过 [Tone]。
 *  - **屏上那整句失败文案不进圆**（星历那句）。圆里只放词和数，句子归下面的卡 ——
 *    128dp 宽的弦上放长话只会折成三行，而这块镜像的用途是"抬一眼"，不是读说明。
 */
@Composable
fun ScreenMirror(
    snap: StatePayload?,
    online: Boolean,
    modifier: Modifier = Modifier,
    diameter: Dp = 224.dp,
) {
    val reduce = LocalReduceMotion.current
    val sat = snap?.sat
    val moduleOff = sat != null && !sat.moduleOk
    val left = sat?.nextPassInS
    val phase = sat?.phase ?: SatPhase.idle

    // 外圈 = 主链路。没屏接入、或者还没算出状态时打灰：灰在这套原语里只有一个意思 —— 没有这一帧的观众
    val linkColor by animateColorAsState(
        when {
            snap == null || !online -> Ink.Track
            snap.link.primary === LinkPrimary.sat -> Ink.Sat
            else -> Ink.Ok
        },
        Motion.colorSpec(reduce),
        label = "mirrorLink",
    )
    // 内圈 = 卫星相位走到哪儿了。这几档填充比是抄展示端 draw() 的，两边不许各调一套
    val fill by animateFloatAsState(
        when (phase) {
            SatPhase.idle -> 0f
            SatPhase.searching -> 0.35f
            SatPhase.locked -> 0.85f
            SatPhase.tx_pending -> 0.55f
            SatPhase.tx_ok -> 1f
            SatPhase.tx_fail -> 0.3f
            SatPhase.tx_no_ack -> 0.2f
        },
        Motion.tweenSpec(reduce),
        label = "mirrorFill",
    )
    val phaseColor by animateColorAsState(
        if (phase === SatPhase.tx_fail || phase === SatPhase.tx_no_ack) Ink.Alert else Ink.Wait,
        Motion.colorSpec(reduce),
        label = "mirrorPhase",
    )

    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val hub = Offset(size.width / 2f, size.height / 2f)
            val r0 = size.minDimension / 2f
            // 屏本体：一枚真黑。OLED 熄了就是黑的，而它坐在 #212834 的卡底上 ——
            // 不用画边框，这一格已经读得出"那是一块屏"。外面那圈是壳，不表态
            drawCircle(Oled, radius = r0 - 1.6.dp.toPx(), center = hub)
            drawCircle(
                color = Ink.Outline,
                radius = r0 - 0.8.dp.toPx(),
                center = hub,
                style = Stroke(width = 1.6.dp.toPx()),
            )
            val linkW = 3.dp.toPx()
            val linkR = r0 - 8.dp.toPx()
            val linkBox = Size(linkR * 2f, linkR * 2f)
            val linkAt = Offset(hub.x - linkR, hub.y - linkR)
            // 底部那段缺口是触摸区（屏上那颗"按住说一句"）。它不表态，所以永远是结构灰
            drawArc(
                color = Ink.Track,
                startAngle = 140.4f,
                sweepAngle = 79.2f,
                useCenter = false,
                topLeft = linkAt,
                size = linkBox,
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
            )
            // 链路环：从左上绕到右下的那 280.8°
            drawArc(
                color = linkColor,
                startAngle = -140.4f,
                sweepAngle = 280.8f,
                useCenter = false,
                topLeft = linkAt,
                size = linkBox,
                style = Stroke(width = linkW, cap = StrokeCap.Round),
            )
            if (snap != null && online && snap.link.primary === LinkPrimary.sat) {
                // 卫星为主时把缺口也接上，半透明 —— 屏那侧这条说的是"整圈都在卫星上"
                drawArc(
                    color = linkColor.copy(alpha = 0.55f),
                    startAngle = 140.4f,
                    sweepAngle = 39.2f,
                    useCenter = false,
                    topLeft = linkAt,
                    size = linkBox,
                    style = Stroke(width = linkW, cap = StrokeCap.Round),
                )
            }
            val phaseW = 4.dp.toPx()
            val phaseR = r0 - 20.dp.toPx()
            val phaseBox = Size(phaseR * 2f, phaseR * 2f)
            val phaseAt = Offset(hub.x - phaseR, hub.y - phaseR)
            drawArc(
                color = Ink.Track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = phaseAt,
                size = phaseBox,
                style = Stroke(width = phaseW),
            )
            if (fill > 0f) {
                drawArc(
                    color = phaseColor,
                    startAngle = -90f,
                    sweepAngle = 360f * fill,
                    useCenter = false,
                    topLeft = phaseAt,
                    size = phaseBox,
                    style = Stroke(width = phaseW, cap = StrokeCap.Round),
                )
            }
        }

        if (snap == null) {
            // 首帧：五条垫的就是最终那五行字的形状和高度。少一条，真值落地时整摞字就往上一跳
            Column(
                modifier = Modifier.width(diameter * 0.60f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Grid.x4),
            ) {
                SkeletonBar(width = 56.dp, height = 11.dp)
                SkeletonBar(width = 84.dp, height = 15.dp)
                SkeletonBar(width = 64.dp, height = 24.dp)
                SkeletonBar(width = 96.dp, height = 13.dp)
                SkeletonBar(width = 104.dp, height = 11.dp)
            }
        } else {
            MirrorText(snap, moduleOff, left, diameter)
        }
    }
}

/**
 * 圆里那一摞字。**宽 = 直径的 0.60**：最宽那行（坐标）落在离圆心最远的高度上，
 * 那个高度的弦长就这么多，再宽一点字尾就被相位环切掉。
 * 每行 maxLines=1：这一格宁可裁，也不能折行把圆撑破。
 */
@Composable
private fun MirrorText(snap: StatePayload, moduleOff: Boolean, left: Int?, size: Dp) {
    val sat = snap.sat
    val cnr = sat.cnr
    Column(
        modifier = Modifier.width(size * 0.60f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Grid.x4),
    ) {
        Text(
            if (moduleOff) "GT1005" else (sat.satName ?: "—"),
            style = Type.Overline.copy(color = if (moduleOff) Ink.Alert else Ink.Sat),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
        Text(
            if (moduleOff) "终端离线" else phaseLabel(sat.phase),
            style = Type.Body.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
        Text(
            when {
                moduleOff -> "${sat.queuePending}${sat.queueCapacity?.let { "/$it" } ?: ""} 条"
                left == null -> "—"
                left <= 0 -> "开窗"
                else -> clockFace(left)
            },
            style = Type.Numeric.copy(
                color = when {
                    moduleOff -> Ink.Alert
                    left != null && left <= 0 -> Ink.Ok
                    else -> Ink.TextHigh
                },
            ),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
        Text(
            when {
                moduleOff -> "补发等终端回来"
                cnr != null -> "CNR %.1f dB".format(cnr)
                else -> "未锁定"
            },
            style = Type.Caption,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
        // 等宽：坐标有一位小数错位会被读成"位置变了"，而这行恰恰是拿来核对的。
        // 11sp 是这一摞里唯一在字号 1.3 下仍装得下的写法 —— 再大一号就会被圆裁掉尾巴
        Text(
            "%.4f, %.4f".format(snap.gnss.lat, snap.gnss.lon),
            style = Type.Mono.copy(fontSize = 11.sp, color = Ink.TextLow),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 圆底下那一行。它只回答一件事：这一帧现在有没有人在看。几台是卡标题右边那格的活，这里不重复 */
@Composable
fun MirrorCaption(
    online: Boolean,
    serverError: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Grid.x8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(if (serverError != null) Tone.Alert else if (online) Tone.Ok else Tone.Neutral)
        Text(
            when {
                serverError != null -> "接入点起不来，这一帧还没地方推"
                online -> "屏上此刻就是这一帧"
                else -> "还没有屏接入 · 这是手机算出来、准备推过去的那一帧"
            },
            style = Type.Caption,
            color = if (serverError != null) Ink.Alert else Ink.TextMid,
        )
    }
}

private val Oled = Color(0xFF000000)

/**
 * 钟表写法。和车辆页那串数字同一套理由：24sp 等宽在圆里怎么都不会折，
 * "9 分 21 秒"六个汉字宽在这儿量不出来。
 */
private fun clockFace(sec: Int): String {
    val h = sec / 3600
    val m = sec % 3600 / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
