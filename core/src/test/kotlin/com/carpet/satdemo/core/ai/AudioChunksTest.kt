package com.carpet.satdemo.core.ai

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioChunksTest {

    private fun pcm(bytes: Int) = ByteArray(bytes) { (it % 127).toByte() }

    @Test
    fun `切出去的块拼回来就是原样，一块不多一块不少`() {
        val want = pcm(4460 * 32)  // 4.46 秒那句实测的字节数量级
        val frames = AudioChunks.commands("k-42-7", want, 1_700_000_000_000)
        assertTrue(frames.isNotEmpty(), "一段正经语音不该切成空表")
        val got = ByteArray(want.size)
        var at = 0
        frames.forEachIndexed { i, f ->
            val args = f.command!!.args
            assertEquals(i, (args["seq"]!! as kotlinx.serialization.json.JsonPrimitive).content.toInt(), "第 $i 帧的 seq 不连续")
            assertEquals(want.size, (args["total"]!! as kotlinx.serialization.json.JsonPrimitive).content.toInt())
            val part = Base64.getDecoder().decode((args["b64"]!! as kotlinx.serialization.json.JsonPrimitive).content)
            part.copyInto(got, at)
            at += part.size
        }
        assertEquals(want.size, at, "拼起来的长度和原样不一样")
        assertTrue(want.contentEquals(got), "内容对不上")
    }

    @Test
    fun `每帧编出来都不许超屏侧的组帧上限`() {
        // 超了不是报错，是固件那头静默丢帧：现场看到的就是"这句话没声音"，串口一个错都不报
        framesOf(pcm(64 * 1024)).forEach { frame ->
            val size = frame.encode().toByteArray(Charsets.UTF_8).size
            assertTrue(size <= AudioChunks.JSON_LIMIT, "audio_chunk 编出来 $size 字节，上限 ${AudioChunks.JSON_LIMIT}")
        }
    }

    @Test
    fun `超出屏侧环形缓冲的语音整段不发`() {
        // 半句有声音半句没声音，比整段没声音更难解释；而文字答句走的是另一条帧，不受这里影响
        val tooBig = pcm(AudioChunks.MAX_TOTAL_BYTES + 2)
        assertEquals(emptyList(), AudioChunks.commands("t-1", tooBig, 0))
        assertEquals(emptyList(), AudioChunks.commands("t-1", ByteArray(0), 0))
        assertEquals(emptyList(), AudioChunks.commands("", pcm(3200), 0))
    }

    @Test
    fun `wav 的头不写死 44 字节`() {
        val pcmBody = pcm(1600)
        val wav = riff(pcmBody, extBytes = 2)
        assertEquals(1600, AudioChunks.wavToPcm(wav)?.size ?: -1)
        // 同样一份内容，44 字节头的写法也要能解 —— 两个来源都得吃
        assertEquals(1600, AudioChunks.wavToPcm(riff(pcmBody, extBytes = 0))?.size ?: -1)
        assertNull(AudioChunks.wavToPcm("not a wav at all".toByteArray()))
    }

    @Test
    fun `手机 TTS 的 24k 单声道要重采样成屏要的 16k`() {
        // android.speech.tts 在手机上实测出 24k，直接按 16k 下发会又快又尖
        val body = pcm(24000 * 2)  // 1 秒
        val wav = riff(body, rate = 24000)
        val got = AudioChunks.wavToPcm16k(wav)
        assertTrue(got != null, "24k 单声道是合法输入")
        // 1 秒 → 16000 帧 × 2 字节
        assertTrue(kotlin.math.abs(got!!.size - 32000) <= 4, "重采样之后不是一秒：${got.size} 字节")
    }

    @Test
    fun `立体声要先下混再重采样`() {
        // 44.1k 立体声也见过。两个声道取平均，长度按 16k 算
        val body = pcm(44100 * 4)  // 1 秒
        val wav = riff(body, channels = 2, rate = 44100)
        val got = AudioChunks.wavToPcm16k(wav)
        assertTrue(got != null, "44.1k 立体声是合法输入")
        assertTrue(kotlin.math.abs(got!!.size - 32000) <= 4, "下混重采样之后不是一秒：${got.size} 字节")
    }

    @Test
    fun `本来就是 16k 单声道时原样交出`() {
        val body = pcm(3200)
        assertTrue(body.contentEquals(AudioChunks.wavToPcm16k(riff(body))), "不该多出一层重采样")
    }

    @Test
    fun `不是 16 位的 wav 要说不，不许猜`() {
        val wav8 = riff(pcm(1600), bits = 8)
        assertNull(AudioChunks.wavToPcm16k(wav8), "8 位样本按 16 位播是噪音，宁可这条没声音")
        assertNull(AudioChunks.wavToPcm16k("not a wav at all".toByteArray()))
    }

    private fun framesOf(body: ByteArray) = AudioChunks.commands("t-abcdefghij-999", body, 1_700_000_000_000)

    /** 造一份 WAV：fmt 块后面可以带 0 或 2 个字节的扩展（System.Speech 带 2） */
    private fun riff(body: ByteArray, extBytes: Int = 0, channels: Int = 1, rate: Int = 16000, bits: Int = 16): ByteArray {
        val fmtSize = 16 + extBytes
        val out = java.io.ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        out.write(le32(4 + (8 + fmtSize) + (8 + body.size)))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(le32(fmtSize))
        val blockAlign = channels * bits / 8
        out.write(le16(1)); out.write(le16(channels)); out.write(le32(rate))
        out.write(le32(rate * blockAlign)); out.write(le16(blockAlign)); out.write(le16(bits))
        repeat(extBytes) { out.write(0) }
        out.write("data".toByteArray())
        out.write(le32(body.size))
        out.write(body)
        return out.toByteArray()
    }

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
}
