package com.carpet.satdemo.core.ai

import java.io.ByteArrayOutputStream

/**
 * v4 上行（`command{cmd:mic_chunk}`）的拼装端。
 *
 * 屏在松手那一刻把整段 PCM 一口气推完（`protocol/screen-link.md` §上行音频通道）：
 * `args = {turn_id, seq, total, rate, b64}`，2560 字节一块、`seq` 从 0 连续、`total`
 * 从第一帧起就是整段字节数。拼装判据只有一条"攒到 total 就齐"，缺号当场判废 ——
 * 和 v3 下行那套共用同一条纪律。
 *
 * 这里只拼不转写：ASR 归宿主（`SiliconFlowAsr`），core 不知道网关在哪。
 * **拼和转写都必须跑在 IO 线程**：上行是 ≈170–190 KB/s 持续 0.5–0.7 秒的突发
 * （屏侧 2026-11-11 夜实测），base64 解码加拷贝放进 WebSocket 读线程或 UI 线程都会抖。
 */
object MicUplink {

    /** 屏侧三道闸里那条硬上限：8 秒采集 = 100 帧 = 256 KB。超了整段拒收，不收半段 */
    const val MAX_FRAMES = 100
    const val MAX_TOTAL_BYTES = 256 * 1024

    /** 屏只发 16k（`carpet_mic.c` 采集口径），别的采样率整段不接 —— 对齐屏侧 audio 那条红线 */
    const val SAMPLE_RATE = 16000

    /**
     * 拼完的那一段。`frames` 给日志/界面用（"43 帧、110,080 字节、3.4 秒语音"），
     * 现场验收看的就是这三个数（`docs/APP-CHANGES-2026-11-11.md` §0）。
     */
    data class Segment(val turnId: String, val pcm: ByteArray, val frames: Int) {
        val seconds: Double get() = AudioChunks.secondsOf(pcm.size)
    }

    /**
     * 一个 turn 的积攒器。**同一时刻只认一个 turn_id**：屏上一颗键，说一句是一轮，
     * 中途换号说明屏那侧出岔了 —— 丢掉旧段从新号重攒，比把两段语音缝成一句更接近真相。
     */
    class Collector {
        private var turnId: String? = null
        private var total = -1
        private var nextSeq = 0
        private val buf = ByteArrayOutputStream()

        sealed class Result {
            data object More : Result()
            data class Done(val segment: Segment) : Result()
            /** 段作废、从头再来。`reason` 给日志：现场要能分清是链路丢帧还是屏发错了 */
            data class Reject(val reason: String) : Result()
        }

        fun ingest(turnId: String, seq: Int, total: Int, rate: Int, chunk: ByteArray): Result {
            if (turnId != this.turnId) {
                buf.reset()
                this.turnId = turnId
                this.total = total
                nextSeq = 0
            }
            if (total !in 1..MAX_TOTAL_BYTES) return restart("$turnId total 越界：$total")
            if (rate != SAMPLE_RATE) return restart("$turnId 采样率 $rate，本端只吃 $SAMPLE_RATE")
            if (seq != nextSeq) return restart("$turnId 缺号：等到 $seq，应该 $nextSeq")
            buf.write(chunk)
            nextSeq++
            if (nextSeq > MAX_FRAMES) return restart("$turnId 超过 $MAX_FRAMES 帧上限")
            if (buf.size() > total) return restart("$turnId 收到的比 total 声明的还多")
            if (buf.size() == total) {
                val segment = Segment(turnId, buf.toByteArray(), nextSeq)
                buf.reset()
                this.turnId = null
                nextSeq = 0
                return Result.Done(segment)
            }
            return Result.More
        }

        private fun restart(reason: String): Result.Reject {
            buf.reset()
            turnId = null
            nextSeq = 0
            return Result.Reject(reason)
        }
    }

    /**
     * 16k/单声道/s16le 裸 PCM → 完整 WAV（44 字节 RIFF 头）。
     *
     * `SiliconFlowAsr.transcribe` 只认 WAV：裸 PCM 必须自己封头（屏侧交接 §0 实测口径）。
     */
    fun pcmToWav(pcm: ByteArray, rate: Int = SAMPLE_RATE): ByteArray {
        val out = ByteArrayOutputStream(pcm.size + 44)
        val le32 = { v: Int -> byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()) }
        val le16 = { v: Int -> byteArrayOf(v.toByte(), (v shr 8).toByte()) }
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(le32(36 + pcm.size))
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.write(le32(16))
        out.write(le16(1))          // PCM
        out.write(le16(1))          // 单声道
        out.write(le32(rate))
        out.write(le32(rate * 2))   // 字节率 = rate × 2 字节/样本
        out.write(le16(2))          // 块对齐
        out.write(le16(16))         // s16le
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.write(le32(pcm.size))
        out.write(pcm)
        return out.toByteArray()
    }
}
