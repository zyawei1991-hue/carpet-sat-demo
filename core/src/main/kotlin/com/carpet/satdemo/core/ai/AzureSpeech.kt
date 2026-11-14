package com.carpet.satdemo.core.ai

import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Azure Speech 的 TTS（真·晓伊），D13 记的"正路"：拿订阅 key 走公开 REST 接口，
 * 不再依赖逆向的令牌算法。接口契约是微软文档写的稳定规范，Edge 那条坏了也轮不到它坏。
 *
 * 一句话一个 POST：`https://<region>.tts.speech.microsoft.com/cognitiveservices/v1`，
 * 头带 `Ocp-Apim-Subscription-Key`，体是 SSML，回的就是音频字节。
 * 输出格式故意选和 [EdgeSpeech] 同一款（`audio-24khz-48kbitrate-mono-mp3`），
 * 下游的解码和推屏完全不用知道声音从哪来。
 *
 * **key 不在仓库里**：region/key 从外面喂进来（App 侧烧在 BuildConfig，来源 `local.properties`，
 * gitignore）。没配 key 就整个 object 不参与，调用方按未配置跳过。
 *
 * F0 免费档 50 万字符/月；超了是 429，这里不重试，直接让调用方退下一条。
 */
object AzureSpeech {
    /** 和 Edge 那条同一个音色 */
    const val XIAOYI = "zh-CN-XiaoyiNeural"

    /** 没配 key 时为空串；调用方 `configured` 不过就跳过 */
    var region: String = ""
    var speechKey: String = ""

    val configured: Boolean get() = region.isNotBlank() && speechKey.isNotBlank()

    /** connect 6 秒跟其它几条 HTTP 路同一口径；读超时按每次调用单独设 */
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 合成一句。失败（没配 / 连不上 / 服务不认 / 一个字音频都没回）返回 null。
     *
     * @param text 纯文本，内部做 XML 转义
     * @param voice 音色名，默认 [XIAOYI]
     * @param ratePercent 语速百分比，如 +10 / -8，0 为原速
     * @param pitchHz 音高偏移（赫兹），0 为原音
     * @param timeoutMs 总时限
     */
    fun synthesize(
        text: String,
        voice: String = XIAOYI,
        ratePercent: Int = 0,
        pitchHz: Int = 0,
        timeoutMs: Long = 12_000,
    ): ByteArray? {
        if (!configured) return null
        val body = text.trim()
        if (body.isEmpty()) return null

        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>" +
            "<voice name='${xml(voice)}'>" +
            "<prosody pitch='${signed(pitchHz)}Hz' rate='${signed(ratePercent)}%'>" +
            xml(body) +
            "</prosody></voice></speak>"

        return try {
            val request = Request.Builder()
                .url("https://$region.tts.speech.microsoft.com/cognitiveservices/v1")
                .header("Ocp-Apim-Subscription-Key", speechKey)
                .header("X-Microsoft-OutputFormat", "audio-24khz-48kbitrate-mono-mp3")
                .header("User-Agent", "CarpetSatDemo")
                .post(ssml.toRequestBody("application/ssml+xml".toMediaType()))
                .build()
            client.newBuilder()
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
                .newCall(request)
                .execute()
                .use { resp ->
                    if (resp.code != 200) return null
                    val bytes = resp.body?.bytes() ?: return null
                    if (!resp.header("Content-Type").orEmpty().startsWith("audio/")) return null
                    bytes.takeIf { it.size > 1024 }
                }
        } catch (e: Exception) {
            null
        }
    }

    private fun signed(v: Int): String = if (v >= 0) "+$v" else v.toString()

    private fun xml(s: String): String = buildString(s.length + 16) {
        for (ch in s) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '\'' -> append("&apos;")
                '"' -> append("&quot;")
                else -> append(ch)
            }
        }
    }
}
