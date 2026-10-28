package com.carpet.satdemo.core.link

import com.carpet.satdemo.core.modem.SatFailReason
import com.carpet.satdemo.core.modem.SatPath
import com.carpet.satdemo.core.modem.SatResult
import com.carpet.satdemo.core.modem.SatSend

/**
 * §6.1 红线一的可执行版本：**SOS / 补发的状态文案只能从这一张表里出来**。
 *
 * 为什么放到 core 而不是各端自己写：这条线的内容是"不许让界面把卫星 ACK 说成平台已收到"，
 * 而界面有三处（App、圆屏固件、演示控制台）。文案散在三处就守不住一条线，
 * 收进 core 之后 [BANNED] 可以用单测逐条扫，写错当场红。
 */
object SosCopy {

    /**
     * 任何输入下都不允许出现的说法。
     *
     * 「已送达」系列禁得这么死是有具体原因的：〔规 §3.1.4 注6〕明确写了
     * 卫星应答只表示**卫星**收到，不代表星座核心网收到，更不代表平台业务侧受理。
     * 在救援语境里说"已送达"，用户会停止重试 —— 那是会出人命的措辞。
     */
    val BANNED = listOf("已送达", "发送成功", "送达成功", "救援已在路上", "救援队已在路上", "平台已受理", "一定没问题")

    /**
     * @param platformAcked 只有真的收到**平台侧回执**才传 true。
     * 星上 ACK 不算 —— 传错了就是踩红线，[com.carpet.satdemo.core.engine.DemoEngine] 里
     * 那个入口叫 `onPlatformAck`，故意不叫 `onAck`。
     */
    fun statusLine(send: SatSend, nextPassInS: Long? = null, platformAcked: Boolean = false): String {
        val base = when {
            platformAcked -> "平台已回执"
            send.result == SatResult.COMPLETE -> "卫星已收到，正等平台回执"
            send.via == SatPath.QUEUED && send.result == SatResult.NO_ACK -> "已存入终端队列，过境时自动发"
            send.result == SatResult.NO_ACK -> "卫星未确认，请在开阔处等待下次过境"
            else -> "本次没发出去：" + failPhrase(send.reason)
        }
        // 三态里除了"平台已回执"，剩下的都必须给一个可动作的时间，否则用户只能干等
        val wait = if (!platformAcked && send.result != SatResult.COMPLETE) nextPassPhrase(nextPassInS) else null
        return listOfNotNull(base, wait).joinToString("｜")
    }

    /**
     * 撤销那句话。**分三档，而且不许合并成一档**：
     * 撤销报文本身可能也发不出去，也可能原 SOS 已经进了模组队列 —— 后一种情况
     * 说"已取消"就是让用户以为不用管了，而那条 SOS 马上就会过顶发出去。
     * 这一条是 [BANNED] 那张表的另一半：红线不只防"说过头"，也要防"撤过头"。
     */
    fun cancelLine(send: SatSend, alreadyHanded: Boolean): String = when {
        !send.accepted -> "撤销没送出去：" + failPhrase(send.reason) + "｜原报文仍在终端里，请再试一次"
        alreadyHanded -> "撤销报文已排队，但原 SOS 已交给终端，不保证赶在它前面｜请留意平台是否仍然来电"
        send.result == SatResult.COMPLETE -> "撤销已发出，卫星已收到撤销报文｜正等平台确认"
        send.via == SatPath.QUEUED -> "撤销已存入终端队列，过境时随原报文一起发"
        else -> "撤销报文已发出，未获卫星确认｜若平台联系，请说明误触"
    }

    fun failPhrase(reason: SatFailReason?): String = when (reason) {
        SatFailReason.NO_REPLY -> "终端没有回应，检查供电和连线"
        SatFailReason.TOO_LONG -> "报文超出终端报的长度等级上限"
        SatFailReason.QUEUE_FULL -> "终端缓存已满，等下次过境后再补"
        SatFailReason.DROPPED_BY_TIMEOUT -> "上次过境没发出去，已重新排队"
        SatFailReason.TRANSMIT_FAILED -> "终端发射失败"
        SatFailReason.NOT_PROVISIONED -> "终端还没就绪，正在建立会话"
        SatFailReason.UPGRADING -> "终端正在升级固件，升级期间发不出去，完成后会自动补发"
        null -> "原因未知"
    }

    /** 「距下次过顶 4 分 49 秒」。星历没到手时宁可写"未知"，也不要显示 0 秒 */
    fun nextPassPhrase(nextPassInS: Long?): String? = when {
        nextPassInS == null -> "下次过顶时间未知"
        nextPassInS <= 0 -> "正在过境窗口内"
        else -> "距下次过顶 " + minuteSecond(nextPassInS)
    }

    private fun minuteSecond(sec: Long): String {
        val m = sec / 60
        val s = sec % 60
        return if (m > 0) "$m 分 $s 秒" else "$s 秒"
    }
}
