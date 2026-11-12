package com.carpet.satdemo.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.carpet.satdemo.core.protocol.UiEmotion

/**
 * 手机上的那只形象 —— **占位版：直接用屏上那只**。
 *
 * 内部演示，老板拍板"先别管版权"，所以这里把上游 Muse 固件圆屏上的默认角色
 * （muse_pixel.c 渲染出的 9 档规范姿态）逐像素烘焙进来，保证和圆屏上那个
 * 一模一样。版权没拿到（docs/AVATAR.md §5 红线），**对外发布前必须整体回退**：
 * MuseAvatarData.kt + 本文件 + PixelAvatarRenderTest.kt，换回自研角色。
 *
 * 契约不变：`avatarPixels()` 还是 64×64 ARGB 的纯函数，测试不碰 Android 类；
 * `dim = !live` 还是"未接入不许亮语义色"；`PixelAvatar(...)` 的入参和
 * ScreenMirror 的两个调用点都没动。
 */
private const val G = MuseAvatarData.G

/** 契约那 7 档 → 屏上角色的状态。映射对着 docs/AVATAR.md §3 那张表 */
internal fun poseFor(emotion: UiEmotion?): IntArray = when (emotion) {
    UiEmotion.neutral -> MuseAvatarData.IDLE
    UiEmotion.happy -> MuseAvatarData.HAPPY
    UiEmotion.excited -> MuseAvatarData.LISTENING // 圆屏没有"更开心"一档，竖耳蓝光是最接近的"兴奋警觉"
    UiEmotion.sad -> MuseAvatarData.OFF_DIMMED // OFF 网格辉光灰化：闭眼垂着，连微光都熄了才是"蔫"
    UiEmotion.worried -> MuseAvatarData.ERROR // 警觉脸：皱眉+红光
    UiEmotion.sleepy -> MuseAvatarData.OFF
    UiEmotion.thinking -> MuseAvatarData.THINKING
    null -> MuseAvatarData.IDLE
}

/** 状态档里"会发光"的语义色。dim 判定直接从像素颜色反查，不维护第二张表 */
internal val SCHEME: Set<Int> = MuseAvatarData.SCHEME_COLORS

/** 剪影/五官/底噪这些与状态无关的结构色：dim 时保留它们，让形象仍可辨认 */
internal val STRUCT: Set<Int> = MuseAvatarData.PALETTE
    .filter { it != 0 }
    .filter { !SCHEME.contains(it) }
    .toSet()

/** dim 时语义色的替换灰（保明暗层次，角色还能读出来） */
private fun grey(c: Int): Int {
    val idx = MuseAvatarData.PALETTE.indexOf(c)
    return if (idx >= 0) MuseAvatarData.PALETTE_FULL[MuseAvatarData.greyIndex(c)] else c
}

/** 逐帧等价物：查表出 64×64 ARGB。纯函数，不碰 Android 类 */
internal fun avatarPixels(state: IntArray, dim: Boolean = false): IntArray {
    val out = IntArray(G * G)
    for (i in 0 until G * G) {
        val c = MuseAvatarData.PALETTE_FULL[state[i]]
        if (c == 0) continue
        out[i] = if (dim && SCHEME.contains(c)) grey(c) else c
    }
    return out
}

@Composable
fun PixelAvatar(emotion: UiEmotion?, modifier: Modifier = Modifier, diameter: Dp = 120.dp, live: Boolean = true) {
    val bmp = remember(emotion, live) {
        Bitmap.createBitmap(avatarPixels(poseFor(emotion), dim = !live), G, G, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Canvas(modifier = modifier.size(diameter)) {
        drawImage(
            image = bmp,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = FilterQuality.None,
        )
    }
}
