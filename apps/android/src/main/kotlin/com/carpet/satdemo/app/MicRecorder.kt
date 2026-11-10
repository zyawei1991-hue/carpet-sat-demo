package com.carpet.satdemo.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream

/**
 * 手机侧的一段录音：按住就录，松开就停，出来一个 WAV 字节流交给 ASR。
 *
 * 采样率固定 16k 单声道 —— 这正是 SenseVoiceSmall 期望的输入，省掉一次重采样。
 * 不做流式：demo 就"录完这一句再发"，简单可控，也不用碰 Opus 编码。
 *
 * 录到的 PCM 先扔进内存（一句话几秒，几百 KB，不落盘），停止时在 [wav] 里补 WAV 头。
 */
class MicRecorder {

    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private val pcm = ByteArrayOutputStream()
    @Volatile private var running = false

    /** 16k / 单声道 / 16bit。三处必须一致，写错一个 WAV 头里的参数就对不上 */
    private val sampleRate = 16_000
    private val channelMask = AudioFormat.CHANNEL_IN_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT

    /** 有麦克风权限吗（没权限就先去要，别开 AudioRecord —— 那样会直接抛） */
    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** 当前是否在录，UI 拿它画红点/脉冲 */
    val recording: Boolean get() = running

    /**
     * 开始录。返回 false 表示没起来（没权限 / 麦克风被占）—— 调用方给一句人话，不要静默。
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBuf <= 0) return false
        val bufSize = maxOf(minBuf, sampleRate * 2 / 2) // 至少 ~0.5s 的缓冲
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, channelMask, encoding, bufSize)
        } catch (e: Exception) {
            null
        } ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        pcm.reset()
        record = rec
        running = true
        worker = Thread {
            val chunk = ByteArray(bufSize)
            try {
                rec.startRecording()
                while (running) {
                    val n = rec.read(chunk, 0, chunk.size)
                    if (n > 0) pcm.write(chunk, 0, n) else if (n < 0) break
                }
            } catch (_: Exception) {
                // 读中断（对端被占/松手太快）都当正常收尾，出来多少算多少
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
            }
        }.apply { isDaemon = true; start() }
        return true
    }

    /**
     * 停止并取出这一段。没在录就返回 null。
     *
     * 松手到线程真正收尾之间有个小缝，这里 join 一小会儿等它把最后几百毫秒写完；
     * 等不到也照样出 WAV，只是尾巴可能短一截 —— 比卡住 UI 强。
     */
    fun stop(): ByteArray? {
        if (!running) return null
        running = false
        worker?.let { runCatching { it.join(400) } }
        worker = null
        record = null
        val data = pcm.toByteArray()
        pcm.reset()
        if (data.isEmpty()) return null
        return wav(data)
    }

    /** 松手即弃（取消录音），不出结果 */
    fun cancel() {
        if (!running) return
        running = false
        worker?.let { runCatching { it.join(400) } }
        worker = null
        record = null
        pcm.reset()
    }

    /** 给裸 PCM 补一个标准 44 字节 WAV 头，ASR 服务按文件名/魔数认格式 */
    private fun wav(pcmData: ByteArray): ByteArray {
        val byteRate = sampleRate * 2 // 单声道 16bit
        val out = ByteArrayOutputStream(pcmData.size + 44)
        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
        }
        fun le16(v: Int) {
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
        }
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        ascii("RIFF"); le32(36 + pcmData.size); ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(1)
        le32(sampleRate); le32(byteRate); le16(2); le16(16)
        ascii("data"); le32(pcmData.size)
        out.write(pcmData)
        return out.toByteArray()
    }
}
