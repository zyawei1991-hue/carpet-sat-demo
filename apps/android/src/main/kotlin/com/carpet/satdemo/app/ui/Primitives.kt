package com.carpet.satdemo.app.ui

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Material 3 官方 motion 的本地化取值（参考 nowinandroid / Tivi 的用法，都是 Apache-2.0）。
 * 只留三档：**内容换页**用 slide+fade，**状态变化**用 tween，**进出场**用展开/收起。
 * 不自创第四种 —— 动效一多，用户会以为每个动画都对应一个功能。
 *
 * 每个规格都要调用方传 [reduce]（值来自 [LocalReduceMotion]）：`transitionSpec`、`enter`、`exit`
 * 这些 lambda **不是** Composable，读不了 CompositionLocal，所以只能在外面取好再传进来。
 * 系统"移除动画"打开时位移和尺寸动画收掉，淡入淡出和颜色变化留着 ——
 * Material 的要求是**减动效，不是取消反馈**，那两样才是"我按到了"的证据，
 * 全砍成瞬变会让界面读起来像死机。
 */
object Motion {
    const val Fast = 160
    const val Normal = 260
    const val Slow = 420

    /** 减动效时保留的那一档：位移归零，淡入淡出不归零 */
    const val FadeOnly = 120

    /** M3 的 emphasized 缓动：起步果断、收尾稳住。默认的 FastOutSlowIn 在短距离上看着像匀速 */
    private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 出场比入场快。拖长的反馈会被读成延迟 */
    private val EmphasizedExit = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** 换场景：横向推入。方向跟着"往哪边切"，反着推会让人怀疑自己按错了 */
    fun slideForward(forward: Boolean, reduce: Boolean): ContentTransform =
        if (reduce) fadeOnly()
        else (slideInHorizontally(tween(Normal, easing = Emphasized)) { if (forward) it / 6 else -it / 6 } +
            fadeIn(tween(Fast))).togetherWith(
            slideOutHorizontally(tween(Normal, easing = EmphasizedExit)) { if (forward) -it / 6 else it / 6 } +
            fadeOut(tween(Fast)),
        )

    /** 换 Tab 那一档：轻微上推 + 淡入淡出 */
    fun tabChange(reduce: Boolean): ContentTransform =
        if (reduce) fadeOnly()
        else (slideInVertically(tween(Normal, easing = Emphasized)) { it / 12 } + fadeIn(tween(FadeOnly)))
            .togetherWith(slideOutVertically(tween(Normal, easing = EmphasizedExit)) { -it / 12 } + fadeOut(tween(FadeOnly)))

    /** 数值/颜色这类"值本身在动"的规格。减动效时压到最短，不压成 0 —— 0 会让数字凭空换 */
    fun tweenSpec(reduce: Boolean): FiniteAnimationSpec<Float> = tween(dur(Normal, reduce, min = 90), easing = Emphasized)
    fun colorSpec(reduce: Boolean): FiniteAnimationSpec<Color> = tween(dur(Normal, reduce, min = 90))

    /** 展开/收起统一用这一对。以前各处手写出入动画，四个地方四种时长 */
    fun expandIn(reduce: Boolean): EnterTransition =
        fadeIn(tween(if (reduce) FadeOnly else Fast)) +
            expandVertically(if (reduce) tween(0) else tween(Normal, easing = Emphasized))
    fun expandOut(reduce: Boolean): ExitTransition =
        fadeOut(tween(if (reduce) FadeOnly else Fast)) +
            shrinkVertically(if (reduce) tween(0) else tween(Normal, easing = EmphasizedExit))

    /** 时长都过这里。[min] 是"减动效也不许归零"的下限 */
    fun dur(ms: Int, reduce: Boolean, min: Int = 0): Int = if (reduce) min else ms

    private fun fadeOnly(): ContentTransform =
        fadeIn(tween(FadeOnly)) togetherWith fadeOut(tween(FadeOnly))
}

/**
 * 系统的"移除动画"。值由 [CarpetTheme] 用 [systemReduceMotion] 注进来，界面各处只读它，
 * 自己不查设置。默认 false：查不到（比如单测里的假 Context）就当动画开着。
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * 读系统的动画开关。这里只能用 `*_animation_scale`：`AccessibilityManager.isReduceMotionEnabled()`
 * 是 API 35 才有的，本工程 compileSdk 34，编译期就碰不到那个字段。
 * 三个 scale 都读 —— 厂商 ROM 把动画关掉时改的并不统一，只认一个会在某些机器上漏掉。
 */
