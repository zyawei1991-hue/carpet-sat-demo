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
    data class SatSending(val attempt: Int, val frameHex: String, val via: SatPath) : SosProgress

    /** 三态 + 成因。展示端的红字由这条生成，不由 UI 自己判断（§6.1 红线一） */
    data class SatResultReady(val attempt: Int, val send: SatSend) : SosProgress

    /** 双发里的蜂窝那一路。cell 不可用时不会发，也就不会回调 */
    data class CellReady(val ok: Boolean) : SosProgress

    /** 重发用尽。展示端要弹红字并显示 nextPassInS，不能只转圈 */
    data class Exhausted(val attempts: Int, val last: SatSend, val nextPassInS: Long?) : SosProgress
}

/**
 * SOS 例外流程。规范见 protocol/link-policy.md「SOS 例外」。
 *
 * 关键点一：SOS **不走链路状态机**。判链路要 20s，人等不起，
 * 所以这里无条件双发，蜂窝那一路能不能成由调用方给的 [cellOk] 决定。
 *
 * 关键点二（V1.3 新增）：**重发的含义随发送方式变**。
 *  - 即时发送（MCQ）：三次无 ACK 重发是三次不同的尝试，合理；
 *  - 排队发送（SFQ）：模组会自己在下次过境时发出去，**再存一遍就是在队列里放三条重复 SOS**
 *    （GM1003 一共 50 个位）。所以排队那一路只在"模组没收下"时才重试，收下了就交给过境。
 *
 * 语句级模组（[SentenceModem]）不支持即时发送（MCQ 线格式没文档，§9 #13），
 * 所以真机到货初期走的是排队那一路。
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
    ): SatSend {
        val frame = encoder.encode(SosFrame.MSG_SOS, lat, lon, whenSec, batteryPct, flags)
        val hex = frame.toHex()

        val uploader = cell
        if (cellOk && uploader != null) {
            progress(SosProgress.CellReady(runCatching { uploader.postSos(hex) }.getOrDefault(false)))
        }

        val via = if (modem.supportsImmediateSend) SatPath.IMMEDIATE else SatPath.QUEUED
        var last = SatSend.failed(via, SatFailReason.NO_REPLY)
        repeat(policy.sosMaxAttempts) { i ->
            val attempt = i + 1
            progress(SosProgress.SatSending(attempt, hex, via))
            last = runCatching {
                if (via == SatPath.IMMEDIATE) modem.sendMcq(hex) else modem.enqueueSfq(hex)
            }.getOrDefault(SatSend.failed(via, SatFailReason.NO_REPLY))
            progress(SosProgress.SatResultReady(attempt, last))
            // 即时那一路要星上确认才算成；排队那一路只要模组收下就交给过境
            if (last.result == SatResult.COMPLETE || (via == SatPath.QUEUED && last.accepted)) return last
        }

        progress(SosProgress.Exhausted(policy.sosMaxAttempts, last, runCatching { modem.secondsToNextPass() }.getOrNull()))
        return last
    }

    /**
     * 误触撤销。走同一条卫星链路，所以也要等确认才算真撤了。
     *
     * 返回里带 [CancelOutcome.frameHex]：撤销**不发 progress**（它不是三次重发那套流程），
     * 调用方拿不到 hex 就没法在日志和协议事件里说清"撤的是哪一帧"。
     */
    suspend fun cancel(
        lat: Double,
        lon: Double,
        whenSec: Long,
        batteryPct: Int,
        flags: Int,
    ): CancelOutcome {
        val hex = encoder.encode(SosFrame.MSG_SOS_CANCEL, lat, lon, whenSec, batteryPct, flags).toHex()
        val via = if (modem.supportsImmediateSend) SatPath.IMMEDIATE else SatPath.QUEUED
        val send = runCatching {
            if (via == SatPath.IMMEDIATE) modem.sendMcq(hex) else modem.enqueueSfq(hex)
        }.getOrDefault(SatSend.failed(via, SatFailReason.NO_REPLY))
        return CancelOutcome(send, hex)
    }

    /** 撤销的结果：报文本身 + 它是哪一帧 */
    data class CancelOutcome(val send: SatSend, val frameHex: String)
}
