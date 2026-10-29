package com.carpet.satdemo.app

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **设计 token 的尺子。** 它不检查好不好看，只检查三件能算的事 ——
 * 而这三件正是上一版"乱"的可量化成因（数字见 `tools/contrast_ladder.py` 与 DesignTokens 的类注释）：
 *
 *  1. **相邻底色拉不开**（旧版 Deep/Card = 1.09:1）：深色下这个差肉眼读不出层级，
 *     于是卡片和卡里的块看着是同一层，六张卡糊成一堵墙；
 *  2. **小字与告警红低于 WCAG AA 正文 4.5:1**（旧版 3.77 / 3.75）：而这两支颜色承担的恰恰是
 *     "下一步该干什么"；
 *  3. **标题与正文同为 15sp**：层级只剩字重，扫读时抓不到锚点。
 *
 * 为什么要写成测试而不是评审意见：**色号是会被顺手改的**。下一次有人觉得"这个蓝再亮点好看"，
 * 他会直接改十六进制，不会去算它压在 Raise 上还剩几比几。这条测试让他改完就红。
 *
 * 读源码而不是读 `Ink.*`：那是 Compose 的 `Color`（`androidx.compose.ui:ui-graphics`），
 * 在纯 JVM 单测里连类都装不起来。而这里要防的错恰恰是"源码里的十六进制被改坏"，扫文本正中目标。
 */
class ContrastLadderTest {

    private val src = File("src/main/kotlin/com/carpet/satdemo/app/ui/DesignTokens.kt")

    private fun parseColors(): Map<String, Long> {
        val re = Regex("val (\\w+) = Color\\(0x([0-9A-Fa-f]{8})\\)")
        return re.findAll(read()).associate { it.groupValues[1] to (it.groupValues[2].toLong(16) and 0xFFFFFFL) }
    }

    private fun parseTypeScale(): List<Double> =
        Regex("fontSize = ([0-9.]+)\\.sp").findAll(read()).map { it.groupValues[1].toDouble() }.toList()

    private fun read(): String {
        assertTrue(src.isFile, "找不到 DesignTokens.kt：${src.absolutePath}（测试的工作目录应是 :app 模块根）")
        return src.readText()
    }

    private fun lum(c: Long): Double {
        fun chan(i: Int): Double {
            val u = ((c shr (16 - i * 8)) and 0xFFL) / 255.0
            return if (u <= 0.04045) u / 12.92 else Math.pow((u + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * chan(0) + 0.7152 * chan(1) + 0.0722 * chan(2)
    }

    private fun cr(map: Map<String, Long>, a: String, b: String): Double {
        val la = lum(checkNotNull(map[a]) { "DesignTokens 里没有 $a" })
        val lb = lum(checkNotNull(map[b]) { "DesignTokens 里没有 $b" })
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun `相邻底色必须拉得开`() {
        val c = parseColors()
        // 页面→卡是"浮起来"的下限。1.09 那一版就是在这里失的职
        assertTrue(cr(c, "Deep", "Card") >= 1.25, "Deep/Card 只有 ${"%.3f".format(cr(c, "Deep", "Card"))}，卡片读不出浮起")
        // 强调卡只比卡亮一点就够了：它另外有一根描边承担层级
        assertTrue(cr(c, "Card", "Raise") >= 1.10, "Card/Raise 只有 ${"%.3f".format(cr(c, "Card", "Raise"))}")
        // 轨道与描边必须比卡底明显，否则进度条和"强调卡那根线"都是看不见的装饰
        assertTrue(cr(c, "Card", "Track") >= 1.20, "Card/Track 只有 ${"%.3f".format(cr(c, "Card", "Track"))}")
        assertTrue(cr(c, "Card", "Outline") >= 1.60, "Card/Outline 只有 ${"%.3f".format(cr(c, "Card", "Outline"))}")
        // 背板比页面亮一点点是故意的：它要"托"住一组行，不该读成一张卡
        assertTrue(cr(c, "Deep", "Sunk") in 1.03..1.15, "Deep/Sunk = ${"%.3f".format(cr(c, "Deep", "Sunk"))}，分组背板要么看不见要么抢卡片")
    }

    @Test
    fun `每一支文字色在每个底色上都得过 AA 正文`() {
        val c = parseColors()
        val bgs = listOf("Deep", "Sunk", "Card", "Raise")
        val fgs = listOf("TextHigh", "TextMid", "TextLow", "Ok", "Wait", "Alert", "Sat")
        val bad = mutableListOf<String>()
        bgs.forEach { bg ->
            fgs.forEach { fg ->
                val r = cr(c, bg, fg)
                if (r < 4.5) bad += "$fg on $bg = ${"%.2f".format(r)}"
            }
        }
        assertTrue(bad.isEmpty(), "低于 4.5:1 的组合：\n  " + bad.joinToString("\n  "))
    }

    /**
     * chip 选中态。**这里禁止"语义色淡底 + 同色字"** ——
     * `Sat.copy(alpha=0.16)` 压在卡底上再写一支 Sat 的字只有 4.43:1，淡底把自己的字压糊了。
     * 现在 [Chip] 用实底语义色 + [Ink.Deep] 的字，那条要求 Deep 压在每支语义色上都过 4.5。
     */
    @Test
    fun `实底 chip 上的深色字必须过 AA`() {
        val c = parseColors()
        listOf("Sat", "Ok", "Wait", "Alert").forEach { fg ->
            val r = cr(c, fg, "Deep")
            assertTrue(r >= 4.5, "Ink.Deep on $fg = ${"%.2f".format(r)}，实底 chip 的字会糊")
        }
    }

    @Test
    fun `字号阶梯六档齐全且相邻比值在一到两档之间`() {
        val sizes = parseTypeScale()
        listOf(11.0, 12.5, 15.0, 18.0, 24.0, 34.0).forEach {
            assertTrue(sizes.contains(it), "字号阶梯缺 $it（实到：$sizes）")
        }
        val ladder = listOf(11.0, 12.5, 15.0, 18.0, 24.0, 34.0)
        ladder.zipWithNext().forEach { (a, b) ->
            val r = b / a
            assertTrue(r in 1.13..1.45, "$a→$b 比值 ${"%.2f".format(r)} 出界：跳两档是断层，差太少读不出层级")
        }
    }

    /**
     * 淡底系数只许一个。各处手写 alpha 的那几支从来没算过对比度，
     * 而"半透明的语义色当底色"正是深色 UI 里最容易悄悄糊掉的地方。
     */
    @Test
    fun `语义色淡底只走 Ink_TINT 一个数`() {
        val alphas = Regex("""(?:Ink|Tone)\.\w+(?:\.color)?\.copy\(alpha = (0\.[0-9]+)f\)""")
            .findAll(read()).map { it.groupValues[1] }.toSet()
        assertTrue(alphas.isEmpty() || alphas == setOf("0.16"), "DesignTokens 里出现了手写 alpha：$alphas")
        assertEquals("0.16", Regex("const val TINT = (0\\.[0-9]+)f").find(read())!!.groupValues[1])
    }
}
