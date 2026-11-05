package com.carpet.satdemo.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.carpet.satdemo.app.ui.PrivacyTier
import com.carpet.satdemo.core.maint.WorkOrderCodec
import com.carpet.satdemo.core.protocol.MessageBudget
import com.carpet.satdemo.core.vehicle.ValueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 手机这一半的那层薄壳：喂给 `:core` 引擎的读数是哪来的、勾了哪几项、什么档位能出网。
 *
 * 这一层最怕两件事：**桩数据被标成实测**，和**车主填的东西重开就没了**。
 * 两条都各占一组测试。
 */
@RunWith(AndroidJUnit4::class)
class MaintStoreTest {

    private fun store() = MaintStore(
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("t-${System.nanoTime()}", 0),
    )

    @Test
    fun `quota stays a stub until a real terminal is attached`() {
        val s = store()
        val board = s.board(quotaWaiting = 480, quotaCapacity = 500, quotaReal = false)
        val sg = s.suggest(board).single { it.rule == "quota.pack" }
        // 额度那条数现在是从模拟模组读的 —— 那它就是桩，标必须在
        assertEquals("示意数据 · 未接真源", sg.badge)
        assertEquals(ValueSource.Stub, sg.evidence.first().source)
    }

    @Test
    fun `no capacity means no quota suggestion at all`() {
        // 没读到型号就不说。"说不准"不是一条建议，是一行噪音
        val s = store()
        assertTrue(s.suggest(s.board(null, null, false)).none { it.rule == "quota.pack" })
        assertTrue(s.suggest(s.board(0, 0, false)).none { it.rule == "quota.pack" })
    }

    @Test
    fun `what the owner typed is still there after the store is rebuilt`() {
        val a = store()
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("t-shared", 0)
        val withPrefs = MaintStore(prefs)
        withPrefs.setFilterDays(500)
        withPrefs.setHasChild(true)
        assertEquals(500, MaintStore(prefs).filterDays())
        assertTrue(MaintStore(prefs).hasChild())
        // 孩子那一条会改变判据的严重度，所以这个开关不是装饰
        val kid = withPrefs.suggest(withPrefs.board(null, null, false)).single { it.rule == "cabin.filter" }
        assertEquals("kids", kid.sceneKey)
        assertFalse(a.hasChild())
    }

    @Test
    fun `local-only tier refuses the uplink whichever side is stricter`() {
        val s = store()
        assertFalse(s.canSendOut(PrivacyTier.Guarded, PrivacyTier.Strict))
        // 底座比家长设置更严时也得拦住：取的是更严的那个，不是"最后设置的那个"
        assertFalse(s.canSendOut(PrivacyTier.Strict, PrivacyTier.Open))
        assertTrue(s.canSendOut(PrivacyTier.Guarded, PrivacyTier.Open))
    }

    @Test
    fun `a picked list becomes one frame that fits the smallest budget`() {
        val s = store()
        val all = s.suggest(s.board(480, 500, false))
        assertTrue("没勾过之前不该有东西进工单", s.picked(all).isEmpty())
        val chosen = all.take(3)
        chosen.forEach { s.toggle(it.code) }
        assertEquals(3, s.picked(all).size)
        val bytes = WorkOrderCodec.encode(s.order(chosen))
        assertEquals(WorkOrderCodec.BYTES, bytes.size)
        assertTrue(WorkOrderCodec.fits(MessageBudget.WORST_KNOWN))
        assertEquals(chosen.map { it.code }, WorkOrderCodec.decode(bytes).items.map { it.code })
        s.clearSelected()
        assertTrue(s.picked(all).isEmpty())
    }

    @Test
    fun `the store admits which parts of the order are invented`() {
        // 车架号要 OBD 接上才读得到（09 02）；额度现在是模拟模组报的。
        // 这两个 getter 是界面那句"车架号是编的"的出处 —— 它们必须一直说实话，直到真源进来
        val s = store()
        assertFalse(s.vinReal)
        assertFalse(s.quotaReal)
        assertEquals("示意判据 · 未接手册", s.criteria.badge)
    }
}
