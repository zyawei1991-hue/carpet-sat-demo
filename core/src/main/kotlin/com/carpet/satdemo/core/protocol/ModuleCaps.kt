package com.carpet.satdemo.core.protocol

/**
 * 模组能力档：**全部来自 `ICI` 语句和模组型号，运行时注入，不许写死。**
 *
 * 这个文件存在的理由是附录 E.4/E.6 那两条：厂商文档里同一件事有两种数值
 * （报文长度 90~1900 B 五个等级、队列 500 条 vs 50 条），选错哪一档都不是改一个常数的事 ——
 * 报文格式和补发策略都会跟着变。所以把它们做成"注入的配置 + 明确的坏档默认值"，
 * 等时空道宇回答 §9 #12/#14 之后只改取值，不改结构。
 */

/**
 * 模组型号。〔规 §3.1.12 注 / §3.1.13 注〕只给了这两颗，**"GT1005" 在三份文档里一次都没出现**
 * （§9 #12，当前最贵的待确认问题）。
 */
enum class ModuleModel(
    /** SFQ 待发队列上限 */
    val queueCapacity: Int,
    /** 掉电后队列还在不在 */
    val persistsAcrossPowerLoss: Boolean,
    /**
     * 入队后多久没发出去就被模组**主动丢弃**。
     * GM1001 **没有超时机制**（null）；GM1003 默认 4 分钟。
     */
    val dropUnsentAfterMs: Long?,
    /** SFQ 的优先级字段：只有 GM1003 支持，GM1001 固定填 0 */
    val supportsPriority: Boolean,
) {
    /** FLASH：500 条 / 掉电保存 / 无超时 */
    GM1001(500, true, null, false),

    /** RAM：50 条 / 掉电丢失 / 默认 4 分钟未发出即丢弃 */
    GM1003(50, false, 4 * 60_000L, true),
    ;

    companion object {
        /** 没确认之前的默认档 —— 按最坏的设计，别按好的 */
        val WORST_KNOWN = GM1003
    }
}

/** `ICI` 字段 4 服务类型。决定**下行消息怎么收**，是两套状态机（E.10 #3） */
enum class AccessService {
    /** 长接入：模组自动收下行，App 只等 MCI */
    LONG,

    /** 短接入：**必须主动 CEQ 查询**，且"查询一条、接收一条" */
    SHORT,
    ;

    companion object {
        fun fromCode(code: Int): AccessService? = when (code) {
            1 -> LONG
            2 -> SHORT
            else -> null
        }
    }
}

/**
 * `ICI` 字段 9「通信单元发送消息长度」的等级。
 * 〔规 §3.1.4 注7〕：此字段长度限制对应通信申请语句中通信数据字段的**字节**长度。
 * 但厂商传的是 hex 字符串，这个数按字节还是字符算没答（§9 #13）——
 * 所以 [maxBytes] 只是原始数值，**要判断发不发得出去请用 [MessageBudget]**。
 */
enum class MsgLengthLevel(val level: Int, val maxBytes: Int) {
    L1(1, 90),
    L2(2, 200),
    L3(3, 230),
    L4(4, 430),
    L5(5, 1900),
    ;

    companion object {
        fun fromLevel(level: Int): MsgLengthLevel? = entries.firstOrNull { it.level == level }

        /** 没确认之前的默认档 */
        val WORST_KNOWN = L1
    }
}

/** `ICI` 字段 6 通信方式的位掩码（Bit0 实时回传 / Bit1 存储回传 / Bit2 实时分发 / Bit3 存储分发） */
@JvmInline
value class CommunicationBits(val mask: Int) {
    val realtimeUp: Boolean get() = mask bit 0
    val storedUp: Boolean get() = mask bit 1
    val realtimeDown: Boolean get() = mask bit 2
    val storedDown: Boolean get() = mask bit 3

    /**
     * 〔规 §3.1.4 注5〕只具备"实时回传"权限时，模组**只会选择有地球站跟踪或星间链路的卫星**。
     * 这类卫星过境窗口比存储转发式的更挑地方 —— 演示现场"没星"很可能是这个而不是天线问题。
     */
    val realtimeOnlyUp: Boolean get() = realtimeUp && !storedUp

    val storedDownOnly: Boolean get() = storedDown && !realtimeDown

    companion object {
        const val NONE = 0
    }
}

private infix fun Int.bit(n: Int) = (this shr n) and 1 == 1

/**
 * 一次上电会话确定的能力集。App 拿到它才能决定：报文预算、队列策略、下行怎么收、能不能要 ACK。
 */
data class ModuleCaps(
    /** `ICI` 字段 1 卫星号码（物联卡号）。"1" 是无效卡 */
    val cardId: String,
    val model: ModuleModel,
    val lengthLevel: MsgLengthLevel,
    val service: AccessService,
    val communication: CommunicationBits,
    /** `ICI` 字段 8 卫星应答模式：0 无应答 1 有应答 */
    val satelliteAckAvailable: Boolean,
    /** 组网方式位图，目前只有 Bit0「通信单元-平台」有定义 */
    val networking: Int,
    val moduleSerial: String?,
) {
    val isInvalidCard: Boolean get() = cardId == INVALID_CARD_ID

    /**
     * 单条报文的数据字段上限（字节）。
     *
     * ⚠️ 这是 `ICI` 字段的**原始数值**。「这个数到底按字节还是按 hex 字符算」文档没定死（§9 #13），
     * 所以发不发得出去**不要直接比它**，走 [budget] —— 那里有最坏口径。
     */
    val maxMessageBytes: Int get() = lengthLevel.maxBytes

    /** 运行时注入的报文预算：等级 + 单位口径 → 一帧/一片能用多少字节 */
    fun budget(unit: LengthUnit = LengthUnit.WORST_KNOWN): MessageBudget = MessageBudget(lengthLevel, unit)

    /**
     * 能不能向用户承诺"星上确认"。
     * 注意即使 true，**卫星 ACK 也只代表卫星收到，不代表核心网收到**（§6.1 红线一）。
     */
    val canExpectSatelliteAck: Boolean get() = satelliteAckAvailable

    companion object {
        /** 〔规 §3.1.4 注1〕1 为无效卡，无效卡的通信单元不进行选星操作 */
        const val INVALID_CARD_ID = "1"

        /**
         * 未确认时的兜底档：**GM1003 + 等级 1 + 短接入 + 无应答**。
         * 每一维都取文档里最不利的那一档，宁可少承诺。
         */
        fun worstKnown(cardId: String = INVALID_CARD_ID) = ModuleCaps(
            cardId = cardId,
            model = ModuleModel.WORST_KNOWN,
            lengthLevel = MsgLengthLevel.WORST_KNOWN,
            service = AccessService.SHORT,
            communication = CommunicationBits(CommunicationBits.NONE),
            satelliteAckAvailable = false,
            networking = 0,
            moduleSerial = null,
        )
    }
}
