package com.carpet.satdemo.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Material 3 官方 motion 的本地化取值（参考 nowinandroid / Tivi 的用法，都是 Apache-2.0）。
 * 只留三档：**内容换页**用 slide+fade，**状态变化**用 spring，**进出场**用短 tween。
 * 不自创第四种 —— 动效一多，用户会以为每个动画都对应一个功能。
 */
object Motion {
    const val Fast = 160
    const val Normal = 260
    const val Slow = 420

    /** 换 Tab / 换场景：横向推入。方向跟着"往哪边切"，反着推会让人怀疑自己按错了 */
    fun slideForward(forward: Boolean): ContentTransform =
        (slideInHorizontally(tween(Normal)) { if (forward) it / 6 else -it / 6 } + fadeIn(tween(Fast))) togetherWith
            (slideOutHorizontally(tween(Normal)) { if (forward) -it / 6 else it / 6 } + fadeOut(tween(Fast)))

    val springSpec = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    val tweenSpec = tween<Float>(Normal)

    /** 颜色不能复用 [tweenSpec]：动画规格带类型，拿 Float 的规格去插 Color 是编译期就拦住的错法 */
    val colorSpec = tween<Color>(Normal)
}

/**
 * 卡片原语。**卡片之间只用底色区分，不画边框不画阴影**（§5.2 第 3 条）。
 *
 * @param mock 非空时在卡片角上打「示意数据」徽标。**桩数据必须可辨** ——
 *   前装车开不开放数据还没定，演示时观众把示意读成实测，比少一张卡片损失大得多。
 */
@Composable
fun InkCard(
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Neutral,
    mock: Boolean = false,
    emphasis: Boolean = false,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Ink.Card, RoundedCornerShape(Grid.x16))
            .padding(Grid.x16),
    ) {
        // 图标列在最左，内容整体右移：**每张卡的标题从同一条竖线起排**。
        // 之前图标单独占一行，一屏六张卡读起来是六段"带图标的段落"，不是六张同一族的卡
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Grid.x12),
            verticalAlignment = Alignment.Top,
        ) {
            if (icon != null || emphasis) {
                Column(verticalArrangement = Arrangement.spacedBy(Grid.x8)) {
                    icon?.let { IconBadge(it, if (tone === Tone.Neutral) Ink.TextMid else tone.color) }
                    if (emphasis) Dot(tone, modifier = Modifier.padding(start = Grid.x4))
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Grid.x8),
            ) {
                if (mock) MockChip()
                content()
            }
        }
    }
}

/** 语义图标底座。**图标只当"这张卡讲什么"用，不当装饰**（§5.2 第 4 条） */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Int = 36) {
    Box(
        modifier = modifier
            .size(size.dp)
            .background(tint.copy(alpha = 0.14f), RoundedCornerShape(Grid.x8)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size - 14).dp))
    }
}

/** 语义点。§5.2 第 4 条：形要有语义，所以这个点只用来标状态，不当装饰 */
@Composable
fun Dot(tone: Tone, modifier: Modifier = Modifier) {
    val c by animateColorAsState(tone.color, tween(Motion.Normal), label = "dot")
    Box(
        modifier = modifier
            .size(Grid.x8)
            .background(c, CircleShape),
    )
}

@Composable
fun MockChip() {
    Box(
        modifier = Modifier
            .background(Ink.Track, RoundedCornerShape(Grid.x4))
            .padding(horizontal = Grid.x8, vertical = Grid.x4),
    ) {
        Text("示意数据 · 未接真源", style = Type.Caption.copy(color = Ink.Wait))
    }
}

/** 状态徽标。文案由调用方给，颜色只从 Tone 里取 */
@Composable
fun Badge(text: String, tone: Tone) {
    Box(
        modifier = Modifier
            .background(tone.color.copy(alpha = 0.18f), RoundedCornerShape(Grid.x4))
            .padding(horizontal = Grid.x8, vertical = Grid.x4),
    ) {
        Text(text, style = Type.Caption.copy(color = tone.color, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
fun CardTitle(text: String, trailing: String? = null, icon: ImageVector? = null, tone: Tone = Tone.Neutral) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Grid.x8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Icon(it, contentDescription = null, tint = if (tone === Tone.Neutral) Ink.TextMid else tone.color, modifier = Modifier.size(18.dp))
            }
            Text(text, style = Type.Body.copy(fontWeight = FontWeight.SemiBold))
        }
        trailing?.let { Text(it, style = Type.Caption) }
    }
}

/**
 * 首页那种"一屏先回答一个问题"的大数字块。**只允许给真数据用** ——
 * 桩数据不配有这个字号，它就该待在带「示意数据」徽标的小字行里。
 */
@Composable
fun HeroStat(
    icon: ImageVector,
    value: String,
    unit: String? = null,
    caption: String,
    tone: Tone = Tone.Neutral,
    modifier: Modifier = Modifier,
) {
    val color = if (tone === Tone.Neutral) Ink.TextHigh else tone.color
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Grid.x16),
    ) {
        IconBadge(icon, color, size = 48)
        Column(verticalArrangement = Arrangement.spacedBy(Grid.x4)) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(Grid.x4),
            ) {
                AnimatedContent(targetState = value, transitionSpec = { Motion.slideForward(true) }, label = "hero") { t ->
                    Text(t, style = Type.Display.copy(color = color))
                }
                unit?.let { Text(it, style = Type.Body, color = Ink.TextMid) }
            }
            Text(caption, style = Type.Caption)
        }
    }
}

