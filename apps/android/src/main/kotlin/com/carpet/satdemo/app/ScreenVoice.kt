package com.carpet.satdemo.app

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.carpet.satdemo.core.ai.AudioChunks
import com.carpet.satdemo.core.screen.ScreenLinkServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 音高/语速的一档预设。
 *
 * 圆屏的嘴在手机上（见 [ScreenVoice] 类注释），所以"可爱女声"这件事**不是换个模型**，
 * 而是这台手机把同一句合成成什么音色。同一个中文引擎，音高从 1.0 抬到 1.18 就从"播报"
 * 变成"在跟你说话"；再快一点又会显年轻。现场没有第二台机器可以试点，所以这三档直接做成名字。
 */
data class VoiceTone(val id: String, val label: String, val pitch: Float, val rate: Float) {
    companion object {
        val Normal = VoiceTone("normal", "原声", 1f, 1f)
        val Sweet = VoiceTone("sweet", "更甜", 1.18f, 1.0f)
        val SweetFast = VoiceTone("sweet-fast", "更甜·轻快", 1.24f, 1.08f)
        val Slow = VoiceTone("slow", "更甜·慢", 1.14f, 0.88f)
        val all = listOf(Normal, Sweet, SweetFast, Slow)
        fun of(id: String?): VoiceTone = all.firstOrNull { it.id == id } ?: Normal
    }
}

/** 手机上装了的一个语音引擎。id 是包名（换引擎时要用它），label 是给用户看的名字 */
data class VoiceEngine(val id: String, val label: String)

/**
 * 引擎内部的一个人声。一个中文引擎常常同时带男声、女声、童声（Google 那台尤其多）。
 * `name` 是引擎自己的键，换声时原样交回去；`label` 只取末尾那段给人看，太长就截断。
 */
data class VoiceFace(val name: String, val label: String)

/**
 * 把手机里的答句念出来 —— **在圆屏上念**。
 *
 * 之前声音只在桌面彩排台那条 `--voice` 上：手机这边 `DialogBridge.onAnswer` 根本没人接，
 * 于是屏上有字、喇叭里一个字都没有，而代码里没有任何一行报错。真机反馈的"没声音"就是这一条。
 *
 * 链路：`TextToSpeech.synthesizeToFile`（WAV）→ [AudioChunks.wavToPcm16k] 收成 16k/单声道 →
 * [AudioChunks.commands] 切块 → `command{audio_chunk}` 经 WebSocket 推给屏。
 * 声音是**文字的附加品**：合成失败、屏没升到 v3、连接断了，任何一步都只让这一句没声音，
 * 文字那条 `dialog` 帧照常发 —— 这个顺序由 [com.carpet.satdemo.core.ai.DialogBridge] 保证，
 * 这里只许失败、不许阻塞调用方。
 *
 * **引擎可以换**：系统默认引擎不一定是最甜的那个。手机上常同时装着华为/小爱/讯飞，
 * 各有各的中文女声；旧实现永远只用默认那一台，用户装了更好的也听不出来。
 * 换引擎 = 把旧实例 shutdown、用包名重新起一台，必须走同一条串行线程。
 *
 * 一个串行执行器：TTS 引擎本身不是线程安全的，而且两句同时合成会让屏上两句话声音叠在一起。
 */
