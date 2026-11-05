package com.carpet.satdemo.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.carpet.satdemo.app.AppRuntime
import com.carpet.satdemo.core.link.LinkPhase
import com.carpet.satdemo.core.protocol.FenceState
import com.carpet.satdemo.core.protocol.GnssSource
import com.carpet.satdemo.core.protocol.GnssState
import com.carpet.satdemo.core.protocol.LinkPrimary
import com.carpet.satdemo.core.protocol.LinkState
import com.carpet.satdemo.core.protocol.SatPhase
import com.carpet.satdemo.core.protocol.SatState
import com.carpet.satdemo.core.protocol.SosLevel
import com.carpet.satdemo.core.protocol.SosState
import com.carpet.satdemo.core.protocol.StatePayload
import com.carpet.satdemo.core.runtime.DemoRuntime
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 车辆页那张「该做的事」。**这一张要拍到的是三个动作之后的样子**：勾两项、展开工单、
 * 换一档隐私 —— 只拍缺省态就等于没拍（[VehicleScreenRenderTest] 那条教训在这里同样成立）。
 *
 * 另外钉一句界面层面的硬要求：**这一版没有上行**。卫星那条路是 M2，还没接，
 * 所以卡上出现的话必须是"只在手机里 / 复制出去对码"，不许出现"已发送""已通知门店"。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaintCardRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    private fun live(): StatePayload = StatePayload(
        link = LinkState(primary = LinkPrimary.cell, cellOk = true, satOk = true),
        sat = SatState(
            phase = SatPhase.locked, satName = "GW-03", cnr = 12.4, nextPassInS = 561,
            moduleOk = true, queuePending = 3, queueCapacity = 50, tpiAgeS = 95, tpiStale = false,
            ephState = com.carpet.satdemo.core.ephemeris.EphState.SUCCESS,
            nextPassDurS = 240, nextPassMaxEl = 18.0, aimAz = 45.0,
        ),
        gnss = GnssState(30.2541, 120.1456, altM = 12.0, hdop = 0.9, source = GnssSource.phone, fixAgeS = 2.0),
        fence = FenceState(active = true, inside = true, name = "西湖景区", distM = 320.0),
        sos = SosState(level = SosLevel.normal),
        phoneBattery = 87,
    )

    private fun mount(fontScale: Float? = null): AppRuntime {
        val app = AppRuntime(ApplicationProvider.getApplicationContext())
        val status = DemoRuntime.Status(
            running = true, phase = LinkPhase.CELL, snapshot = live(), clients = 1, localIp = "192.168.1.9",
        )
        // 字号要在 setContent **之前**改：密度和字号是组合开始时读进去的，
        // 拍完再改只会得到一张和 1.0 一模一样的图，而看上去是"这一档也测过了"
        if (fontScale != null) {
            val cfg = compose.activity.resources.configuration
            cfg.fontScale = fontScale
            @Suppress("DEPRECATION")
            compose.activity.resources.updateConfiguration(cfg, null)
        }
        compose.setContent { CarpetTheme { VehicleTab(app, status) } }
        compose.waitForIdle()
        return app
    }

    @Test
    @Config(qualifiers = "w411dp-h2600dp-xxhdpi")
    fun selectTwoThenOpenOrder() {
        val app = mount()
        scrollToCard()
        // 桩数据必须当街标出来。**用 onAllNodes**：这一页现在有两张卡带着同一个标
        // （整车状态 + 该做的事），`onNodeWithText` 遇到两条匹配会直接抛歧义，
        // 而那条歧义恰恰是"这页诚不诚实"的证据，不是测试的失败
        assertTrue(
            "车辆页必须有「示意数据 · 未接真源」这个标",
            compose.onAllNodesWithText("示意数据 · 未接真源").fetchSemanticsNodes().isNotEmpty(),
        )
        compose.onNodeWithText("该做的事").assertExists()
        compose.onNodeWithText("补气到 2.5 bar").performClick()
        compose.onNodeWithText("换空调滤芯").performClick()
        compose.onNode(hasText("已勾 2 项")).assertExists()
        compose.onNodeWithText("生成工单").performClick()
        compose.waitForIdle()
        // substring：那一行原文是「21 字节 · 一帧装得下」，整句匹配是匹配不上的
        compose.onNode(hasText("21 字节", substring = true)).assertExists()
        // 红线：还没接上行，就不许有任何"已经送到"的说法（substring，防它藏在长句里）
        compose.onNode(hasText("已发送", substring = true)).assertDoesNotExist()
        compose.onNode(hasText("已通知门店", substring = true)).assertDoesNotExist()
        shoot("maint-411-order")
        app.release()
    }

    /** 本地留存档：工单照生成，但那句说法要跟着档位变 */
    @Test
    @Config(qualifiers = "w384dp-h2600dp-xhdpi")
    fun strictTierKeepsTheOrderLocal() {
        val app = mount()
        app.setPrivacyChoice(PrivacyTier.Strict)
        scrollToCard()
        compose.onNodeWithText("做常规保养").performClick()
        compose.onNodeWithText("生成工单").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("本地留存", substring = true)).assertExists()
        compose.onNode(hasText("卫星上行还没接", substring = true)).assertDoesNotExist()
        shoot("maint-384-strict")
        app.release()
    }

    /** 字号 1.3：三行小字最容易在这一档挤成竖排。Mate60 上就是这一档 */
    @Test
    @Config(qualifiers = "w384dp-h2600dp-xhdpi")
    fun largeFont() {
        val app = mount(fontScale = 1.3f)
        scrollToCard()
        compose.onNodeWithText("判据是谁定的").performClick()
        compose.waitForIdle()
        // 不 assert 这一句，这张图就可能是一张"折叠区根本没展开"的图，而看上去像测过了
        compose.onNode(hasText("间隔和标定胎压还等手册", substring = true)).assertExists()
        shoot("maint-384-font130")
        app.release()
    }

    /**
     * **交互测试要用装得下整页的视口，一屏高那一档只用于看首屏。**
     * 上一轮这三条里有两条是"假通过"：384×836 那一档里「该做的事」整张卡在折叠线以下，
     * `performClick` 打的是空处，而 `shoot()` 用 `root.draw()` —— 它只拍到窗口那么高，
     * 于是图里没有折叠区，断言也找不到折叠区，测试却一路绿灯到 assert 那一步才红。
     * 这是 #32 那条教训的第二种犯法：上一种是空态截图看不见有数据的分支，
     * 这一种是"点下去之后"那个分支根本没被点开。
     */
    private fun scrollToCard() {
        compose.onNodeWithText("该做的事").performScrollTo()
        compose.waitForIdle()
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
}