/** 一行"标签 — 值"。缺数据传 null，这里画 `--` 而不是 0 —— 0 是个看起来像事实的谎 */
@Composable
fun ValueRow(label: String, value: String?, tone: Tone = Tone.Neutral, mono: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = Type.Caption, modifier = Modifier.padding(end = Grid.x12))
        Text(
            value ?: "--",
            style = if (mono) Type.Mono else Type.Body,
            color = when {
                value == null -> Ink.TextLow
                tone !== Tone.Neutral -> tone.color
                else -> Ink.TextHigh
            },
        )
    }
}

/**
 * 倒计时环。**圆环 = 剩余**（§5.2 第 4 条）。
 * fraction 是"还剩多少"，不是"已经过了多少" —— 反过来会让人以为窗口在变大。
 */
@Composable
fun CountdownRing(
    fraction: Float,
    center: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    sub: String? = null,
) {
    // 1Hz 的跳变直接画会一格一格地抖，动一下眼睛才读得出"在倒计时"而不是"数字变了"
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), Motion.tweenSpec, label = "ring")
    val c by animateColorAsState(tone.color, Motion.colorSpec, label = "ringColor")
    Box(modifier = modifier.size(96.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val track = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
            // 画在包围盒内侧：半径减一个线宽，否则圆环会被裁掉一边
            val box = Size(size.minDimension - track.width * 2f, size.minDimension - track.width * 2f)
            val origin = Offset((size.width - box.width) / 2f, (size.height - box.height) / 2f)
            drawArc(
                color = Ink.Track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = origin,
                size = box,
                style = track,
            )
            if (f > 0f) {
                drawArc(
                    color = c,
                    startAngle = -90f,
                    sweepAngle = 360f * f,
                    useCenter = false,
                    topLeft = origin,
                    size = box,
                    style = track,
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(center, style = Type.Hero.copy(color = c))
            sub?.let { Text(it, style = Type.Caption) }
        }
    }
}

/**
 * 过顶窗口弧线。**弧线 = 一轮卫星周期里能用的那一段**。
 *
 * 只画算得出来的东西：窗口长度 / (等待 + 窗口)。契约里没有"整周期多长"这个字段
 * （SatState 只有 next_pass_in_s / next_pass_dur_s），所以不假装画得出星下点轨迹。
 */
@Composable
fun PassArc(
    windowFraction: Float,
    progressInWindow: Float? = null,
    tone: Tone = Tone.Sat,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxWidth().height(56.dp)) {
        val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
        val r = size.width / 2f - stroke.width
        val c = Offset(size.width / 2f, size.height - stroke.width / 2f)
        val box = Size(r * 2f, r * 2f)
        val origin = Offset(c.x - r, c.y - r)
        drawArc(
            color = Ink.Track,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = origin,
            size = box,
            style = stroke,
        )
        drawArc(
            color = tone.color,
            startAngle = 180f,
            sweepAngle = 180f * windowFraction.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = origin,
            size = box,
            style = stroke,
        )
        progressInWindow?.let { p ->
            val a = Math.toRadians(180.0 + 180.0 * p.coerceIn(0f, 1f))
            drawCircle(
                color = Ink.TextHigh,
                radius = stroke.width,
                center = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat()),
            )
        }
    }
}

/** 朝向指引。**点 = 该把天线/车头朝哪边**，正北 0° 顺时针（aim_az 的口径） */
@Composable
fun AimDial(azimuthDeg: Double?, tone: Tone = Tone.Sat, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(72.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f - 6.dp.toPx()
            drawCircle(color = Ink.Track, radius = r, center = c, style = Stroke(width = 2.dp.toPx()))
            azimuthDeg?.let { deg ->
                val a = Math.toRadians(deg - 90.0)
                drawLine(
                    color = tone.color,
                    start = c,
                    end = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat()),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
        Text(if (azimuthDeg == null) "无方位" else "N${azimuthDeg.toInt()}°", style = Type.Caption)
    }
}

/**
 * 队列点阵。**点 = 待补发的条数**，额度写在右下角（E.10 #8：
 * 光说"待补发 47 条"，没人知道离 GM1003 那 50 个槽只差 3 条）。
 * 点最多画 24 个，多的用 +N 表示 —— 画 500 个点不是信息，是噪声。
 */
@Composable
fun QueueDots(pending: Int, capacity: Int?, tone: Tone) {
    val full = capacity != null && pending >= capacity
    val shown = min(pending, 24)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Grid.x4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(shown) {
                Box(
                    modifier = Modifier
                        .size(Grid.x4)
                        .background(if (full) Ink.Alert else tone.color, CircleShape),
                )
            }
            if (pending > shown) Text("+${pending - shown}", style = Type.Caption)
            if (pending == 0) Text("队列空", style = Type.Caption)
        }
        Text(
            capacity?.let { "$pending/$it" } ?: "$pending",
            style = Type.Mono.copy(color = if (full) Ink.Alert else Ink.TextMid),
        )
    }
}

/**
 * 进度条（固件升级用，屏上那排按钮的禁用态看它）。
 * 自己画，不用 Material 的 LinearProgressIndicator：它在 M3 各版本里签名动过，
 * 为一个条把自己绑死在某个 BOM 版本上不划算。
 */
@Composable
fun ProgressBar(fraction: Float, tone: Tone = Tone.Wait, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), Motion.tweenSpec, label = "bar")
    val c by animateColorAsState(tone.color, Motion.colorSpec, label = "barColor")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Grid.x8)
            .background(Ink.Track, CircleShape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(f)
                .height(Grid.x8)
                .background(c, CircleShape),
        )
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Type.Caption, color = Ink.TextLow, modifier = modifier.padding(top = Grid.x4))
}