@Composable
internal fun systemReduceMotion(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) {
        listOf(
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            Settings.Global.WINDOW_ANIMATION_SCALE,
        ).any { key ->
            runCatching { Settings.Global.getFloat(ctx.contentResolver, key, 1f) }.getOrDefault(1f) == 0f
        }
    }
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
    level: Level? = null,
    tone: Tone = Tone.Neutral,
    mock: Boolean = false,
    emphasis: Boolean = false,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // null = "这张卡不表态"，交给页面。卡片自己挑档，一屏就会同时出现好几张强调卡
    val lvl = level ?: LocalCardLevel.current
    val shape = if (lvl === Level.Raised) RoundedCornerShape(Round.Hero) else RoundedCornerShape(Round.Card)
    val bg = when (lvl) {
        Level.Plain -> Ink.Card
        Level.Raised -> Ink.Raise
        Level.Group -> Ink.Sunk
    }
    val box = modifier
        .fillMaxWidth()
        .background(bg, shape)
    val edged = if (lvl === Level.Raised) box.border(1.dp, Ink.Outline, shape) else box
    Box(
        modifier = edged.then(if (lvl === Level.Group) Modifier else Modifier.padding(Grid.Pad)),
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
                content()
                // 「示意数据」挂在**卡尾**，不挂卡头。它原来占掉第一行，
                // 于是左边那枚图标对齐的是标注、而不是标题 —— 每张桩数据卡的标题都比图标低一行。
                // 挪到末尾之后图标和标题重新对齐，标注仍然逐卡挂着，一个字没少
                if (mock) MockChip()
            }
        }
    }
}

/** 卡片的三档层级。见 [InkCard] */
enum class Level { Plain, Raised, Group }

/**
 * 页面替卡片挑的档。卡片不传 `level` 时读这个孔，所以"谁是本页的主问题"写在流里、
 * 不写在卡片里 —— 同一张卡换个场景未必还是主角，卡片自己说不了。
 */
val LocalCardLevel = staticCompositionLocalOf { Level.Plain }

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
            .then(if (onClick != null) Modifier.clickable(onClick = onClick).defaultMinSize(minHeight = 48.dp) else Modifier)
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
 * 空状态卡。**没有数据的时候，它只许占一行。**
 *
 * 这条是照着截图加的：「下次过境」在还没取到星历时，照样画了 104dp 的倒计时环、
 * 56dp 的弧线、72dp 的指北针 —— 一张四百多 dp 高的卡里只有三个 `--`。
 * 大图元是**为数据准备的**，没数据还画，就是把空态当内容渲染，
 * 整页因此显得既空又坏。有数了再换回带环的那版，一行代码的事。
 */
@Composable
fun EmptyCard(
    title: String,
    icon: ImageVector,
    hint: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    InkCard(modifier = modifier, icon = icon) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Grid.x12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Grid.x4),
            ) {
                Text(title, style = Type.Title)
                Text(hint, style = Type.Caption, color = Ink.TextLow)
            }
            if (action != null && onAction != null) GhostAction(action, onClick = onAction)
        }
    }
}

/**
 * 文字型次要动作。**不再画成一颗灰底大按钮**：一屏五颗按钮的时候，没有一颗是重点。
 * 但它的**手指区域仍然要到 48dp**（Material 的下限）—— "长得轻"和"点得着"是两件事，
 * 之前为了轻把它做成了一行 25dp 高的字，实测要很准地戳中间才按得到。
 */
@Composable
fun GhostAction(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clickable(onClick = onClick)
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .padding(horizontal = Grid.x8),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.Caption.copy(color = Ink.Sat, fontWeight = FontWeight.SemiBold))
    }
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
 * 顶部状态栏。**一条栏，三段，不三枚胶囊。**
 *
 * 上一版是三枚各自带底色的 pill，三枚 pill 之间的间距和卡片间距一模一样 ——
 * 于是它在屏幕上读起来就是"三张卡"，首屏凭空多出三个方块。现在合成一根背板、
 * 段与段之间一根 [Ink.Track] 细线：一个视觉单元，回答一个问题。
 *
 * **没数据用 [Tone.Neutral]，画空心圈。** 之前"未连 / -- / 没跑"三格全上琥珀 [Ink.Wait]，
 * 而 Wait 在这套色里是"正在等一个已知会来的东西"，用户看到的是"三处告警"。
 * 没连上和正在等本来就不该一个颜色，形上也分开：实心=有状态，空心=还不知道。
 *
 * **段按权重分宽度、装不下只走省略号，绝不折行。** Row 不换行，非权重的子节点只能按
 * 自身宽度量：411dp（Pixel 口径）上三枚芯片 + 一颗 SOS 刚好塞满，我据此以为没问题；
 * 384dp（华为 Mate60）上余量一负，被挤的那一枚就把字**竖着拆成两列**（真机照片第一条）。
 * 只按一种视口自测，等于没测。
 */
