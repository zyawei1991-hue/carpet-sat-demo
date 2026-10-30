package com.carpet.satdemo.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.ripple.LocalRippleTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **按下去之后有没有回声**：这三样是这一轮补的反馈，都必须被画出来看过才算数。
 *
 * 为什么单开一张而不是拍整页：这三件东西在真机上都只在**一瞬间**存在 ——
 * 按住 SOS 的那三秒、回答落地前的那两秒、第一条状态回来前的那半秒。
 * 整页截图靠 `waitForIdle()` 稳定，而那三段时间 Idle 之后早就没了。
 * 所以把图元抽成能吃参数的 composable，直接摆在这一张里量：
 * 环会不会切出导航栏、骨架条和真值是不是一样高、占位气泡和回答气泡是不是同一块位置。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w384dp-h836dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedbackStatesRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    /** 「上次成功是 09:14」那个瞬间。按本机时区凑出来，这张图是自查用的，不该跟着 CI 的时区变一个数 */
    private val EPOCH_0914: Long = ZonedDateTime
        .of(LocalDate.now(), LocalTime.of(9, 14), ZoneId.systemDefault())
        .toInstant().toEpochMilli()

    /** 视口给到 2600dp 高：这张是**状态对照表**，不是"首屏"，一屏装不下就得整张拍全，
     *  不然最下面那一档永远没被看过 —— 而它恰恰是新加的那一档 */
    @Test
    @Config(sdk = [34], qualifiers = "w384dp-h2600dp-xhdpi")
    fun renderFeedbackStates() {
        compose.setContent {
            CarpetTheme {
                Column(
                    modifier = Modifier.fillMaxSize().background(Ink.Deep).padding(Grid.Page),
                    verticalArrangement = Arrangement.spacedBy(Grid.x16),
                ) {
                    InkCard {
                        Text("按住 SOS：外圈走到第几段", style = Type.Caption)
                        Row(horizontalArrangement = Arrangement.spacedBy(Grid.x16)) {
                            SosArmRing(0.33f)
                            SosArmRing(0.66f)
                            SosArmRing(1f)
                        }
                    }
                    InkCard {
                        Text("这句还在路上", style = Type.Caption)
                        ThinkingBubble()
                    }
                    InkCard {
                        Text("第一条状态还没回来", style = Type.Caption)
                        ValueRow("主路", null, warming = true)
                        ValueRow("载噪比", null, warming = true)
                        ValueRow("主路", "蜂窝", tone = Tone.Ok)
                        ValueRow("载噪比", "12.4 dB")
                    }
                    InkCard {
                        // 过境卡/终端卡的首帧形状。整页那张拍到折线以下就没了，所以在这里单摆一份量：
                        // 28dp 那条替的是 24sp 的大数字，13dp 那条替的是正文 —— 真值落地时行高不能变
                        Text("首帧：大数字先垫一条和它同高的", style = Type.Caption)
                        SkeletonBar(width = 112.dp, height = 28.dp)
                        SkeletonBar(width = 188.dp)
                        SkeletonBar(width = 168.dp)
                    }
                    InkCard {
                        Text("这一块坏了：说清哪没拿到、上次成功是几点、能做什么", style = Type.Caption)
                        BlockError(
                            reason = "星历数据已过期，联网更新后重试",
                            lastOkAtMs = EPOCH_0914,
                            onRetry = { },
                        )
                        // 按下去那一下换成不可点的同尺寸标签：字换了但卡片不能跟着抖
                        BlockError(reason = "星历数据已过期，联网更新后重试", lastOkAtMs = EPOCH_0914, busy = true, onRetry = { })
                        // 从没成功过：那一行整个不写；没定位时「重试」也不给，按十次也是这句
                        BlockError(reason = "还没有定位，暂时算不出过境窗口")
                    }
                }
            }
        }
        compose.waitForIdle()
        shoot("feedback-states")
        val f = File(outDir, "feedback-states.png")
        assertTrue("没拍出来：$f", f.length() > 1000)
    }

    /**
     * **按压反馈不许溅品牌色**：Material 3 默认拿 `colorScheme.primary` 当 ripple，
     * 我们主色板的主色是卫星青 —— 不接管就是全站几十处点按都在闪青色。
     * 这条断言防的是"某天把 provider 删了"：删掉之后画面不会坏，只有按压色悄悄变回去，
     * 那种改动没人会专门去看第二遍，所以只能让测试盯着。
     */
    @Test
    fun rippleIsNeutralNotBrand() {
        var themed: Any? = null
        var unthemed: Any? = null
        compose.setContent {
            unthemed = LocalRippleTheme.current
            CarpetTheme { themed = LocalRippleTheme.current }
        }
        compose.waitForIdle()
        assertTrue("CarpetTheme 没接管 ripple：$themed", themed === InkRippleTheme)
        assertTrue("没套主题时读到的是 Material 默认，说明上面那条是被 CarpetTheme 换掉的", unthemed !== InkRippleTheme)
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
        writePng(File(outDir, "$name.png"), bmp.width, bmp.height, px)
    }
}
