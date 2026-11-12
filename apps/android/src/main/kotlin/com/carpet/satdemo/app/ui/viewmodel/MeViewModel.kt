package com.carpet.satdemo.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carpet.satdemo.app.AppRuntime
import com.carpet.satdemo.core.ai.AiEndpoint
import com.carpet.satdemo.core.ai.PermissionTier
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 「我的」页。这一页承担三件事：设备管理卡、几条开关、AI 能力档。
 *
 * 开关里只有 [Flags] 那几个布尔和两枚服务开关；哨兵/围栏的现值在快照里，
 * 所以这一页的 Ready 同时带上 flags 和快照里的两枚布尔 —— 界面照着画勾，
 * 不再从 `status.snapshot?.sentinel?.armed` 处一路问下去。
 */
sealed interface MeUiState {
    data object Loading : MeUiState

    data class Ready(
        val flags: FlagsUi,
        val sentinel: Boolean,
        val fence: Boolean,
        val guardRunning: Boolean,
        val assistant: MeAssistantUi,
    ) : MeUiState
}

/** [com.carpet.satdemo.app.AppRuntime.Flags] 里这一页管得着的三枚 */
data class FlagsUi(
    val acceptScreens: Boolean = false,
    val demoMode: Boolean = false,
)

data class MeAssistantUi(
    val endpoint: AiEndpoint = AiEndpoint(),
    val granted: PermissionTier = PermissionTier.ReadLocal,
    /** 「它能不能做这 N 件事」的逐条判定。granted 一变就得重算，原来靠 remember(granted) 兜 */
    val skills: List<SkillRowUi> = emptyList(),
)

data class SkillRowUi(val title: String, val needs: PermissionTier, val allowed: Boolean)

class MeViewModel(private val app: AppRuntime) : ViewModel() {

    private data class RuntimePart(
        val acceptScreens: Boolean, val demoMode: Boolean, val sentinel: Boolean, val fence: Boolean, val running: Boolean,
    )

    private val runtimeFlow = combine(app.flags, app.status) { f, st ->
        RuntimePart(
            acceptScreens = f.acceptScreens,
            demoMode = f.demoMode,
            sentinel = st.snapshot?.sentinel?.armed == true,
            fence = st.snapshot?.fence?.active == true,
            running = st.running,
        )
    }

    private val assistantFlow = combine(app.aiEndpoint, app.aiGranted) { ep, g ->
        MeAssistantUi(
            endpoint = ep,
            granted = g,
            skills = app.aiSkills().map { SkillRowUi(it.title, it.needs, it.allowed) },
        )
    }

    val uiState: StateFlow<MeUiState> = combine(runtimeFlow, assistantFlow) { r, a ->
        MeUiState.Ready(
            flags = FlagsUi(r.acceptScreens, r.demoMode),
            sentinel = r.sentinel,
            fence = r.fence,
            guardRunning = r.running,
            assistant = a,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MeUiState.Loading)

    fun setGuard(on: Boolean) = app.setGuard(on)

    fun setAcceptScreens(on: Boolean) = app.setFlag(app.flags.value.copy(acceptScreens = on))

    fun setDemoMode(on: Boolean) = app.setFlag(app.flags.value.copy(demoMode = on))

    fun setSentinel(on: Boolean) = app.setSentinel(on)

    fun setFence(on: Boolean, name: String) = app.setFence(on, name)

    fun setGranted(tier: PermissionTier) = app.setAiGranted(tier)
}