@Composable
fun StatusRail(items: List<RailItem>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Ink.Sunk, RoundedCornerShape(Round.Pill))
            .padding(horizontal = Grid.x12, vertical = Grid.x8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, item ->
            if (i > 0) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = Grid.x12)
                        .width(1.dp)
                        .height(16.dp)
                        .background(Ink.Track),
                )
            }
            Row(
                modifier = Modifier.weight(1f, fill = false),
                horizontalArrangement = Arrangement.spacedBy(Grid.x8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 点不缩，标签不缩，缩的只可能是那个"值"—— 它是这行里唯一可推断的信息
                Dot(item.tone)
                Text(
                    item.label,
                    style = Type.Caption.copy(color = Ink.TextHigh, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
                item.value?.let {
                    Text(
                        it,
                        style = Type.Caption,
                        color = if (item.tone === Tone.Neutral) Ink.TextLow else item.tone.color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
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
    val shape = RoundedCornerShape(Round.Chip)
    Box(
        modifier = modifier
            // 未选中改成"描边、不填底"：填了 Ink.Track 之后这一排比卡片本身还抢眼，
            // 而 M3 的 assist/filter chip 未选中态就是一根描边。选中仍是实底，对比度那条不变
            .then(
                if (selected) Modifier.background(Ink.Sat, shape)
                else Modifier.border(1.dp, Ink.Outline, shape),
            )
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = Grid.x12, vertical = Grid.x8),
        // 撑到 48dp 之后必须把字重新居中：Box 默认 TopStart，不写这一行字就贴着上沿，
        // 下面空出一截，看着像控件没画完
        contentAlignment = Alignment.Center,
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
    val reduce = LocalReduceMotion.current
    var open by rememberSaveable(label) { mutableStateOf(expandedByDefault) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .defaultMinSize(minHeight = 48.dp),
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
        AnimatedVisibility(visible = open, enter = Motion.expandIn(reduce), exit = Motion.expandOut(reduce)) {
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

/**
 * 语义点。§5.2 第 4 条：形要有语义，所以这个点只用来标状态，不当装饰。
 * **[Tone.Neutral] 画空心圈**：实心=系统知道现在是什么状态，空心=还不知道。
 * 上一版"没连上"和"正在等"都是实心黄点，用户读到的是三处故障。
 */
@Composable
fun Dot(tone: Tone, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val c by animateColorAsState(tone.color, Motion.colorSpec(reduce), label = "dot")
    if (tone === Tone.Neutral) {
        Box(
            modifier = modifier
                .size(Grid.x8)
                .border(1.5.dp, Ink.TextLow, CircleShape),
        )
    } else {
        Box(
            modifier = modifier
                .size(Grid.x8)
                .background(c, CircleShape),
        )
    }
}

/**
 * 「示意数据」标。**必须一直挂着，但它可以很轻** ——
 * 之前它是一枚带底的 12.5sp 琥珀砖，一页出现三次，比它标注的数字还抢眼，
 * "诚实标注"这条要求反过来变成了噪声。现在去掉底色、降到 11sp，只留一颗点：
 * 读得见，但不抢。文字一个字没减，"未接真源"这半句恰恰是最要紧的那半。
 */
@Composable
fun MockChip() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Grid.x4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(5.dp).background(Ink.Wait, CircleShape))
        Text(
            "示意数据 · 未接真源",
            style = Type.Overline.copy(color = Ink.Wait, letterSpacing = 0.2.sp, lineHeight = 14.sp),
        )
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
fun CardTitle(
    text: String,
    trailing: String? = null,
    trailingAction: (() -> Unit)? = null,
    icon: ImageVector? = null,
    tone: Tone = Tone.Neutral,
) {
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
        trailing?.let { t ->
            // 同一个槽位里，"状态"和"能按的动作"必须长得不一样：
            // 之前"记一次车位"和"熄火停车"都是灰字，用户只能靠试才知道哪个能按。
            // 动作那一只 clickable 写在 padding 外面，撑出来的空白也算触摸区（48dp 下限）
            if (trailingAction == null) Text(t, style = Type.Caption, color = Ink.TextLow)
            else Box(
                modifier = Modifier
                    .clickable(onClick = trailingAction)
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(t, style = Type.Caption, color = Ink.Sat, modifier = Modifier.padding(horizontal = Grid.x4))
            }
        }
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
    val reduce = LocalReduceMotion.current
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
                AnimatedContent(targetState = value, transitionSpec = { Motion.slideForward(true, reduce) }, label = "hero") { t ->
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
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        // 标签 12.5、值 15：行高统一到值的 21，两个字号才落在同一条基线带上。
        // 行距交给卡片的 Column 管，这里不再自己加 padding
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Type.Caption.copy(lineHeight = 21.sp), modifier = Modifier.padding(end = Grid.x12))
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
 *
 * **这个环只留给"整圈就是全部"的场景**（SOS 那三秒：0→100% 每一步都对应一个动作）。
 * 过境倒计时原来是画在它上面的，12 分钟的视野里"还剩 9 分"只有 6% 的弧，
 * 真机照片里那根细绿弧看着像仪表坏了 —— 已改到 [PassWindowCard] 的时间条上。
 *
 * **中间那串数字一行装得下才算数**：原来是 [Type.Hero]（24sp 比例字体）写"9 分 21 秒"，
 * 104dp 的圆里量不到那么宽，于是折成两行、孤零零一个"秒"字压在环的描边上（真机照片第二条）。
 * 现在换成 [Type.Numeric]（等宽、1Hz 跳秒不抖行）+ maxLines=1 硬约束：
 * 装不下宁可裁，绝不让它拆行把卡撑歪。
 */
@Composable
fun CountdownRing(
    fraction: Float,
    center: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    sub: String? = null,
) {
    val reduce = LocalReduceMotion.current
    // 1Hz 的跳变直接画会一格一格地抖，动一下眼睛才读得出"在倒计时"而不是"数字变了"
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), Motion.tweenSpec(reduce), label = "ring")
    val c by animateColorAsState(tone.color, Motion.colorSpec(reduce), label = "ringColor")
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
        // 内径 = 104 - 2×(线宽 6 + 留白 6) ≈ 76dp：给这串数字的实际可用宽度就是这个数，
        // "9:21" 四个等宽字符 24sp 约 58dp，留得下；再长就会被裁，所以调用方给的是钟表写法
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Grid.x4),
        ) {
            Text(
                center,
                style = Type.Numeric.copy(color = c),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
            sub?.let {
                Text(
                    it,
                    style = Type.Caption,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(88.dp),
                )
            }
        }
    }
}

/**
 * 朝向指引。**点 = 该把天线/车头朝哪边**，正北 0° 顺时针（aim_az 的口径）。
 *
 * 指针**从 hub 往外画，不穿过圆心**：上一版是从圆心拉一条 4dp 粗的线到边上，
 * 而中间那枚 "N45°" 正好钉在圆心上 —— 真机照片里那条线把自家标签切成两半，
 * 这张卡最要紧的两个信息（方向、度数）互相盖住了。现在圆心让给文字，指针只做指针。
 *
 * 尺寸从 72dp 降到 56dp：它旁边已经写了"朝 45°（东北）"，一个 56dp 的盘足够说明方位，
 * 再大就是抢整张卡的注意力。
 */
@Composable
fun AimDial(azimuthDeg: Double, tone: Tone = Tone.Sat, modifier: Modifier = Modifier) {
    val c = if (tone === Tone.Neutral) Ink.TextMid else tone.color
    Box(modifier = modifier.size(56.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val hub = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f - 5.dp.toPx()
            drawCircle(color = Ink.Track, radius = r, center = hub, style = Stroke(width = 2.dp.toPx()))
            // 正北那根刻度：没有它，"盘上那根线"和"北偏东 45°"之间要靠用户自己推
            drawLine(
                color = Ink.Outline,
                start = Offset(hub.x, hub.y - r),
                end = Offset(hub.x, hub.y - r + 5.dp.toPx()),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            val a = Math.toRadians(azimuthDeg - 90.0).toFloat()
            val ux = cos(a)
            val uy = sin(a)
            // 从 0.34r 起画：圆心那格留给标签，指针尾部不越过去
            drawLine(
                color = c,
                start = Offset(hub.x + r * 0.34f * ux, hub.y + r * 0.34f * uy),
                end = Offset(hub.x + r * ux, hub.y + r * uy),
                strokeWidth = 3.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        Text(
            "${azimuthDeg.toInt()}°",
            style = Type.Caption.copy(color = c, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
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
    val reduce = LocalReduceMotion.current
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), Motion.tweenSpec(reduce), label = "bar")
    val c by animateColorAsState(tone.color, Motion.colorSpec(reduce), label = "barColor")
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

/**
 * 占比分段条。**加起来是 100% 的几项，画一根分段条，不画几根独立进度条** ——
 * 能耗那张卡原来就是三根：驱动 78% 一根、空调 14% 一根、待机 8% 一根，
 * 后两根在截图里看着像画坏了的空槽，而且三根条并不能让人读出"这是一整体被分成三份"。
 */
@Composable
fun StackedBar(parts: List<Pair<Int, Color>>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Grid.x8)
            .clip(RoundedCornerShape(Grid.x4)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        parts.forEach { (v, c) ->
            if (v > 0) Box(modifier = Modifier.weight(v.toFloat()).fillMaxHeight().background(c))
        }
    }
}

/** 分段条的图例：一颗点 + 名字 + 数。点和条上的颜色一一对应，不另设第二套色 */
@Composable
fun LegendDot(color: Color, label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Grid.x4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(6.dp).background(color, CircleShape))
        Text("$label $value", style = Type.Caption, color = Ink.TextMid)
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
