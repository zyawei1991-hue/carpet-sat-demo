package com.carpet.satdemo.app.ui

import androidx.compose.material.ripple.LocalRippleTheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material.ripple.RippleTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * §5.2 设计语言的代码化。**V1.10 这一版是按算出来的数字重做的，不是审美微调。**
 *
 * 上一版"乱"的三条成因全部可以量化（`tools/contrast_ladder.py` 算 WCAG 对比度，
 * 结论由 `ContrastLadderTest` 钉住，谁改色号谁就得先过这条尺子）：
 *
 *  1. **相邻底色只有 1.09:1 / 1.14:1**。深黑底下这个差肉眼读不出来，
 *     于是"卡片"和"卡里的块"看着是同一层，六张卡糊成一堵墙。现在页面→卡拉开到 **1.33:1**，
 *     并且**一屏最多用三层**（页面 / 卡 / 强调卡），层级一多就又回到猜。
 *  2. **说明文字 `TextLow` 在卡底上 3.77:1、告警红 3.75:1**，都低于 WCAG AA 正文的 4.5:1 ——
 *     而这两支颜色承担的恰恰是"下一步该干什么"。现在所有文字色在所有底色上都 **≥4.5**。
 *  3. 卡片标题与正文**同为 15sp**，层级只剩字重。现在补了 18sp 的 [Type.Title] 和 11sp 的
 *     [Type.Overline]，阶梯固定成 11 / 12.5 / 15 / 18 / 24 / 34（比值稳定在 1.2 上下）。
 *
 * 还有一条纪律上的更正：**允许描边了**。上一版写的是"不靠描边和阴影"，等于把层级全压在明度差上，
 * 而明度差在深色下天然拉不开 —— 那条原则本身把自己绑死在走不通的路上。现在结构靠
 * **明度阶梯 + 强调卡的一根描边**，语义仍然只靠颜色（§5.2 第 2 条没变：彩色只表达状态，不做装饰）。
 */
object Ink {
    /** 窗口底 */
    val Deep = Color(0xFF080B10)

    /** 分组背板：比窗口底略亮，专门用来"托"一组行，本身不当卡片读 */
    val Sunk = Color(0xFF11161E)

    /** 普通卡底。和页面拉开 1.33:1，这是"浮起来"的下限 */
    val Card = Color(0xFF212834)

    /** 强调卡底 / 输入框底。一屏只允许极少数块用到 */
    val Raise = Color(0xFF2A3340)

    /** 轨道、进度槽、分隔线这类结构灰，不参与语义色 */
    val Track = Color(0xFF364051)

    /** 描边。**只画在强调卡上**，普通卡不描边 —— 全屏都是框就等于没有层级 */
    val Outline = Color(0xFF455468)

    val TextHigh = Color(0xFFF2F5F9)

    /** 次要文字、值的本色 */
    val TextMid = Color(0xFFB3BFCE)

    /** 说明文字。在卡底和强调卡底上都必须过 4.5:1，上一版就是在这里失的职 */
    val TextLow = Color(0xFF94A1B4)

    /** 绿=正常 */
    val Ok = Color(0xFF3FB950)

    /** 黄=等待 */
    val Wait = Color(0xFFE3B341)

    /** 红=SOS / 告警。只有真出事才允许用它；上一版 0xDA3633 在卡底上只有 3.75:1 */
    val Alert = Color(0xFFFF6B63)

    /** 蓝=卫星链路，同时也是唯一的动作色（按钮、链接、选中） */
    val Sat = Color(0xFF58A6FF)

    /**
     * 语义色的"淡底"系数（徽标、图标座）。**统一走这一个数**，
     * 不许各处手写 alpha —— 手写的那些从来没算过对比度。
     */
    const val TINT = 0.16f
}

/** 语义色 + 它的说法。UI 上不许绕开这张表自己挑颜色 */
enum class Tone(val color: Color, val label: String) {
    Ok(Ink.Ok, "正常"),
    Wait(Ink.Wait, "等待"),
    Alert(Ink.Alert, "告警"),
    Sat(Ink.Sat, "卫星链路"),
    Neutral(Ink.TextMid, "—"),
    ;

    companion object {
        fun of(ok: Boolean, wait: Boolean = false): Tone = if (ok) Ok else if (wait) Wait else Alert
    }
}

/**
 * 间距。**留白是这一版主要的层级手段**，所以按用途分档、不许混用：
 * [Page] 页面左右留白，[Group] 组与组，[Card] 卡与卡，[Inner] 卡内行与行，[Pad] 卡内边距。
 * 上一版这些位置全是 16，组和卡读起来是同一层 —— 那正是"一摞同权重方块"的来源。
 */
