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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **整机外壳的四页扫图**：`CarpetRoot` 连着底部导航一起拍，不是把单个 Tab 摘出来拍。
 *
 * 为什么要有这一支：之前每一支 render test 都 `setContent { VehicleTab(...) }`，
 * 于是**导航栏、SOS 那颗、Scaffold 的 insets、页与页共用的那一格底部留白**从来不在画面里。
 * 用户说"每个页面都有问题"——四个页面同时出问题，最不可能的原因是四张卡片各自画歪，
 * 最可能的是它们共用的这一层出了问题，而这一层恰好是我看不见的。
 *
 * 数据走真链路：`runtime.tickOnce()` 让 snapshot 真的长出数，再让 `CarpetRoot` 自己 collect
 * `app.status`。不手搭 Status —— 手搭出来的图和真实那一帧对不对得上没人知道。
 *
 * 视口 384dp：Mate60 是 1260×2720 / 密度 3.25，可视宽 384dp、高 836dp（见 §5.2 那条
 * "只按一种视口自测等于没测"）。高度给两个档：836dp 是"点进去第一眼"，2600dp 是整页内容。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShellSweepRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    private fun boot(): AppRuntime {
        val app = AppRuntime(ApplicationProvider.getApplicationContext<Application>())
        // 用 allowScreen 而不是 pairScreen：后者会 say() 一句"已把 desk-01 放进白名单"，
        // 于是每一张截图底部都压着一条 Snackbar —— 那是 harness 自己按的钮，不是 App 的缺陷。
        // 上一版我没认出来，差点把"每条消息被截成两行"当成一条真 bug 报上去
        app.allowScreen("desk-01")
        // 转一圈：这一圈之后 status.snapshot 不再是 null，有数的那些分支才会被画出来
        runBlocking { app.runtime.tickOnce() }
        return app
    }

    private fun setFontScale(scale: Float) {
        val cfg = compose.activity.resources.configuration
        cfg.fontScale = scale
        @Suppress("DEPRECATION")
        compose.activity.resources.updateConfiguration(cfg, null)
    }

    private fun shootTab(name: String, tab: Int, fontScale: Float? = null) {
        val app = boot()
        app.setTab(tab)
        if (fontScale != null) setFontScale(fontScale)
        compose.setContent { CarpetTheme { CarpetRoot(app) } }
        compose.waitForIdle()
        val snap = app.status.value.snapshot
        println("[shell] name=$name snapshot=${if (snap == null) "null" else "有数"} font=${compose.activity.resources.configuration.fontScale}")
        shoot(name)
        app.release()
    }

    private fun shoot(name: String) {
        val root = compose.activity.findViewById<View>(android.R.id.content)
        assertTrue("根 View 还没布局（0x0），先让主线程跑一轮", root.width > 0 && root.height > 0)
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(WindowBg)
        root.draw(canvas)
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val f = File(outDir, "$name.png")
        writePng(f, bmp.width, bmp.height, px)
        assertTrue("PNG 没写出来：$f", f.length() > 1000)
        println("[shot] ${f.canonicalPath} ${bmp.width}x${bmp.height} ${f.length()}B")
    }

    @Test
    @Config(qualifiers = "w384dp-h836dp-xhdpi")
    fun homeFirstScreen() = shootTab("shell-home-384", 0)

    @Test
    @Config(qualifiers = "w384dp-h836dp-xhdpi")
    fun vehicleFirstScreen() = shootTab("shell-vehicle-384", 1)

    @Test
    @Config(qualifiers = "w384dp-h836dp-xhdpi")
    fun familyFirstScreen() = shootTab("shell-family-384", 2)

    @Test
    @Config(qualifiers = "w384dp-h836dp-xhdpi")
    fun meFirstScreen() = shootTab("shell-me-384", 3)

    /** 整页：滚动内容全铺开，看每一组的顺序与留白，不是只看第一眼 */
    @Test
    @Config(qualifiers = "w384dp-h2600dp-xhdpi")
    fun homeWholePage() = shootTab("shell-home-384-deep", 0)

    @Test
    @Config(qualifiers = "w384dp-h2600dp-xhdpi")
    fun vehicleWholePage() = shootTab("shell-vehicle-384-deep", 1)

    @Test
    @Config(qualifiers = "w384dp-h2600dp-xhdpi")
    fun familyWholePage() = shootTab("shell-family-384-deep", 2)

    @Test
    @Config(qualifiers = "w384dp-h2600dp-xhdpi")
    fun meWholePage() = shootTab("shell-me-384-deep", 3)

    /** 系统字号调大那一档：真机上"每个页面都乱"最常见的一个来源就是这个 */
    @Test
    @Config(qualifiers = "w384dp-h836dp-xhdpi")
    fun homeFirstScreenFont130() = shootTab("shell-home-384-font130", 0, fontScale = 1.3f)

    @Test
    @Config(qualifiers = "w384dp-h836dp-xhdpi")
    fun vehicleFirstScreenFont130() = shootTab("shell-vehicle-384-font130", 1, fontScale = 1.3f)

    @Test
    @Config(qualifiers = "w384dp-h836dp-xhdpi")
    fun familyFirstScreenFont130() = shootTab("shell-family-384-font130", 2, fontScale = 1.3f)

    @Test
    @Config(qualifiers = "w384dp-h836dp-xhdpi")
    fun meFirstScreenFont130() = shootTab("shell-me-384-font130", 3, fontScale = 1.3f)

    /** 窄屏下限：分屏与老机型 */
    @Test
    @Config(qualifiers = "w360dp-h836dp-xhdpi")
    fun homeNarrowest() = shootTab("shell-home-360", 0)

    @Test
    @Config(qualifiers = "w360dp-h836dp-xhdpi")
    fun meNarrowest() = shootTab("shell-me-360", 3)
}
