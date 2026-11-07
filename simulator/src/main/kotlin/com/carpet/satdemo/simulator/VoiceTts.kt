package com.carpet.satdemo.simulator

import com.carpet.satdemo.core.ai.AudioChunks
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * Windows 自带的离线中文语音：一句答话 → 16k/单声道/s16le 的裸 PCM。
 *
 * 为什么用 `System.Speech` 而不是装一个：这台机器上有它（.NET 自带），而现场最不该出现的
 * 就是"为了演示还得先装东西"。它也是唯一在这台称过的中文离线合成 —— 实测 16000 Hz、
 * 单声道、16 位，`data` 块在**第 46 字节**（fmt 块多带 2 字节扩展），
 * 所以解头那一步走 [AudioChunks.wavToPcm] 而不是写死 44。
 *
 * 只有合成，没有识别：听那一路还是不在这里（真机上归 `android.speech`，Muse 那一路归它自己的云）。
 *
 * 每次一问起一个 powershell 进程（约 0.6 秒 + 念完那句的时间）。这在大模型那条路上完全藏得住 ——
 * 模型本身就要等一两秒，而这两段是串行的。
 */
class VoiceTts(
    private val script: Path,
    private val workDir: Path,
    /** -10..10，0 是正常语速。1 是称过"听得清又不拖"那一档 */
    private val rate: Int = 1,
    private val note: (String) -> Unit = {},
) {
    private val seq = AtomicInteger()

    /** 同步合成。调用方负责别把它放在 UI/链路的线上 —— 这里只保证"要多久就说多久"。 */
    fun pcm(text: String): ByteArray? {
        if (!Files.exists(script)) {
            note("找不到合成脚本 $script")
            return null
        }
        val tag = seq.incrementAndGet()
        val txt = workDir.resolve("tts-$tag.txt")
        val wav = workDir.resolve("tts-$tag.wav")
        return try {
            // 中文不进 .ps1：PowerShell 5.1 按 GBK 解无 BOM 的 UTF-8 脚本，会把注释下面那一行吃掉
            Files.writeString(txt, text, Charsets.UTF_8)
            val proc = ProcessBuilder(
                "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString(),
                "-TextFile", txt.toString(), "-WavOut", wav.toString(), "-Rate", rate.toString(),
            ).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            val code = proc.waitFor()
            if (code != 0) {
                note("合成失败 exit=$code：$out")
                return null
            }
            val pcm = AudioChunks.wavToPcm(Files.readAllBytes(wav))
            if (pcm == null) {
                note("合成回来了但解不出 data 块：$out")
                null
            } else {
                note("合成 %.1f 秒（%s）".format(AudioChunks.secondsOf(pcm.size), out))
                pcm
            }
        } catch (e: Exception) {
            note("合成异常：${e.message}")
            null
        } finally {
            Files.deleteIfExists(txt)
            Files.deleteIfExists(wav)
        }
    }
}
