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
import kotlin.math.abs

/**
 * 手机上的那只形象。照的是 Muse 的形象**格式**，不是 Meta 的那个角色 ——
 * 上游 `AVATAR_RECIPE.md` 第 25–27 行原话："The default avatar is Meta's character,
 * and the Apache License doesn't grant rights to it." 能借的是那套语言：64×64 网格、
 * 按语义命名的调色板、4×4 Bayer 有序抖动、剪影 1px 硬描边、左上光源、按状态染色的边缘光、
 * 还有 PER-MODE SCHEMES 那七档模式色。角色本身是我们自己的（docs/AVATAR.md §1）。
 *
 * 为什么用位图不用矢量：两边像不像，凭的是同一张网格和同一份色表，不是"感觉差不多"。
 * 屏那侧 `muse_pixel.c` 就是在 64×64 上逐像素画的，这里逐像素算完再按整数倍硬放大，不做平滑。
 *
 * **这一版是静帧**。镜像的用途是抬一眼看出屏现在什么情绪，而情绪是 1 Hz 那一帧里的慢变量；
 * 眨眼和浮动是屏那侧 25fps 的事。要接动画得先解决 README §5.4 那条口型抢占，
 * 不该在这里先塞一个每帧重写 4096 个像素的计时器。
 */
private const val G = 64

/** 本体四档浅石板。角色定在 docs/AVATAR.md §1，改这里要连着改那份文档 */
private val BODY = intArrayOf(0xFFE6EEF8.toInt(), 0xFFC9D4E3.toInt(), 0xFF8FA3BF.toInt(), 0xFF5A6B85.toInt())
private const val OUT = 0xFF05070A.toInt()
private const val PANEL_L = 0xFF1B2330.toInt()
private const val PANEL_D = 0xFF10161F.toInt()
private const val EYE = 0xFFEAF2FF.toInt()
private const val MOUTH = 0xFFDCE6F5.toInt()
private const val BLUSH = 0xFFFF8FA3.toInt()
/** 心是自己的颜色，不跟着状态色走：64px 上"飘着两颗粉的"才读得出是被摸了 */
private const val HEART_C = 0xFFFF5C7A.toInt()

/**
 * 上游 `avatar_prompt.md` 里 PER-MODE SCHEMES 那张表（4 级辉光：亮→深，加一支 accent）。
 * 抄它是因为它就是 Muse 界面围绕形象用的那套语言；两边各调一套就不可能像。
 * 没用的 BOOT/LISTENING/SPEAKING/ERROR 先不留 —— 手机上这四档不该由情绪枚举来演。
 */
internal val SCHEME = mapOf(
    "idle" to (intArrayOf(0xFFF4E8FF.toInt(), 0xFFC7A4FF.toInt(), 0xFF9A6BFF.toInt(), 0xFF5B3FD9.toInt()) to 0xFFA77DFF.toInt()),
    "thinking" to (intArrayOf(0xFFFFE6FF.toInt(), 0xFFFF9CF0.toInt(), 0xFFD35BFF.toInt(), 0xFF7A2BD9.toInt()) to 0xFFE07BFF.toInt()),
    "off" to (intArrayOf(0xFFD8D4FF.toInt(), 0xFF8F86D9.toInt(), 0xFF5A4FB0.toInt(), 0xFF2E2870.toInt()) to 0xFF7C72D0.toInt()),
)

internal enum class Eyes { bead, arc, closed, half, wide, up }
internal enum class Mouth { smile, grin, flat, wavy, open, hmm }

internal data class AvatarPose(
    val eyes: Eyes,
    val mouth: Mouth,
    val scheme: String,
    val hearts: Int = 0,
    val zzz: Int = 0,
    val dots: Boolean = false,
    val sweat: Boolean = false,
    val droop: Boolean = false,
    /** 离地几像素。最多 2：再多头顶那截天线就顶出 64 的边 */
    val hop: Int = 0,
    val sparkles: Int = 2,
)

/** 契约那 7 档 → 姿势。映射与 `firmware/screen/muse/README.md` §5.4 同一张表 */
internal fun poseFor(emotion: UiEmotion?): AvatarPose = when (emotion) {
    UiEmotion.neutral -> AvatarPose(Eyes.bead, Mouth.smile, "idle")
    UiEmotion.happy -> AvatarPose(Eyes.arc, Mouth.grin, "idle", hearts = 2, hop = 1, sparkles = 3)
    UiEmotion.excited -> AvatarPose(Eyes.arc, Mouth.open, "idle", hearts = 3, hop = 2, sparkles = 5)
    UiEmotion.sad -> AvatarPose(Eyes.half, Mouth.flat, "idle", droop = true, sparkles = 1)
    UiEmotion.worried -> AvatarPose(Eyes.wide, Mouth.wavy, "thinking", sweat = true)
    UiEmotion.sleepy -> AvatarPose(Eyes.closed, Mouth.flat, "off", zzz = 2, sparkles = 0)
    UiEmotion.thinking -> AvatarPose(Eyes.up, Mouth.hmm, "thinking", dots = true, sparkles = 3)
    // null = 这一帧不带情绪，落回中性，不是保持上一帧（§5.4 那条老坑）
    null -> AvatarPose(Eyes.bead, Mouth.smile, "idle")
}

