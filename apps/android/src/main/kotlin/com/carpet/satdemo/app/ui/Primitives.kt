package com.carpet.satdemo.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
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

    /** 展开/收起统一用这一对。以前各处手写出入动画，四个地方四种时长 */
    val expandIn = fadeIn(tween(Fast)) + expandVertically(tween(Normal))
    val expandOut = fadeOut(tween(Fast)) + shrinkVertically(tween(Normal))
}

/**
 * 卡片。**层级只有三档，一屏不许用出第四档**（[Level]）：
 *  - [Level.Plain] 默认：卡底，无描边 —— 绝大多数卡片；
 *  - [Level.Raised] 强调：更亮一档的底 + 一根描边，**一屏最多一个**，给当前主问题；
 *  - [Level.Group] 分组背板：更暗一档 + 无内边距，用来把若干 [RowLine] 收进同一个标题下。
 *
 * 之前所有卡都是同一档、同一间距，读起来就是"一摞同权重方块"。分档不是审美，
 * 是让用户扫一眼就知道该先看哪一块。
 *
 * @param mock 非空时在卡片角上打「示意数据」徽标。**桩数据必须可辨** ——
 *   前装车开不开放数据还没定，演示时观众把示意读成实测，比少一张卡片损失大得多。
 */
@Composable
fun InkCard(
    modifier: Modifier = Modifier,
    level: Level = Level.Plain,
    tone: Tone = Tone.Neutral,
    mock: Boolean = false,
    emphasis: Boolean = false,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = if (level === Level.Raised) RoundedCornerShape(Round.Hero) else RoundedCornerShape(Round.Card)
    val bg = when (level) {
        Level.Plain -> Ink.Card
        Level.Raised -> Ink.Raise
        Level.Group -> Ink.Sunk
    }
    val box = modifier
        .fillMaxWidth()
        .background(bg, shape)
    val edged = if (level === Level.Raised) box.border(1.dp, Ink.Outline, shape) else box
    Box(
        modifier = edged.then(if (level === Level.Group) Modifier else Modifier.padding(Grid.Pad)),
    ) {
        // 图标列在最左，内容整体右移：**每张卡的标题从同一条竖线起排**
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
                verticalArrangement = Arrangement.spacedBy(Grid.Inner),
            ) {
                if (mock) MockChip()
                content()
            }
        }
    }
}

/** 卡片的三档层级。见 [InkCard] */
enum class Level { Plain, Raised, Group }

/**
 * 分组背板：一个标题 + 若干行。**组内不再各是一张卡**，
 * 因为"每组一张卡"才是上一版最直接的乱源 —— 六个独立方块之间全是同一种间距。
 */
@Composable
fun InkGroup(
    title: String,
    trailing: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    InkCard(modifier = modifier, level = Level.Group) {
        Column(modifier = Modifier.padding(horizontal = Grid.x16, vertical = Grid.x12)) {
            RowLineHead(title, trailing)
            content()
        }
    }
}

@Composable
private fun RowLineHead(title: String, trailing: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // **组标题必须比组里的行大**。上一版这里用 Overline（11sp），而 RowLine 的标签是 Body（15sp）——
        // 标题比它管的每一行都小，分组这件事就只剩代码里知道，屏幕上读不出来。整页截图第一条实拍的层级 bug。
        Text(title, style = Type.Title)
        trailing?.let { Text(it, style = Type.Caption, color = Ink.TextLow) }
    }
}

/** 组里的一行。行与行之间一根 [HairLine]，比"每行一张卡"密得多也好读得多 */
@Composable
fun RowLine(
    label: String,
    value: String?,
    tone: Tone = Tone.Neutral,
    mono: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = Grid.Inner),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Type.Body.copy(color = Ink.TextMid), modifier = Modifier.padding(end = Grid.x12))
        Text(
            value ?: "--",
            // 等宽只换字体、不掉字号：值比标签小一档会被读成"这行不重要"，而它恰恰是这行的答案
            style = if (mono) Type.Body.copy(fontFamily = FontFamily.Monospace) else Type.Body,
            color = when {
                value == null -> Ink.TextLow
                tone !== Tone.Neutral -> tone.color
                else -> Ink.TextHigh
            },
        )
    }
}

