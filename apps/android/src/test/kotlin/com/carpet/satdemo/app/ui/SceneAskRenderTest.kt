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
import com.carpet.satdemo.core.ai.TurnScene
import com.carpet.satdemo.core.protocol.CommandType
import com.carpet.satdemo.core.protocol.ScreenPage
import com.carpet.satdemo.core.runtime.DemoRuntime
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **两个场景页顶上那根就地发问条，喂了对话之后拍。**
 *
 * 这一张要回答的是上一轮用户那句"又乱"里剩下的一半：首页那块对话主角卡搬到每个场景页各一份，
 * 就是四张一样的大卡。所以这里看的不是"有没有"，而是**它有没有比首页那张小一号**，
 * 以及归页之后这一页只看得见自己那串（另一页那句不许漏进来）。
 *
 * 走的是真链路：`app.askAi(...)` 起的那一轮经由 [com.carpet.satdemo.core.ai.DialogBridge] 入账，
 * scene 由发起那一屏带进来。屏报的页用 [ScreenPage]，家人页那一句归页靠的是 `page_report` 换算，
 * 现场彩排时这两格同时成立才会有那句「圆屏此刻就在这一页」。
 *
 * 卡片流那边喂的是首帧空快照：那一段的版式归 [VehicleScreenRenderTest] 那几张管，
 * 这一张只盯顶上那根条，两张图各管一件事，出问题能立刻分清是哪一侧。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SceneAskRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    private fun status(app: AppRuntime, page: ScreenPage): DemoRuntime.Status {
        // 页由真链路那一格报上来（`page_report`）而不是我手搭一个 Status：
        // 这一张要拍的正是"屏报了页 → 手机那串对话归进对应那一页"这条整线，
        // 手搭的 Status 会让画面里的两句互相打不上
        assertTrue("屏报的页没被认下：$page", app.runtime.handleScreenCommand(CommandType.page_report, buildJsonObject { put("page", page.name) }))
        return app.runtime.status.value.copy(
            running = true,
            clients = 1,
            localIp = "192.168.1.9",
        )
    }

    @Test
    @Config(qualifiers = "w384dp-h836dp-xhdpi")
    fun vehiclePageAskBar() {
        val app = AppRuntime(ApplicationProvider.getApplicationContext())
        app.pairScreen("desk-01")
        // 另一页先问一句：这一页不该看见它
        app.askAi("娃在屏上说了啥", scene = TurnScene.family)
        compose.setContent { CarpetTheme { VehicleTab(app, status(app, ScreenPage.vehicle)) } }
        compose.waitForIdle()
        app.askAi("车在哪", scene = TurnScene.vehicle)
        compose.waitForIdle()
        shoot("ask-vehicle-384")
        app.release()
    }

    @Test
    @Config(qualifiers = "w384dp-h836dp-xhdpi")
    fun familyPageAskBar() {
        val app = AppRuntime(ApplicationProvider.getApplicationContext())
        app.pairScreen("desk-01")
        app.askAi("车在哪", scene = TurnScene.vehicle)
        compose.setContent { CarpetTheme { FamilyTab(app, status(app, ScreenPage.kids)) } }
        compose.waitForIdle()
        // 屏此刻在亲子页，它说的话要落进这一串
        app.simulateScreenQuestion("今天放学下雨了")
        compose.waitForIdle()
        shoot("ask-family-384")
        app.release()
    }

    /**
     * 首页那一张：**两页各问的一句都要在这里出现**。
     * 归页是视图，不是把记录切开 —— 这一张拍的就是这句话。
     * 顺带能核对首页镜像那一格读的是屏自己报的那一页（「屏上现在 · 亲子」）。
     */
    @Test
    @Config(qualifiers = "w384dp-h836dp-xhdpi")
    fun homePageShowsEveryScene() {
        val app = AppRuntime(ApplicationProvider.getApplicationContext())
        app.pairScreen("desk-01")
        app.askAi("车在哪", scene = TurnScene.vehicle)
        compose.setContent { CarpetTheme { HomeShell(app, status(app, ScreenPage.kids)) } }
        compose.waitForIdle()
        app.simulateScreenQuestion("今天放学下雨了")
        compose.waitForIdle()
        shoot("ask-home-384")
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
}
