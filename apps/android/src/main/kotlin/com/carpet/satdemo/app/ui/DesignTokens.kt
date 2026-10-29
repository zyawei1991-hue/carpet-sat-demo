package com.carpet.satdemo.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * §5.2 六条设计语言里第一条和第二条的代码化。**底色只有两档，彩色只用来表达状态语义** ——
 * 所以这里不允许出现"装饰用"的颜色，也不允许往组件里传任意 Color 值：
 * 要新加一个颜色，先回答它对应哪个状态语义，答不上来就不该有。
 */
object Ink {
    /** 窗口底 */
    val Deep = Color(0xFF0E1116)

    /** 卡片底。和 Deep 只差一档明度，层级靠字重和留白承担（§5.2 第 3 条：不靠描边和阴影） */
    val Card = Color(0xFF161B22)

    /** 轨道、分隔这类"结构性"的灰，不参与语义色 */
    val Track = Color(0xFF21262D)

    val TextHigh = Color(0xFFE6EDF3)
    val TextMid = Color(0xFF8B949E)
    val TextLow = Color(0xFF6E7681)

    /** 绿=正常 */
    val Ok = Color(0xFF2EA043)

    /** 黄=等待 */
    val Wait = Color(0xFFD29922)

    /** 红=SOS / 告警。只有真出事才允许用它 */
    val Alert = Color(0xFFDA3633)

    /** 蓝=卫星链路 */
    val Sat = Color(0xFF388BFD)
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

/** §5.2 第 6 条：间距只取 4 的倍数，不留"看起来差不多"的自定义值 */
object Grid {
    val x4: Dp = 4.dp
    val x8: Dp = 8.dp
    val x12: Dp = 12.dp
    val x16: Dp = 16.dp
    val x24: Dp = 24.dp
    val x32: Dp = 32.dp
}

/** §5.2 第 3 条：字号三档、字重两档，没有第四种。**V1.7 只做了一处放宽：Display** ——
 *  首页两个主区各要回答一个大问题（"连没连上""还要多久"），15sp 的正文撑不起"一屏一重心"。
 *  Display 只允许出现在 [HeroStat] 上，一屏最多两个；正文/说明仍旧只有 Body 和 Caption 两档。 */
object Type {
    val Display = TextStyle(
        fontSize = 32.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.SemiBold,
        color = Ink.TextHigh,
    )
    val Hero = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
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
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Normal,
        color = Ink.TextMid,
    )

    /** 线路原文、坐标这类东西必须等宽，否则一位小数错位会被读成数字变了 */
    val Mono = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = FontFamily.Monospace,
        color = Ink.TextMid,
    )
}

@Composable
fun CarpetTheme(content: @Composable () -> Unit) {
    // 只借 Material 3 的深色装配（ripple、控件底色走 surface），
    // 语义色不让它动态取：主色板跟着手机壁纸变，"红"就不再只等于出事了（§5.2 第 2 条）
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ink.Sat,
            background = Ink.Deep,
            surface = Ink.Card,
            onBackground = Ink.TextHigh,
            onSurface = Ink.TextHigh,
            error = Ink.Alert,
        ),
        content = content,
    )
}
