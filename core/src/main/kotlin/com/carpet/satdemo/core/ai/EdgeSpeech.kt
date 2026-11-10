package com.carpet.satdemo.core.ai

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Edge「朗读」接口的合成器：一句话进去，一段 24 kHz 单声道 MP3 出来。
 *
 * 桌面那侧一直是 `tools/tts-edge.py` → pip 装的 `edge_tts` → 晓伊。手机上要同一个声音，
 * 靠的就是这里：同一个接口、同一个音色名、同一个准入令牌（[EdgeToken]）。拿到 MP3 之后
 * 解码和推屏完全复用 [NetVoice] 那条既有链路，下游不需要知道声音从哪来。
 *
 * 只做到了"一句话一个连接"：答句本来就短，复用连接要处理分句、边界事件和超时重连，
 * 在这个体量上不值当。一次失败就返回 null，调用方退回本机引擎。
 *
 * **没有 key**：这个接口靠 `TrustedClientToken` + 时间令牌准入，不是账号体系。
 */
object EdgeSpeech {
    private const val HOST = "speech.platform.bing.com"
    private const val PATH = "/consumer/speech/synthesize/readaloud/edge/v1"
    private const val CHROMIUM = "143.0.3650.75"
    private const val SEC_MS_GEC_VERSION = "2-$CHROMIUM"
    private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"

    /** 晓伊：年轻女声，和桌面演示用的是同一个 */
    const val XIAOYI = "zh-CN-XiaoyiNeural"

    private val random = SecureRandom()

    /**
     * 合成一句。失败（连不上 / 服务不认 / 一个字音频都没回）返回 null。
     *
     * @param text 纯文本，内部做 XML 转义
     * @param voice 音色名，默认 [XIAOYI]
     * @param ratePercent 语速百分比，如 +10 / -8，0 为原速
     * @param pitchHz 音高偏移（赫兹），0 为原音
     * @param timeoutMs 从发起到收完音频的总时限
     */
    fun synthesize(
        text: String,
        voice: String = XIAOYI,
        ratePercent: Int = 0,
        pitchHz: Int = 0,
        timeoutMs: Long = 12_000,
    ): ByteArray? {
        val body = text.trim()
        if (body.isEmpty()) return null

        val audio = ByteArrayOutputStream()
        val done = CountDownLatch(1)

        val uri = URI(
            "wss://$HOST$PATH?TrustedClientToken=${EdgeToken.TRUSTED_CLIENT_TOKEN}" +
                "&ConnectionId=${hex(32)}" +
                "&Sec-MS-GEC=${EdgeToken.generate()}" +
                "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"
        )

        val client = object : WebSocketClient(uri) {
            override fun onOpen(handshake: ServerHandshake) {
                send(configFrame())
                send(ssmlFrame(body, voice, ratePercent, pitchHz))
            }

            override fun onMessage(message: String) {
                if (message.contains("Path:turn.end")) done.countDown()
            }

            override fun onMessage(bytes: java.nio.ByteBuffer) {
                val frame = ByteArray(bytes.remaining())
                bytes.get(frame)
                if (frame.size < 2) return
                val headerLength = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
                if (headerLength + 2 > frame.size) return
                val header = String(frame, 0, headerLength, Charsets.US_ASCII)
                if (header.contains("Path:audio")) {
                    audio.write(frame, headerLength + 2, frame.size - headerLength - 2)
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                done.countDown()
            }

            override fun onError(ex: Exception) {
                done.countDown()
            }
        }

        client.addHeader("Origin", ORIGIN)
        client.addHeader("User-Agent", USER_AGENT)
        client.addHeader("Pragma", "no-cache")
        client.addHeader("Cache-Control", "no-cache")
        client.addHeader("Cookie", "muid=${hex(32)};")
        client.setConnectionLostTimeout(0)

        return try {
            if (!client.connectBlocking(6, TimeUnit.SECONDS)) return null
            // 超时就算失败，不退半句：截断的答句比"这句没念"更难解释，而兜底那条能给完整一句
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
            audio.toByteArray().takeIf { it.size > 1024 }
        } catch (e: Exception) {
            null
        } finally {
            // 不用 closeBlocking：服务端万一不回关闭帧，它会无限期等下去，而这条线程还要念下一句
            runCatching { client.close() }
        }
    }

    /** 告诉服务要什么格式；`sentenceBoundaryEnabled` 开着头帧里会多几条元数据，我们不用但也不碍事 */
    private fun configFrame(): String =
        "X-Timestamp:${rfc1123()}\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"true","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}""" +
            "\r\n"

    private fun ssmlFrame(text: String, voice: String, ratePercent: Int, pitchHz: Int): String {
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='${xml(voice)}'>" +
            "<prosody pitch='${signed(pitchHz)}Hz' rate='${signed(ratePercent)}%' volume='+0%'>" +
            xml(text) +
            "</prosody></voice></speak>"
        return "X-RequestId:${hex(32)}\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${rfc1123()}Z\r\n" +
            "Path:ssml\r\n\r\n" +
            ssml
    }

    /** 上游这里多带一个 Z（服务端就是这么收的），别"顺手修正" */
    private fun rfc1123(): String {
        val fmt = SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
            Locale.US,
        )
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
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

    private fun hex(count: Int): String {
        val bytes = ByteArray(count / 2)
        random.nextBytes(bytes)
        val sb = StringBuilder(count)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0xF])
        }
        return sb.toString()
    }
}
