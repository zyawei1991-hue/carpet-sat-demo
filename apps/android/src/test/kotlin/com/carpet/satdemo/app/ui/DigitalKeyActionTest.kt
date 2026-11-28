package com.carpet.satdemo.app.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.carpet.satdemo.app.AppRuntime
import com.carpet.satdemo.app.engineStopOutcome
import com.carpet.satdemo.core.ai.PermissionTier
import com.carpet.satdemo.core.vehicle.XinyeCredentials
import com.carpet.satdemo.core.vehicle.XinyeDigitalKeyClient
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h836dp-xhdpi")
class DigitalKeyActionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val configFile: File
        get() = File(ApplicationProvider.getApplicationContext<Application>().noBackupFilesDir, "digital-key-partner.properties")

    private val outDir = File("build/screenshots").apply { mkdirs() }

    @After
    fun clearConfig() {
        configFile.delete()
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("demo", 0)
            .edit()
            .putString("ai_tier", PermissionTier.ReadLocal.name)
            .remove("engine_stop_attempt_at")
            .remove("engine_stop_history_id")
            .remove("engine_stop_lookup_id")
            .remove("engine_stop_lookup_source")
            .commit()
    }

    @Test
    fun receiptTextNeverTurnsUnsuccessIntoSuccess() {
        assertEquals("成功", engineStopOutcome("Cut off the fuel supply: Success!"))
        assertEquals("失败", engineStopOutcome("unsuccess"))
        assertEquals("失败", engineStopOutcome("not success"))
        assertEquals("失败", engineStopOutcome("未成功"))
        assertEquals("失败", engineStopOutcome("执行失败"))
        assertEquals("结果未识别", engineStopOutcome("车辆已回复"))
        assertEquals("结果未识别", engineStopOutcome("指令发送成功，等待车辆执行"))
        assertEquals("结果未识别", engineStopOutcome("ENGINE STOP success=false"))
        assertEquals("结果未识别", engineStopOutcome("engine stop success not confirmed"))
        assertEquals("结果未识别", engineStopOutcome("prefix ENGINE STOP Success"))
        assertEquals("结果未识别", engineStopOutcome(null))
    }

    @Test
    fun remoteEngineStopUsesRealActionAndMissingConfigIsVisible() {
        configFile.delete()
        val app = AppRuntime(ApplicationProvider.getApplicationContext())
        try {
            compose.setContent { CarpetTheme { SceneActions(listOf("寻车", "远程熄火", "报一下车况"), app) } }
            compose.waitForIdle()
            compose.onNodeWithText("熄火待配置").assertExists()
            compose.runOnIdle { app.remoteEngineStop() }
            compose.runOnIdle {
                assertTrue(app.snack.value.orEmpty().contains("不可逆权限"))
            }
            compose.runOnIdle { app.setAiGranted(PermissionTier.Irreversible) }
            compose.runOnIdle { app.remoteEngineStop() }
            compose.runOnIdle {
                assertTrue(app.snack.value.orEmpty().contains("数字钥匙未配置"))
            }
            compose.onNodeWithText("远程熄火").performClick()
            val picker = shadowOf(compose.activity).nextStartedActivity
            assertEquals(Intent.ACTION_GET_CONTENT, picker.action)
            assertTrue(picker.categories?.contains(Intent.CATEGORY_OPENABLE) == true)
            assertEquals("*/*", picker.type)
            assertFalse(configFile.exists())
            assertFalse(app.snack.value.orEmpty().contains("test-key"))
            compose.onNodeWithText("寻车").performClick()
            compose.runOnIdle {
                assertEquals("「寻车」还没连上车，现在做不了", app.snack.value)
            }
            shoot("digital-key-action-384")
        } finally {
            app.release()
        }
    }

    @Test
    fun configuredFileChangesOnlyTheEngineStopStatus() {
        configFile.writeText(validConfig)
        val app = AppRuntime(ApplicationProvider.getApplicationContext())
        try {
            compose.setContent { CarpetTheme { SceneActions(listOf("寻车", "远程熄火", "报一下车况"), app) } }
            compose.waitForIdle()
            compose.onNodeWithText("熄火已接合作方").assertExists()
            assertFalse(app.snack.value.orEmpty().contains("test-key"))
            shoot("digital-key-configured-384")
        } finally {
            app.release()
        }
    }

    @Test
    fun configImportRejectsInvalidAndOversizedFilesWithoutReplacingGoodConfig() {
        val app = AppRuntime(ApplicationProvider.getApplicationContext())
        try {
            assertTrue(app.importDigitalKeyText(validConfig))
            assertEquals(validConfig, configFile.readText())
            assertFalse(app.importDigitalKeyText("appid=test-app\nkey=test-key\n"))
            assertFalse(app.importDigitalKeyText("x=" + "a".repeat(64 * 1024)))
            assertEquals(validConfig, configFile.readText())
            assertTrue(app.digitalKeyConfigured())
        } finally {
            app.release()
        }
    }

    @Test
    fun ambiguousEngineStopNeverResendsUntilPermissionIsCycled() {
        configFile.writeText(validConfig)
        val sends = AtomicInteger()
        fun fakeApp() = AppRuntime(ApplicationProvider.getApplicationContext()) { credentials ->
            XinyeDigitalKeyClient(credentials, transport = { request ->
                when {
                    request.url.encodedPath.endsWith("/auth") -> response(
                        request,
                        """{"code":0,"expiresIn":7200000,"accessToken":"token-1"}""",
                    )
                    request.url.encodedPath.endsWith("/xinye/instruction/send") -> {
                        if (sends.incrementAndGet() == 1) throw IOException("response lost")
                        response(
                            request,
                            """{"code":0,"details":[{"code":0,"historyId":"264","uuid":"u-2","status":0}]}""",
                        )
                    }
                    request.url.encodedPath.endsWith("/instruction/relay") -> response(
                        request,
                        """{"code":0,"details":[{"code":0,"uuid":"u-2","status":1,"response":"ENGINE STOP Success"}]}""",
                    )
                    else -> error("unexpected request")
                }
            })
        }
        var app = fakeApp()
        try {
            compose.setContent { CarpetTheme { Text("ready") } }
            compose.runOnIdle { app.setAiGranted(PermissionTier.Irreversible) }
            compose.runOnIdle { app.remoteEngineStop() }
            awaitSnack(app, "远程熄火结果未确认")
            compose.runOnIdle { app.remoteEngineStop() }
            compose.runOnIdle {
                assertTrue(
                    app.snack.value.orEmpty().contains("当前不重发") ||
                        app.snack.value.orEmpty().contains("先别重复发"),
                )
            }
            assertEquals(1, sends.get())

            app.release()
            app = fakeApp()
            compose.runOnIdle { app.remoteEngineStop() }
            compose.runOnIdle { assertTrue(app.snack.value.orEmpty().contains("当前不重发")) }
            assertEquals(1, sends.get())

            compose.runOnIdle {
                app.setAiGranted(PermissionTier.ReadLocal)
                app.setAiGranted(PermissionTier.Irreversible)
                app.remoteEngineStop()
            }
            awaitSnack(app, "车辆已回复远程熄火指令")
            compose.runOnIdle { assertTrue(app.snack.value.orEmpty().contains("结果=成功")) }
            assertEquals(2, sends.get())
        } finally {
            app.release()
        }
    }

    @Test
    fun unrecognizedVehicleReplyKeepsTheResendGuardAndOnlyPolls() {
        configFile.writeText(validConfig)
        val sends = AtomicInteger()
        val receipts = AtomicInteger()
        fun fakeApp() = AppRuntime(ApplicationProvider.getApplicationContext()) { credentials ->
            XinyeDigitalKeyClient(credentials, transport = { request ->
                when {
                    request.url.encodedPath.endsWith("/auth") -> response(
                        request,
                        """{"code":0,"expiresIn":7200000,"accessToken":"token-1"}""",
                    )
                    request.url.encodedPath.endsWith("/xinye/instruction/send") -> {
                        sends.incrementAndGet()
                        response(
                            request,
                            """{"code":0,"details":[{"code":0,"historyId":"263","uuid":"u-1","status":0}]}""",
                        )
                    }
                    request.url.encodedPath.endsWith("/instruction/relay") -> {
                        receipts.incrementAndGet()
                        response(
                            request,
                            """{"code":0,"details":[{"code":0,"uuid":"u-1","status":1,"response":"车辆已回复"}]}""",
                        )
                    }
                    else -> error("unexpected request")
                }
            })
        }
        var app = fakeApp()
        try {
            compose.setContent { CarpetTheme { Text("ready") } }
            compose.runOnIdle { app.setAiGranted(PermissionTier.Irreversible) }
            compose.runOnIdle { app.remoteEngineStop() }
            awaitSnack(
                app,
                "结果=结果未识别",
                "当前不重发",
            )
            assertEquals(1, sends.get())
            assertEquals(1, receipts.get())

            app.release()
            app = fakeApp()
            compose.runOnIdle { app.remoteEngineStop() }
            awaitSnack(app, "当前不重发")
            assertEquals(2, receipts.get())
            assertEquals(1, sends.get())
        } finally {
            app.release()
        }
    }

    @Test
    fun permissionCycleWhileSendIsInFlightCannotEraseTheAttempt() {
        configFile.writeText(validConfig)
        val sends = AtomicInteger()
        val sendEntered = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        fun fakeApp() = AppRuntime(ApplicationProvider.getApplicationContext()) { credentials ->
            XinyeDigitalKeyClient(credentials, transport = { request ->
                when {
                    request.url.encodedPath.endsWith("/auth") -> response(
                        request,
                        """{"code":0,"expiresIn":7200000,"accessToken":"token-1"}""",
                    )
                    request.url.encodedPath.endsWith("/xinye/instruction/send") -> {
                        sends.incrementAndGet()
                        sendEntered.countDown()
                        assertTrue(releaseSend.await(5, TimeUnit.SECONDS))
                        throw IOException("response lost")
                    }
                    else -> error("unexpected request")
                }
            })
        }
        var app = fakeApp()
        try {
            compose.setContent { CarpetTheme { Text("ready") } }
            compose.runOnIdle { app.setAiGranted(PermissionTier.Irreversible) }
            compose.runOnIdle { app.remoteEngineStop() }
            compose.waitUntil(5_000) { sendEntered.count == 0L }
            compose.runOnIdle {
                app.setAiGranted(PermissionTier.ReadLocal)
                app.setAiGranted(PermissionTier.Irreversible)
            }
            compose.runOnIdle { assertTrue(app.snack.value.orEmpty().contains("还在确认中")) }
            compose.runOnIdle { releaseSend.countDown() }
            awaitSnack(app, "远程熄火结果未确认")

            app.release()
            app = fakeApp()
            compose.runOnIdle { app.remoteEngineStop() }
            compose.runOnIdle { assertTrue(app.snack.value.orEmpty().contains("当前不重发")) }
            assertEquals(1, sends.get())
        } finally {
            releaseSend.countDown()
            app.release()
        }
    }

    @Test
    fun permissionRevokedDuringAuthPreventsTheInstructionSend() {
        configFile.writeText(validConfig)
        val sends = AtomicInteger()
        lateinit var app: AppRuntime
        app = AppRuntime(ApplicationProvider.getApplicationContext()) { credentials ->
            XinyeDigitalKeyClient(
                credentials,
                transport = { request ->
                    when {
                        request.url.encodedPath.endsWith("/auth") -> {
                            app.setAiGranted(PermissionTier.ReadLocal)
                            response(
                                request,
                                """{"code":0,"expiresIn":7200000,"accessToken":"token-1"}""",
                            )
                        }
                        request.url.encodedPath.endsWith("/xinye/instruction/send") -> {
                            sends.incrementAndGet()
                            error("permission was revoked")
                        }
                        else -> error("unexpected request")
                    }
                },
                beforeSend = {
                    PermissionTier.allows(app.aiGranted.value, PermissionTier.Irreversible)
                },
            )
        }
        try {
            compose.setContent { CarpetTheme { Text("ready") } }
            compose.runOnIdle { app.setAiGranted(PermissionTier.Irreversible) }
            compose.runOnIdle { app.remoteEngineStop() }
            awaitSnack(app, "远程熄火已取消", "本次未发送")
            assertEquals(0, sends.get())
            compose.runOnIdle { app.remoteEngineStop() }
            compose.runOnIdle { assertTrue(app.snack.value.orEmpty().contains("不可逆权限")) }
        } finally {
            app.release()
        }
    }

    private fun response(request: Request, body: String) = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("stub")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    private fun awaitSnack(app: AppRuntime, vararg expected: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            compose.runOnIdle { }
            val actual = app.snack.value.orEmpty()
            if (expected.all { actual.contains(it) }) return
            Thread.sleep(25)
        }
        throw AssertionError("等待提示 ${expected.toList()}，实际=${app.snack.value}")
    }

    private val validConfig = """
        appid=test-app
        key=test-key
        licenseNumber=TEST-001
    """.trimIndent()

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
