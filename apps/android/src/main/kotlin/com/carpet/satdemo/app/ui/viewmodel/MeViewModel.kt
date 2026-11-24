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
        /** 断联提醒开没开。开没开由 core 的判定状态出，界面不许自己记一个布尔 */
        val leaveBehind: Boolean = false,
        /** 那一行下面跟着的现值话。空 = 用开关自带的说明 */
        val leaveBehindLine: String = "",
        val guardRunning: Boolean,
        val assistant: MeAssistantUi,
        /** 它现在的名字。空 = 还没起名，界面上先给起名入口 */
        val petName: String = "",
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
    /** 它的名字，从 [AppRuntime.petName] 带上来，起名卡和文案层都读这一份 */
    val petName: String = "",
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

    private data class GuardPart(val armed: Boolean, val line: String)

    private val guardFlow = combine(app.status, app.leaveBehindLine) { _, line ->
        // 借 status 那一拍驱动：断联的现值只有 1Hz 在跑的时候才在动，
        // 单独读一次 leaveBehindLine 会让"已断联 12 秒"那行停在布防那一刻
        GuardPart(app.leaveBehindArmed(), line)
    }

    private val assistantFlow = combine(app.aiEndpoint, app.aiGranted, app.petName) { ep, g, name ->
        MeAssistantUi(
            endpoint = ep,
            granted = g,
            skills = app.aiSkills().map { SkillRowUi(it.title, it.needs, it.allowed) },
            petName = name,
        )
    }

    val uiState: StateFlow<MeUiState> = combine(runtimeFlow, assistantFlow, guardFlow) { r, a, g ->
        MeUiState.Ready(
            flags = FlagsUi(r.acceptScreens, r.demoMode),
            sentinel = r.sentinel,
            fence = r.fence,
            leaveBehind = g.armed,
            leaveBehindLine = g.line,
            guardRunning = r.running,
            assistant = a,
            petName = a.petName,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MeUiState.Loading)

    fun setGuard(on: Boolean) = app.setGuard(on)

    fun setAcceptScreens(on: Boolean) = app.setFlag(app.flags.value.copy(acceptScreens = on))

    fun setDemoMode(on: Boolean) = app.setFlag(app.flags.value.copy(demoMode = on))

    fun setSentinel(on: Boolean) = app.setSentinel(on)

    fun setFence(on: Boolean, name: String) = app.setFence(on, name)

    fun setLeaveBehind(on: Boolean) = app.setLeaveBehind(on)

    fun setGranted(tier: PermissionTier) = app.setAiGranted(tier)

    fun setPetName(name: String) = app.setPetName(name)
}
