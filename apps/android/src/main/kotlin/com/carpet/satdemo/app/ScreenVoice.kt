package com.carpet.satdemo.app

import android.content.Context
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
) {
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "screen-voice").apply { isDaemon = true }
    }
    @Volatile
    private var latch = CountDownLatch(1)
    private val unusable = AtomicBoolean(false)
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

    /** 播放这条试听/答句的 MediaPlayer。下一句来时先停旧的，两句不叠 */
    private var phonePlayer: MediaPlayer? = null

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

    /** 一句答话。**调用即返回**：合成要一两秒，不能在网关那条协程里等 */
    fun speak(turnId: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        worker.execute { runCatching { synthesizeAndSend(turnId, body) }.onFailure { log("屏幕语音失败：${it.message}") } }
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
                    _issue.value = null
                    lastGate = null
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
        phonePlayer = player
        player.setOnCompletionListener { mp ->
            runCatching { mp.release() }
            if (phonePlayer === mp) phonePlayer = null
            _previewing.value = false
        }
        player.setOnErrorListener { mp, what, extra ->
            log("本机试听：播放器出错（$what/$extra）")
            runCatching { mp.release() }
            if (phonePlayer === mp) phonePlayer = null
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
            phonePlayer = null
            false
        }
    }

    private fun stopPhonePlayer() {
        phonePlayer?.let { p ->
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
        phonePlayer = null
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
    private fun speakHere(engine: TextToSpeech, text: String) {
        val id = "f-${seq++}"
        _source.value = "system"
        val ret = engine.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), id)
        if (ret != TextToSpeech.SUCCESS) log("屏幕语音：手机喇叭也不接受这一句（返回 $ret）")
    }

    /**
     * 答句的完整路径。**先试网络合成**（[NetVoice]），再退本机引擎。
     *
     * 顺序是这次真机反馈定下来的：系统语音包不能当成"一定有"。网络那条拿到 MP3 之后
     * 两条去处同源 —— 屏连着且 ≥v3 就解码成 16k PCM 推过去，否则就在本机播同一个 MP3。
     * 屏和手机喇叭因此念的是**同一句、同一个声音**，不会再出现"屏上是一种音色、手机是另一种"。
     */
    private fun synthesizeAndSend(turnId: String, text: String) {
        val link = server()
        val screenReady = link != null && link.audioReady

        val result = playNetAnswer(turnId, text, link)
        if (result != null) {
            // 必须分清"手机响了"和"屏收到了"：解码失败时只有手机在响，屏的缓冲里一个字都没有。
            // 只看手机这一侧就清 issue，下次拔掉手机才发现屏从来没说过话
            _issue.value = when (result) {
                NetResult.Both, NetResult.ScreenOnly -> null
                NetResult.PhoneOnly ->
                    if (screenReady) "这句只在这台手机上念了，圆屏那边暂时放不出来"
                    else whyNot(link)
                NetResult.Nothing -> "这句没念出来，可以再问一次"
            }
            if (_issue.value == null) lastGate = null
            else if (lastGate != _issue.value) { lastGate = _issue.value; log("屏幕语音：${_issue.value}") }
            return
        }

        // 网络不通，退本机引擎
        if (unusable.get() || !latch.await(engineWaitMs, TimeUnit.MILLISECONDS)) {
            gate("这句没念出来。先连上能上网的 Wi-Fi 再问一次；还是没声的话，去手机设置里装一个中文语音包")
            return
        }
        val engine = tts ?: run {
            gate("这句没念出来，这台手机上没有可用的语音引擎")
            return
        }

        // 送不到屏（没连 / v2）就先在本机念。合成成 PCM 再发是给屏的路径，
        // 那一步占这条串行线程一两秒且屏根本收不到，所以这里直接走本机播放
        if (!screenReady) {
            applyTone()
            speakHere(engine, text)
            gate(whyNot(link))
            return
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
                return
            }
            if (!done.await(20, TimeUnit.SECONDS)) {
                speakHere(engine, text)
                gate("这句念得太慢，先在手机上念了")
                return
            }
            if (!file.exists() || file.length() == 0L) {
                speakHere(engine, text)
                gate("这句没合成出来，先在手机上念了")
                return
            }
            val pcm = AudioChunks.wavToPcm16k(file.readBytes())
            if (pcm == null) {
                speakHere(engine, text)
                gate("这句的音频圆屏用不了，先在手机上念了")
                return
            }
            // 合成期间屏可能刚断：执行器排了一两秒，这一步必须重新读一次连接
            val live = server()
            if (live == null || !live.audioReady) {
                speakHere(engine, text)
                gate("圆屏刚刚断开了，这句先在手机上念了")
                return
            }
            _source.value = "system"
            val frames = AudioChunks.commands(turnId, pcm, System.currentTimeMillis())
            if (frames.isEmpty()) {
                speakHere(engine, text)
                gate("这句有点长，圆屏一次放不下，先在手机上念了")
                return
            }
            live.broadcast(frames)
            // 这一句真送出去了：清掉上一次的原因，设置页上那行红字跟着消失
            _issue.value = null
            lastGate = null
            log("屏幕语音：${AudioChunks.secondsOf(pcm.size)} 秒语音已推给屏，${frames.size} 块")
        } finally {
            file.delete()
        }
    }

    private enum class NetResult { Both, ScreenOnly, PhoneOnly, Nothing }

    /**
     * 网络合成分支。拿到 MP3 后：屏能收就解码推屏，同时**本机也放**（屏可能音量小或被拔了）。
     *
     * 返回值：null = 网络这条整个不通（调用方退本机引擎）；[NetResult] 说清到底谁听见了 ——
     * "手机响了"和"屏收到了"是两件事，混成一个布尔就把"解码失败"报成成功。
     */
    private fun playNetAnswer(turnId: String, text: String, link: ScreenLinkServer?): NetResult? {
        val mp3 = NetVoice.fetch(text, _tone.value) ?: return null
        // 这一句的声音来源此刻就定了：后面不管送屏还是本机放，都是网络合成那一份
        _source.value = NetVoice.lastSource
        var screenGot = false

        if (link != null && link.audioReady) {
            val pcm = NetVoice.pcm16k(mp3)
            if (pcm == null) {
                log("屏幕语音：网络合成的 MP3 没解成 16k PCM，这句只在本机响")
            } else {
                // commands() 超帧上限会抛：那是一句"编不进 4096"的硬错，别让它把整句吞掉
                val frames = runCatching {
                    AudioChunks.commands(turnId, pcm, System.currentTimeMillis())
                }.onFailure {
                    log("屏幕语音：语音切块失败（${it.message}），这句只在本机响")
                }.getOrDefault(emptyList())
                if (frames.isNotEmpty()) {
                    link.broadcast(frames)
                    log("屏幕语音：网络合成 ${AudioChunks.secondsOf(pcm.size)} 秒语音已推给屏，${frames.size} 块")
                    screenGot = true
                }
            }
        }

        var phoneGot = false
        val player = NetVoice.playerFor(mp3)
        if (player != null) {
            stopPhonePlayer()
            phonePlayer = player
            player.setOnCompletionListener { mp ->
                runCatching { mp.release() }
                if (phonePlayer === mp) phonePlayer = null
            }
            player.setOnErrorListener { mp, _, _ ->
                runCatching { mp.release() }
                if (phonePlayer === mp) phonePlayer = null
                true
            }
            try {
                player.start()
                phoneGot = true
                log("屏幕语音：网络合成一句，本机喇叭播放中（${mp3.size} 字节）")
            } catch (e: Exception) {
                runCatching { player.release() }
                phonePlayer = null
                log("屏幕语音：本机播放器起不来（${e.message}）")
            }
        }
        return when {
            screenGot && phoneGot -> NetResult.Both
            screenGot -> NetResult.ScreenOnly
            phoneGot -> NetResult.PhoneOnly
            else -> NetResult.Nothing
        }
    }

    fun release() {
        runCatching { stopPhonePlayer() }
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        worker.shutdownNow()
    }
}
