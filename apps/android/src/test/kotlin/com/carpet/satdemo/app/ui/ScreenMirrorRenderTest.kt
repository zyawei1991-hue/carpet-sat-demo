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
import com.carpet.satdemo.core.ephemeris.EphState
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
 * **首页那块圆屏镜像，喂了数据之后拍。**
 *
 * 为什么单独一张：镜像是"有数才画"的东西，而 [HomeScreenRenderTest] 里 [AppRuntime] 从来不 `start()`，
 * `status.snapshot` 永远是 null —— 那一套 harness 只会拍到骨架。上两轮就是在这个坑里栽的：
 * **harness 只拍缺省状态，等于没拍。**
 *
 * 三个状态都得看：连着屏 / 没屏 / 首帧。因为这三格用的是同一套圆，差别只在环的颜色和圆里那一摞字，
 * 最容易出的错是把"没屏接入"画得像"屏上黑着"。
 *
 * 数字是编的（这一屏只用于自查），产物落 `build/screenshots/`，不进版本库。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenMirrorRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    private fun live(): StatePayload = StatePayload(
        link = LinkState(primary = LinkPrimary.cell, cellOk = true, satOk = true),
        sat = SatState(
            phase = SatPhase.locked,
            satName = "GW-03",
            cnr = 12.4,
            nextPassInS = 561,
            moduleOk = true,
            queuePending = 3,
            queueCapacity = 50,
            tpiAgeS = 95,
            tpiStale = false,
            ephState = EphState.SUCCESS,
            nextPassDurS = 240,
            nextPassMaxEl = 18.0,
            aimAz = 45.0,
        ),
        gnss = GnssState(30.25412, 120.14563, altM = 12.0, hdop = 0.9, source = GnssSource.phone, fixAgeS = 2.0),
        fence = FenceState(active = true, inside = true, name = "西湖景区", distM = 320.0),
        sos = SosState(level = SosLevel.normal),
        phoneBattery = 87,
    )

    /** 卫星为主链路 + 窗口开着：外圈该接成整圈，中间那个数该是"开窗" */
    private fun inWindow(): StatePayload = live().copy(
        link = LinkState(primary = LinkPrimary.sat, cellOk = false, satOk = true),
        sat = live().sat.copy(phase = SatPhase.tx_pending, nextPassInS = 0, queuePending = 0, queueCapacity = null),
    )

    /** 终端掉线：圆里那句"补发等终端回来"和那个 3/50 条，必须一眼和"正常等过顶"分得开 */
    private fun moduleOff(): StatePayload = live().copy(
        sat = live().sat.copy(moduleOk = false, phase = SatPhase.tx_fail, cnr = null, nextPassInS = null),
    )

    private fun shootHome(name: String, payload: StatePayload?, clients: Int) {
        val app = AppRuntime(ApplicationProvider.getApplicationContext())
        app.pairScreen("desk-01")
        val status = DemoRuntime.Status(
            running = true,
            phase = LinkPhase.CELL,
            snapshot = payload,
            clients = clients,
            localIp = "192.168.1.9",
        )
        compose.setContent { CarpetTheme { HomeShell(app, status) } }
        compose.waitForIdle()
        shoot(name)
        app.release()
    }

    @Test
    @Config(qualifiers = "w384dp-h1600dp-xhdpi")
    fun renderConnected() {
        shootHome("mirror-384-connected", live(), 1)
    }

    @Test
    @Config(qualifiers = "w384dp-h1600dp-xhdpi")
    fun renderSatWindow() {
        shootHome("mirror-384-sat", inWindow(), 1)
    }

    @Test
    @Config(qualifiers = "w384dp-h1600dp-xhdpi")
    fun renderModuleOff() {
        shootHome("mirror-384-module-off", moduleOff(), 1)
    }

    /** 白名单里填过号、但此刻一台都没连上：这一帧没人看，圆必须看得出来"没观众" */
    @Test
    @Config(qualifiers = "w384dp-h1600dp-xhdpi")
    fun renderNoClient() {
        shootHome("mirror-384-no-client", live(), 0)
    }

    /** 首帧：snapshot 还是 null。这一张盯骨架 —— 四条垫的位置要和真值那四行对上 */
    @Test
    @Config(qualifiers = "w384dp-h1600dp-xhdpi")
    fun renderFirstFrame() {
        shootHome("mirror-384-warming", null, 0)
    }

    /** 字号 1.3：圆里那几行是固定宽度 + maxLines=1，字一大只会被裁。裁成什么样必须看见 */
    @Test
    @Config(qualifiers = "w384dp-h1600dp-xhdpi")
    fun renderLargeFont() {
        val cfg = compose.activity.resources.configuration
        cfg.fontScale = 1.3f
        @Suppress("DEPRECATION")
        compose.activity.resources.updateConfiguration(cfg, null)
        shootHome("mirror-384-font130", live(), 1)
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
