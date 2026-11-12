package com.carpet.satdemo.app.ui

import com.carpet.satdemo.core.protocol.UiEmotion
import java.io.File
import org.junit.Assert.assertEquals
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
        // 真正的红线是状态色一支都不许出现，所以直接拿状态色表来比
        val leaked = dim.filter { it != 0 }.map { it }.filter { SCHEME.contains(it) }.distinct()
        assertTrue("未接入还在用状态色：" + leaked.map { Integer.toHexString(it) }, leaked.isEmpty())
        assertTrue("dim 和 live 一模一样，dim 参数没起作用", live.toList() != dim.toList())
        // 反过来也得成立：亮着的时候确实在用状态色，不然上面那条空断言永远绿
        val used = live.filter { it != 0 }.filter { SCHEME.contains(it) }.distinct()
        assertTrue("live 那版也没在用状态色，说明色表和渲染脱钩了", used.isNotEmpty())
    }

    /** 状态色表和渲染必须同源：搬角色时漏掉一支辉光色，dim 就会把语义色漏出去（V1.10.9 那条红线的守卫） */
    @Test
    fun `状态色表覆盖所有非结构像素`() {
        val struct = STRUCT
        val unknown = MuseAvatarData.PALETTE
            .filter { it != 0 }
            .filter { !struct.contains(it) }
            .filter { !SCHEME.contains(it) }
            .distinct()
        assertTrue("调色板里有既不是结构色、也不在状态色表里的颜色：" +
            unknown.map { Integer.toHexString(it) }, unknown.isEmpty())
        // dim 灰档不能和已有色撞车，不然 SAD 派生时 remap 会指到语义色上
        val clash = MuseAvatarData.GREYS.filter { MuseAvatarData.PALETTE.contains(it) }
        assertTrue("dim 灰档撞了已有调色板色：" + clash.map { Integer.toHexString(it) }, clash.isEmpty())
    }

    /** 只用来看一眼：这台机器打不开 PNG，ASCII 是唯一的"眼睛" */
    @Test
    fun `ASCII 对照`() {
        val dir = File("build/screenshots").apply { mkdirs() }
        val structTags = mapOf(
            0xFFFFFFFF.toInt() to 'o', // 高光
            0xFF100C08.toInt() to '#', // 硬描边
            0xFF392820.toInt() to 'C', // 斑点/暗结构
            0xFF391C18.toInt() to 'c',
            0xFF6A5541.toInt() to 'm',
            0xFFAC997B.toInt() to '~', // 毛绒中调
            0xFF8B7562.toInt() to '=',
            0xFFCDBE9C.toInt() to ':',
            0xFFE6D6BD.toInt() to '+', // 皮毛亮
            0xFFF6DEBD.toInt() to '+',
            0xFFFFEEDE.toInt() to ' ', // 脸板
            0xFFFFEED5.toInt() to ' ',
            0xFFEECAA4.toInt() to '-',
        )
        val sb = StringBuilder()
        for (e in order) {
            val px = avatarPixels(poseFor(e))
            sb.append("=== $e\n")
            for (y in 0 until 64) {
                for (x in 0 until 64) {
                    val c = px[y * 64 + x]
                    sb.append(
                        when {
                            c == 0 -> '.'
                            structTags[c] != null -> structTags.getValue(c)
                            SCHEME.contains(c and 0xFFFFFF) -> '*'
                            else -> '?'
                        }
                    )
                }
                sb.append('\n')
            }
        }
        File(dir, "avatar-ascii.txt").writeText(sb.toString())
    }

    /** 七档情绪对上去的必须都是上游那几档真实姿态，不能是随手编的 */
    @Test
    fun `每档情绪都落在合法的上游姿态上`() {
        val legal = setOf(
            MuseAvatarData.IDLE, MuseAvatarData.LISTENING, MuseAvatarData.THINKING,
            MuseAvatarData.WORKING, MuseAvatarData.SPEAKING, MuseAvatarData.HAPPY,
            MuseAvatarData.OFF, MuseAvatarData.ERROR, MuseAvatarData.BOOT,
            MuseAvatarData.OFF_DIMMED, // sad 是 OFF 的辉光灰化派生档，唯一一个 App 侧合成的姿势
        )
        for (e in order + listOf<UiEmotion?>(null)) {
            val grid = poseFor(e)
            assertEquals("$e 的姿势不是 64×64 网格", 64 * 64, grid.size)
            assertTrue("$e 对上去的姿势不在上游 9 档里", legal.any { it === grid })
        }
    }
}