private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

private fun bayer(x: Int, y: Int): Float = (BAYER[(y and 3) * 4 + (x and 3)] + 0.5f) / 16f

private fun pow3(v: Float) = v * v * v

private fun se(x: Float, y: Float, cx: Float, cy: Float, w: Float, h: Float): Boolean =
    pow3(abs((x - cx) / w)) + pow3(abs((y - cy) / h)) <= 1f

/** 脸板用正椭圆：超椭在 10×8 这种尺寸上角是方的，64px 下就成了一块护目镜 */
private fun ell(x: Float, y: Float, cx: Float, cy: Float, w: Float, h: Float): Boolean {
    val dx = (x - cx) / w
    val dy = (y - cy) / h
    return dx * dx + dy * dy <= 1f
}

private fun disc(x: Float, y: Float, cx: Float, cy: Float, r: Float): Boolean {
    val dx = x - cx
    val dy = y - cy
    return dx * dx + dy * dy <= r * r
}

private val HEART = arrayOf(".X.X.", "XXXXX", "XXXXX", ".XXX.", "..X..")
private val ZED = arrayOf("XXXX", "..XX", ".XX.", "XXXX")
private val DROP = arrayOf(".X.", ".XX.", "XXXX")
private val SPARK = arrayOf(".X.", "XXX", ".X.")

private const val DECOR = 10
private const val HEARTP = 11

/**
 * 逐像素画一遍，返回 64×64 的 ARGB。**纯函数，不碰 Android 类** ——
 * 截图 harness 于是能直接拿它出七档对照表，不用等 Robolectric 把 Bitmap 喂出来。
 */
