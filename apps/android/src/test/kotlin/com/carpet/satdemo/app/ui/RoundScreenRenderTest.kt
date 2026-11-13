package com.carpet.satdemo.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.carpet.satdemo.app.AppRuntime
import com.carpet.satdemo.core.protocol.DialogSource
import com.carpet.satdemo.core.protocol.ScreenPage
import com.carpet.satdemo.core.protocol.StatePayload
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **圆屏档自检（DESIGN-RULES §3 的第二档）**：410×502 曲边视口。
 *
 * 这一支给两边共用：App 侧改圆屏相关的图元（镜像、Avatar、状态条）前先拍一遍，
 * 圆屏侧落手势层时拿同一组图对齐视觉语言 —— 两边读的是同一份 Ink/Grid/Round token，
 * 改了 token 这组图就变，谁改谁看得见。
 *
 * 曲边裁切检查用一条近似规则：圆屏角部曲率半径约 40dp，内容盒四边各缩进 24dp
 * （DESIGN-RULES §2 布局第一条的圆屏档），关键内容不许落进裁切带。
 * 这里不做逐像素断言 —— 断言只钉"图拍出来了且非空"，视觉判断靠人看图。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoundScreenRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    // ScreenMirrorRenderTest.live() 是 private 的，反射借一遍省得抄一份编数；
    // 那边的数变了这边跟着变 —— 两支测试看的是同一份"真值"
    private fun live(): StatePayload {
        val t = ScreenMirrorRenderTest::class.java
        val m = t.getDeclaredMethod("live")
        m.isAccessible = true
        return m.invoke(t.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()) as StatePayload
    }

    private fun shootRound(name: String, content: @Composable () -> Unit) {
        compose.setContent { CarpetTheme { content() } }
        compose.waitForIdle()
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
        println("[round] ${f.canonicalPath} ${bmp.width}x${bmp.height}")
    }

    /** 圆屏上最常驻的东西：镜像圆。直径收进 410dp 宽 - 2×24dp 边距以内 */
    @Test
    @Config(qualifiers = "w410dp-h502dp-xhdpi")
    fun mirrorRound() {
        shootRound("round-mirror-live") {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                ScreenMirror(snap = live(), online = true, diameter = 224.dp)
            }
        }
    }

    /** 屏没连上那一帧：圆里的话术必须能读，不能被曲边裁掉 */
    @Test
    @Config(qualifiers = "w410dp-h502dp-xhdpi")
    fun mirrorRoundNoClient() {
        shootRound("round-mirror-noclient") {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                ScreenMirror(snap = null, online = false, diameter = 224.dp)
            }
        }
    }

    /** 对话页那一帧：条目行是圆屏上信息密度最高的画面 */
    @Test
    @Config(qualifiers = "w410dp-h502dp-xhdpi")
    fun mirrorRoundDialog() {
        shootRound("round-mirror-dialog") {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                ScreenMirror(
                    snap = live(),
                    online = true,
                    diameter = 224.dp,
                    page = ScreenPage.face,
                    line = ScreenDialogLine(source = DialogSource.asr, atMs = 0L),
                )
            }
        }
    }
}
