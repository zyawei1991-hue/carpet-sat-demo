package com.carpet.satdemo.core.protocol

/**
 * 语句 → 业务对象的解码。字段序号全部按〔规 §3.1.x〕的表格，**从 1 开始编号，这里减 1**。
 *
 * 为什么在 core 里自己解一遍（厂商 SDK 里已经有 Parse* 类了）：
 *  1. 「会讲合法语句的假模组」必须在没有 AAR 的 JVM 侧跑起来，否则到货前这段逻辑一行都测不了（§8 近期第一件事）；
 *  2. 现场排障要能独立判断"是模组发歪了还是我们读歪了"，只依赖厂商解析器就没有第三方证人；
 *  3. 真机上仍以厂商 SDK 为准 —— 这里是**镜像实现**，字段口径一旦和厂商不一致，测试例句会先炸。
 *
 * 解码一律**宽松取值、严格校验**：校验和已在 [GsatSentence.parse] 那关把住，
 * 这里遇到字段缺失返回 null 而不是抛，因为缺字段是文档已知会漂移的地方
 * （PDI 的 M 组卫星对数量就不固定）。
 */
object GsatDecoder {

    /**
     * `ICI`：设备 IC 信息。**这是整个产品的能力闸门** —— 报文长度、长短接入、
     * 有没有卫星应答、能不能只选实时星，全在这九个字段的第 4/6/8/9 项里（E.4）。
     *
     * 传进来的 model（型号）无法从语句里读出来 —— 规范没给型号字段，
     * 所以它只能由配置注入（§9 #12 没答之前就是运维填的那个值）。
     */
    fun ici(s: GsatSentence, model: ModuleModel): ModuleCaps? {
        val f = s.fields
        if (f.size < 9) return null
        val cardId = f[0].trim()
        val service = AccessService.fromCode(f[3].trim().toIntOrNull() ?: return null) ?: return null
        val ack = f[7].trim().toIntOrNull() ?: return null
        val level = MsgLengthLevel.fromLevel(f[8].trim().toIntOrNull() ?: return null) ?: return null
        return ModuleCaps(
            cardId = cardId,
            model = model,
            lengthLevel = level,
            service = service,
            communication = CommunicationBits(hexByte(f[5]) ?: 0),
            satelliteAckAvailable = ack == 1,
            networking = hexByte(f[2]) ?: 0,
            moduleSerial = null,
        )
    }

    /** `DII`：通信单元信息 + 它当前认为的位置 */
    fun dii(s: GsatSentence): DiiInfo? {
        val f = s.fields
        if (f.size < 5) return null
        val position = if (f.size >= 10) {
            GeodeticPosition.fromTpiFields(f[6].trim(), f[7].trim(), f[8].trim(), f[9].trim())
        } else {
            null
        }
        return DiiInfo(
            moduleSoftwareVersion = f[1].trim(),
            moduleHardwareVersion = f[2].trim(),
            serialNumber = f[3].trim(),
            userVersion = f[4].trim(),
            reportedPosition = position,
        )
    }

    /**
     * `PDI`：卫星跟踪状态。
     *
     * 〔规 注1/注2〕两条产品级事实，直接决定 UI 能不能说"可以发了"：
     *  - **有卫星 ≠ 可用卫星**（只有权限匹配的星才算可用，载噪比过低的也不算）；
     *  - **卫星忙碌时不可发射**。
     */
    fun pdi(s: GsatSentence): PdiInfo? {
        val f = s.fields
        if (f.size < 6) return null
        val selected = f[1].trim().toIntOrNull() ?: return null
        val locked = f[5].trim().toIntOrNull() ?: return null
        val satellites = ArrayList<SatelliteCnr>(4)
        // 锁定卫星是 (编号, 载噪比) 成对出现的，从第 7 个字段起，最多 4 对
        var i = 6
        while (i + 1 < f.size && satellites.size < locked) {
            val id = f[i].trim().toIntOrNull() ?: break
            val cnr = f[i + 1].trim().toDoubleOrNull() ?: break
            // 0 表示未锁定（〔规 §3.1.5 字段 8/10〕），不是"信号很弱"
            if (cnr > 0.0) satellites += SatelliteCnr(id, cnr)
            i += 2
        }
        return PdiInfo(
            selectedSatelliteId = selected,
            channelBusy500bps = hexByte(f[3]) ?: 0,
            channelBusy2kbps = f[4].trim().toIntOrNull() ?: 0,
            lockedCount = locked,
            lockedSatellites = satellites,
        )
    }

    /** `SFI`：存储/发送状态反馈 */
    fun sfi(s: GsatSentence): SfiInfo? {
        val f = s.fields
        if (f.size < 2) return null
        val state = f[0].trim().toIntOrNull() ?: return null
        val id = f[1].trim().toIntOrNull() ?: return null
        // 失败原因只在状态 3（存储失败）时生效（〔规 §3.1.13 注2〕）
        val reason = f.getOrNull(2)?.trim()?.toIntOrNull()
        return SfiInfo(
            state = SfiState.fromCode(state) ?: return null,
            messageId = id,
            failReason = reason?.let { SfiFailReason.fromCode(it) },
        )
    }

