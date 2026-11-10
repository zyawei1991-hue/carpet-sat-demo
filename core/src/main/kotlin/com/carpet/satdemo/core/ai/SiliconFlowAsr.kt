package com.carpet.satdemo.core.ai

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection

/**
 * 硅基流动的 ASR（手机侧语音输入）：AudioRecord 录出的 WAV 16k 单声道，
 * 一个 multipart POST 到 `/v1/audio/transcriptions`，回 JSON `{ "text": "..." }`。
 *
 * 和 [AzureSpeech] 同一条纪律：key 不在仓库里，从外面喂进来（App 侧烧在 BuildConfig，
 * 来源 `local.properties`，gitignore）。没配 key 整个 object 不参与，调用方按未配置降级。
 *
 * 模型用免费档 `FunAudioLLM/SenseVoiceSmall`（中文识别效果足够演示）。
 * 失败不重试，返回 null 让调用方给一句人话提示。
 */
object SiliconFlowAsr {
    const val MODEL = "FunAudioLLM/SenseVoiceSmall"
    const val BASE_URL = "https://api.siliconflow.cn/v1/audio/transcriptions"

    /** 没配 key 时为空串；调用方 `configured` 不过就降级 */
    var apiKey: String = ""

    val configured: Boolean get() = apiKey.isNotBlank()

    /**
     * 转写一段 WAV。
     *
     * @param wav 完整的 WAV 字节（16k 单声道 PCM 录出来的，参数已写进 RIFF 头）
     * @param timeoutMs 总时限；SenseVoiceSmall 对 10s 音频很快，给长一点是怕冷启动
     * @return 转写文本；没配 key / 连不上 / 服务不认 / 空文本 一律 null
     */
    fun transcribe(wav: ByteArray, timeoutMs: Long = 20_000): String? {
        if (!configured || wav.isEmpty()) return null

        val boundary = "----CarpetSat" + System.currentTimeMillis().toString(16)
        val head = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
            append(MODEL).append("\r\n")
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n")
            append("Content-Type: ").append(guessMime("speech.wav")).append("\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        val tail = ("\r\n--" + boundary + "--\r\n").toByteArray(Charsets.UTF_8)

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(BASE_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = timeoutMs.toInt()
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("User-Agent", "CarpetSatDemo")
                setFixedLengthStreamingMode(head.size + wav.size + tail.size)
            }
            conn.outputStream.use { out ->
                out.write(head)
                out.write(wav)
                out.write(tail)
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = conn.inputStream.use { input ->
                val buf = ByteArrayOutputStream()
                input.copyTo(buf)
                buf.toString("UTF-8")
            }
            extractText(body)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** 只挑 `"text"` 字段：demo 不引 JSON 库，:core 全程手解（同 Providers.kt 的口径） */
    internal fun extractText(json: String): String? {
        val key = "\"text\""
        val k = json.indexOf(key)
        if (k < 0) return null
        var i = k + key.length
        while (i < json.length && json[i] != ':') i++
        i++
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != '"') return null
        val sb = StringBuilder()
        i++
        while (i < json.length) {
            val c = json[i]
            when {
                c == '"' -> return sb.toString()
                c == '\\' && i + 1 < json.length -> {
                    when (json[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        else -> sb.append(json[i + 1])
                    }
                    i += 2
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return null
    }

    private fun guessMime(name: String): String {
        val map = mapOf(
            "wav" to "audio/wav",
            "mp3" to "audio/mpeg",
            "m4a" to "audio/mp4",
            "aac" to "audio/aac",
            "ogg" to "audio/ogg",
            "opus" to "audio/ogg",
            "flac" to "audio/flac",
        )
        val ext = name.substringAfterLast('.', "").lowercase()
        return map[ext] ?: URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
    }
}
