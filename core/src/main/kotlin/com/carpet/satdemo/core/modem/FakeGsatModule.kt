package com.carpet.satdemo.core.modem

import com.carpet.satdemo.core.protocol.AccessService
import com.carpet.satdemo.core.protocol.CommunicationBits
import com.carpet.satdemo.core.protocol.GeodeticPosition
import com.carpet.satdemo.core.protocol.GsatParseResult
import com.carpet.satdemo.core.protocol.GsatSentence
import com.carpet.satdemo.core.protocol.GsatSpeaker
import com.carpet.satdemo.core.protocol.GsatVerb
import com.carpet.satdemo.core.protocol.ModuleModel
import com.carpet.satdemo.core.protocol.MsgLengthLevel
import com.carpet.satdemo.core.protocol.SfiFailReason
import com.carpet.satdemo.core.protocol.SfiState
import com.carpet.satdemo.core.protocol.hexToBytes
import com.carpet.satdemo.core.protocol.toHex
import java.nio.charset.StandardCharsets

/**
 * **会讲合法语句的假模组**（§8「近期该做的四件事」第 1 条、附录 E.10）。
 *
 * 和 [SimulatedModem] 的分工要分清，两个不是重复实现：
 *  - [SimulatedModem] 是**场景层**替身：直接给"过境窗口内 ACK / 窗口外 NO_ACK"这种业务结论，
 *    服务于 8 帧彩排和 DemoEngine 的脚本，不碰协议；
 *  - 本类是**协议层**替身：收 `$CC…` 吐 `$GS…`，校验和、字段序号、队列上限、
 *    长度等级都按规范执行。[SentenceModem] 走的是和真机一模一样的解析路径。
 *
 * 有了它，到货前就能把「解析 → 状态机 → UI」整条链跑通；到货当天只需要把
 * [InMemoryBytePipe] 换成真串口，业务代码一行不改。
 */
