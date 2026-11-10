package com.carpet.satdemo.core.ai

import com.carpet.satdemo.core.protocol.CommandPayload
import com.carpet.satdemo.core.protocol.CommandType
import com.carpet.satdemo.core.protocol.MsgType
import com.carpet.satdemo.core.protocol.ScreenMessage
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.Base64

/**
 * 答句语音怎么切成 v3 的 `audio_chunk`（契约见 `protocol/screen-link.md`）。
 *
 * 这里只有**编码**，没有合成：TTS 引擎归宿主 —— 彩排台是 Windows 那份离线中文语音，
 * 手机上换成 `android.speech.tts`，两边喂进来的都是同一种东西（16k / 单声道 / s16le 的裸 PCM），
 * 切块和上行只有一份代码。这跟 [DialogBridge] 住在 core 是同一条理由：两边各写一遍迟早对不上。
 *
 * 屏那侧的环形缓冲和 4096 字节的组帧上限决定了这里的两个数，改一个要改另一个。
 */
object AudioChunks {
    /** 屏侧播放节拍按这个数算：`MUSE_AUDIO_RATE`，也是 ES8311 那条 I2S 的采样率 */
    const val SAMPLE_RATE = 16000

    /** 一块的字节数 = 80 毫秒。base64 之后 3416 字符，整帧约 3.5 KB */
    const val BYTES_PER_CHUNK = 2560

    /**
     * 屏侧缓冲的容量（**64 秒**）。超过就整段不发 —— 半句有声音半句没声音比全没声音更难解释。
     *
     * 这个数和固件 `carpet_audio.c` 的 `AUDIO_CAP` 是**同一个东西**，改一个必须改另一个。
     * 2026-11-06 从 512 KB 提到 2 MB：真模型一句答句实测 32 字就吃掉 7.5 秒，
     * 16 秒那档一句话就能撞穿，撞穿的现场表现是"屏上有整句话、喇叭一句都没有"。
     * 屏的 PSRAM 实测 8 MB（开机日志 `octal_psram density 0x03 = 64 Mbit`），
     * 拿 2 MB 还剩 6 MB 给界面和字库；真拿不到时固件会自己打一行报错并把语音这条路关掉。
     */
    const val MAX_TOTAL_BYTES = 2 * 1024 * 1024

    /** 固件那边 `SCREEN_LINK_MAX_FRAME` 是 4096，留 300 字节给 JSON 自己长大 */
    const val JSON_LIMIT = 3800

