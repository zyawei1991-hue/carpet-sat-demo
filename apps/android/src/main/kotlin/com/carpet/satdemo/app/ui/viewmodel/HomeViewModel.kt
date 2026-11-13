package com.carpet.satdemo.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carpet.satdemo.app.AppRuntime
import com.carpet.satdemo.app.ScreenVoice
import com.carpet.satdemo.app.VoiceEngine
import com.carpet.satdemo.app.VoiceFace
import com.carpet.satdemo.app.VoiceTone
import com.carpet.satdemo.app.ui.Scene
import com.carpet.satdemo.app.ui.ScreenDialogLine
import com.carpet.satdemo.core.ai.AiEndpoint
import com.carpet.satdemo.core.ai.PermissionTier
import com.carpet.satdemo.core.ai.Turn
import com.carpet.satdemo.core.devices.Device
import com.carpet.satdemo.core.devices.DeviceKind
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 首页（Tab 1）的 UI 状态层。UDF 的"数据向上"在这一层汇拢：
 * [AppRuntime] 还是领域层唯一的那一份事实，这里只把 UI 要读的十几条流
 * 合成一个 [HomeUiState]；界面不再各自 collectAsState，事件一律走 VM 的动作方法向下传。
 *
 * 之前 [com.carpet.satdemo.app.ui.HomeShell] 一棵组件树里散着 26 处 collectAsState 和
 * 6 处不走 Flow 的裸读（guardPinned / screenDialog / screenProtocol / allowedScreens /
 * orderedCards / managedCards）—— 裸读的那些不随状态变化重算，界面有没有刷新全看运气。
 * 合进 combine 之后它们跟着同一条流走，同帧同值。
 */
sealed interface HomeUiState {
    data object Loading : HomeUiState

    data class Ready(
        val scene: Scene,
        /** 场景卡片流（已按用户排序、滤掉隐藏和固定项），过境卡由兜底栏管，不在这串里 */
        val stream: List<String>,
        /** 「调整顺序」那一页管得了的卡（滤掉 SOS / 链路） */
        val managed: List<String>,
        val hidden: Set<String>,
        val guardOn: Boolean,
        val cancelLine: String?,
        val assistant: AssistantUiState,
        val dock: DockUiState,
    ) : HomeUiState
}

/** 就地发问条共用的那一小份：车辆页和亲子页各挂一个，首页用的是整块 [AssistantUiState] */
data class AskUiState(
    val chat: List<Turn> = emptyList(),
    val thinking: Boolean = false,
    val modelConfigured: Boolean = false,
)

/** AI 助手主卡（首页那张 Raised）。语音引擎/发音人/收音全部从 AppRuntime 的流里合出来 */
data class AssistantUiState(
    val chat: List<Turn> = emptyList(),
    val thinking: Boolean = false,
    val endpoint: AiEndpoint = AiEndpoint(),
    val granted: PermissionTier = PermissionTier.ReadLocal,
    val error: String? = null,
    val probing: Boolean = false,
    val engine: String? = null,
    val source: String? = null,
    val engines: List<VoiceEngine> = emptyList(),
    val engineChoice: String? = null,
    val tone: VoiceTone = VoiceTone.Normal,
    val answerDevice: ScreenVoice.ScreenDevice = ScreenVoice.ScreenDevice.screen,
    val faces: List<VoiceFace> = emptyList(),
    val faceChoice: String? = null,
    val previewing: Boolean = false,
    val voiceIssue: String? = null,
    val listening: Boolean = false,
    val transcribing: Boolean = false,
    val screenDialog: Boolean = false,
    val screenProtocol: Int = 0,
)

/** 圆屏接入卡。allowed 从"组合时裸读"变成随 devices/flags/status 一起重算 */
data class DockUiState(
    val acceptScreens: Boolean = false,
    val screens: List<Device> = emptyList(),
    val allowed: Set<String> = emptySet(),
    val screenLine: ScreenDialogLine? = null,
)

class HomeViewModel(private val app: AppRuntime) : ViewModel() {

    private data class DialogPart(val chat: List<Turn>, val thinking: Boolean, val error: String?, val probing: Boolean)
    private data class VoiceCore(
        val engine: String?, val source: String?, val engines: List<VoiceEngine>, val choice: String?, val tone: VoiceTone,
    )
    private data class VoiceFacePart(
        val device: ScreenVoice.ScreenDevice, val faces: List<VoiceFace>, val choice: String?,
        val previewing: Boolean, val issue: String?,
    )
    private data class MicScreenPart(val listening: Boolean, val transcribing: Boolean, val dialog: Boolean, val protocol: Int)
    private data class CardPart(val scene: Scene, val stream: List<String>, val managed: List<String>, val hidden: Set<String>)

