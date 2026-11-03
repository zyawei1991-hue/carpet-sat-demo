package com.carpet.satdemo.app.ui

import com.carpet.satdemo.core.ephemeris.EphState
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
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **没有屏接入时，这块镜像不许借用任何语义色。**
 *
 * 为什么要写成测试而不是评审意见：V1.10.8 交付时我只把外环打灰，圆里照旧是卫星蓝的星名、
 * 琥珀的相位环、绿的开窗数 —— 截图上它和"屏真的亮着"长得一模一样。这类回退不是靠记性防得住的：
 * 下一个人觉得"灰圈太素了，给点颜色"就会直接把语义色加回去，而他不会先去查这块屏到底连没连。
 *
 * 这条尺子只问一件事：`online=false` 时，两支颜色必须都是结构灰。
 */
class ScreenMirrorHonestyTest {

    private fun payload(primary: LinkPrimary, phase: SatPhase) = StatePayload(
        link = LinkState(primary = primary, cellOk = true, satOk = true),
        sat = SatState(
            phase = phase,
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

    @Test
    fun `首帧和没屏接入时链路环都是结构灰`() {
        assertEquals(Ink.Track, mirrorLinkColor(null, online = true))
        assertEquals(Ink.Track, mirrorLinkColor(payload(LinkPrimary.sat, SatPhase.locked), online = false))
        assertEquals(Ink.Track, mirrorLinkColor(payload(LinkPrimary.cell, SatPhase.locked), online = false))
    }

    @Test
    fun `真的接上屏才给语义色`() {
        assertEquals(Ink.Sat, mirrorLinkColor(payload(LinkPrimary.sat, SatPhase.locked), online = true))
        assertEquals(Ink.Ok, mirrorLinkColor(payload(LinkPrimary.cell, SatPhase.locked), online = true))
    }

    @Test
    fun `没屏接入时相位环一档都不许出琥珀或红`() {
        SatPhase.entries.forEach { p ->
            assertEquals("phase=$p 在未接入时不该有语义色", Ink.Outline, mirrorPhaseColor(p, online = false))
        }
    }

    @Test
    fun `接入后相位环按相位表态`() {
        assertEquals(Ink.Wait, mirrorPhaseColor(SatPhase.locked, online = true))
        assertEquals(Ink.Alert, mirrorPhaseColor(SatPhase.tx_fail, online = true))
        assertEquals(Ink.Alert, mirrorPhaseColor(SatPhase.tx_no_ack, online = true))
    }
}
