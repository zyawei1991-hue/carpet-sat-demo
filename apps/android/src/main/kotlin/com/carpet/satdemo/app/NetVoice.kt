package com.carpet.satdemo.app

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import com.carpet.satdemo.core.ai.AudioChunks
import com.carpet.satdemo.core.ai.AzureSpeech
import com.carpet.satdemo.core.ai.EdgeSpeech
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 网络合成：这台手机的第二张嘴。
 *
 * 为什么要有它：2026-11-09 真机反馈"试听没声、对话没声、屏上也没声"。查下来网关
 * `api.maomaoai.pro` 的 31 个模型里一个语音合成都没有（20 个常见 TTS 型号逐条试过，
 * 全是 503 model_not_found），所以声音只能在手机里造；而只指望系统 TTS，就等于把
 * "这台机装没装中文语音包"变成"有没有声音"——现场那台显然没装成。
 *
 * **第一选择是 Azure 的晓伊**（[AzureSpeech]，配了订阅 key 才参与）：走微软公开 REST 契约，
 * 不吃"Edge 接口改算法"那种险（D13 记的正路）。**第二选择是 Edge 的晓伊**（[EdgeSpeech]）：
 * 桌面彩排台一直是这个声音，它要 WebSocket + 时间令牌，比前两条脆，所以连带重试都没有 ——
 * 一次不通就退百度那条无 key 的 HTTP 口，再不通才退本机引擎。
 *
 * 两条路都只负责"有声音"，MP3 解码走 `MediaCodec`，解出来的 PCM 交给
 * [AudioChunks.toMono16k] 收成 16k 单声道 —— 和 WAV 那条路落到同一个形状，
 * 屏那侧不需要知道声音从哪来。
 */
internal object NetVoice {
    /** connect 6 秒跟 core 那几条 HTTP 路同一口径；读超时 15 秒是旧实现的口径 */
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    /** 一句答话的上限。答句已压到 ≤30 字（拍板口径）；再长的输入按 30 字截断 —— 300 字 ≈ 2.13 MB PCM 会撞穿屏侧 2 MB 缓冲整段不接 */
    private const val MAX_CHARS = 30

    /** 晓伊的基频，把音色档的音高倍数换算成 prosody 的赫兹偏移 */
    private const val XIAOYI_BASE_HZ = 324.0

    /** 这句是谁念的：`azure` = Azure 晓伊，`edge` = Edge 晓伊，`baidu` = 百度那条兜底 */
    @Volatile
    var lastSource: String? = null
        private set

    /**
     * 合成一句，拿回 MP3 字节（null = 三条路都不通）。
     * 不做重试：答句晚了三秒比晚六秒更糟，失败就换下一条。
     *
     * Azure（D13 的正路，配了 key 才参与）排在 Edge 前面：同一个晓伊，
     * 但走的是微软公开契约，不吃"接口改算法"那种整条翻车的险。
     */
    fun fetch(text: String, tone: VoiceTone): ByteArray? {
        val body = text.trim().take(MAX_CHARS)
        if (body.isEmpty()) return null

        val ratePercent = Math.round((tone.rate - 1f) * 100f)
        val pitchHz = Math.round(XIAOYI_BASE_HZ * (tone.pitch - 1.0)).toInt()

        if (AzureSpeech.configured) {
            lastSource = "azure"
            val azure = try {
                AzureSpeech.synthesize(text = body, ratePercent = ratePercent, pitchHz = pitchHz)
            } catch (e: Exception) {
                null
            }
            if (azure != null) return azure
        }

        lastSource = "edge"
        val edge = try {
            EdgeSpeech.synthesize(
                text = body,
                ratePercent = ratePercent,
                pitchHz = pitchHz,
            )
        } catch (e: Exception) {
            null
        }
        if (edge != null) return edge

        lastSource = "baidu"
        return baidu(body, tone.rate).also { if (it == null) lastSource = null }
    }

    private fun baidu(body: String, rate: Float): ByteArray? {
        // spd 是倍速，5 是常速。音色档的 rate 只映射到这三档，音高接口给不了
        val spd = when {
            rate >= 1.05f -> 6
            rate <= 0.92f -> 4
            else -> 5
        }
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("fanyi.baidu.com")
            .encodedPath("/gettts")
            .addQueryParameter("lan", "zh")
            .addQueryParameter("text", body)
            .addQueryParameter("spd", spd.toString())
            .addQueryParameter("source", "web")
            .build()
        return try {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .get()
                    .build(),
            ).execute().use { resp ->
                if (resp.code != 200) return null
                if (!resp.header("Content-Type").orEmpty().startsWith("audio/")) return null
                resp.body?.bytes()?.takeIf { it.size > 512 }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 直接在这台手机里播（不经过屏）。prepare 在调用线程上做完，失败返回 null */
    fun playerFor(mp3: ByteArray): MediaPlayer? = try {
        MediaPlayer().apply {
            setDataSource(BytesSource(mp3))
            prepare()
        }
    } catch (e: Exception) {
        null
    }

    /**
     * MP3 → 屏要的 16k / 单声道 / s16le。任何一步不成立都返回 null，
     * 由调用方把"为什么没送到屏"说出来。
     */
    fun pcm16k(mp3: ByteArray): ByteArray? {
        val source = BytesSource(mp3)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(source)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val fmt = extractor.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()

            val out = ByteArrayOutputStream(mp3.size * 4)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val deadline = System.currentTimeMillis() + 20_000
            while (!outputDone) {
                if (System.currentTimeMillis() > deadline) return null
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx) ?: return null
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(idx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val idx = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        // 有的解码器会吐 float PCM；那条路这里不接，宁可退回本机引擎
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) != 2
                        ) return null
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (idx >= 0) {
                        val buf = codec.getOutputBuffer(idx)
                        if (buf != null && info.size > 0) {
                            val chunk = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.get(chunk)
                            out.write(chunk)
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            val pcm = out.toByteArray()
            if (pcm.size < AudioChunks.SAMPLE_RATE / 10 * 2) return null
            return if (channels == 1 && rate == AudioChunks.SAMPLE_RATE) pcm
            else AudioChunks.toMono16k(pcm, channels, rate)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** 让 MediaExtractor / MediaPlayer 直接读内存里的 MP3，不落临时文件 */
    private class BytesSource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(size.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize(): Long = data.size.toLong()

        override fun close() {}
    }
}
