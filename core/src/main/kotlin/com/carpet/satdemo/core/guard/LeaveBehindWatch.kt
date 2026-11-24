package com.carpet.satdemo.core.guard

import com.carpet.satdemo.core.devices.Device
import com.carpet.satdemo.core.devices.DeviceKind
import com.carpet.satdemo.core.devices.DeviceLink

/**
 * 断联提醒 —— 遗留车内告警（§3.3，架构文档 §7「走散 / 遗留车内告警」）能落地的第一版。
 *
 * 原设计要「两个定位源做差」：手机走了、车内那台设备还留在车的位置上不动 → 报警。
 * 但圆屏没有 GNSS、手环硬件还没买，第二个定位源今天拿不到，所以这一版换一个判据：
 * **链路本身就是距离证据**。手机是服务端、屏连的是手机/车那侧的网络，人带着手机走远，
 * 这条链路必然先断。于是「断联 + 手机已经离开停车圈」这两个条件同时成立时，
 * 能说的是「你离开时那台设备没跟着你走」——这句有依据；「车内有人」那句没有，不许说。
 * 高温和体征两个数据源仍然缺，本模块不假装它们存在。
 *
 * 判定为什么单独一个类、且只吃 [Device] 和「离开多少米」：
 * 围栏的圆归引擎画（它已经有 [com.carpet.satdemo.core.engine.DemoEngine.setFence] 那套几何），
 * 通知和卫星归 App 层，这里只回答一个问题——**这一拍该不该打扰人**。
 * 因此它能脱离手机跑单测，误报那几条规则必须是被测出来的，不是被现场发现的。
 */
