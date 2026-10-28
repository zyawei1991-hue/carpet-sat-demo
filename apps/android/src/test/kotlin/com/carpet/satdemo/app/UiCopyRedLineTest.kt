package com.carpet.satdemo.app

import com.carpet.satdemo.core.link.SosCopy
import com.carpet.satdemo.app.ui.Cards
import com.carpet.satdemo.app.ui.Scene
import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * §6.1 红线一的**界面侧**看护。
 *
 * 文案表的单测在 core 里，那条只保证 [SosCopy] 自己不说错话。这一条管的是另一件事：
 * 谁在 Compose 那一片源码里手写了一句带"已送达"的话。规则是**状态文案只能出自那张表**，
 * 而 UI 一旦能自由拼句子，这条线就是靠自觉 —— 自觉在赶工那天最先失效。
 *
 * 扫源码而不是扫渲染结果，是因为渲染要在 Robolectric 里跑一遍 Compose，代价大得多，
 * 而这一条要防的错恰恰是"有人把 banned 词直接写进了 Kotlin 文件"。
 */
class UiCopyRedLineTest {

    private val uiSources = File("src/main/kotlin/com/carpet/satdemo/app/ui")

    @Test
    fun `UI 源码里不许出现被禁的措辞`() {
        assertTrue(uiSources.isDirectory, "找不到 UI 源码目录：${uiSources.absolutePath}（测试的工作目录应是 :app 模块根）")
        val hits = uiSources.listFiles { f -> f.isFile && f.extension == "kt" }
            .orEmpty()
            .flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    val bad = SosCopy.BANNED.firstOrNull { line.contains(it) }
                    // 注释里写这个词是在解释"为什么不许说"，不是违规；只有出现在代码里才算
                    if (bad != null && !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//")) {
                        "${f.name}:${i + 1} 「$bad」 $line"
                    } else null
                }
            }
        assertTrue(hits.isEmpty(), "这些句子把卫星 ACK 说成了平台受理（§6.1 红线一），文案必须走 SosCopy：\n" + hits.joinToString("\n"))
    }

    /**
     * 场景里写了 key、注册表里没有 —— 结果不是报错，而是**那张卡安静地不出现**。
     * §5.3 要求"注册表里没有的卡就不渲染，也不报错"，这条对真缺能力是对的，
     * 对打错字也是同一行为，所以打错字只能靠这一条测出来。
     */
    @Test
    fun `每个场景引用的卡片都在注册表里`() {
        val registry = Cards.registry
        val known = registry.all.map { it.key }.toSet()
        val used = Scene.entries.flatMap { it.profile.cards + listOf(it.profile.headline) }.toSet()
        val missing = used - known
        assertTrue(missing.isEmpty(), "这些卡片 key 没有任何实现，界面上会直接少一张卡且没有提示：$missing")
    }
}