    /**
     * PCM → 一串 `audio_chunk` 帧。空输入、超出 [MAX_TOTAL_BYTES] 都返回空表，
     * 由调用方打印"为什么没声音"：文字答句走的是另一条帧，语音失败绝不能把答句一起扣下。
     *
     * 每帧都带 `total`，屏靠它判"收齐了"；`seq` 从 0 连续，缺一块屏就把整段丢掉（同样不挡文字）。
     */
    fun commands(turnId: String, pcm: ByteArray, tsMs: Long): List<ScreenMessage> {
        if (turnId.isEmpty() || pcm.isEmpty() || pcm.size > MAX_TOTAL_BYTES) return emptyList()
        val b64 = Base64.getEncoder()
        val out = ArrayList<ScreenMessage>((pcm.size + BYTES_PER_CHUNK - 1) / BYTES_PER_CHUNK)
        var index = 0
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + BYTES_PER_CHUNK, pcm.size)
            val part = ByteArray(end - offset)
            pcm.copyInto(part, 0, offset, end)
            val frame = ScreenMessage(
                t = MsgType.command,
                seq = index.toLong(),
                ts = tsMs,
                command = CommandPayload(
                    cmd = CommandType.audio_chunk,
                    args = buildJsonObject {
                        put("turn_id", JsonPrimitive(turnId))
                        put("seq", JsonPrimitive(index))
                        put("total", JsonPrimitive(pcm.size))
                        put("rate", JsonPrimitive(SAMPLE_RATE))
                        put("b64", JsonPrimitive(b64.encodeToString(part)))
                    },
                ),
            )
            // 编码之后超了 4096，固件那头是**静默丢帧**（组帧缓冲溢出），不是报错。
            // 所以这条必须在手机端当场抛，而不是让现场去怀疑 Wi-Fi。
            check(frame.encode().toByteArray(Charsets.UTF_8).size <= JSON_LIMIT) {
                "audio_chunk 第 $index 块编出来 ${frame.encode().toByteArray(Charsets.UTF_8).size} 字节，超了屏侧 $JSON_LIMIT 的组帧上限"
            }
            out += frame
            offset = end
            index++
        }
        return out
    }

    /**
     * WAV → 裸 PCM：从 `fmt ` 链里找 `data` 块。
     *
     * 不写死 44：Windows 那份 System.Speech 出来的头是 46 字节（多带 2 字节的 fmt 扩展），
     * 按 44 切会把开头两个字节当成样本播出去，听起来是"咔"一声而不是一句错。
     */
    fun wavToPcm(wav: ByteArray): ByteArray? {
        if (wav.size < 12) return null
        if (String(wav, 0, 4, Charsets.US_ASCII) != "RIFF") return null
        if (String(wav, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var at = 12
        while (at + 8 <= wav.size) {
            val id = String(wav, at, 4, Charsets.US_ASCII)
            val size = le32(wav, at + 4)
            val body = at + 8
            if (id == "data") {
                val end = minOf(body + size, wav.size)
                return if (end <= body) null else wav.copyOfRange(body, end)
            }
            // 块长按偶数对齐（RIFF 规定奇数长度补一个填充字节）
            at = body + size + (size % 2)
        }
        return null
    }

    /** PCM 的秒数，给日志和"这句话念了多久"用 */
    fun secondsOf(pcmBytes: Int): Double = pcmBytes.toDouble() / (SAMPLE_RATE * 2)

    /**
     * 任意 WAV → 屏要的形状（[SAMPLE_RATE] / 单声道 / s16le）。format 里缺项或不是 16 位就返回 null。
     *
     * 为什么不能直接用 [wavToPcm]：手机上 `android.speech.tts` 的 `synthesizeToFile` 输出的是
     * **引擎自己的采样率**（实测 22050 / 24000，还见过立体声），而且 WAV 头里没写"给我 16k"这个选项。
     * 把 24k 的裸 PCM 按 16k 声明发下去，屏上听起来是又快又尖的一整句 —— 这比没声音更难判断是哪里坏了。
     * 桌面那份 System.Speech 恰好就是 16k 单声道，所以那边走 [wavToPcm] 不受影响。
     */
    fun wavToPcm16k(wav: ByteArray): ByteArray? {
        if (wav.size < 12) return null
        if (String(wav, 0, 4, Charsets.US_ASCII) != "RIFF") return null
        if (String(wav, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var at = 12
        var channels = 0
        var rate = 0
        var bits = 0
        var data: ByteArray? = null
        while (at + 8 <= wav.size) {
            val id = String(wav, at, 4, Charsets.US_ASCII)
            val size = le32(wav, at + 4)
            val body = at + 8
            when (id) {
                "fmt " -> if (size >= 16 && body + 16 <= wav.size) {
                    channels = le16(wav, body + 2)
                    rate = le32(wav, body + 4)
                    bits = le16(wav, body + 14)
                }
                "data" -> {
                    val end = minOf(body + size, wav.size)
                    if (end > body) data = wav.copyOfRange(body, end)
                }
            }
            at = body + size + (size % 2)
        }
        val pcm = data ?: return null
        if (channels <= 0 || rate <= 0 || bits != 16) return null
        if (channels == 1 && rate == SAMPLE_RATE) return pcm
        return toMono16k(pcm, channels, rate)
    }

    /**
     * 下混 + 线性插值重采样。**不是高保真，够一句话听懂**：TTS 的频带本来就窄，
     * 带限滤波在手机上换来的只有延迟，而屏那头是 80ms 一块、实时播的。
     */
    fun toMono16k(pcm: ByteArray, channels: Int, rate: Int): ByteArray {
        val frames = pcm.size / (2 * channels)
        if (frames <= 0) return ByteArray(0)
        // 先下混成一个 int16 序列，重采样只面对一条线
        val mono = ShortArray(frames)
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += le16(pcm, (i * channels + c) * 2)
            mono[i] = (sum / channels).toShort()
        }
        if (rate == SAMPLE_RATE) return shortsToBytes(mono, 0, frames)
        val outFrames = (frames.toLong() * SAMPLE_RATE / rate).toInt().coerceAtLeast(1)
        val out = ShortArray(outFrames)
        val step = rate.toDouble() / SAMPLE_RATE
        for (i in 0 until outFrames) {
            val pos = i * step
            val i0 = pos.toInt().coerceAtMost(frames - 1)
            val i1 = (i0 + 1).coerceAtMost(frames - 1)
            val frac = pos - i0
            out[i] = (mono[i0] + (mono[i1] - mono[i0]) * frac).toInt().toShort()
        }
        return shortsToBytes(out, 0, outFrames)
    }

    private fun shortsToBytes(s: ShortArray, from: Int, count: Int): ByteArray {
        val b = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = s[from + i].toInt()
            b[i * 2] = (v and 0xff).toByte()
            b[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        return b
    }

    private fun le16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)

    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)
}