/** 一根分隔线。只用于组内行与行之间，不许拿来当"卡片边框"用 */
@Composable
fun HairLine(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().height(1.dp).background(Ink.Track))
}

/**
 * 小节标题。**它必须大过自己那行说明**：上一版标题 11sp、说明 12.5sp，
 * 屏幕上是说明在领读、标题在当注脚 —— 这条只有整页截图能看出来，读代码看不出来。
 */
@Composable
fun SectionHead(title: String, note: String? = null, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Grid.x4)) {
        Text(title, style = Type.Body.copy(fontWeight = FontWeight.SemiBold, color = Ink.TextHigh))
        note?.let { Text(it, style = Type.Caption, color = Ink.TextLow) }
    }
}

/**
 * 一行式设备状态条。**首页顶部用它回答"现在能不能用"，而不是让用户滚三屏去找**。
 * 每枚 chip 只写"什么 + 状态"，颜色一律从 [Tone] 取，不引入第二种表达方式。
 */
@Composable
fun StatusRail(items: List<RailItem>, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Grid.x8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { it0 ->
            Row(
                modifier = Modifier
                    .background(Ink.Card, RoundedCornerShape(Round.Pill))
                    .padding(horizontal = Grid.x12, vertical = Grid.x8),
                horizontalArrangement = Arrangement.spacedBy(Grid.x8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(it0.tone)
                Text(it0.label, style = Type.Caption.copy(color = Ink.TextHigh, fontWeight = FontWeight.Medium))
                it0.value?.let { Text(it, style = Type.Caption, color = it0.tone.color) }
            }
        }
        // 右边那格空着是浪费：状态条是首页唯一"不滚动就在"的位置，安全件该钉在这里
        Spacer(modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

data class RailItem(val label: String, val value: String? = null, val tone: Tone)

/**
 * 选择器 chip（场景、权限档这种"选一档"的地方）。
 *
 * **选中用实底 + 深色字，不用"语义色 + 同色字"**。之前那种是 `Sat.copy(alpha=0.22)` 的淡底上
 * 再写一支 `Sat` 的字，算下来只有 **4.43:1**（低于 AA 的 4.5）—— 淡底把自己的字压糊了，
 * 而这类 chip 恰恰是"我现在在哪一档"的唯一线索。实底 Sat 配 [Ink.Deep] 是 7.5:1，
 * 顺带也和 M3 的 filter chip 选中态对上了。
 */
@Composable
fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    Box(
        modifier = modifier
            .background(if (selected) Ink.Sat else Ink.Track, RoundedCornerShape(Round.Chip))
            .clickable(onClick = onClick)
            .padding(horizontal = Grid.x12, vertical = Grid.x8),
    ) {
        Text(
            label,
            style = Type.Caption.copy(
                color = if (selected) Ink.Deep else Ink.TextHigh,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
        )
    }
}

/**
 * 长说明文字。**默认收起，点标题才展开** —— 上一版把"配对为什么是这三步""为什么不先做自动发现"
 * 这类工程说明直接摊在首页正文里，那是把 changelog 当界面用，也是"信息乱"最实在的来源。
 * 该说的话一句没少，只是不再抢第一屏的注意力。
 */
@Composable
fun CollapsableNote(
    label: String,
    text: String,
    tone: Tone = Tone.Neutral,
    expandedByDefault: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable(label) { mutableStateOf(expandedByDefault) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open },
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
        }
        AnimatedVisibility(visible = open, enter = Motion.expandIn, exit = Motion.expandOut) {
            Text(
                text,
                style = Type.Caption,
                color = if (tone === Tone.Neutral) Ink.TextLow else tone.color,
                modifier = Modifier.padding(top = Grid.x8),
            )
        }
    }
}

/** 语义图标底座。**图标只当"这张卡讲什么"用，不当装饰**（§5.2 第 4 条） */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Int = 36) {
    Box(
        modifier = modifier
            .size(size.dp)
            .background(tint.copy(alpha = Ink.TINT), RoundedCornerShape(Grid.x8)),
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
            .background(Ink.Wait.copy(alpha = Ink.TINT), RoundedCornerShape(Round.Chip))
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
            .background(tone.color.copy(alpha = Ink.TINT), RoundedCornerShape(Round.Chip))
            .padding(horizontal = Grid.x8, vertical = Grid.x4),
    ) {
        Text(text, style = Type.Caption.copy(color = tone.color, fontWeight = FontWeight.SemiBold))
    }
}

