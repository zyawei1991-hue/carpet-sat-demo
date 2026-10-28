package com.carpet.satdemo.core.modem

import com.carpet.satdemo.core.protocol.AccessService
import com.carpet.satdemo.core.protocol.DiiInfo
import com.carpet.satdemo.core.protocol.GeodeticPosition
import com.carpet.satdemo.core.protocol.GsatDecoder
import com.carpet.satdemo.core.protocol.GsatSentence
import com.carpet.satdemo.core.protocol.GsatSpeaker
import com.carpet.satdemo.core.protocol.ModuleCaps
import com.carpet.satdemo.core.protocol.PdiInfo
import com.carpet.satdemo.core.protocol.ModuleModel
import com.carpet.satdemo.core.protocol.SfiState
import com.carpet.satdemo.core.protocol.hexToBytes
import com.carpet.satdemo.core.protocol.toHex
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 语句级模组客户端：把 [SatelliteModem] 的语义翻译成 `$CC…` 语句、把 `$GS…` 回话翻回来。
 *
 * 定位说明（重要，别误解这份实现的角色）：
 *  - **真机上首选厂商 SDK**。SDK 的 `ModuleDataManager` 已经把这件事做完了，
 *    我们没理由在 Android 侧用这一份替代它（附录 E.1、E.10 #1）。
 *  - 这一份活在 **JVM 侧**：CI 单测、到货前的全链路预演、以及"和模组对练"时判断
 *    到底是模组发歪了我们读歪了 —— 需要一个独立于厂商解析器的证人。
 *
 * 与之配对的是 [FakeGsatModule]（会讲合法语句的假模组），两者用 [InMemoryBytePipe] 对接，
 * 所以**跑的是和真机完全相同的一条解析路径**，不是"我以为的协议"。
 */
