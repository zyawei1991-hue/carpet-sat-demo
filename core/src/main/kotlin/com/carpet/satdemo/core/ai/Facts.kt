package com.carpet.satdemo.core.ai

import com.carpet.satdemo.core.runtime.DemoRuntime

/**
 * 事实表：全部从状态机读，一个都不新造（core/ai 的设计前提）。
 *
 * 这段原来长在 App 里。搬到 core 是为了让**手机和桌面彩排台读同一张表**：
 * 彩排台上答得对、装到手机上答得不一样，这种偏差只会在演示现场暴露，而那时候已经太晚。
 *
 * [scene] 与 [aiEndpointConfigured] 由宿主给 —— 场景标签和端点配置是各自界面里的状态，
 * 不是运行时事实，所以不作"从状态机读"这条规矩的反例。
 */
fun DemoRuntime.skillContext(scene: String, aiEndpointConfigured: Boolean = false): SkillContext {
    val s = status.value.snapshot
    val u = engine.usage()
    return SkillContext(
        scene = scene,
        phase = s?.sat?.phase?.name,
        secondsToNextPass = s?.sat?.nextPassInS,
        passWindowSec = s?.sat?.nextPassDurS,
        moduleOnline = s?.sat?.moduleOk,
        gnssLat = s?.gnss?.lat?.takeIf { it != 0.0 },
        gnssLon = s?.gnss?.lon?.takeIf { it != 0.0 },
        queueWaiting = s?.sat?.queuePending,
        queueCapacity = s?.sat?.queueCapacity,
        handedOver = u.handedOver,
        phoneBattery = s?.phoneBattery,
        ephemerisFresh = s?.sat?.ephState?.name?.let { it == "SUCCESS" },
        fenceActive = s?.fence?.active,
        fenceInside = s?.fence?.takeIf { it.active }?.inside,
        screensOnline = status.value.clients,
        aiEndpointConfigured = aiEndpointConfigured,
    )
}