    private val assistantFlow = combine(
        combine(app.chat, app.aiThinking, app.aiError, app.aiProbing) { c, t, e, p -> DialogPart(c, t, e, p) },
        combine(app.aiEndpoint, app.aiGranted) { ep, g -> ep to g },
        combine(app.voiceEngine, app.voiceSource, app.voiceEngines, app.voiceChoice, app.voiceTone) { e, s, es, c, t ->
            VoiceCore(e, s, es, c, t)
        },
        combine(app.answerDevice, app.voiceFaces, app.voiceFaceChoice, app.voicePreviewing, app.voiceIssue) { d, f, fc, p, i ->
            VoiceFacePart(d, f, fc, p, i)
        },
        // screenDialog / screenProtocol 是裸 getter，原来组合到哪算到哪；挂上 status 流，链路一动就重算
        combine(app.listening, app.transcribing, app.status) { l, t, _ -> MicScreenPart(l, t, app.screenDialog, app.screenProtocol) },
    ) { d, cfg, vc, vf, ms ->
        AssistantUiState(
            chat = d.chat,
            thinking = d.thinking,
            endpoint = cfg.first,
            granted = cfg.second,
            error = d.error,
            probing = d.probing,
            engine = vc.engine,
            source = vc.source,
            engines = vc.engines,
            engineChoice = vc.choice,
            tone = vc.tone,
            answerDevice = vf.device,
            faces = vf.faces,
            faceChoice = vf.choice,
            previewing = vf.previewing,
            voiceIssue = vf.issue,
            listening = ms.listening,
            transcribing = ms.transcribing,
            screenDialog = ms.dialog,
            screenProtocol = ms.protocol,
        )
    }

    private val cardFlow = combine(app.scene, app.cardOrder, app.hiddenCards) { scene, _, hidden ->
        CardPart(scene, app.orderedCards(scene).filterNot { it == "pass" }, app.managedCards(scene), hidden)
    }

    private val dockFlow = combine(app.flags, app.devices, app.screenLine, app.status) { f, devs, line, _ ->
        DockUiState(f.acceptScreens, devs.filter { it.kind == DeviceKind.DisplayScreen }, app.allowedScreens(), line)
    }

    val uiState: StateFlow<HomeUiState> =
        combine(assistantFlow, cardFlow, dockFlow, app.cancelLine) { a, cards, dock, cancel ->
            // guardPinned 读的是守护服务在不在跑；status 汇进 dockFlow，服务一有动静这里就跟着重算
            HomeUiState.Ready(cards.scene, cards.stream, cards.managed, cards.hidden, app.guardPinned, cancel, a, dock)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState.Loading)

    private val micFlow = combine(app.listening, app.transcribing) { l, t -> l to t }

    /** 麦克风那颗钮只吃这两条；挂在 VM 上，壳里不再各自 collect */
    val listenState: StateFlow<Pair<Boolean, Boolean>> =
        micFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false to false)

    fun ask(text: String) {
        if (text.isNotBlank()) app.askAi(text)
    }

    fun skillTitle(id: String?): String? = id?.let { app.skillTitle(it) }

    fun setTone(tone: VoiceTone) = app.setVoiceTone(tone)

    fun setEngine(id: String?) = app.setVoiceEngine(id)

    fun setFace(name: String?) = app.setVoiceFace(name)

    fun setAnswerDevice(device: ScreenVoice.ScreenDevice) = app.setAnswerDevice(device)

    fun previewVoice() = app.previewVoice()

    fun sceneOn(scene: Scene) = app.sceneOn(scene)

    fun moveCardUp(key: String) = app.moveCard(key, up = true)

    fun toggleCard(key: String, hidden: Boolean) = app.toggleCard(key, hidden)

    fun setAcceptScreens(on: Boolean) = app.setFlag(app.flags.value.copy(acceptScreens = on))

    fun pairScreen(id: String): Boolean = app.pairScreen(id)

    fun revokeScreen(id: String) = app.revokeScreen(id)

    fun openSkills() = app.openTab(AppRuntime.Tab.Me)

    /** 首页圆屏卡进来的：设备与底座中心在「我的」（Tab 3），配对表单整个归它管 */
    fun openDeviceCenter() = app.openTab(AppRuntime.Tab.Me)
}