    /**
     * `MCI`：卫星下发的报文。
     *
     * ⚠️ 规范自己的例句是 `$CCMCI,120000,0,,0102A1A2*74` —— 下行语句却写了 `CC` 前缀，
     * 而且那个校验和**只有按 CC 才算得出来**。所以方向判断必须认动词，不能认前缀，
     * 见 [com.carpet.satdemo.core.modem.SentenceFramer]。
     */
    fun mci(s: GsatSentence): MciInfo? {
        val f = s.fields
        if (f.size < 4) return null
        val data = f[3].trim()
        return MciInfo(
            utcTime = f[0].trim(),
            /** 〔规 注1〕收到平台消息时发信方号码为 0 */
            senderCardId = f[1].trim(),
            payload = if (data.isHexData()) hexToBytes(data) else ByteArray(0),
        )
    }

    /** `CEI`：离线消息查询申请反馈。剩余条数在字段 2（〔规 §3.1.9〕） */
    fun cei(s: GsatSentence): CeiInfo? {
        val f = s.fields
        if (f.size < 2) return null
        return CeiInfo(
            utcTime = f[0].trim(),
            flag = f[1].trim(),
            remaining = f.getOrNull(2)?.trim()?.toIntOrNull(),
        )
    }

    /** `CES`：查询结果。剩余条数不含本次随语句一起吐出的那条 MCI（〔规 §3.1.10 注2〕） */
    fun ces(s: GsatSentence): CesInfo? {
        val f = s.fields
        if (f.size < 2) return null
        return CesInfo(utcTime = f[0].trim(), flag = f[1].trim(), remaining = f.getOrNull(2)?.trim()?.toIntOrNull() ?: 0)
    }

    /** 十六进制字节字段（`aa` 形态，如 `00`～`FF`） */
    private fun hexByte(field: String): Int? {
        val t = field.trim().removePrefix("0x").removePrefix("0X")
        if (t.length != 2 || !t.isHexData()) return null
        return t.toInt(16)
    }
}

data class DiiInfo(
    val moduleSoftwareVersion: String,
    val moduleHardwareVersion: String,
    val serialNumber: String,
    val userVersion: String,
    /**
     * 模组报的位置。**注意这不是模组的定位能力**：
     * 〔规 §3.1.2 注〕"本语句中的时间与经纬度均由外部输入获得（通过 TPI 语句）" ——
     * 它是我们喂进去的值被回显出来。把它当 GPS 用会拿到一个陈旧甚至虚构的位置。
     */
    val reportedPosition: GeodeticPosition?,
)

data class SatelliteCnr(val id: Int, val cnrDbHz: Double)

data class PdiInfo(
    val selectedSatelliteId: Int,
    val channelBusy500bps: Int,
    val channelBusy2kbps: Int,
    val lockedCount: Int,
    val lockedSatellites: List<SatelliteCnr>,
) {
    /** 字段 2 为 0 = 无可用卫星，不可发射（〔规 §3.1.5 注1〕） */
    val hasSelectableSatellite: Boolean get() = selectedSatelliteId != 0

    /** 〔规 注2〕卫星忙碌时不可发射 */
    val channelBusy: Boolean get() = channelBusy2kbps != 0 || channelBusy500bps != 0

    val canTransmit: Boolean get() = hasSelectableSatellite && !channelBusy

    /** 展示端"对星中/已锁定"用：取信号最好的一颗 */
    val bestLocked: SatelliteCnr? get() = lockedSatellites.maxByOrNull { it.cnrDbHz }
}

/** `SFI` 报文状态（〔规 §3.1.13 字段 1〕） */
enum class SfiState(val code: Int) {
    STORED(1),
    SENT(2),
    STORE_FAILED(3),
    TRANSMIT_FAILED(4),
    ;

    companion object {
        fun fromCode(code: Int): SfiState? = entries.firstOrNull { it.code == code }
    }
}

/** `SFI` 失败原因。GM1003 的"超时"就是这里第 3 项（E.6） */
enum class SfiFailReason(val code: Int) {
    TOO_LONG(1),
    BUFFER_FULL(2),
    TIMEOUT(3),
    ;

    companion object {
        fun fromCode(code: Int): SfiFailReason? = entries.firstOrNull { it.code == code }
    }
}

data class SfiInfo(val state: SfiState, val messageId: Int, val failReason: SfiFailReason?) {
    /** 〔规 注1〕存储失败时报文 ID 为 0；ID > 65535 回绕到 1 */
    val success: Boolean get() = state == SfiState.SENT

    /**
     * 是不是"被模组主动丢弃"。GM1003 默认 4 分钟没发出去就走到这里，
     * 而 App 侧的持久队列必须把它当成**未送达**重新入队，否则 SOS 会静默消失。
     */
    val droppedByTimeout: Boolean get() = state == SfiState.TRANSMIT_FAILED && failReason == SfiFailReason.TIMEOUT
}

data class MciInfo(val utcTime: String, val senderCardId: String, val payload: ByteArray) {
    /** 〔规 注1〕发信方号码为 0 = 平台消息，不是某台终端 */
    val fromPlatform: Boolean get() = senderCardId.trim() == "0"
}

data class CeiInfo(val utcTime: String, val flag: String, val remaining: Int?)
data class CesInfo(val utcTime: String, val flag: String, val remaining: Int)
