package com.carpet.satdemo.core.modem

import com.carpet.satdemo.core.protocol.SfiFailReason
import com.carpet.satdemo.core.protocol.SfiState

/**
 * 一条报文**是怎么出去的**。
 *
 * 这条区分是 §6.1 红线一的物理基础：排队（SFQ）只是把报文交给了模组的队列，
 * 星上还没收到；即时（MCQ）才谈得上 ACK。两者共用一句"已发送"就是把用户骗了一次。
 */
enum class SatPath {
    /** 立即发射并等星上确认（MCQ）。厂商 SDK 支持，语句层不支持（§9 #13） */
    IMMEDIATE,

    /** 交给模组队列，等下次过境自动发（SFQ） */
    QUEUED,
}

/**
 * 失败成因。规范里能读到的码只有 SFI 那三个，其余是我们自己必须区分的：
 * **展示端的红字要能说清"是没发出去，还是发出去没人认"**，这两种该做的动作不一样
 * （前者换开阔地重发，后者等过境）。
 */
enum class SatFailReason {
    /** 模组一个字都没回：拔线、上电未完成、BLE 断了 */
    NO_REPLY,

    /** 报文超过 ICI 报上来的长度等级上限 */
    TOO_LONG,

    /** 模组缓存已满（GM1003 只有 50 条） */
    QUEUE_FULL,

    /** 存进去了但没发出去就掉了：GM1003 的 4 分钟超时。App 侧必须重新排队 */
    DROPPED_BY_TIMEOUT,

    /** 星上没确认，模组自己报了发射失败 */
    TRANSMIT_FAILED,

    /** 上电会话没走完 / 无效卡：这时候连预算都不知道，发什么都可能是白发 */
    NOT_PROVISIONED,
    ;

    companion object {
        /** SFI 的失败原因码 → 我们的口径。状态 4（发射失败）没有原因码，归 TRANSMIT_FAILED */
        fun fromSfi(state: SfiState, code: SfiFailReason?): SatFailReason = when {
            state == SfiState.TRANSMIT_FAILED && code == SfiFailReason.TIMEOUT -> DROPPED_BY_TIMEOUT
            state == SfiState.TRANSMIT_FAILED -> TRANSMIT_FAILED
            code == SfiFailReason.TOO_LONG -> TOO_LONG
            code == SfiFailReason.BUFFER_FULL -> QUEUE_FULL
            else -> NO_REPLY
        }
    }
}

/**
 * 一次发送的完整结果：**三态 + 路径 + 成因**。
 *
 * 三态就是 [SatResult] 那三个，一个不多一个不少：
 *  - [SatResult.COMPLETE] 只代表**卫星收到了**，不代表星座核心网收到（〔规 §3.1.4 注6〕）；
 *  - [SatResult.NO_ACK] 已经发出去了，星上没确认；
 *  - [SatResult.FAIL] 没发出去，成因看 [reason]。
 *
 * 界面文案必须由 [com.carpet.satdemo.core.link.SosCopy] 从这一个对象生成，
 * 谁都不许在 UI 里另写一句（§6.1 红线一）。
 */
data class SatSend(
    val result: SatResult,
    val via: SatPath,
    val reason: SatFailReason? = null,
    /** 模组队列里的报文 ID（SFI 字段 2）。对账和"被超时丢弃后重新排队"都要靠它 */
    val messageId: Int? = null,
) {
    /** 模组是否收下了这一条。**收下 ≠ 送达**，补发队列只看这个位 */
    val accepted: Boolean get() = result != SatResult.FAIL

    companion object {
        fun failed(via: SatPath, reason: SatFailReason): SatSend = SatSend(SatResult.FAIL, via, reason)
    }
}