class LeaveBehindWatch(
    /**
     * 断联之后要等多久才认账。给宽是为了压误报：屏自己重启、路由换手、蓝牙抖动
     * 都会造成几秒到几十秒的断链，那种时候报警等于教用户把这个开关关掉。
     */
    var holdMs: Long = DEFAULT_HOLD_MS,
) {
    enum class Phase {
        /** 没布防 */
        Idle,

        /** 布防中，盯的那几台都还连着 */
        Armed,

        /** 有设备断联了，还在宽限期内或人还没走远 */
        Waiting,

        /** 已经报过。一次断联事件只报一次，直到那台设备重新连上 */
        Alarmed,
    }

    /** 布防那一刻登记在册的一台车内设备。只留 id 和名字：台账每拍会换，抄整份会读到旧状态 */
    data class Tracked(val id: String, val title: String)

    /** [tick] 吐出来的一句话。调用方负责念：日志、通知栏、卫星，各取所需 */
    sealed interface Note {
        val device: Tracked

        /** 刚断联，开始计时。这一刻**不该响**，只该在日志里留痕 */
        data class Lost(override val device: Tracked, val willFireInMs: Long) : Note

        /** 断联时间够了，但人还在停车圈里 —— 更像屏自己掉线，不能报遗留 */
        data class InPlace(override val device: Tracked) : Note

        /** 告警。[awayM] = 已经离开圈多少米 */
        data class Fire(override val device: Tracked, val awayM: Long, val heldMs: Long) : Note

        /** 重新连上。[wasAlarmed] = 刚才那条告警就此解除 */
        data class Back(override val device: Tracked, val wasAlarmed: Boolean) : Note
    }

    var phase: Phase = Phase.Idle
        private set

    var tracked: List<Tracked> = emptyList()
        private set

    private class Episode {
        var droppedSince: Long? = null
        var notedLost = false
        var notedInPlace = false
        var fired = false
    }

    private val episodes = LinkedHashMap<String, Episode>()

    /** 哪些设备算「可能被留在车上的那一台」。卫星模组和手机自己随行，排除 */
    private val inCarKinds = setOf(
        DeviceKind.DisplayScreen,
        DeviceKind.ChildDevice,
        DeviceKind.Wearable,
        DeviceKind.Vehicle,
    )

    /**
     * 布防。
     *
     * @return null = 成功；非空 = 拒绝原因，**必须原样念给用户**。宁可开不起来也不要开一个
     *         永远不会响的开关——那比不开更危险（§6.1 红线一同款道理：不许让用户以为有人在看着）
     */
    fun arm(devices: List<Device>, nowMs: Long, holdMs: Long = this.holdMs): String? {
        val candidates = devices.filter { it.kind in inCarKinds && it.online }
        if (candidates.isEmpty()) {
            return "现在没有连着手机的车内设备（圆屏 / 儿童设备 / 手环），断联提醒管不了。" +
                "先把要留在车上那台连上再开这个开关"
        }
        this.holdMs = holdMs
        tracked = candidates.map { Tracked(it.id, it.title) }
        episodes.clear()
        phase = Phase.Armed
        return null
    }

    /** 撤防。清干净，不留「下一次 tick 再补一条」的状态 */
    fun disarm() {
        tracked = emptyList()
        episodes.clear()
        phase = Phase.Idle
    }

    /**
     * 每拍喂一次（1Hz 那一圈就够，宽限期本身是几十秒量级）。
     *
     * @param outsideM 已经离开停车圈多少米；null = 还在圈内，或**还没有定位**。
     *   没有定位时宁可选择不报：猜出来的「车里有人」会被当成狼来了，一次就毁掉这个功能
     */
    fun tick(devices: List<Device>, outsideM: Double?, nowMs: Long): List<Note> {
        if (phase == Phase.Idle) return emptyList()
        val notes = ArrayList<Note>()
        val byId = devices.associateBy { it.id }
        for (t in tracked) {
            val d = byId[t.id]
            // 台账里没了 = 被摘掉了（revoke / drop），按断联算。
            // Degraded 不算断：契约不匹配的屏仍然连着，那是"不能说"不是"不在"
            val gone = d == null || d.link is DeviceLink.Offline || d.link is DeviceLink.Absent
            val ep = episodes.getOrPut(t.id) { Episode() }
            if (!gone) {
                if (ep.droppedSince != null || ep.fired) {
                    notes += Note.Back(t, wasAlarmed = ep.fired)
                    episodes.remove(t.id)
                }
                continue
            }
            val since = ep.droppedSince ?: nowMs.also { ep.droppedSince = it }
            val held = nowMs - since
            if (!ep.notedLost) {
                ep.notedLost = true
                notes += Note.Lost(t, (holdMs - held).coerceAtLeast(0))
            }
            if (held >= holdMs) {
                val away = outsideM?.takeIf { it > 0 }
                if (away != null) {
                    if (!ep.fired) {
                        ep.fired = true
                        notes += Note.Fire(t, away.toLong(), held)
                    }
                } else if (!ep.notedInPlace) {
                    ep.notedInPlace = true
                    notes += Note.InPlace(t)
                }
            }
        }
        phase = when {
            episodes.values.any { it.fired } -> Phase.Alarmed
            episodes.values.any { it.droppedSince != null } -> Phase.Waiting
            else -> Phase.Armed
        }
        return notes
    }

    /** 卡面上那行状态话。日志和界面共用一份，别在 UI 里再判一次 */
    fun statusLine(nowMs: Long): String {
        val names = tracked.joinToString("/") { it.title }.ifEmpty { "无" }
        return when (phase) {
            Phase.Idle -> "断联提醒未开"
            Phase.Armed -> "断联提醒盯住 $names"
            Phase.Waiting -> {
                val held = episodes.values.mapNotNull { it.droppedSince }.maxOrNull()?.let { nowMs - it } ?: 0
                "已断联 ${held / 1000} 秒，${((holdMs - held) / 1000).coerceAtLeast(0)} 秒后仍没接上且人已离开就报警"
            }
            Phase.Alarmed -> "已报：$names 在你离开时仍留在车上"
        }
    }

    companion object {
        /** 默认 60 秒：够压掉屏重启，又不至于让孩子在车里等到中暑才响 */
        const val DEFAULT_HOLD_MS = 60_000L
    }
}
