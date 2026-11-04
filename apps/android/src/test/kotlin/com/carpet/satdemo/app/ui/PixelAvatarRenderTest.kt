package com.carpet.satdemo.app.ui

import com.carpet.satdemo.core.protocol.UiEmotion
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 形象七档情绪的对照表。
 *
 * 为什么不用 Compose 规则拍：`avatarPixels()` 是纯函数，测试直接拿它的像素出图，
 * 不经过 Bitmap/Robolectric —— 那样挂掉的时候报的是"渲染环境"，不是"形象画错了"。
 * 手机上那块镜像卡走的是同一个函数，所以这张表就是 App 里会看到的东西。
 *
 * 顺序（第一行四个、第二行三个）：neutral / happy / excited / sad / worried / sleepy / thinking
 */
class PixelAvatarRenderTest {

    private val order = listOf(
        UiEmotion.neutral, UiEmotion.happy, UiEmotion.excited, UiEmotion.sad,
        UiEmotion.worried, UiEmotion.sleepy, UiEmotion.thinking,
    )

    @Test
    fun `七档情绪画出来是七个不同的姿势`() {
        val sigs = order.map { e ->
            val px = avatarPixels(poseFor(e))
            val ink = px.count { it != 0 }
            assertTrue("$e 几乎没画东西：$ink 个像素", ink > 700)
            // 上限是防描边那圈的：邻居判据一旦读到自己刚写下的像素，边缘光会一路扩散，
            // 把整张 64×64 涂成状态色 —— 看着"挺满"，其实是画坏了
            assertTrue("$e 画到整张画布都满了：$ink 个像素", ink < 2600)
            // 顶边和底边不许有像素：形象是"站在地上的一只"，天线顶出去或者脚沉到边里
            // 都是画歪了，而 64px 上被裁掉的那几像素在缩略图里根本看不出来
            val top = (0 until 64).count { px[it] != 0 }
            val bottom = (0 until 64).count { px[63 * 64 + it] != 0 }
            assertTrue("$e 顶到画布边上了：顶边 $top、底边 $bottom 个像素", top == 0 && bottom == 0)
            // 位置和颜色的和：两个姿势只要有一处不同就会不一样，不需要容忍度
            px.foldIndexed(0L) { i, acc, c -> acc * 31L + (i.toLong() * 7L + (c.toLong() and 0xFFFFFFL)) }
        }
        assertTrue("有两档情绪画成了同一个姿势", sigs.distinct().size == sigs.size)
    }

    @Test
    fun `对照表出图`() {
        val scale = 4
        val pad = 12
        val cell = 64 * scale
        val cols = 4
        val rows = 2
        val w = cols * cell + (cols + 1) * pad
        val h = rows * cell + (rows + 1) * pad
        val sheet = IntArray(w * h) { WindowBg }
        order.forEachIndexed { idx, e ->
            val px = avatarPixels(poseFor(e))
            val r = idx / cols
            val c = idx % cols
            val ox = pad + c * (cell + pad)
            val oy = pad + r * (cell + pad)
            for (y in 0 until cell) for (x in 0 until cell) {
                val p = px[(y / scale) * 64 + (x / scale)]
                sheet[(oy + y) * w + ox + x] = if (p == 0) WindowBg else p
            }
        }
        val dir = File("build/screenshots")
        dir.mkdirs()
        writePng(File(dir, "avatar-7-emotions.png"), w, h, sheet)
    }

    /** 没屏接入时不许亮语义色：这条和镜像卡那条红线是同一件事（V1.10.9） */
    @Test
    fun `未接入时辉光退成结构灰`() {
        val live = avatarPixels(poseFor(UiEmotion.thinking))
        val dim = avatarPixels(poseFor(UiEmotion.thinking), dim = true)
        // 数"颜色种类"是假判据：本体那四档石板蓝本身就带色相，dim 一样数得过它。
        // 真正的红线是七档模式色一支都不许出现，所以直接拿模式色表来比
        val mode = SCHEME.values.flatMap { (glow, accent) -> glow.toList() + accent }
            .map { it and 0xFFFFFF }.toSet()
        val leaked = dim.filter { it != 0 }.map { it and 0xFFFFFF }.filter { mode.contains(it) }.distinct()
        assertTrue("未接入还在用状态色：" + leaked.map { Integer.toHexString(it) }, leaked.isEmpty())
        assertTrue("dim 和 live 一模一样，dim 参数没起作用", live.toList() != dim.toList())
        // 反过来也得成立：亮着的时候确实在用状态色，不然上面那条空断言永远绿
        val used = live.filter { it != 0 }.map { it and 0xFFFFFF }.filter { mode.contains(it) }.distinct()
        assertTrue("live 那版也没在用状态色，说明色表和渲染脱钩了", used.isNotEmpty())
    }
}
