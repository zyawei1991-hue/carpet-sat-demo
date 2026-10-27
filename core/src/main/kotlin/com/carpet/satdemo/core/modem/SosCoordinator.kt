package com.carpet.satdemo.core.modem

import com.carpet.satdemo.core.link.LinkPolicy
import com.carpet.satdemo.core.protocol.SosFrame
import com.carpet.satdemo.core.protocol.SosFrameEncoder
import com.carpet.satdemo.core.protocol.toHex

/** 蜂窝侧上报。App 里是 HTTPS POST，单测里塞个假的。 */
fun interface CellUploader {
    suspend fun postSos(frameHex: String): Boolean
}

sealed interface SosProgress {
    /** 双发里的卫星那一路。attempt 从 1 开始 */
    data class SatSending(val attempt: Int, val frameHex: String) : SosProgress
    data class SatResultReady(val attempt: Int, val result: SatResult) : SosProgress
    /** 双发里的蜂窝那一路。cell 不可用时不会发，也就不会回调 */
    data class CellReady(val ok: Boolean) : SosProgress
    /** 三次都没 ACK。展示端要弹红字并显示 nextPassInS，不能只转圈 */
    data class Exhausted(val attempts: Int, val nextPassInS: Long?) : SosProgress
}

/**
 * SOS 例外流程。规范见 protocol/link-policy.md「SOS 例外」。
 *
 * 关键点：SOS **不走链路状态机**。判链路要 20s，人等不起，
 * 所以这里无条件双发，蜂窝那一路能不能成由调用方给的 [cellOk] 决定。
 */
class SosCoordinator(
    private val modem: SatelliteModem,
    private val encoder: SosFrameEncoder,
    private val policy: LinkPolicy,
    private val cell: CellUploader? = null,
    private val progress: (SosProgress) -> Unit = {},
) {

    suspend fun trigger(
        lat: Double,
        lon: Double,
        whenSec: Long,
        batteryPct: Int,
        flags: Int,
        cellOk: Boolean,
    ): SatResult {
        val frame = encoder.encode(SosFrame.MSG_SOS, lat, lon, whenSec, batteryPct, flags)
        val hex = frame.toHex()

        val uploader = cell
        if (cellOk && uploader != null) {
            progress(SosProgress.CellReady(runCatching { uploader.postSos(hex) }.getOrDefault(false)))
        }

        var last = SatResult.FAIL
        repeat(policy.sosMaxAttempts) { i ->
            val attempt = i + 1
            progress(SosProgress.SatSending(attempt, hex))
            last = runCatching { modem.sendMcq(hex) }.getOrDefault(SatResult.FAIL)
            progress(SosProgress.SatResultReady(attempt, last))
            if (last == SatResult.COMPLETE) return SatResult.COMPLETE
        }

        progress(SosProgress.Exhausted(policy.sosMaxAttempts, runCatching { modem.secondsToNextPass() }.getOrNull()))
        return last
    }

    /** 误触撤销。走同一条卫星链路，所以也要等 ACK 才算真撤了 */
    suspend fun cancel(lat: Double, lon: Double, whenSec: Long, batteryPct: Int, flags: Int): SatResult {
        val hex = encoder.encode(SosFrame.MSG_SOS_CANCEL, lat, lon, whenSec, batteryPct, flags).toHex()
        return runCatching { modem.sendMcq(hex) }.getOrDefault(SatResult.FAIL)
    }
}
