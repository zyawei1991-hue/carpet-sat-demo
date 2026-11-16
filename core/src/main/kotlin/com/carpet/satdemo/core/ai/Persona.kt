package com.carpet.satdemo.core.ai

/**
 * 对话人格 = SYSTEM 提示词 + 事实表取材范围。按 [TurnScene] 分：
 * owner 2026-11-15 的裁决 —— 卫星只是车载极少数情况用的功能，对话不能满嘴过顶。
 *
 * **事实表按人格取材是这一刀的根**：模型手里只有卫星事实时，问什么它都往卫星上绕；
 * 亲子态根本不喂卫星/链路，模型自然谈孩子的事。
 */
enum class Persona(
    val identity: String,
    /** 卫星/链路那组字段进不进事实表。亲子/助手态一律 false */
    val satelliteFacts: Boolean,
) {
    /** 车载守护：卫星/围栏/车况是本职，但也不主动满屏卫星 */
    Guardian(
        identity = "你是车宠守护助手",
        satelliteFacts = true,
    ),

    /** 亲子陪伴：温暖陪伴，**不喂卫星事实**（没有的事实说没有） */
    Family(
        identity = "你是孩子的陪伴助手",
        satelliteFacts = false,
    ),

    /** 屏 avatar / 对话页：通用问答助手 */
    Assistant(
        identity = "你是圆屏上的语音助手",
        satelliteFacts = false,
    ),
    ;

    companion object {
        fun of(scene: TurnScene?): Persona = when (scene) {
            TurnScene.family -> Family
            TurnScene.assistant -> Assistant
            TurnScene.vehicle -> Guardian
            null -> Guardian
        }
    }
}