/**
 * 卡片标题。**18sp 的 [Type.Title] 是这一版新增的那一档** ——
 * 上一版标题借用 15sp 正文加粗，和正文只差字重，扫读时抓不到锚点。
 */
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
                Icon(
                    it,
                    contentDescription = null,
                    tint = if (tone === Tone.Neutral) Ink.TextMid else tone.color,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(text, style = Type.Title)
        }
        trailing?.let { Text(it, style = Type.Caption, color = Ink.TextLow) }
    }
}

/**
 * 首屏那一个主问题的大数字块。**[Display] 一屏只许出现一次**（唯一例外是 SOS 倒计时环，
 * 它出事时才出现，那时全页重心本来就该在它身上）。
 * 上一版"圆屏 0 台在线"和"对话"各摆了一个大字号块，两个重心等于没有重心。
 *
 * **只允许给真数据用** —— 桩数据不配有这个字号，它就该待在带「示意数据」徽标的小字行里。
 */
@Composable
fun HeroStat(
    icon: ImageVector,
    value: String,
    unit: String? = null,
    caption: String,
    tone: Tone = Tone.Neutral,
    display: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val color = if (tone === Tone.Neutral) Ink.TextHigh else tone.color
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Grid.x16),
    ) {
        IconBadge(icon, color, size = if (display) 52 else 44)
        Column(verticalArrangement = Arrangement.spacedBy(Grid.x4)) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(Grid.x4),
            ) {
                AnimatedContent(targetState = value, transitionSpec = { Motion.slideForward(true) }, label = "hero") { t ->
                    Text(t, style = (if (display) Type.Display else Type.Hero).copy(color = color))
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Grid.x4),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = Type.Caption, modifier = Modifier.padding(end = Grid.x12))
        Text(
            value ?: "--",
            // 等宽只换字体、不掉字号：值比标签小一档会被读成"这行不重要"，而它恰恰是这行的答案
            style = if (mono) Type.Body.copy(fontFamily = FontFamily.Monospace) else Type.Body,
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
    Box(modifier = modifier.size(104.dp), contentAlignment = Alignment.Center) {
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
        // **画成扁椭圆，不画正半圆。** 半径取宽度一半的正圆要 200dp 高才装得下，
        // 而 Compose 的 Canvas 默认不裁切 —— 第一版就是这么把弧线画到框外，
        // 直接穿过了上面的倒计时环（截图 harness 拍出来的第一条 bug）。
        val rx = size.width / 2f - stroke.width
        val ry = size.height - stroke.width
        val c = Offset(size.width / 2f, size.height - stroke.width / 2f)
        val box = Size(rx * 2f, ry * 2f)
        val origin = Offset(c.x - rx, c.y - ry)
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
                center = Offset(c.x + rx * cos(a).toFloat(), c.y + ry * sin(a).toFloat()),
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

/** 纵向占位。层级现在主要靠留白承担，所以这里给一个显式的名字，别处再写 24.dp 就该被问为什么 */
@Composable
fun Gap(size: Int = 16) {
    Spacer(modifier = Modifier.height(size.dp))
}

/** 旧的组标题。V1.10 起改成 [SectionHead]，这里保留是为了让还没改完的卡片不至于编译不过 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = Type.Overline, modifier = modifier.padding(top = Grid.x4))
}
