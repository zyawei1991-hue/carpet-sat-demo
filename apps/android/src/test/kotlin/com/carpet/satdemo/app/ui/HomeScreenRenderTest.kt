package com.carpet.satdemo.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.carpet.satdemo.app.AppRuntime
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **把四个 Tab 真画成 PNG。** 这张测试不是断言，是眼睛的替身。
 *
 * 为什么要它：这台机器上没有手机、也没有 emulator，§5.2 里"一屏最多三层""别挤"这类规则
 * 我此前只用对比度和字号阶梯去*推断*，从没看过它长什么样。Robolectric 的 NATIVE 图形模式
 * 在 JVM 里跑真的 hwui 绘制，于是画布上的像素和手机上渲染的是同一套 —— "乱不乱"这句
 * 终于可以看图说，而不是看算式说。
 *
 * 产物落 `build/screenshots/` 下的 png，**不进版本库**：它是给我自查的草稿，不是给观众的证据。
 *
 * **本机踩过的坑**：Robolectric 自带的下载器在这台机器上取 `android-all-instrumented` 会报
 * `SHA-512 mismatch`（它自己那套 HTTP 拿到的字节和 curl 拿到的不是一份），一次要耗掉二十分钟。
 * 用 curl 把 jar + pom + 两个 `.sha512` 放进 `~/.m2/repository/org/robolectric/android-all-instrumented/14-robolectric-10818077-i6/`
 * 就绕开了 —— 本地仓库命中就不再联网。换台机器重跑，先怀疑这里。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeScreenRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    @Test
    fun renderFourTabs() {
        val app = AppRuntime(ApplicationProvider.getApplicationContext())
        // 白名单里先放一台：空首页拍出来全是"--"，看不出密度和挤压问题
        app.pairScreen("desk-01")
        compose.setContent { CarpetTheme { CarpetRoot(app) } }
        compose.waitForIdle()
        shoot("tab0-home")

        for (t in 1..3) {
            app.setTab(t)
            compose.waitForIdle()
            shoot("tab$t")
        }
        val pngs = outDir.listFiles { f -> f.name.endsWith(".png") }!!.map { it.name }.toSet()
        assertTrue(
            "四张图都要落盘，实际：$pngs",
            pngs.containsAll(setOf("tab0-home.png", "tab1.png", "tab2.png", "tab3.png")),
        )
    }

    /**
     * 整页一张：**手机首屏只装得下两三个主区，而"SOS 不许藏"这条红线要的恰恰是"不滚动就能按到"**。
     * 所以把视口拉到 2600dp 拍全页 —— 折叠线在哪、什么被推到看不见的地方，这张图说了算。
     */
    @Test
    @Config(qualifiers = "w411dp-h2600dp-xxhdpi")
    fun renderWholeHome() {
        val app = AppRuntime(ApplicationProvider.getApplicationContext())
        app.pairScreen("desk-01")
        compose.setContent { CarpetTheme { CarpetRoot(app) } }
        compose.waitForIdle()
        shoot("home-full")
    }

    /**
     * **不走 Compose 的 `captureToImage()`**：它在 Robolectric 下要先等一个"帧可用"的条件，
     * 那个条件在本机 2 秒都不满足（而 `waitForIdle()` 是过的，说明界面早就稳了）。
     * 直接把根 View 画进 Bitmap，路径全程在我手里：绘制是 native 的，取像素是纯拷贝。
     */
    private fun shoot(name: String) {
        val root = compose.activity.findViewById<View>(android.R.id.content)
        assertTrue("根 View 还没布局（0x0），先让主线程跑一轮", root.width > 0 && root.height > 0)
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val f = File(outDir, "$name.png")
        writePng(f, bmp.width, bmp.height, px)
        assertTrue("PNG 没写出来：$f", f.length() > 1000)
        println("[shot] ${f.canonicalPath} ${bmp.width}x${bmp.height} ${f.length()}B")
    }
}