class SentenceModem(
    private val pipe: ModemBytePipe,
    /** 型号只能外部注入：规范里没有型号字段，GT1005 对应哪颗还没确认（§9 #12） */
    private val model: ModuleModel = ModuleModel.WORST_KNOWN,
    private val clock: MonoClock = MonoClock { System.currentTimeMillis() },
    /**
     * 本地语句（读信息、TPI、查队列）的应答超时，不该按分钟等。
     *
     * 这里**没有**"等星上 ACK"的那个超时：等发射结果要先能读 MCQ / 完整的 SFI 发射链路，
     * 而 MCQ 的线格式不在规范正文里（§9 #13）。等厂商答复后再加，别留一个没人用的参数。
     */
    private val cmdTimeoutMs: Long = 3_000,
) : SatelliteModem {

    /**
     * 当前在等哪条语句的应答。非空时，动词匹配的语句进这里；
     * 其余（包括没人问就推上来的 PDI/MCI/SFI）当场 [route]，不留到下一次问答。
     *
     * 之所以不用"全进一个队列、等的时候再捞"：半工口下 1 秒一条的 PDI 会把队列灌满，
     * 下一次 await 就要先吃掉一堆过期上报才能拿到真正的应答，超时会算歪。
     */
    private var awaiting: Awaiting? = null

    private class Awaiting(val verb: String, val slot: Channel<GsatSentence> = Channel(Channel.UNLIMITED))

    private val io = Mutex()

    /** 坏帧计数。半工口读串一位是现场最常见故障，必须能被看到 */
    var badFrames: Int = 0
        private set

    private val downlinkHandlers = ArrayList<(String) -> Unit>()
    private val lockHandlers = ArrayList<(SatLock?) -> Unit>()
    private val traceHandlers = ArrayList<(String) -> Unit>()

    /** 上电会话读到的能力档。没读完为 null，此时**不允许**任何发送 */
    override var caps: ModuleCaps? = null
        private set

    /**
     * 上电会话是否走完。走完之后才允许往模组的发送方向写东西（E.10 #2）。
     *
     * 只读查询（查队列、读 DII）不受这个门限制：现场排障要在"会话起不来"的时候
     * 也能看到模组状态，那时候恰恰最需要这些查询。
     */
    var sessionReady: Boolean = false
        private set

    private var lastPdi: PdiInfo? = null
    private var lastLock: SatLock? = null

    /** 最近一次成功推送 TPI 的时刻，用于陈旧度判断（E.5） */
    var lastTpiAtMs: Long? = null
        private set

    private val framer = SentenceFramer(
        onSentence = { s ->
            val a = awaiting
            if (a != null && s.verb == a.verb) a.slot.trySend(s) else route(s)
        },
        onBadFrame = {
            badFrames++
            trace("坏帧(${it.error}): ${it.raw}")
        },
    )

    init {
        pipe.onBytes { framer.push(it) }
        pipe.onTrace { line -> traceHandlers.forEach { it(line) } }
    }

    override val available: Boolean get() = pipe.isOpen

    // ---- 上电会话（E.10 #2）----

    /**
     * 把模组从"连着"带到"能发"。顺序是厂商文档定的，不能并行也不能猜：
     *
     * ```
     * DII → ICI → CII 都要靠 GMO 单次申请，且 DII/ICI/CII 不可连续输出（〔规 §3.1.2/§3.1.3/§3.1.4〕）
     * 拿到 ICI 才知道报文预算和长短接入 —— 在它之前发任何东西都是拿等级赌博
     * TPI 必须最后发：模组复位/掉电后位置即丢，且下行接收依赖它（〔规 §3.1.7 注〕）
     * PDI 最后开连续输出，上电默认 1 秒一次（〔规 §3.1.5 注〕）
     * ```
     */
    suspend fun bringUp(): BringUpOutcome {
        if (!pipe.isOpen) return BringUpOutcome.PipeClosed
        // 复位残留：上一轮没读完的半行不能带进新一轮
        framer.reset()
        sessionReady = false
        val d = requestOnce("DII", "DII") { GsatDecoder.dii(it) } ?: return BringUpOutcome.NoReply("DII")
        val i = requestOnce("ICI", "ICI") { GsatDecoder.ici(it, model) } ?: return BringUpOutcome.NoReply("ICI")
        // 无效卡：模组根本不选星，后面发什么都白费（〔规 §3.1.4 注1〕）
        if (i.isInvalidCard) return BringUpOutcome.InvalidCard(i.cardId)
        caps = i
        // 位置没准备好就不要开会话：TPI 是"必须配置项"，缺了它下行收不到
        val first = pendingTpi ?: return BringUpOutcome.NeedsLocation
        if (!pushTpi(first)) return BringUpOutcome.NoReply("TPI")
        // 打开 PDI 连续输出（模式 2 开指定语句，频度 1 秒）
        send("GMO", listOf("PDI", "2", "1"))
        sessionReady = true
        return BringUpOutcome.Ready(i, d)
    }

    sealed interface BringUpOutcome {
        data class Ready(val caps: ModuleCaps, val dii: DiiInfo?) : BringUpOutcome
        data class NoReply(val verb: String) : BringUpOutcome
        data class InvalidCard(val cardId: String) : BringUpOutcome

        /** 还没拿到任何位置源。TPI 是必配项，缺了它不能宣称链路可用 */
        data object NeedsLocation : BringUpOutcome
        data object PipeClosed : BringUpOutcome
    }

    // ---- SatelliteModem ----

    override val supportsImmediateSend: Boolean get() = false

    /**
     * ⚠️ **MCQ 的语句格式不在规范正文里**。28 页《通用数据接口规范 V1.0》里
     * 一次都没出现 MCQ / FBI，它只在《基础通信SDK说明》和 AAR 的类名里存在
     * （§9 #13 因此升级成"整个 MCQ 线格式都没文档"）。
     *
     * 所以 JVM 侧**拒绝实现**而不是猜一个格式：猜出来的即时发送在真机上会以
     * 校验和错误或被模组静默丢弃的形态出现，那种 bug 到外场才暴露。
     * 真机走厂商 SDK（它会自己组 MCQ），或者走 [enqueueSfq] 让模组排队。
     */
    override suspend fun sendMcq(hex: String): SatResult = throw UnsupportedOperationException(MCQ_WIRE_FORMAT_UNDOCUMENTED)

    /**
     * 入队等过境自动发（〔规 §3.1.12〕，模组侧 FIFO 自动完成发送）。
     * 返回 false = 没进队列，App 侧持久队列必须自己留着补发。
     */
    override suspend fun enqueueSfq(hex: String): Boolean {
        val c = caps ?: return false
        // 会话没就绪就连"入队"都不发：位置没推进去时模组存了也发不出去，
        // 还会占掉一个队列位（GM1003 只有 50 个），App 侧那份补发反倒被架空
        if (!sessionReady) return false
        val payload = runCatching { hexToBytesSafe(hex) }.getOrNull() ?: return false
        // 预算在这里拦：等级不是常量，是上电读到的（E.4）
        if (payload.size > c.maxMessageBytes) return false
        val stored = io.withLock {
            // 〔规 §3.1.12 注1〕收信方填 0 = 发给平台；注2 优先级仅 GM1003 支持，GM1001 固定 0。
            // 目前所有报文都是普通优先级，所以两种型号都发 0 —— 需要抢占时再改这里。
            request("SFI", cmdTimeoutMs, { send("SFQ", listOf(RECEIVER_PLATFORM, "0", hex)) }) { s -> GsatDecoder.sfi(s) }
        }
        return stored?.state == SfiState.STORED
    }

    /** 队列位还有没有。SFP 查询剩余待发数量，范围 0~512（〔规 §3.1.14〕） */
    suspend fun queryQueueRemaining(): Int? {
        val c = caps ?: return null
        return io.withLock {
            // 模式 2 = 打开指定语句，频度留空即单次（〔规 §3.1.6 注1 / §3.1.14 例〕）
            request("SFP", cmdTimeoutMs, { send("SFP", listOf("2", "1", "", "")) }) { it.fields.getOrNull(2)?.trim()?.toIntOrNull() }
        }?.coerceAtMost(c.model.queueCapacity)
    }

    /**
     * 手机位置 → 模组（E.5）。
     *
     * 注意 **SDK 的 `updateLocation(经度, 纬度)` 参数顺序和语句里的字段顺序（纬在前）相反**，
     * 这里用带名字的类型走一遍，接口层就不可能传反。
     */
    override suspend fun updateLocation(lon: Double, lat: Double) {
        val p = GeodeticPosition.of(lat, lon)
        pendingTpi = p
        pushTpi(p)
    }

    private var pendingTpi: GeodeticPosition? = null

    private suspend fun pushTpi(p: GeodeticPosition): Boolean {
        val epochSec = clock.nowMs() / 1000
        val echoed = io.withLock {
            // 类别 1 = 设置（3 = 返回）；〔规 §3.1.7〕
            request("TPI", cmdTimeoutMs, { send("TPI", listOf("1", epochSec.toString()) + p.tpiFields) }) {
                if (it.fields.firstOrNull()?.trim() == "3") Unit else null
            }
        }
        val ok = echoed != null
        if (ok) lastTpiAtMs = clock.nowMs()
        return ok
    }

    /** TPI 是否已经过期到不能再信（模组复位、掉电、或超时无更新都会走到这里） */
    fun tpiStale(maxAgeMs: Long = TPI_STALE_DEFAULT_MS): Boolean {
        val last = lastTpiAtMs ?: return true
        return clock.nowMs() - last > maxAgeMs
    }

    /**
     * ⚠️ 这里读到的位置**是我们自己推给模组的 TPI 的回显**，不是模组的定位能力
     * （〔规 §3.1.2 注〕"本语句中的时间与经纬度均由外部输入获得"）。
     * 所以每次都现读一次 DII，不用上电时那份缓存 —— 缓存那份是在 TPI 之前读的，
     * 里面必然还没有位置。拿它当 GPS 用会读到陈旧甚至空值。
     */
    override suspend fun readFix(): Fix? {
        val d = requestOnce("DII", "DII") { GsatDecoder.dii(it) } ?: return null
        val pos = d.reportedPosition ?: return null
        val age = lastTpiAtMs?.let { (clock.nowMs() - it) / 1000.0 }
        return Fix(pos.longitude.degrees, pos.latitude.degrees, hdop = null, ageSec = age)
    }

    override fun onDownlink(handler: (String) -> Unit) {
        downlinkHandlers += handler
    }

    override fun onSatelliteLock(handler: (SatLock?) -> Unit) {
        lockHandlers += handler
        handler(lastLock)
    }

    /**
     * 短接入必须主动查询才收得到下行（〔规 §3.1.8 注 / §3.1.10 注2〕）；长接入直接返回，不用查。
     *
     * 「查询一条、接收一条」：每轮 CEQ 之后模组会输出一条 MCI + 一条 CES，
     * CES 的剩余条数**不含刚给出的这条**，所以按它循环到 0 才算刷干净。
     * 消息本体走 [onDownlink]，这里只管把队列抽干。
     */
    suspend fun drainOfflineMessages(maxRounds: Int = 64): Boolean {
        val c = caps ?: return false
        if (!sessionReady) return false
        if (c.service == AccessService.LONG) return true
        repeat(maxRounds) {
            val remaining = io.withLock {
                request("CES", cmdTimeoutMs, { send("CEQ", emptyList()) }) { s -> GsatDecoder.ces(s)?.remaining }
            } ?: return false
            if (remaining == 0) return true
        }
        return false
    }

    override suspend fun secondsToNextPass(): Long? = null

    override fun close() {
        pipe.close()
    }

    // ---- 内部 ----

    /** 只允许对"能被 GMO 开关的输出语句"用这个名字 */
    private fun send(verb: String, fields: List<String>) {
        pipe.write(GsatSentence.build(GsatSpeaker.HOST, verb, fields).toByteArray(Charsets.US_ASCII))
        trace("TX $verb")
    }

    private suspend fun <T> requestOnce(verb: String, gmoTarget: String, map: (GsatSentence) -> T?): T? =
        io.withLock {
            // DII/ICI/CII 只能单次读取：模式 2 打开指定语句，频度 0 = 单次（〔规 §3.1.6〕）
            request(verb, cmdTimeoutMs, { send("GMO", listOf(gmoTarget, "2", "0")) }, map)
        }

    /**
     * 问一条、等一条。
     *
     * **先挂上应答槽、再写串口**：[InMemoryBytePipe] 是同步的，模组（或真机的固件）
     * 有可能在 write 返回之前就把回话送进分帧器。写完之后才挂槽，应答会被当成
     * "没人问的上报"分掉，这一问一答就永远配不上对。
     */
    private suspend fun <T> request(verb: String, timeoutMs: Long, write: () -> Unit, map: (GsatSentence) -> T?): T? {
        val a = Awaiting(verb)
        val prev = awaiting
        awaiting = a
        try {
            write()
            val deadline = clock.nowMs() + timeoutMs
            while (true) {
                val left = deadline - clock.nowMs()
                if (left <= 0) return null
                val s = withTimeoutOrNull(left) { a.slot.receive() } ?: return null
                map(s)?.let { return it }
                // 同动词但解不出来的（字段漂移），不该占着这一次机会，继续等下一条
                route(s)
            }
        } finally {
            awaiting = prev
            a.slot.close()
        }
    }

    private fun route(s: GsatSentence) {
        when (s.verb) {
            "PDI" -> {
                val p = GsatDecoder.pdi(s) ?: return
                lastPdi = p
                val lock = p.bestLocked?.let { SatLock(it.id.toString(), it.cnrDbHz) }
                if (lock != lastLock) {
                    lastLock = lock
                    lockHandlers.forEach { it(lock) }
                }
            }
            "MCI" -> {
                val m = GsatDecoder.mci(s) ?: return
                // 〔规 §3.1.11 注2〕数据字段是十六进制的 ASCII 码，原样交给上层解
                downlinkHandlers.forEach { it(m.payload.toHex()) }
            }
            "SFI" -> {
                val f = GsatDecoder.sfi(s) ?: return
                if (f.droppedByTimeout) trace("模组超时丢弃: 报文ID=${f.messageId}")
            }
            else -> Unit
        }
    }

    /** 当前跟踪状态快照，给展示端画"对星中/已锁定/信道忙" */
    fun lastTrack(): PdiInfo? = lastPdi

    fun onTrace(handler: (String) -> Unit) {
        traceHandlers += handler
    }

    private fun trace(line: String) = traceHandlers.forEach { it(line) }

    private fun hexToBytesSafe(hex: String): ByteArray =
        if (hex.length % 2 == 0 && hex.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }) {
            hexToBytes(hex)
        } else {
            throw IllegalArgumentException("hex 字段非法: $hex")
        }

    companion object {
        /** 〔规 §3.1.12 注1〕向平台发送消息时，收信方号码填 0 */
        const val RECEIVER_PLATFORM = "0"

        /** TPI 建议 1~10 分钟更新一次，超过 15 分钟就认为模组那边不可信（〔规 §3.1.7 注〕） */
        const val TPI_STALE_DEFAULT_MS = 15 * 60_000L

        /** 这条常量的存在本身就是待办：见 §9 #13 */
        const val MCQ_WIRE_FORMAT_UNDOCUMENTED =
            "MCQ 的线格式在《通用数据接口规范 V1.0》里没有定义（只在《基础通信SDK说明》和 AAR 类名中出现）。" +
                "真机请走厂商 SDK 的 sendMsgMCQ，或改用 SFQ 排队；JVM 侧不猜格式。"
    }
}