internal fun avatarPixels(pose: AvatarPose, dim: Boolean = false): IntArray {
    val (glow, accent) = SCHEME.getValue(pose.scheme)
    val part = ByteArray(G * G)
    // hop 是整只往上跳：下面每一个 y 都从 cy 推，不许写死 —— 写死了跳一下脚就留在地上，
    // 天线还会顶出画布。hop 最多 2px，再多头顶那截天线就被裁掉了
    val cy = 33f - pose.hop
    val earCy = if (pose.droop) cy - 17f else cy - 23f
    val earCx = if (pose.droop) 21.5f else 23.5f
    val panelCy = cy - 3f
    val mouthCy = cy + 1.5f
    val footCy = cy + 23f
    val tipCy = cy - 26.5f

    for (y in 0 until G) {
        for (x in 0 until G) {
            val fx = x + 0.5f
            val fy = y + 0.5f
            var p = 0
            when {
                disc(fx, fy, 32f, tipCy, 2.2f) -> p = 6
                abs(fx - 32f) < 0.7f && fy > tipCy + 2f && fy < cy - 21f -> p = 7
                disc(fx, fy, earCx, earCy, 4.2f) || disc(fx, fy, 64f - earCx, earCy, 4.2f) -> p = 2
                ell(fx, fy, 32f, panelCy, 10.5f, 7.6f) -> p = 3
                se(fx, fy, 32f, cy, 17f, 23f) -> p = 1
                disc(fx, fy, 25.5f, footCy, 3.4f) || disc(fx, fy, 38.5f, footCy, 3.4f) -> p = 4
            }
            if (p == 3) {
                val left = fx < 32f
                val ex = if (left) 27.2f else 36.8f
                p = if (
                    when (pose.eyes) {
                        Eyes.bead -> disc(fx, fy, ex, panelCy, 2.1f)
                        Eyes.arc -> disc(fx, fy, ex, panelCy, 2.6f) && !disc(fx, fy, ex, panelCy + 1.4f, 2.4f)
                        Eyes.closed -> abs(fy - panelCy) < 0.8f && abs(fx - ex) < 2.4f
                        Eyes.half -> disc(fx, fy, ex, panelCy - 0.6f, 2.1f) && fy >= panelCy - 0.6f
                        Eyes.wide -> disc(fx, fy, ex, panelCy, 2.8f)
                        Eyes.up -> disc(fx, fy, ex, panelCy - 1.3f, 2.0f)
                    }
                ) 5 else if (
                    when (pose.mouth) {
                        Mouth.smile -> disc(fx, fy, 32f, mouthCy - 1f, 2.6f) && fy >= mouthCy &&
                            !disc(fx, fy, 32f, mouthCy - 2.2f, 2.4f)
                        Mouth.grin -> se(fx, fy, 32f, mouthCy + 0.4f, 3.2f, 1.9f) && fy >= mouthCy - 0.6f
                        Mouth.flat -> abs(fy - mouthCy) < 0.7f && abs(fx - 32f) < 2.4f
                        Mouth.wavy -> abs(fy - (mouthCy + if (((x + y) and 1) == 0) -0.6f else 0.6f)) < 0.6f &&
                            abs(fx - 32f) < 2.8f
                        Mouth.open -> se(fx, fy, 32f, mouthCy, 1.9f, 2.4f)
                        Mouth.hmm -> se(fx, fy, 33.4f, mouthCy, 1.6f, 1.0f)
                    }
                ) 8 else p
            }
            part[y * G + x] = p.toByte()
        }
    }

    // 腮红压在身体上、脸板外；装饰全部是 2–5px 的图章，64×64 上大了就糊住脸
    for (side in intArrayOf(20, 43)) {
        for (dy in -2..2) for (dx in -2..2) {
            val x = side + dx
            val y = (cy + 2.5f).toInt() + dy
            if (x in 0 until G && y in 0 until G && part[y * G + x] == 1.toByte() &&
                disc(x + 0.5f, y + 0.5f, side.toFloat(), cy + 2.5f, 1.9f)
            ) part[y * G + x] = 9
        }
    }
    stamp(part, pose.hearts >= 1, 13, 18, HEART, HEARTP)
    stamp(part, pose.hearts >= 2, 49, 14, HEART, HEARTP)
    stamp(part, pose.hearts >= 3, 8, 9, HEART, HEARTP)
    stamp(part, pose.zzz >= 1, 46, 14, ZED)
    stamp(part, pose.zzz >= 2, 52, 8, ZED)
    stamp(part, pose.sweat, 44, 23, DROP)
    if (pose.dots) {
        blob(part, 45f, 24f, 1.1f)
        blob(part, 49f, 20f, 1.1f)
        blob(part, 53f, 16f, 1.1f)
    }
    val spk = intArrayOf(52, 26, 11, 30, 55, 40, 9, 44, 44, 5)
    repeat(pose.sparkles.coerceAtMost(spk.size / 2)) { i ->
        stamp(part, true, spk[i * 2], spk[i * 2 + 1], SPARK)
    }

    val out = IntArray(G * G)
    for (y in 0 until G) {
        for (x in 0 until G) {
            val p = part[y * G + x].toInt()
            if (p == 0) continue
            val u = (x + 0.5f - 32f) / 17f
            val v = (y + 0.5f - 33f) / 23f
            // 耳朵和脚在身体后面，压暗一档，不然跟本体同色就糊成一坨（上游那条"重叠处要接缝"）
            val depth = when (p) { 2 -> -0.17f; 4 -> -0.24f; else -> 0f }
            val l = 0.52f - 0.42f * u - 0.5f * v + depth + (bayer(x, y) - 0.5f) * 0.24f
            out[y * G + x] = when (p) {
                1, 2, 4, 7 -> BODY[if (l > 0.8f) 0 else if (l > 0.56f) 1 else if (l > 0.34f) 2 else 3]
                3 -> if (l > 0.5f) PANEL_L else PANEL_D
                5 -> EYE
                6 -> if (dim) 0xFF9AA3B2.toInt() else accent
                8 -> MOUTH
                9 -> if (bayer(x, y) > 0.5f) BLUSH else if (l > 0.5f) PANEL_L else PANEL_D
                HEARTP -> if (dim) 0xFF8C94A3.toInt() else HEART_C
                else -> if (dim) 0xFF8C94A3.toInt() else glow[0]
            }
        }
    }
    // 描边 + 边缘光：受光的左上那两条边走状态色，其余走近黑。
    // 邻居必须读 part 那张掩码：边写 out 边读 out 的话，刚描上的那颗像素会让它右下的
    // 邻居也"挨着东西"，一圈圈扩散，整张 64×64 都能被描边填满
    for (y in 0 until G) {
        for (x in 0 until G) {
            if (out[y * G + x] != 0) continue
            val up = y > 0 && part[(y - 1) * G + x] != 0.toByte()
            val lf = x > 0 && part[y * G + x - 1] != 0.toByte()
            val dn = y < G - 1 && part[(y + 1) * G + x] != 0.toByte()
            val rt = x < G - 1 && part[y * G + x + 1] != 0.toByte()
            if (!(up || lf || dn || rt)) continue
            out[y * G + x] = if (up || lf) (if (dim) 0xFF7A8394.toInt() else glow[1]) else OUT
        }
    }
    return out
}

private fun stamp(part: ByteArray, on: Boolean, x0: Int, y0: Int, rows: Array<String>, code: Int = DECOR) {
    if (!on) return
    for (dy in rows.indices) {
        val row = rows[dy]
        for (dx in row.indices) {
            if (row[dx] != 'X') continue
            val x = x0 + dx
            val y = y0 + dy
            if (x in 0 until G && y in 0 until G) part[y * G + x] = code.toByte()
        }
    }
}

private fun blob(part: ByteArray, cx: Float, cy: Float, r: Float) {
    for (y in (cy - r).toInt()..(cy + r).toInt()) {
        for (x in (cx - r).toInt()..(cx + r).toInt()) {
            if (x in 0 until G && y in 0 until G && disc(x + 0.5f, y + 0.5f, cx, cy, r)) {
                part[y * G + x] = DECOR.toByte()
            }
        }
    }
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