object Grid {
    val x4: Dp = 4.dp
    val x8: Dp = 8.dp
    val x12: Dp = 12.dp
    val x16: Dp = 16.dp
    val x24: Dp = 24.dp
    val x32: Dp = 32.dp

    val Page = 16.dp
    val Group = 28.dp
    val Card = 14.dp
    val Inner = 10.dp
    val Pad = 18.dp
}

/** 圆角分两档就够：普通卡 20、强调卡 26。行内元素 8。再多一档就又成噪声 */
object Round {
    val Card = 20.dp
    val Hero = 26.dp
    val Chip = 8.dp

    /** 顶部状态条那种胶囊：圆角等于半个高，它才读得出是"一枚状态"而不是一块方格 */
    val Pill = 20.dp
}

/**
 * 字号阶梯。**[Display] 一屏最多一个** —— 上一版给"圆屏"和"对话"两个主区各摆了一个 34sp 的数，
 * 两个重心等于没有重心，这也是"一眼看不出主次"最直接的一条。
 * 数字（连接几台、还剩几秒）用 [Hero]/[Numeric]，[Display] 只留给屏上那一个主问题。
 */
object Type {
    /** 组标题：小字 + 加字距 + 中灰，负责快速扫读时的锚点 */
    val Overline = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.1.sp,
        color = Ink.TextMid,
    )
    val Display = TextStyle(
        fontSize = 34.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.SemiBold,
        color = Ink.TextHigh,
    )
    val Hero = TextStyle(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
        color = Ink.TextHigh,
    )

    /** 等宽只给数字：1Hz 刷新的秒数用比例字体会抖行 */
    val Numeric = TextStyle(
        fontSize = 24.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace,
        color = Ink.TextHigh,
    )

    /** 卡片标题。比正文大 3sp、重一档，扫读时第一个抓到的就是它 */
    val Title = TextStyle(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
        color = Ink.TextHigh,
    )
    val Body = TextStyle(
        fontSize = 15.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Normal,
        color = Ink.TextHigh,
    )
    val Caption = TextStyle(
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Normal,
        color = Ink.TextMid,
    )

    /** 线路原文、坐标这类东西必须等宽，否则一位小数错位会被读成数字变了 */
    val Mono = TextStyle(
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = FontFamily.Monospace,
        color = Ink.TextMid,
    )
}

/**
 * 按压反馈的颜色。
 *
 * Material 3 的默认 ripple 取 `colorScheme.primary`，而这套色板的主色是卫星青 ——
 * 于是每一次点按都在溅一下强调色，全站几十处。强调色只该出现在**数据、状态、以及那一颗主按钮**上，
 * 出现满屏就等于哪儿都没有重点（taste-skill app-screens §13）。
 * 换成一档很淡的白：它的作用是"我按到了"，不是"这是什么品牌"。
 */
internal object InkRippleTheme : RippleTheme {
    @Composable
    override fun defaultColor(): Color = Color.White

    @Composable
    override fun rippleAlpha(): RippleAlpha = RippleAlpha(0.12f, 0.10f, 0.10f, 0.08f)
}

@Composable
fun CarpetTheme(content: @Composable () -> Unit) {
    // 系统的"移除动画"在这里进 CompositionLocal，只有这一处读设置。
    // Material 的规定：减动效是把位移和尺寸收掉，淡入和变色留着 —— 那是"我按到了"的证据
    val reduceMotion = systemReduceMotion()
    // 只借 Material 3 的深色装配（ripple、控件底色走 surface），
    // 语义色不让它动态取：主色板跟着手机壁纸变，"红"就不再只等于出事了（§5.2 第 2 条）
    CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
        // ripple 的接管必须写在 MaterialTheme **里面**：它自己会再 provide 一枚
        // MaterialRippleTheme（默认色就是 colorScheme.primary），写在外面直接被它盖掉。
        // 这条由 FeedbackStatesRenderTest 钉住 —— 那种"看着改了、其实没生效"的改动没人会再看第二遍
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Ink.Sat,
                onPrimary = Color(0xFF061018),
                secondary = Ink.Sat,
                background = Ink.Deep,
                surface = Ink.Card,
                surfaceVariant = Ink.Raise,
                outline = Ink.Outline,
                onBackground = Ink.TextHigh,
                onSurface = Ink.TextHigh,
                onSurfaceVariant = Ink.TextMid,
                error = Ink.Alert,
            ),
        ) {
            CompositionLocalProvider(LocalRippleTheme provides InkRippleTheme) { content() }
        }
    }
}
