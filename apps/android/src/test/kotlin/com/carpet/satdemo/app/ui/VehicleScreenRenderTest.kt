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
import com.carpet.satdemo.core.ephemeris.EphemerisCopy
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
 * **车辆页，喂了数据之后拍。**
 *
 * 为什么要有这一张：上一轮我按截图 harness 改了六处空态，然后告诉用户"修好了"，
 * 他用真机照片回我"车辆页还是很奇怪"。原因在这套 harness 自己身上 ——
 * [AppRuntime] 在测试里从来没 `start()` 过，1Hz 那一圈不转，`status.snapshot` 一直是 null，
 * 于是 `CountdownRing` / 时间条 / `AimDial` 这些**只在有数时才画的分支，一张都没拍到过**。
 * 我看到的"干净"是空态的干净，用户看到的是有数据的脏。
 * **harness 只拍缺省状态，等于没拍。**
 *
 * 这一张走的是另一条路：**直接构造 [StatePayload] 喂给 [VehicleTab]**，不碰 runtime。
 * 数字是编的（561 秒 = 9 分 21 秒，就是真机照片里那个），但版式是真的 ——
 * 这些 png 只用于自查，不进版本库，也不会当成"实测数据"发给任何人。
 *
 * 三个视口各拍一张整页（411 / 384 / 360），是照着 §5.2 那条"只按一种视口自测等于没测"来的：
 * 384 是华为 Mate60，360 是老机型和分屏的下限。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VehicleScreenRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    /** 有星历、有方位、锁着星：真机照片那一屏 */
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
        gnss = GnssState(30.2541, 120.1456, altM = 12.0, hdop = 0.9, source = GnssSource.phone, fixAgeS = 2.0),
        fence = FenceState(active = true, inside = true, name = "西湖景区", distM = 320.0),
        sos = SosState(level = SosLevel.normal),
        phoneBattery = 87,
    )

    /** 星历没取到：车辆页必须收成一行，不许留三个 `--` */
    private fun noEph(): StatePayload = live().copy(
        sat = live().sat.copy(
            phase = SatPhase.idle,
            satName = null,
            cnr = null,
            nextPassInS = null,
            ephState = EphState.NETWORK_ERROR,
            // 用产品侧那句成品文案，不是我自己编的：这张图也要能核对"E.9 那句人话上没上屏"
            ephFail = EphemerisCopy.failMessage(EphState.NETWORK_ERROR),
            nextPassDurS = null,
            nextPassMaxEl = null,
            aimAz = null,
        ),
        gnss = GnssState(0.0, 0.0, altM = null, hdop = null, source = GnssSource.phone, fixAgeS = null),
        fence = FenceState(active = false, inside = true, name = null, distM = null),
    )

    /**
     * 一个测试方法只能 `setContent` 一次（ComposeActivityRule 第二次会直接抛
     * IllegalStateException），所以"有数/无数"两档各占一个方法，视口靠各自的 @Config 区分。
     */
    @Test
    @Config(qualifiers = "w411dp-h2600dp-xxhdpi")
    fun renderPopulated411() {
        shootPage("vehicle-411-live", live())
        assertTrue(
            "有数那张必须落盘",
            File(outDir, "vehicle-411-live.png").length() > 1000,
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h2600dp-xxhdpi")
    fun renderNoEphemeris411() {
        shootPage("vehicle-411-empty", noEph())
    }

    /** Mate60 那一档：384dp。照片就是这台机器拍的 */
    @Test
    @Config(qualifiers = "w384dp-h2600dp-xhdpi")
    fun renderMate60() {
        shootPage("vehicle-384-live", live())
    }

    @Test
    @Config(qualifiers = "w384dp-h2600dp-xhdpi")
    fun renderMate60NoEphemeris() {
        shootPage("vehicle-384-empty", noEph())
    }

    /** 384dp 但只有**一屏**高：这张才回答"用户点开车辆页第一眼看的是什么" */
    @Test
    @Config(qualifiers = "w384dp-h836dp-xhdpi")
    fun renderMate60FirstScreen() {
        shootPage("vehicle-384-fold", live())
    }

    @Test
    @Config(qualifiers = "w360dp-h2400dp-xhdpi")
    fun renderNarrowest() {
        shootPage("vehicle-360-live", live())
    }

    private fun shootPage(name: String, payload: StatePayload) {
        val app = AppRuntime(ApplicationProvider.getApplicationContext())
        app.pairScreen("desk-01")
        val status = DemoRuntime.Status(
            running = true,
            phase = LinkPhase.CELL,
            snapshot = payload,
            clients = 1,
            localIp = "192.168.1.9",
        )
        compose.setContent { CarpetTheme { VehicleTab(app, status) } }
        compose.waitForIdle()
        shoot(name)
        app.release()
    }

    /** 同 [HomeScreenRenderTest]：`captureToImage()` 在 Robolectric 下等不到那一帧，直接画进 Bitmap */
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
