package com.carpet.satdemo.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carpet.satdemo.app.AppRuntime
import com.carpet.satdemo.core.ai.Turn
import com.carpet.satdemo.core.ai.TurnFrom
import com.carpet.satdemo.core.ai.TurnScene
import com.carpet.satdemo.core.ai.screenSceneOf
import com.carpet.satdemo.core.protocol.ScreenPage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 车辆 / 家人两页共用的就地发问条状态。
 * 这两页的主体是卡片流（`status` 从 Shell 参数直接进 [com.carpet.satdemo.app.ui.CardScope]），
 * 状态层真正要合的只有发问那一根：这一页自己的对话 + 还在等回答 + 模型接没接上。
 */
data class AskBarUiState(
    /** 只滤本页 [TurnScene] 的那几条 */
    val turns: List<Turn> = emptyList(),
    /** 我发出去的那句还没人接 */
    val waiting: Boolean = false,
    val modelConfigured: Boolean = false,
)

abstract class SceneViewModel(private val app: AppRuntime, private val scene: TurnScene) : ViewModel() {

    val ask: StateFlow<AskBarUiState> = combine(app.chat, app.aiThinking, app.aiEndpoint) { chat, thinking, ep ->
        val mine = chat.filter { it.scene == scene }
        AskBarUiState(
            turns = mine.takeLast(2),
            waiting = thinking && mine.lastOrNull()?.from == TurnFrom.Me,
            modelConfigured = ep.configured,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AskBarUiState())

    fun ask(text: String) {
        if (text.isNotBlank()) app.askAi(text, scene = scene)
    }

    /** 屏此刻停在这一页时，发问条上那句话才成立；VM 把这个判断收进来，页面不再直接读 runtime */
    fun isScreenOnPage(page: ScreenPage?): Boolean = screenSceneOf(page) == scene

    /** 这一页的卡片串。用户隐藏或上移一张，`cardOrder`/`hiddenCards` 一变它就推新值 */
    val cards: StateFlow<List<String>> =
        combine(app.scene, app.cardOrder, app.hiddenCards) { sc, _, _ -> app.orderedCards(sc) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), app.orderedCards(app.scene.value))
}

class VehicleViewModel(app: AppRuntime) : SceneViewModel(app, TurnScene.vehicle)

class FamilyViewModel(app: AppRuntime) : SceneViewModel(app, TurnScene.family)