class FakeGsatModule(
    private val pipe: ModemBytePipe,
    /** 这颗模组被开成什么等级 —— 对应 §9 #14 那个还没确认的问题，现在由构造方注入 */
    var lengthLevel: MsgLengthLevel = MsgLengthLevel.L1,
    var service: AccessService = AccessService.SHORT,
    var model: ModuleModel = ModuleModel.WORST_KNOWN,
    /** 〔规 §3.1.4 注1〕"1" 是无效卡，无效卡不选星 */
    var cardId: String = "2025110100002",
    var satelliteAckMode: Int = 1,
    var communicationBits: Int = 0b0000_0011,
    var iccid: String = "89860000000000000001",
    var softwareVersion: String = "GM-TEST-0.1",
    var hardwareVersion: String = "HW-TEST-0.1",
    var serialNumber: String = "SN-TEST-0001",
) {
    private var attached = false
    private var nextMessageId = 1

    /** 模组侧队列（模拟 GM1001 的 500 条 / GM1003 的 50 条两种口径） */
    val queue: MutableList<QueuedMessage> = ArrayList()

    /** 核心网缓存的下行消息，CEQ 一轮只吐一条（〔规 §3.1.10 注2〕） */
    val offlineInbox: ArrayDeque<ByteArray> = ArrayDeque()

    /** App 推给模组的位置。真模组的 DII 会把它回显出来，这里照做 */
    var pushedPosition: GeodeticPosition? = null
        private set

    var pushedEpochSec: Long? = null
        private set

    /** 收到的每一条主机语句，测试可以直接断言"App 到底发了什么" */
    val received: MutableList<GsatSentence> = ArrayList()

    /** 设成 false 用来演"模组不吭声"（拔线、上电未完成） */
    var responsive: Boolean = true

    /** 演"缓存区已满" */
    var refuseNewMessages: Boolean = false

    /**
     * 挂到管道上。必须在 [SentenceModem] 构造之后调用，
     * 因为两边都是"最后一个 onBytes 生效"的语义 —— 挂反了会把对端的回调覆盖掉。
     */
    fun attachToAppSide(): FakeGsatModule {
        if (attached) return this
        attached = true
        pipe.onBytes { bytes ->
            // 半工口下模组也是一行一行读，这里同样用分帧器保证和真机对等
            val text = String(bytes, StandardCharsets.US_ASCII)
            for (line in text.split("\r\n")) {
                if (line.isNotBlank()) handle(GsatSentence.parse(line))
            }
        }
        return this
    }

    /** 主动上报一条（PDI 这种"没问就推上来"的），测试里用来演过境开始 */
    fun report(verb: String, fields: List<String>) = reply(verb, fields)

    private fun handle(result: GsatParseResult) {
        val s = (result as? GsatParseResult.Ok)?.sentence ?: return
        if (s.speaker != GsatSpeaker.HOST) return
        received += s
        if (!responsive) return
        when (s.verb) {
            GsatVerb.GMO -> onGmo(s.fields)
            GsatVerb.TPI -> onTpi(s.fields)
            GsatVerb.SFQ -> onSfq(s.fields)
            GsatVerb.SFP -> onSfp(s.fields)
            GsatVerb.CEQ -> onCeq()
            "MCQ" -> reject(GsatVerb.SFQ, SfiState.STORE_FAILED, SfiFailReason.TOO_LONG)
            else -> Unit
        }
    }

    /**
     * GMO：设定输出/停止输出参数语句（〔规 §3.1.6〕）。
     * 目标语句 1 关 / 2 开 / 3 关全部 / 4 开全部；频度 0 = 单次输出。
     * DII/ICI/CII 只能单次，PDI 可以连续。
     */
    private fun onGmo(fields: List<String>) {
        val target = fields.getOrNull(0)?.trim() ?: return
        val mode = fields.getOrNull(1)?.trim()?.toIntOrNull() ?: return
        val freq = fields.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
        // 模式 3/4 时目标语句字段保留区为空
        if (mode == 3 || mode == 4) return
        if (mode != 2) return
        when (target) {
            "DII" -> reply("DII", diiFields())
            "ICI" -> reply("ICI", iciFields())
            "CII" -> reply("CII", listOf(iccid, "0"))
            // 频度 >0 = 连续输出：这里只在被打开的那一刻吐一次，
            // 周期性由测试自己调 report()，避免假模组里藏一个后台定时器
            "PDI" -> reply("PDI", pdiFields(selected = 301, cnr = 42.5))
            else -> Unit
        }
        if (freq > 0 && target == "PDI") reply("PDI", pdiFields(selected = 301, cnr = 42.5))
    }

    /** TPI 是双向语句：类别 1 设置、3 返回（〔规 §3.1.7〕） */
    private fun onTpi(fields: List<String>) {
        val category = fields.getOrNull(0)?.trim()
        if (category == "3") return
        val epoch = fields.getOrNull(1)?.trim()?.toLongOrNull()
        pushedEpochSec = epoch
        pushedPosition = if (fields.size >= 6) {
            GeodeticPosition.fromTpiFields(fields[2], fields[3], fields[4], fields[5])
        } else {
            null
        }
        // 〔规 注1〕时间字段为 0 表示模组内部已接收到的经纬度数据无效 —— 原样回 0 让上层看见
        val echoEpoch = if (epoch == 0L) 0 else (epoch ?: 0)
        val pos = pushedPosition
        val timeFields = if (pos == null || echoEpoch == 0L) {
            listOf("0.0", "N", "0.0", "E")
        } else {
            listOf(pos.latitude.field, pos.latitude.hemisphere, pos.longitude.field, pos.longitude.hemisphere)
        }
        reply("TPI", listOf("3", echoEpoch.toString()) + timeFields)
    }

    /** SFQ：存储发送申请。等级、队列上限、优先级支持三件事都在这里按型号/等级校验（〔规 §3.1.12〕） */
    private fun onSfq(fields: List<String>) {
        val hex = fields.getOrNull(2)?.trim() ?: ""
        val bytes = runCatching { if (hex.length % 2 == 0) hexToBytes(hex) else null }.getOrNull()
        if (bytes == null) {
            reject(GsatVerb.SFI, SfiState.STORE_FAILED, SfiFailReason.TOO_LONG)
            return
        }
        if (bytes.size > lengthLevel.maxBytes) {
            reject(GsatVerb.SFI, SfiState.STORE_FAILED, SfiFailReason.TOO_LONG)
            return
        }
        if (refuseNewMessages || queue.size >= model.queueCapacity) {
            reject(GsatVerb.SFI, SfiState.STORE_FAILED, SfiFailReason.BUFFER_FULL)
            return
        }
        val id = nextMessageId
        nextMessageId = if (nextMessageId >= 65535) 1 else nextMessageId + 1
        queue += QueuedMessage(id, bytes, priority = fields.getOrNull(1)?.trim()?.toIntOrNull() ?: 0)
        reply(GsatVerb.SFI, listOf(SfiState.STORED.code.toString(), id.toString(), "0"))
    }

    /**
     * SFP：查询/清除待发（〔规 §3.1.14〕）。
     * 例：查询 `$CCSFP,2,1,,`；清除 `$CCSFP,1,2,,`。
     * 回包里字段 3 是剩余数量，范围 0~512。
     */
    private fun onSfp(fields: List<String>) {
        val mode = fields.getOrNull(1)?.trim()
        if (mode == "2") queue.clear()
        reply(GsatVerb.SFP, listOf("1", "1", queue.size.toString(), ""))
    }

    /**
     * CEQ：查询核心网缓存的下行。
     * 〔规 §3.1.10 注2〕**查询一条、接收一条**，且 CES 的剩余条数不含刚吐出的那条。
     */
    private fun onCeq() {
        if (service != AccessService.SHORT) {
            // 长接入不具备离线查询权限，模组会回"权限不足"（〔规 §3.1.10 注1〕）
            reply(GsatVerb.CEI, listOf("000000.00", "2", "0", "0"))
            return
        }
        val msg = offlineInbox.removeFirstOrNull()
        if (msg != null) {
            reply(GsatVerb.MCI, listOf("120000", "0", "", msg.toHex()))
        }
        reply(GsatVerb.CES, listOf("120000", "0", offlineInbox.size.toString()))
    }

    private fun iciFields(): List<String> = listOf(
        cardId,
        "1",
        "01",
        if (service == AccessService.LONG) "1" else "2",
        "0",
        communicationBits.toString(16).padStart(2, '0').uppercase(),
        "0",
        satelliteAckMode.toString(),
        lengthLevel.level.toString(),
    )

    private fun diiFields(): List<String> {
        val pos = pushedPosition
        return listOf(
            "1",
            softwareVersion,
            hardwareVersion,
            serialNumber,
            "",
            "120000.00",
            pos?.latitude?.field ?: "0.0",
            pos?.latitude?.hemisphere ?: "N",
            pos?.longitude?.field ?: "0.0",
            pos?.longitude?.hemisphere ?: "E",
        )
    }

    /** 字段序按〔规 §3.1.5〕：时间, 选择卫星编号, 预留, 500bps忙闲, 2kbps忙闲, 锁定个数, (编号,载噪比)… */
    private fun pdiFields(selected: Int, cnr: Double): List<String> = listOf(
        "120000.00",
        selected.toString(),
        "0",
        "00",
        "0",
        if (cnr > 0) "1" else "0",
        if (cnr > 0) "301" else "0",
        cnr.toString(),
        "0",
        "0",
        "0",
    )

    private fun reply(verb: String, fields: List<String>) {
        pipe.write(GsatSentence.build(GsatSpeaker.UNIT, verb, fields).toByteArray(StandardCharsets.US_ASCII))
    }

    private fun reject(sfiVerb: String, state: SfiState, reason: SfiFailReason) {
        // 存储失败时报文 ID 为 0（〔规 §3.1.13 注1〕）
        reply(GsatVerb.SFI, listOf(state.code.toString(), "0", reason.code.toString()))
    }

    data class QueuedMessage(val id: Int, val payload: ByteArray, val priority: Int)
}