class ScreenVoice(
    private val host: Context,
    private val server: () -> ScreenLinkServer?,
    private val log: (String) -> Unit,
    initialEngine: String? = null,
    initialTone: VoiceTone = VoiceTone.Normal,
    initialFace: String? = null,
    initialDevice: ScreenDevice = ScreenDevice.screen,
) {
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "screen-voice").apply { isDaemon = true }
    }
    @Volatile
    private var latch = CountDownLatch(1)
    private val unusable = AtomicBoolean(false)
    /** 系统引擎。主线程建、worker 用、屏起轮时从读线程 [stopSystemSpeech] 掐 —— 所以是 volatile */
    @Volatile
    private var tts: TextToSpeech? = null
    private var seq = 0

    /**
     * 这台手机现在用的是哪个 TTS 引擎。**必须能看见**：
     * 之前圆屏上调试时接的是 Edge 的晓伊，那是桌面那侧的 `tts-edge.py`；手机这条一直走系统引擎，
     * 两个声音本来就不是同一个 —— 界面上不写，用户只能凭耳朵猜"是不是退化了"。
     */
    private val _engine = MutableStateFlow<String?>(null)
    val engine: StateFlow<String?> = _engine

    /** 手机上装了哪些中文引擎。给用户换一台更甜的机会，而不是替他认定默认那台最好 */
    private val _engines = MutableStateFlow<List<VoiceEngine>>(emptyList())
    val engines: StateFlow<List<VoiceEngine>> = _engines

    private val _tone = MutableStateFlow(initialTone)
    val tone: StateFlow<VoiceTone> = _tone

    /** 手机起头那一轮由哪个设备发声（owner 2026-11-11 拍的规矩）。默认圆屏 */
    private val _screenDevice = MutableStateFlow(initialDevice)
    val screenDevice: StateFlow<ScreenDevice> = _screenDevice
    fun setScreenDevice(next: ScreenDevice) { _screenDevice.value = next }

    enum class ScreenDevice(val label: String) { screen("圆屏"), phone("这台手机") }

    /** 起头在手机、落点是不是这台手机（屏没连 / 没升到 v3 时也落回手机） */
    private fun answerHere(): Boolean {
        val link = server()
        return _screenDevice.value == ScreenDevice.phone || link == null || !link.audioReady
    }

    /**
     * §5.1 打点第 ④ 段「首帧音频推屏」，一轮只报第一句。两处推屏落点都在这条串行 worker 上，
     * 所以 once 守卫用普通字段就够，不需要锁。
     */
    var onFirstAudioToScreen: (turnId: String) -> Unit = {}
    private var audioHookTurn: String? = null
    private fun noteFirstAudio(turnId: String) {
        if (audioHookTurn == turnId) return
        audioHookTurn = turnId
        onFirstAudioToScreen(turnId)
    }

    /**
     * 这台引擎里的中文人声。空 = 引擎没报（老引擎不实现 voices）或者只有一种。
     *
     * **这是最直接的一条"换可爱女声"的路**：Google 那台在中国区常见一男一女，
     * 默认给的常常是男声；换成人声列表里那个女性名字，发音人就从根上变了，
     * 不用装别的 App、也不用刷固件。
     */
    private val _faces = MutableStateFlow<List<VoiceFace>>(emptyList())
    val faces: StateFlow<List<VoiceFace>> = _faces

    /** 用户选过的那个发音人（引擎报的 name）。null = 引擎默认 */
    private val _face = MutableStateFlow(initialFace)
    val face: StateFlow<String?> = _face

    /** 手机上正在试听。试听失败也要说出来，不能只是没声 */
    private val _previewing = MutableStateFlow(false)
    val previewing: StateFlow<Boolean> = _previewing

    /**
     * 声音从哪儿来：`edge` = Edge 的晓伊（和桌面同一个），`baidu` = 网络兜底那条，
     * `system` = 本机 TTS 引擎。
     *
     * 界面上必须看得见。2026-11-09 现场全是"没声"，而当时唯一能看的那一格写着引擎名，
     * 看上去一切正常 —— 现在这一格说的是**这一句到底是谁念的**。晓伊和兜底必须分开：
     * 用户耳朵听到的差别就是这两格，混成一个"网络合成"等于把问题重新藏起来。
     */
    private val _source = MutableStateFlow<String?>(null)
    val source: StateFlow<String?> = _source

    /**
     * 系统引擎每次合成前的等待上限。
     *
     * 原来是 8 秒，而引擎初始化慢的时候试听和答句都卡在这上面，用户看到的是"点了没反应"。
     * 2.5 秒没起来就走网络那条路 —— 它有 6 秒的连接超时，等它的信息量比干等引擎大。
     */
    private val engineWaitMs = 2500L

    /** [phonePlayer]/[phoneDone] 的锁：播放回调在主线程、合成在 worker、屏起轮在读线程，三处都要看它 */
    private val phoneLock = Any()

    /** 正在手机上放的答句。§4 按句念时下一句**先等这句放完**再 start，不掐半句 */
    @Volatile
    private var phonePlayer: MediaPlayer? = null

    /** 和 [phonePlayer] 配对的"放完了"闩：完成/出错回调、被主动 stop 都落闩，等的人不悬死 */
    @Volatile
    private var phoneDone: CountDownLatch? = null

    /**
     * 本机嘴的"代次"。每来一句新答话 +1；屏起新一轮录音时也 +1。
     *
     * 光靠排队里的 `stopPhonePlayer()` 拦不住上一轮：worker 是串行线程，上一句的
     * `phoneDone.await(...)` 正占着它，排队的停止要等 await 返回才执行 —— 那正是播放结束、
     * 已经录完的那一刻。所以停止必须**直接做**（[stopForNewScreenTurn]），而"还在队列里 /
     * 卡在 await 里的旧句子"就靠这个数作废：每步开跑前比对一次，代次变了就不再开口。
     */
    private val speechGen = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 上一次没把声音送出去的原因（null = 最近一次是好的，或者还没念过）。
     *
     * 这件事必须有一格状态，不能只写日志：屏只报到 v2 时原来的实现是 `return`，**一行日志都没有**，
     * 用户看到的就是"字到了、没声音、哪儿也不报错"。现在这一句会摆到设置页上，
     * 而且它说的是"下一步该做什么"（去刷固件 / 去配对 / 去换引擎），不是"失败了"。
     */
    private val _issue = MutableStateFlow<String?>(null)
    val issue: StateFlow<String?> = _issue

    /** 同一个原因只写一次日志：答句是一句接一句来的，每句都刷一条会把现场日志冲掉 */
    private var lastGate: String? = null

    /**
     * §4 长答按句分段念：**同一轮**的断点。
     *
     * 固件那边（`carpet_audio.c` 的 `feed()`）只认"同 turn_id 下 seq 从上次断的地方接着数，
     * total 一直是整轮的字节数"。第二句的 seq 重新从 0 数，整段会被当成乱序直接丢掉；
     * 每句各报各的字节数，第一句播到一半就被判成"收齐了"。所以这两格必须跨句活着，
     * 新一轮（[speak] 换了 turnId）就归零。
     */
    private var turnSeq: String? = null
    private var nextSeq = 0
    private var turnBytes = 0

    private fun gate(line: String) {
        _issue.value = line
        if (lastGate != line) {
            lastGate = line
            log("屏幕语音：$line")
        }
    }

    init {
        open(initialEngine)
    }

    /**
     * 起一台引擎。[engineName] 为空 = 系统默认那台。
     *
     * 每一台都要自己一份 latch：换引擎之后 [speak] 必须等**新**的那台准备好，
     * 等旧的会导致要么立刻返回、要么等满八秒。
     */
    private fun open(engineName: String?) {
        val ready = CountDownLatch(1)
        latch = ready
        unusable.set(false)
        var speech: TextToSpeech? = null
        val listener = TextToSpeech.OnInitListener { status ->
            if (status != TextToSpeech.SUCCESS) {
                unusable.set(true)
                log(
                    if (engineName == null) "屏幕语音：手机没有可用的 TTS 引擎，答句只有字没有声"
                    else "屏幕语音：引擎「${labelOf(engineName)}」没起来，答句只有字没有声",
                )
                _engine.value = null
                ready.countDown()
                return@OnInitListener
            }
            val s = speech
            _engines.value = runCatching {
                s?.engines?.map { VoiceEngine(it.name, it.label.ifBlank { labelOf(it.name) }) }.orEmpty()
            }.getOrDefault(emptyList())
            val lang = s?.setLanguage(Locale.SIMPLIFIED_CHINESE)
            if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                unusable.set(true)
                log("屏幕语音：这个引擎没有中文语音包，答句只有字没有声")
                _engine.value = null
            } else {
                // 引擎报的人声清单。先筛中文，再排除需要联网的那几个：
                // 演示现场的网络是给链路用的，把一句话押在几个 G 的模型下载上就是在赌
                _faces.value = runCatching {
                    s?.voices.orEmpty()
                        .filter { it.locale.language.equals("zh", ignoreCase = true) && !it.isNetworkConnectionRequired }
                        .map { VoiceFace(it.name, faceLabel(it.name)) }
                        .distinctBy { it.name }
                        .sortedBy { it.label }
                }.getOrDefault(emptyList())
                // 用户选过的那一个优先；找不到就退回引擎默认（可能被系统换过机型）
                val chosen = _face.value?.let { name -> s?.voices?.firstOrNull { it.name == name } }
                if (chosen != null) {
                    val ok = runCatching { s?.setVoice(chosen) }.getOrDefault(TextToSpeech.ERROR)
                    if (ok != TextToSpeech.SUCCESS) log("屏幕语音：发音人「${faceLabel(chosen.name)}」没切过去，用引擎默认")
                }
                val t = _tone.value
                s?.setPitch(t.pitch)
                s?.setSpeechRate(t.rate)
                _engine.value = labelOf(s?.defaultEngine ?: engineName)
                log(
                    "屏幕语音：本机引擎 ${_engine.value ?: "未知"}" +
                        (if (_faces.value.isEmpty()) "" else "，${_faces.value.size} 个中文人声") +
                        "（音高 ${t.pitch}，语速 ${t.rate}）",
                )
            }
            ready.countDown()
        }
        speech = try {
            if (engineName == null) {
                TextToSpeech(host.applicationContext, listener)
            } else {
                TextToSpeech(host.applicationContext, listener, engineName)
            }
        } catch (e: Exception) {
            // 指定的包名已经卸载了（用户换过引擎）：说出来，别静默退成没声
            log("屏幕语音：引擎「${labelOf(engineName)}」起不来（${e.message}）")
            unusable.set(true)
            _engine.value = null
            null
        }
        tts = speech
        if (speech == null) ready.countDown()
    }

    /** 换引擎。旧的先停再 shutdown —— 一台引擎的实例不能同时开着两个 */
    fun setEngine(engineName: String?) {
        worker.execute {
            runCatching { tts?.stop(); tts?.shutdown() }
            tts = null
            open(engineName)
        }
    }

    /** 换音色档。立刻生效，下一句就用新的音高语速 */
    fun setTone(next: VoiceTone) {
        _tone.value = next
        worker.execute { applyTone() }
    }

    /**
     * 换发音人（引擎内部的人声）。[name] 为 null = 回到引擎默认。
     * 找不到这个 name 时不动引擎，只在日志里说一句 —— 用户换过机、引擎升级都可能让它消失。
     */
    fun setFace(name: String?) {
        _face.value = name
        worker.execute {
            val engine = tts ?: return@execute
            if (name == null) {
                log("屏幕语音：发音人回到引擎默认")
                return@execute
            }
            val voice = runCatching { engine.voices?.firstOrNull { it.name == name } }.getOrNull()
            if (voice == null) {
                log("屏幕语音：这台引擎里已经没有「${faceLabel(name)}」了")
                return@execute
            }
            val ret = runCatching { engine.setVoice(voice) }.getOrDefault(TextToSpeech.ERROR)
            log(
                if (ret == TextToSpeech.SUCCESS) "屏幕语音：发音人 → ${faceLabel(name)}"
                else "屏幕语音：发音人「${faceLabel(name)}」切不过去（返回 $ret）",
            )
        }
    }

    /** 每一句之前都把发音人和音色钉一次：换引擎/系统升级都可能把这两项复位 */
    private fun applyFace(engine: TextToSpeech) {
        val name = _face.value ?: return
        runCatching {
            engine.voices?.firstOrNull { it.name == name }?.let { engine.setVoice(it) }
        }
    }

    private fun faceLabel(name: String): String {
        // Google 那边是 zh-CN-language-cmn-cn-xiaoxiao 这种长键，取最后两段就够分辨
        val tail = name.substringAfterLast('/').split('-', '#', '_').filter { it.isNotBlank() }
        val short = tail.takeLast(2).joinToString(" ")
        return short.ifBlank { name }.take(18)
    }

    private fun applyTone() {
        val engine = tts ?: return
        val t = _tone.value
        // 发音人先钉：有的引擎 setVoice 会把音高语速一起复位，顺序反了这一句就白调了
        applyFace(engine)
        runCatching {
            engine.setPitch(t.pitch)
            engine.setSpeechRate(t.rate)
        }
        log("屏幕语音：音色 → ${t.label}（音高 ${t.pitch}，语速 ${t.rate}）")
    }

    /**
     * 一句答话。**调用即返回**：合成要一两秒，不能在网关那条协程里等。
     *
     * 规矩（owner 2026-11-18 覆盖 11-11 那条「屏上对话只由圆屏念，无例外」）：
     * 齿轮面板「由哪个设备发声」对**所有**轮次生效，屏上起头的和手机起头的走同一张路由表
     * （[answerHere]）—— 选了「这台手机」就只由手机念，屏一个音频帧都不收；
     * 屏没连 / 没升到 v3 时照旧落回手机。任何一轮只许一个设备出声。
     *
     * 11-11 那版在这里给屏起头开了一个 `screenOnly = true` 的后门，绕过选择格，
     * 于是 owner 把设置改成「这台手机」后屏上起头的对话照样一声不出 —— 后门这次删掉了。
     */
    fun speak(turnId: String, text: String, origin: com.carpet.satdemo.core.protocol.DialogOrigin) {
        val body = text.trim()
        if (body.isEmpty()) return
        val gen = speechGen.get()
        worker.execute {
            runCatching { synthesizeAndSend(turnId, body, origin = origin, gen = gen) }
                .onFailure { log("屏幕语音失败：${it.message}") }
        }
    }

    /**
     * §4（owner 2026-11-15 拍板的妥协案）：长答**按句切**，不攒整轮。
     *
     * 真模型的答句动辄七八十字，整段合成要十几秒，车主听见的是长久的沉默；
     * 按句切之后第一句几秒内就开口，后面的句子在第一句播放期间陆续补上。
     * 屏那侧同一 `turn_id` 接续 seq（[AudioChunks.commands] 的 seqStart/totalBytes），
     * 手机喇叭那侧 QUEUE_ADD 顺序排 —— 两条路都不会把句子顺序念乱。
     */
    internal fun splitSentences(text: String): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        text.forEach { ch ->
            buf.append(ch)
            if (ch in "。！？；\n") {
                val s = buf.toString().trim()
                if (s.isNotEmpty()) out += s
                buf.clear()
            }
        }
        buf.toString().trim().takeIf { it.isNotEmpty() }?.let { out += it }
        // 太碎的尾句并回前一句，别让「好的。」单独占一轮合成
        if (out.size >= 2 && out.last().length <= 3) {
            out[out.size - 2] = out[out.size - 2] + out.last()
            out.removeAt(out.size - 1)
        }
        return out
    }

    /**
     * 在这台手机上直接念一句，**不经过圆屏**。
     *
     * 存在的理由：声音这条链以前只有真屏才能验 —— 屏没连、没升 v3，就什么都听不见，
     * 于是"是不是没声"永远没法在手机上先排除。这里念的是同一个引擎、同一句试听文，
     * 真屏上听到的才和这里一样；圆屏那条合成链路（[synthesizeAndSend]）坏没坏另说。
     */
    fun speakOnPhone(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        _previewing.value = true
        worker.execute {
            try {
                if (playNet(body)) {
                    // 播放器起来了 ≠ 听得见：媒体音量为 0 时和"引擎坏了"一个样
                    _issue.value = silentPhone()
                    if (_issue.value == null) lastGate = null
                    else log("屏幕语音：${_issue.value}")
                    return@execute
                }
                // 网络那条不通才落到本机引擎。这一步失败**必须**写进 issue：
                // 以前这里只打日志，用户点十次试听也得不到半句解释
                if (!latch.await(engineWaitMs, TimeUnit.MILLISECONDS) || unusable.get()) {
                    gate("试听没有出声。先连上能上网的 Wi-Fi 再试；还是没声的话，去手机设置里装一个中文语音包")
                    _previewing.value = false
                    return@execute
                }
                val engine = tts ?: run {
                    gate("试听没有出声。这台手机上没有可用的语音引擎，连上网之后可以再试一次")
                    _previewing.value = false
                    return@execute
                }
                applyTone()
                _source.value = "system"
                val id = "p-${seq++}"
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { _previewing.value = false }
                    @Deprecated("老 API 仍会回调这一支") override fun onError(utteranceId: String?) { _previewing.value = false }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        gate("试听没有出声，这台手机里的语音引擎没能念出来。换一个引擎再试一次")
                        _previewing.value = false
                    }
                })
                // 本机播放用 QUEUE_FLUSH：再点一次就是听新的一句，不排队
                if (engine.speak(body, TextToSpeech.QUEUE_FLUSH, Bundle(), id) != TextToSpeech.SUCCESS) {
                    gate("试听没有出声，这台手机里的语音引擎没能念出来。换一个引擎再试一次")
                    _previewing.value = false
                } else {
                    log("本机试听：交给 ${_engine.value ?: "未知"} 念了一句")
                }
            } catch (e: Exception) {
                gate("试听没有出声，可以再点一次试试")
                _previewing.value = false
            }
        }
    }

    /**
     * 网络合成 + 在本机播放。成功返回 true（播放本身是异步的，[previewing] 跟着播放器走）。
     *
     * 为什么这条排在系统引擎前面：用户那台手机就是"试听没声、对话没声"，
     * 而声音全押在系统语音包上时，没装语音包的手机等于整条链路没有嘴。
     * 这条不依赖任何本地语音包，只要有网。
     */
    private fun playNet(text: String): Boolean {
        val mp3 = NetVoice.fetch(text, _tone.value) ?: return false
        val player = NetVoice.playerFor(mp3) ?: return false
        stopPhonePlayer()
        // 试听不排队，也就没有"等上一句放完"的闩：安一个已落下的闩，让 [phoneDone] 不为空。
        // gen 传当前值 —— 试听是用户手点的，不该被"开始说的下一句"作废
        installPhonePlayer(player, CountDownLatch(0), speechGen.get())
        player.setOnCompletionListener { mp ->
            runCatching { mp.release() }
            clearPhonePlayer(mp)
            _previewing.value = false
        }
        player.setOnErrorListener { mp, what, extra ->
            log("本机试听：播放器出错（$what/$extra）")
            runCatching { mp.release() }
            clearPhonePlayer(mp)
            _previewing.value = false
            true
        }
        return try {
            player.start()
            // 只有真起来才记"这句是网络念的"：起不来还留着 net，界面会说谎
            _source.value = NetVoice.lastSource
            log("本机试听：网络合成了一句（${mp3.size} 字节 MP3）")
            true
        } catch (e: Exception) {
            log("本机试听：播放器起不来（${e.message}）")
            runCatching { player.release() }
            clearPhonePlayer(player)
            false
        }
    }

    /** 在锁里摘下当前播放器并落闩（"这句不会再有完成回调了"）；停/释放由调用方在锁外做 */
    private fun detachPlayerLocked(): MediaPlayer? {
        phoneDone?.countDown()
        phoneDone = null
        val cur = phonePlayer
        phonePlayer = null
        return cur
    }

    /**
     * 掐掉本机正在放的那一句。
     *
     * 除了内部"下一句接着放"和 [release]，**屏开始新一轮录音时也要调一次**（[stopForNewScreenTurn]）：
     * 屏侧起轮是无条件 `carpet_audio_abort`（停嘴留账），手机这边不跟着停，
     * 上一句答话就会从手机喇叭录进下一句问话里。
     */
    fun stopPhonePlayer() {
        val p = synchronized(phoneLock) { detachPlayerLocked() }
        // 停/释放放在锁外：release 可能同步触发监听器，别在锁里绕回自己
        p?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
    }

    /**
     * 装上"正在放这一句"。动 [phonePlayer]/[phoneDone] 一律经过这里或 [stopPhonePlayer]，
     * 播放回调在主线程、合成在 worker、屏起轮在 WebSocket 读线程 —— 三处都得看同一把锁。
     *
     * [gen] 在**锁里**核对（[stopForNewScreenTurn] 也是在锁里 +1 的）：这两步因此有先后次序 ——
     * 要么先加代次（这里返回 false，这句不起播），要么先装上（那边的 stop 就会看见它并掐掉）。
     * 放在锁外核对会留一个"stop 时还没有 player、随后 worker 才起播"的缝。
     * 返回 false = 这句已经作废，调用方必须 release 掉 [player]。
     */
    private fun installPhonePlayer(player: MediaPlayer, done: CountDownLatch, gen: Int): Boolean =
        synchronized(phoneLock) {
            if (gen != speechGen.get()) {
                false
            } else {
                phoneDone = done
                phonePlayer = player
                true
            }
        }

    /** 播放回调收尾：只清自己那次装的，别把后来者的引用抹掉 */
    private fun clearPhonePlayer(mp: MediaPlayer) {
        synchronized(phoneLock) { if (phonePlayer === mp) phonePlayer = null }
    }

    /**
     * 护闸（11-18 交接单第 2 条）：屏开始新一轮录音，就把手机嘴闭上。
     *
     * 只在**本机是落点**时才有意义 —— 选了「圆屏」的轮次手机本来就没在放，调了也是空转；
     * 但**不必**判这一层：屏起轮代表"人现在在屏边说下一句了"，无论设置如何，
     * 手机喇叭此刻出声都是错的。所以无条件停，语义最简、也最难漏。
     *
     * **不排队**：这一步直接在调用线程（WebSocket 读线程）上做。之前它被塞进 `worker`，
     * 而 worker 是串行的 —— 上一句的 `phoneDone.await(30s)` 正占着那条线程，排队的停止
     * 要等它放完才执行，等于半点用没有（那正是"上一句已经录进这一句"的时刻）。
     * 停是幂等的，从别的线程调是安全的（[stopPhonePlayer] 自己加锁）。
     *
     * 同时把 [speechGen] +1：还在队列里没开跑的旧句子一并作废，否则停下这一句之后
     * 下一句又接着响。
     */
    fun stopForNewScreenTurn(turnId: String) {
        // 加代次和摘 player 放在同一把锁里（worker 那边"核代次 + 装 player"也在这把锁里）：
        // 于是这两件事必然分先后，不会出现"停的时候还没有 player、随后它才起播"的缝
        var playing = false
        val p = synchronized(phoneLock) {
            speechGen.incrementAndGet()
            playing = phonePlayer != null
            detachPlayerLocked()
        }
        p?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
        // 系统引擎那条路是 QUEUE_ADD 排队念的，不归 phonePlayer 管：这里也得掐，
        // 否则"选这台手机 + 网络不通"的现场，上一句照样从喇叭念进新一句
        stopSystemSpeech()
        if (playing) log("屏幕语音：屏起新的一轮（$turnId），手机停嘴让路")
    }

    /**
     * 掐掉本机 TTS 引擎正在念 / 排着的句子。只碰引擎，不打日志（调用方知道上下文）。
     *
     * 系统引擎没有"停掉某一句"的接口：`stop()` 是整队清空。这正是要的 ——
     * 屏起轮等于"别再念了"，残留的半句一样会录进新问话。
     */
    private fun stopSystemSpeech() {
        val e = tts ?: return
        runCatching { e.stop() }
    }

    /** 引擎包名对用户没有意义，能认出来的换成名字；认不出来的原样给，方便现场对型号 */
    private fun labelOf(name: String?): String = when {
        name.isNullOrBlank() -> "系统 TTS"
        name.contains("google", ignoreCase = true) -> "Google 语音服务"
        name.contains("huawei", ignoreCase = true) || name.contains("hmos", ignoreCase = true) -> "华为语音引擎"
        name.contains("miui", ignoreCase = true) || name.contains("xiaomi", ignoreCase = true) -> "小爱语音引擎"
        name.contains("iflytek", ignoreCase = true) -> "讯飞语音引擎"
        name.contains("baidu", ignoreCase = true) -> "百度语音引擎"
        else -> name
    }

    /**
     * 为什么这句送不到屏上。**每种原因都要说清下一步**：
     * - 没接屏 → 去首页配对；
     * - 接到 v1/v2 → 屏那边收不到 `audio_chunk`，声音要 `protocols` 里报 3 的新固件；
     * - 引擎不行 → 换引擎或装中文语音包。
     *
     * 这两种屏侧原因现在**都退到手机喇叭**念（[speakHere]）。退回去不代表屏上那条通了，
     * 所以原因照样摆出来 —— 不然用户会以为屏已经会说话，下次拔掉手机才发现全是手机在响。
     */
    private fun whyNot(link: ScreenLinkServer?): String {
        if (link == null || link.clientCount == 0) {
            return "圆屏没连上，这句先在手机上念了。连上之后声音会跟着屏走"
        }
        return "这块屏的系统版本还不支持播放语音，这句先在手机上念了。" +
            "屏升级之后会自动改成屏出声"
    }

    /**
     * 用同一台引擎、同一套音色在这台手机里直接念。
     *
     * 2026-11-09 现场：屏只报到 v2 时 `audio_chunk` 一帧都到不了，整条链路静默丢声，
     * 于是"答案有声"这件事在真机上从来没成立过。手机喇叭是这台设备本来就有的嘴 ——
     * 屏还没升级之前，先让答案听得见，比守着一个"协议正确但没声"的状态有用。
     */
    private fun speakHere(engine: TextToSpeech, text: String): Boolean {
        val id = "f-${seq++}"
        _source.value = "system"
        val ret = engine.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), id)
        if (ret != TextToSpeech.SUCCESS) {
            log("屏幕语音：手机喇叭也不接受这一句（返回 $ret）")
            return false
        }
        return true
    }

    /**
     * 手机媒体音量。**0 和"引擎坏了"在现场长得一模一样**（都是一点声音没有），
     * 而用户能做的事完全不同：调音量 vs 装语音包。所以这一格要能读、也要能报出来。
     * -1 = 读不到（个别 ROM 限制），当作未知，不拿它下结论。
     */
    private fun musicVolume(): Int {
        val am = host.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return -1
        return runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(-1)
    }

    /**
     * 手机落点念了却听不见的原因；null = 没查到明确原因（音量读不到也算未知，不下结论）。
     *
     * 现场两种"一点声音没有"和"引擎坏了"长得一模一样，但用户能做的事完全不同：
     * 媒体音量 0 → 调音量；连着车载蓝牙/耳机 → 声音跑到那台设备去了。
     */
    private fun silentPhone(): String? {
        val am = host.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val vol = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(-1)
        if (vol == 0) return "这句在手机上念了，但手机媒体音量是 0，所以听不见。把音量调大再问一次"
        val bt = runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        }.getOrDefault(false)
        if (bt) return "这句在手机上念了，但手机连着蓝牙，声音从蓝牙设备出。断开蓝牙再问一次"
        return null
    }

    /**
     * 答句的完整路径。**先试网络合成**（[NetVoice]），再退本机引擎。
     *
     * 顺序是这次真机反馈定下来的：系统语音包不能当成"一定有"。网络那条拿到 MP3 之后
     * 两条去处同源 —— 屏连着且 ≥v3 就解码成 16k PCM 推过去，否则就在本机播同一个 MP3。
     * 屏和手机喇叭因此念的是**同一句、同一个声音**，不会再出现"屏上是一种音色、手机是另一种"。
     */
    private fun synthesizeAndSend(
        turnId: String,
        text: String,
        origin: com.carpet.satdemo.core.protocol.DialogOrigin? = null,
        gen: Int = speechGen.get(),
    ) {
        // 入队之后、轮到执行之前屏可能已经起了新一轮：这时整轮作废，一个字都别开口
        if (gen != speechGen.get()) {
            log("屏幕语音：这句排到时新一轮已经开始（$turnId），不再开口")
            return
        }
        // §4：一句一句来。第一句先走完整条路（定落点、报 issue），后面的句子照同一个落点补上。
        // 遇到这轮拿不准的句子就停：后半句 seq 接不上，屏会整段丢，比只念半句的错更难解释
        val parts = splitSentences(text)
        var spoken = 0
        var stop = false
        val t0 = System.currentTimeMillis()
        parts.forEachIndexed { i, part ->
            if (stop) return@forEachIndexed
            // 每句之间都可能被新一轮打断：上一句放完那一刻屏正好起轮，
            // 不在这儿比对一次，这句就会接着响，再录进新的问话
            if (gen != speechGen.get()) {
                log("屏幕语音：新一轮已开始（$turnId），余下句子不念了")
                stop = true
                return@forEachIndexed
            }
            runCatching { synthesizeOne(turnId, part, origin, first = i == 0, spokenBytesSoFar = spoken, gen = gen) }
                .onFailure { log("屏幕语音失败（第 ${i + 1}/${parts.size} 句）：${it.message}") }
                .onSuccess { sent ->
                    if (sent == null) stop = true
                    else {
                        // 首句出声要多久：现场"半天不说话"时，这三种慢法（模型、取数、合成）
                        // 在界面上长得一模一样，只有这两个数放一起才分得清是谁的锅
                        if (i == 0) log("屏幕语音：首句已出（${System.currentTimeMillis() - t0} ms，${sent} 字节）")
                        spoken += sent
                    }
                }
        }
    }

    /**
     * 合成并送出一**句**。返回 null = 这句没能推给屏（路由失败/切块失败），调用方必须停；
     * 返回正数 = 这句计入整轮音频的字节数，下一句的 total 要把它算进去。
     * 网络这条路自己在本机放音的情况也返回字节数 —— 那份 PCM 本来就是同一段声音，
     * 屏这边只要真收到了，断句账就得记下去。
     */
    private fun synthesizeOne(
        turnId: String,
        text: String,
        origin: com.carpet.satdemo.core.protocol.DialogOrigin?,
        first: Boolean,
        spokenBytesSoFar: Int,
        gen: Int,
    ): Int? {
        // 新一轮已经开始：这句不念（也顺带把本机嘴闭上，防止它正好还在放上一轮）
        if (gen != speechGen.get()) {
            log("屏幕语音：新一轮已开始（$turnId），这句不念了")
            return null
        }
        // 新一轮：断点归零。同一轮（含一句拆出的各段、试听后的追问）接着数
        if (turnSeq != turnId) {
            turnSeq = turnId
            nextSeq = 0
            turnBytes = 0
        }
        val seqStart = nextSeq
        val bytesBefore = turnBytes

        val link = server()
        val screenReady = link != null && link.audioReady

        // 每句都打一条路由快照：现场"声音从哪出"对不上时，这一行就是第一现场。
        // 落点只剩一条判据（[answerHere]），所以这里把它的答案直接打出来，
        // 省得下一个人从 setting 和链路两处自己推
        val here = answerHere()
        log(
            "屏幕语音：路由 answerDevice=${_screenDevice.value.name} here=$here " +
                "origin=$origin audioReady=$screenReady negotiated=${link?.negotiatedVersion ?: "-"} " +
                "phoneVolume=${musicVolume()}"
        )

        val result = playNetAnswer(turnId, text, link, seqStart, bytesBefore, gen)
        if (result != null) {
            // 这句哪儿都没响：后半句没有接着说的账本了，整轮停在这里
            if (result.where == NetResult.Nothing) return null
            if (result.bytes > 0) {
                nextSeq = seqStart + result.frames
                turnBytes = bytesBefore + result.bytes
            }
            // 必须分清"手机响了"和"屏收到了"：解码失败时只有手机在响，屏的缓冲里一个字都没有。
            // 只看手机这一侧就清 issue，下次拔掉手机才发现屏从来没说过话
            _issue.value = when (result.where) {
                NetResult.ScreenOnly -> null
                NetResult.PhoneOnly ->
                    // 用户主动选"这台手机"= 预期落点，不是问题，别挂红字；
                    // 只有"屏该接但没接住"（选了圆屏却降级）才需要解释。
                    // 例外是媒体音量为 0：念了但一点听不见，必须说出来
                    if (answerHere() && _screenDevice.value == ScreenDevice.phone) silentPhone()
                    else if (screenReady) "这句只在这台手机上念了，圆屏那边暂时放不出来"
                    else whyNot(link)
                NetResult.Nothing -> "这句没念出来，可以再问一次"
            }
            if (_issue.value == null) lastGate = null
            else if (lastGate != _issue.value) { lastGate = _issue.value; log("屏幕语音：${_issue.value}") }
            return result.bytes
        }

        // 网络不通，退本机引擎
        if (unusable.get() || !latch.await(engineWaitMs, TimeUnit.MILLISECONDS)) {
            gate("这句没念出来。先连上能上网的 Wi-Fi 再问一次；还是没声的话，去手机设置里装一个中文语音包")
            return null
        }
        val engine = tts ?: run {
            gate("这句没念出来，这台手机上没有可用的语音引擎")
            return null
        }

        // 送不到屏（没连 / v2）就先在本机念。合成成 PCM 再发是给屏的路径，
        // 那一步占这条串行线程一两秒且屏根本收不到，所以这里直接走本机播放
        if (!screenReady) {
            applyTone()
            val ok = speakHere(engine, text)
            val silent = if (ok) silentPhone() else null
            when {
                !ok -> gate("这句没念出来，这台手机里的语音引擎没接住。换一个引擎再试一次")
                silent != null -> gate(silent)
                else -> gate(whyNot(link))
            }
            return null
        }

        // 落点跟 playNetAnswer 同一条规矩：用户选了"这台手机"，屏在线也不许往屏推
        if (answerHere()) {
            applyTone()
            val ok = speakHere(engine, text)
            if (!ok) {
                gate("这句没念出来，这台手机里的语音引擎没接住。换一个引擎再试一次")
            } else {
                // 念成功了也未必听得见：媒体音量为 0 时现场和"没声"一模一样
                _issue.value = silentPhone()
                if (_issue.value == null) lastGate = null
                else if (lastGate != _issue.value) { lastGate = _issue.value; log("屏幕语音：${_issue.value}") }
            }
            log("屏幕语音：本机引擎念这一句（owner 选的这台手机）")
            return 0
        }

        // 换过引擎之后旧实例上的音高会丢，这里每句都重设一次（开销可忽略）
        applyTone()

        val id = "v-${seq++}"
        val file = File(host.cacheDir, "tts-${turnId.filter { it.isLetterOrDigit() }.take(24)}-$id.wav")
        val done = CountDownLatch(1)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { done.countDown() }
            @Deprecated("老 API 仍会回调这一支") override fun onError(utteranceId: String?) { done.countDown() }
            override fun onError(utteranceId: String?, errorCode: Int) {
                log("屏幕语音：TTS 合成失败（错误码 $errorCode）")
                done.countDown()
            }
        })
        try {
            val ret = engine.synthesizeToFile(text, Bundle(), file, id)
            if (ret != TextToSpeech.SUCCESS) {
                speakHere(engine, text)
                gate("这句没能交给圆屏去念，先在手机上念了")
                return null
            }
            if (!done.await(20, TimeUnit.SECONDS)) {
                speakHere(engine, text)
                gate("这句念得太慢，先在手机上念了")
                return null
            }
            if (!file.exists() || file.length() == 0L) {
                speakHere(engine, text)
                gate("这句没合成出来，先在手机上念了")
                return null
            }
            // 合成占的那一两秒里屏可能起轮了：先作废，再往下就走"退回本机"的坑
            if (gen != speechGen.get()) {
                log("屏幕语音：这句合成完发现新一轮已开始，不放了")
                return null
            }
            val pcm = AudioChunks.wavToPcm16k(file.readBytes())
            if (pcm == null) {
                speakHere(engine, text)
                gate("这句的音频圆屏用不了，先在手机上念了")
                return null
            }
            // 合成期间屏可能刚断：执行器排了一两秒，这一步必须重新读一次连接
            val live = server()
            if (live == null || !live.audioReady) {
                speakHere(engine, text)
                gate("圆屏刚刚断开了，这句先在手机上念了")
                return null
            }
            // 系统引擎这条路同样量一次峰值：TTS 合成出零和网络那条是两种病因，日志要认得出来
            val amp = AudioChunks.peakAmp(pcm)
            log("屏幕语音：解码 PCM ${pcm.size} B，peak=${peakOf(pcm)}（源 system）")
            // #76 收口：peak=0 就是合成器交回来一段静音，推给屏只会换来"屏不出声"。
            // 本机接住、把原因摆在设置页 —— 静音从"要现场猜"变成"当场可查可听"
            if (amp == 0) {
                log("answer_peak=0.00 dest=本机（system 合成给了静音）")
                speakHere(engine, text)
                gate("这句合成出来是一段静音，屏收不到声音，先在手机上念了")
                return null
            }
            _source.value = "system"
            val frames = AudioChunks.commands(turnId, pcm, System.currentTimeMillis(), seqStart, bytesBefore + pcm.size)
            if (frames.isEmpty()) {
                speakHere(engine, text)
                gate("这句有点长，圆屏一次放不下，先在手机上念了")
                return null
            }
            live.broadcast(frames)
            noteFirstAudio(turnId)
            nextSeq = seqStart + frames.size
            turnBytes = bytesBefore + pcm.size
            // 这一句真送出去了：清掉上一次的原因，设置页上那行红字跟着消失
            _issue.value = null
            lastGate = null
            log("屏幕语音：${AudioChunks.secondsOf(pcm.size)} 秒语音已推给屏，${frames.size} 块。answer_peak=${peakOf(pcm)} dest=屏")
            return pcm.size
        } finally {
            file.delete()
        }
    }

    private enum class NetResult { ScreenOnly, PhoneOnly, Nothing }

    /**
     * [NetResult] 的账本：[bytes] 是这次真的推给屏的 PCM 字节数（0 = 屏一个帧都没收到），
     * [frames] 是推出去的 audio_chunk 块数。屏没收到时 bytes=0，调用方必须停 ——
     * 后半句的 seq 会接在一段屏根本没收到的账上。
     */
    private data class NetVoiceResult(val where: NetResult, val bytes: Int, val frames: Int)

    /**
     * 网络合成分支。拿到 MP3 后按**发声落点**决定去处：
     * - 落屏（[answerHere] 为 false，且屏在线可播）：只推屏，本机一声不出；
     * - 本机落点：只在手机放，**屏一个音频帧都不收**（一句 27 字 ≈ 75 帧 / 262 KB，不该为
     *   "反正屏也听不见"往链路上灌）；
     * - 两个都不落点才退回旧行为（不会发生：设置只有圆屏/这台手机两档）。
     *
     * 落点只剩 [wantScreen] = `!answerHere()` 一条判据。11-11 那版还带一个 `screenOnly`
     * 参数专门给屏起头开后门，11-18 删掉了 —— 屏起头也归选择格管。
     *
     * 返回值：null = 网络这条整个不通（调用方退本机引擎）；[NetVoiceResult] 说清到底谁听见了 ——
     * "手机响了"和"屏收到了"是两件事，混成一个布尔就把"解码失败"报成成功。
     */
    private fun playNetAnswer(
        turnId: String,
        text: String,
        link: ScreenLinkServer?,
        seqStart: Int,
        bytesBefore: Int,
        gen: Int,
    ): NetVoiceResult? {
        val mp3 = NetVoice.fetch(text, _tone.value) ?: return null
        // 合成要一两秒，这期间屏可能已经起轮录音了：代次一变这句就作废，别把上一轮的答话
        // 念进下一轮。此时**什么都不做**就返回 —— 退回本机引擎只会把同样的声音晚一步送出去
        if (gen != speechGen.get()) {
            log("屏幕语音：这句合成完发现新一轮已开始，不放了")
            return NetVoiceResult(NetResult.Nothing, 0, 0)
        }
        // 这一句的声音来源此刻就定了：后面不管送屏还是本机放，都是网络合成那一份
        _source.value = NetVoice.lastSource
        var screenGot = false
        var sentBytes = 0
        var sentFrames = 0

        val wantScreen = !answerHere()
        if (wantScreen && link != null && link.audioReady) {
            val pcm = NetVoice.pcm16k(mp3)
            if (pcm == null) {
                log("屏幕语音：网络合成的 MP3 没解成 16k PCM，这句只在本机响")
            } else {
                // §A（11-18）：屏侧实测"播放中 level 0.00"，硬证据指向推过去的 PCM 内容全零。
                // 这一行把"解码出了零"和"传输/屏侧出了零"当场分开：peak=0 就是解码这条的问题，
                // 重测时照这一行查；peak>0 才轮到怀疑链路
                val amp = AudioChunks.peakAmp(pcm)
                log(
                    "屏幕语音：解码 PCM ${pcm.size} B，peak=${peakOf(pcm)}（mp3 ${mp3.size} B，源 ${_source.value ?: "-"}）"
                )
                // #76 收口：解码回来全零就不推 —— 推了屏也是"播放中 level 0.00"，等于把静音事故原样转交给现场。
                // 这一段不置 screenGot，下面"屏一个字节都没收到"那条本机路会接住；换源重合成是下一步
                if (amp > 0) {
                    // commands() 超帧上限会抛：那是一句"编不进 4096"的硬错，别让它把整句吞掉
                    val frames = runCatching {
                        AudioChunks.commands(turnId, pcm, System.currentTimeMillis(), seqStart, bytesBefore + pcm.size)
                    }.onFailure {
                        log("屏幕语音：语音切块失败（${it.message}），这句只在本机响")
                    }.getOrDefault(emptyList())
                    if (frames.isNotEmpty()) {
                        link.broadcast(frames)
                        noteFirstAudio(turnId)
                        log("屏幕语音：网络合成 ${AudioChunks.secondsOf(pcm.size)} 秒语音已推给屏，${frames.size} 块。answer_peak=${peakOf(pcm)} dest=屏")
                        screenGot = true
                        sentBytes = pcm.size
                        sentFrames = frames.size
                    }
                } else {
                    log("屏幕语音：网络合成的 PCM 全零，这句不推给屏。answer_peak=0.00 dest=本机（源 ${_source.value ?: "-"}）")
                }
            }
        }

        var phoneGot = false
        // 屏一个字节都没收到时本机照样接住（落点定的是"该谁"，不是"谁聋了还硬等"）；
        // 屏收着了就不许再放，两只嘴念同一句是红线
        if (!wantScreen || !screenGot) {
            val player = NetVoice.playerFor(mp3)
            if (player != null) {
                // §4 按句念：这句要排在上一句**之后**放。原来直接 stopPhonePlayer()，
                // 第一句念到一半就被第二句掐死（owner 2026-11-16「第一句只会念一半」的手机喇叭版）。
                // 这条是串行 worker 线程，等放完再起下一句不挡任何人；30 秒封顶是防
                // 完成回调没来的保险丝，不是正常路径
                val prevDone = phoneDone
                if (prevDone != null && !prevDone.await(30, TimeUnit.SECONDS)) {
                    log("屏幕语音：上一句 30 秒没放完，这句不等了")
                }
                // 等到这儿，正常的上一句已经自己收尾（phonePlayer 置空）；还留着的要么是
                // 试听那份 MP3、要么是回调没来的残骸 —— 都得先掐掉，否则两只嘴一起响
                stopPhonePlayer()
                val done = CountDownLatch(1)
                // gen 在锁里复核：这一步不成立就说明屏刚刚起了新一轮，这句不许起播
                if (!installPhonePlayer(player, done, gen)) {
                    log("屏幕语音：等到该念时新一轮已开始，这句不放了")
                    runCatching { player.release() }
                    return NetVoiceResult(NetResult.Nothing, 0, 0)
                }
                player.setOnCompletionListener { mp ->
                    runCatching { mp.release() }
                    clearPhonePlayer(mp)
                    done.countDown()
                }
                player.setOnErrorListener { mp, _, _ ->
                    runCatching { mp.release() }
                    clearPhonePlayer(mp)
                    done.countDown()
                    true
                }
                try {
                    player.start()
                    phoneGot = true
                    log("屏幕语音：网络合成一句，本机喇叭播放中（${mp3.size} 字节）。answer_peak=未量 dest=本机")
                } catch (e: Exception) {
                    runCatching { player.release() }
                    clearPhonePlayer(player)
                    done.countDown()
                    log("屏幕语音：本机播放器起不来（${e.message}）")
                }
            }
        }
        return when {
            screenGot -> NetVoiceResult(NetResult.ScreenOnly, sentBytes, sentFrames)
            phoneGot -> NetVoiceResult(NetResult.PhoneOnly, 0, 0)
            else -> NetVoiceResult(NetResult.Nothing, 0, 0)
        }
    }

    /** 16k 单声道 s16le 的绝对值峰值（0.00–1.00），算法在 [AudioChunks.peakAmp]，和屏侧 mic 那条同一把尺 */
    private fun peakOf(pcm: ByteArray): String = "%.2f".format(AudioChunks.peakAmp(pcm) / 32768.0)

    fun release() {
        runCatching { stopPhonePlayer() }
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        worker.shutdownNow()
    }
}
