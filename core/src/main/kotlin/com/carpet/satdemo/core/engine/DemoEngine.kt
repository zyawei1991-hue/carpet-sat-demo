package com.carpet.satdemo.core.engine

import com.carpet.satdemo.core.ephemeris.EphemerisTracker
import com.carpet.satdemo.core.ephemeris.EphState
import com.carpet.satdemo.core.link.CellStatus
import com.carpet.satdemo.core.link.LinkEvent
import com.carpet.satdemo.core.link.LinkPhase
import com.carpet.satdemo.core.link.LinkPolicy
import com.carpet.satdemo.core.link.LinkSignal
import com.carpet.satdemo.core.link.LinkStateMachine
import com.carpet.satdemo.core.modem.SatFailReason
import com.carpet.satdemo.core.modem.SatLock
import com.carpet.satdemo.core.modem.SatPath
import com.carpet.satdemo.core.modem.SatResult
import com.carpet.satdemo.core.modem.SatSend
import com.carpet.satdemo.core.modem.SatelliteModem
import com.carpet.satdemo.core.modem.SosCoordinator
import com.carpet.satdemo.core.modem.SosProgress
import com.carpet.satdemo.core.modem.TpiTracker
import com.carpet.satdemo.core.protocol.EventKind
import com.carpet.satdemo.core.protocol.EventPayload
import com.carpet.satdemo.core.protocol.FenceState
import com.carpet.satdemo.core.protocol.GeodeticPosition
import com.carpet.satdemo.core.protocol.GnssSource
import com.carpet.satdemo.core.protocol.GnssState
import com.carpet.satdemo.core.protocol.LinkPrimary
import com.carpet.satdemo.core.protocol.LinkState
import com.carpet.satdemo.core.protocol.ModuleCaps
import com.carpet.satdemo.core.protocol.MsgType
import com.carpet.satdemo.core.protocol.AccessService
import com.carpet.satdemo.core.protocol.MessageBudget
import com.carpet.satdemo.core.protocol.ModuleModel
import com.carpet.satdemo.core.protocol.MsgLengthLevel
import com.carpet.satdemo.core.protocol.SatPhase
import com.carpet.satdemo.core.protocol.SatState
import com.carpet.satdemo.core.protocol.ScreenMessage
import com.carpet.satdemo.core.protocol.SentinelState
import com.carpet.satdemo.core.protocol.SosFrame
import com.carpet.satdemo.core.protocol.SosFrameEncoder
import com.carpet.satdemo.core.protocol.SosLevel
import com.carpet.satdemo.core.protocol.SosState
import com.carpet.satdemo.core.protocol.StatePayload
import com.carpet.satdemo.core.protocol.toHex
import com.carpet.satdemo.core.queue.Outbox
import com.carpet.satdemo.core.queue.OutboxClass
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 把链路状态机、卫星模块、SOS 流程拧成一股的中间层。
 *
 * 刻意不碰 Android API，也不碰 socket —— App 只负责把传感器读数喂进来、
 * 把 [snapshot] 推给展示端。这样整条业务链在 JVM 单测里就能跑完，
 * 不用等真机、不用模拟器、不用卫星。
 */
class DemoEngine(
    val modem: SatelliteModem,
    ownerId: Long,
    val policy: LinkPolicy = LinkPolicy(),
    /**
     * TPI 周期任务（E.10 #5）。位置是**喂给**模组的，模组那边过期就收不到下行，
     * 所以这件事得有专门的持有者；不给就是"这条链路没有 TPI 概念"（场景层替身、单测）。
     */
    private val tpi: TpiTracker? = null,
    /**
     * 星历（E.10 #7）。厂商给的是**窗口 + 进出方位角 + 最大仰角**，
     * 比模组那个"还有 N 秒"信息量大，所以它一可用就以它为准。
     */
    private val ephemeris: EphemerisTracker? = null,
) {
    private val machine = LinkStateMachine(policy)
    private val encoder = SosFrameEncoder(ownerId)

    var phase: LinkPhase = machine.phase
        private set

    /**
     * 离线期间攒着等补发的报文（E.10 #8）。容量、超时、掉电要不要重推都来自**模组型号**，
     * 不写死 —— 见 [Outbox]。RECOVER 态由 App 刷出去。
     */
    val outbox = Outbox(modem.caps)

    /** 待补发的队首顺序（屏上那个数字是它的 size）。已交给模组盯梢的不算在这里 */
    val pending: List<String> get() = outbox.hexes

    /** 平台下发的最后一条原始报文，SOS 回执靠它 */
    var lastDownlinkHex: String? = null
        private set

    /** 下一条卫星过顶还有多少秒。星历不可用时为 null，展示端要显示「--」而不是 0 */
    var nextPassInS: Long? = null

    /** SOS 全过程的进度，App 拿来写日志、发协议事件 */
    var onSosProgress: (SosProgress) -> Unit = {}

    private val sos = SosCoordinator(
        modem = modem,
        encoder = encoder,
        policy = policy,
        progress = { onSosProgressHandler(it) },
    )

    private fun onSosProgressHandler(p: SosProgress) {
        when (p) {
            is SosProgress.SatSending -> {
                sosAttempts = p.attempt
                lastSosHex = p.frameHex
            }
            is SosProgress.Exhausted -> sosNextPassInS = p.nextPassInS
            is SosProgress.SatResultReady -> lastSosSend = p.send
            else -> Unit
        }
        onSosProgress(p)
    }

    private var sosNextPassInS: Long? = null
    private var lastSosSend = SatSend(SatResult.FAIL, SatPath.IMMEDIATE, SatFailReason.NO_REPLY)
    private var lastSosHex = ""

    /** SOS 被写锁/没电挡在门外，整条还留在 App 队列里。等模组一空就要立刻冲出去，不等 RECOVER */
    private var sosParked = false
    private var fix: GnssState? = null
    private var phoneBatteryPct = 100
    private var fence = FenceState(active = false, inside = true)
    private var sentinel = SentinelState()
    private var demoMode = false
    private var sosLevel = SosLevel.normal
    private var sosSinceTs: Long? = null
    private var sosAttempts = 0
    private var lock: SatLock? = null

    init {
        modem.onSatelliteLock { lock = it }
        modem.onDownlink { lastDownlinkHex = it }
    }

    fun onCellStatus(status: CellStatus, nowMs: Long): List<ScreenMessage> =
        emit(machine.onSignal(LinkSignal.Cell(status, nowMs)), nowMs)

    fun onTick(nowMs: Long): List<ScreenMessage> {
        adoptCaps()
        reclaimDropped()
        noteModulePowerLoss(nowMs)
        reparkEmergency(nowMs)
        // 「平台已确认」是给人看一眼就收回去的回执，长期压着会把轨迹图挡住；
        // 未确认那条不能收 —— 那是催人在开阔处等下次过境的红字
        val since = sosSinceTs
        if (sosLevel == SosLevel.acked && since != null && nowMs - since >= ACKED_BANNER_HOLD_MS) {
            sosLevel = SosLevel.normal
            sosSinceTs = null
            sosAttempts = 0
        }
        return emit(machine.onSignal(LinkSignal.Tick(nowMs)), nowMs)
    }

    /** 上电会话读到 ICI 之前只能按最坏档排队，读到之后立刻换成真档（E.10 #8） */
    private fun adoptCaps() {
        val caps = modem.caps ?: return
        if (adoptedCaps === caps) return
        adoptedCaps = caps
        outbox.adopt(caps)
    }

    private var adoptedCaps: ModuleCaps? = null
    private var lastModuleAvailable = true

    /**
     * 模组掉电/复位这一路必须让队列知道，而且**处理方向按型号相反**（〔规 §3.1.12/§3.1.13 注〕）：
     * GM1003 队列在 RAM 里，掉电即空，交出去的必须整体重推；
     * GM1001 存 FLASH，那些报文还在里面躺着，重推就是把同一条 SOS 又发一遍。
     * 判定全在 [Outbox.onModulePowerLoss] 里按 [QueuePolicy] 走，这里只负责发现这件事发生了。
     */
    private fun noteModulePowerLoss(nowMs: Long) {
        val up = modem.available
        if (lastModuleAvailable && !up) outbox.onModulePowerLoss()
        lastModuleAvailable = up
        outbox.sweep(nowMs)
    }

    fun onSatAck(nowMs: Long, ok: Boolean): List<ScreenMessage> =
        emit(machine.onSignal(LinkSignal.SatAck(nowMs, ok)), nowMs)

    /** 状态机这一轮要求发探测包，等 [drainProbe] 把它真的发出去 */
    private var probeRequested = false

    /**
     * 把攒下的探测请求真的走一趟模块。探测包就是一条心跳，
     * 收到 ACK 才说明卫星可用，状态机据此从 PROBE 切 SAT。
     * 调用方（App / 演示驱动器）每秒 tick 后调一次，返回值直接推给展示端。
     */
    suspend fun drainProbe(nowMs: Long): List<ScreenMessage> {
        if (!probeRequested) return emptyList()
        probeRequested = false
        val f = fix
        val hex = encoder.encode(
            SosFrame.MSG_HEARTBEAT, f?.lat ?: 0.0, f?.lon ?: 0.0,
            whenSec = nowMs / 1000, batteryPct = phoneBatteryPct,
            // 探测包只在蜂窝已经判死的时候发，所以不带 DUAL 标志
            flags = frameFlags(cellOk = false),
        ).toHex()
        val ok = runCatching { modem.sendMcq(hex) }.getOrDefault(SatSend.failed(SatPath.IMMEDIATE, SatFailReason.NO_REPLY)).result == SatResult.COMPLETE
        return onSatAck(nowMs, ok)
    }

    /** 手机 GPS 或 GT1005 GNSS 出了个定位 */
    fun onFix(lat: Double, lon: Double, source: GnssSource, hdop: Double?, fixAgeSec: Double?) {
        fix = GnssState(lat, lon, altM = null, hdop = hdop, source = source, fixAgeS = fixAgeSec)
        // 位置一有新值就交给 TPI 任务：模组那边只认我们推过去的这一份（E.5）
        tpi?.onPosition(GeodeticPosition.of(lat, lon))
    }

    fun onPhoneBattery(pct: Int) {
        phoneBatteryPct = pct.coerceIn(0, 100)
    }

    fun onDemoModeChanged(on: Boolean) {
        demoMode = on
    }

    fun armSentinel(armed: Boolean) {
        sentinel = sentinel.copy(armed = armed)
    }

    /** 越界距离由调用方算好传进来，core 不引地理库 */
    fun onFenceDistance(distM: Double) {
        fence = fence.copy(inside = distM >= 0, distM = distM)
    }

    fun setFence(active: Boolean, name: String?) {
        fence = fence.copy(active = active, inside = true, name = name, distM = null)
    }

    fun fenceBreached(): Boolean = fence.active && !fence.inside

    /**
     * 心跳。CELL 态由 App 自己走蜂窝上报，这里只处理卫星那一路；
     * 模块没收下就进 [pending]，别丢。
     */
    suspend fun heartbeat(nowSec: Long, cellOk: Boolean): Boolean {
        val f = fix ?: return false
        val hex = encoder.encode(
            SosFrame.MSG_HEARTBEAT, f.lat, f.lon, nowSec, phoneBatteryPct, flags = frameFlags(cellOk),
        ).toHex()
        if (phase == LinkPhase.CELL) return true
        val send = modem.enqueueSfq(hex)
        if (send.accepted) return true
        outbox.offer(hex, OutboxClass.Telemetry, nowMs = nowSec * 1000)
        return false
    }

    /**
     * RECOVER 态把离线期间攒下的补发出去。一次 tick 只刷 [DRAIN_BURST] 条 ——
     * 真机上一条 SFQ 要过一次 BLE 写，一秒刷干净反而是假的，而且展示端正好想看
     * 那个数字往下掉。刷完才告诉状态机可以回 CELL；中途模块又不收了就停在 RECOVER。
     *
     * 一次推几条还要看 [Outbox.slotsLeft]：模组只有 50 个槽（GM1003），
     * 推满了它只会回"缓存已满"，那条报文又得回到队首白转一圈。
     */
    suspend fun drainPending(nowMs: Long): List<ScreenMessage> {
        reclaimDropped()
        // 被写锁挡下的 SOS 不等 RECOVER：升级一结束就该冲出去，晚了就是人命。
        // 普通心跳仍然只在 RECOVER 刷，CELL 态下不该去碰卫星链路
        if (phase != LinkPhase.RECOVER && !sosParked) return emptyList()
        var flushed = 0
        for (entry in outbox.peek(nowMs)) {
            if (flushed >= DRAIN_BURST || outbox.slotsLeft() <= 0) break
            if (!modem.enqueueSfq(entry.hex).accepted) break
            // 双写的分岔（E.10 #8）：SOS 交出去之后 App 仍留一份盯着，模组偷偷丢了要能捡回来；
            // 心跳不留 —— 下一分钟就有一条新的盖过它，留着只是占掉 50 个槽里的一个
            if (entry.kind == OutboxClass.Telemetry) outbox.release(entry.hex) else outbox.markHanded(entry.hex, nowMs)
            if (sosParked && entry.hex == lastSosHex) sosParked = false
            flushed++
        }
        return if (outbox.size == 0 && phase == LinkPhase.RECOVER)
            emit(machine.onSignal(LinkSignal.QueueDrained(nowMs)), nowMs) else emptyList()
    }

    /**
     * SOS。不进链路状态机、不等蜂窝判定 —— 见 protocol/link-policy.md「SOS 例外」。
     * 没有定位时也照样要发，用 flags.bit2 标定位已过期，让平台知道这条不可信到什么程度。
     */
    suspend fun triggerSos(nowSec: Long, cellOk: Boolean): SosResult {
        sosLevel = SosLevel.pending
        sosSinceTs = nowSec * 1000
        sosNextPassInS = null

        val f = fix
        val send = runCatching {
            sos.trigger(
                lat = f?.lat ?: 0.0,
                lon = f?.lon ?: 0.0,
                whenSec = nowSec,
                batteryPct = phoneBatteryPct,
                flags = frameFlags(cellOk),
                cellOk = cellOk,
            )
        }.getOrDefault(SatSend.failed(SatPath.IMMEDIATE, SatFailReason.NO_REPLY))
        lastSosSend = send

        // §6.1 红线一：星上 ACK 不能把状态推到 acked。acked 只由平台回执触发，见 onPlatformAck()
        sosLevel = if (send.accepted) SosLevel.sent else SosLevel.pending
        // 一个字节都没写出去的（升级写锁、没电、缓存满），留在 App 侧队列等补发。
        // 已经存进模组队列的（accepted）不能重复留：GM1003 只有 50 个槽，重三条就是 6%
        if (!send.accepted && send.reason in REQUEUE_REASONS) {
            outbox.offer(lastSosHex, OutboxClass.Emergency, nowMs = nowSec * 1000)
            sosParked = true
        } else if (send.accepted && send.via == SatPath.QUEUED) {
            // 双写：模组那句"收下"只代表进了它自己的队列。GM1003 存够 4 分钟没发出去就偷偷丢掉
            // （〔规 §3.1.13 注〕），App 这份盯梢副本就是唯一能把它捡回来的东西（E.6）。
            // 模组没报告丢也没关系 —— 超时判定在 Outbox 里按型号自己算。
            outbox.offer(lastSosHex, OutboxClass.Emergency, nowMs = nowSec * 1000)
            outbox.markHanded(lastSosHex, nowSec * 1000)
        }
        // 星上确认发出去了 = 模组队列里已经没有它，盯梢副本撤掉
        if (send.result == SatResult.COMPLETE) outbox.confirm(lastSosHex)
        return SosResult(send, lastSosHex, f != null, sosAttempts, sosNextPassInS)
    }

    /**
     * **误触撤销**（§3 那张表里标"引擎层已实现、UI 层还没做"的那一条）。
     *
     * 说清它能做到什么、做不到什么，比实现它更重要：
     *  - 原 SOS **还在 App 队列里没交出去**时，撤销是真的拦住了 —— 顺手把盯梢副本 release 掉；
     *  - 原 SOS **已经进了模组队列**时，撤销只是又排了一条 `MSG_SOS_CANCEL` 上去，
     *    谁先过顶发出去不由我们决定。这时候界面上写"已取消"就是撒谎。
     * 所以返回值里带 [CancelOutcome.Blocked]，UI 必须按这一档措辞（走 [com.carpet.satdemo.core.link.SosCopy.cancelLine]）。
     */
    suspend fun cancelSos(nowSec: Long, cellOk: Boolean): CancelResult {
        val f = fix
        val handed = outbox.handedHexes.contains(lastSosHex)
        val out = runCatching {
            sos.cancel(
                lat = f?.lat ?: 0.0,
                lon = f?.lon ?: 0.0,
                whenSec = nowSec,
                batteryPct = phoneBatteryPct,
                flags = frameFlags(cellOk),
            )
        }.getOrDefault(
            SosCoordinator.CancelOutcome(
                SatSend.failed(SatPath.QUEUED, SatFailReason.NO_REPLY),
                lastSosHex,
            ),
        )
        val send = out.send

        if (send.accepted) {
            // 撤销能被模组收下，就把原 SOS 的 App 侧副本清掉，否则它会被一直补发、越撤越多
            outbox.release(lastSosHex)
            sosParked = false
            if (!handed) {
                sosLevel = SosLevel.normal
                sosSinceTs = null
                sosAttempts = 0
            }
        }
        lastSosSend = send
        return CancelResult(send, handed, out.frameHex)
    }

    /** 撤销的结果。[alreadyHanded] 为真代表原 SOS 已经在模组那边了，撤销只是又发一条 */
    data class CancelResult(
        val send: SatSend,
        val alreadyHanded: Boolean,
        val cancelFrameHex: String,
    ) {
        val accepted: Boolean get() = send.accepted
    }

    /**
     * 用量台账（§8 R1 那条"用量页"的数据源）。
     *
     * 全部现读，不留副本：**额度是型号口径**（GM1001 500 / GM1003 50，§9 #12 还没确认），
     * 长度等级是**卡口径**（ICI 现场报的），写死一个数就会在换卡/换型号那天变成假数据。
     */
    fun usage(): Usage = Usage(
        model = modem.caps?.model,
        level = modem.caps?.lengthLevel,
        service = modem.caps?.service,
        cardId = modem.caps?.cardId,
        capacity = outbox.policy.capacity,
        waiting = outbox.size,
        handedOver = outbox.handedSize,
        dropped = outbox.dropped,
        reclaimed = outbox.reclaimed,
        budget = MessageBudget.from(modem.caps),
    )

    /** 见 [usage]。空值一律是"没读到"，UI 要显示 `--` 而不是 0 */
    data class Usage(
        val model: ModuleModel?,
        val level: MsgLengthLevel?,
        val service: AccessService?,
        val cardId: String?,
        val capacity: Int,
        val waiting: Int,
        val handedOver: Int,
        val dropped: Int,
        val reclaimed: Int,
        val budget: MessageBudget,
    ) {
        /** 模组那侧还剩几个槽。已交出去的都算占位，因为它们可能还没发走 */
        val slotsLeft: Int get() = (capacity - handedOver).coerceAtLeast(0)

        /** 水位，0f..1f。给那条进度条 —— §5.2「图形必须带语义」：这条画的就额度压力 */
        val pressure: Float get() = if (capacity <= 0) 0f else (handedOver.toFloat() / capacity).coerceIn(0f, 1f)

        /** 无效卡（ICI 回 1）：这时候连额度都不能信，用量页要说"这张卡没就绪" */
        val invalidCard: Boolean get() = cardId == ModuleCaps.INVALID_CARD_ID
    }

    /** 到点就把位置推给模组。App 的 1Hz 循环调它，没有 TPI 任务时返回 null */
    suspend fun tickTpi(): TpiTracker.Status? = tpi?.tick()

    /**
     * 星历这一轮该不该重算，由 [EphemerisTracker] 自己判断（每日 update、挪了 20 公里、窗口走完）。
     *
     * 倒计时以星历为准（它是厂商口径，还多给窗口长度和方位角）；**只有算不出来才**退回模组那个粗估，
     * 并且把"为什么算不出来"一起带上屏 —— 说成"没卫星信号"是 E.9 点名的错法。
     */
    suspend fun tickEphemeris(): EphemerisTracker.Status? {
        val tracker = ephemeris ?: return null
        val status = tracker.refresh(fix?.let { GeodeticPosition.of(it.lat, it.lon) })
        nextPassInS = if (status.state == EphState.SUCCESS) {
            status.nextPassInS
        } else {
            runCatching { modem.secondsToNextPass() }.getOrNull()
        }
        return status
    }

    /**
     * 平台侧回执到了（4G 下行或卫星下行里的救援受理确认）。
     *
     * 名字故意不叫 `onAck`：**星上 ACK 不算**（〔规 §3.1.4 注6〕卫星应答只代表卫星收到），
     * 叫 ack 会让后来人以为可以在发送回调里调它。这是 §6.1 红线一在代码里的唯一入口。
     */
    fun onPlatformAck() {
        if (sosLevel == SosLevel.sent || sosLevel == SosLevel.pending) {
            sosLevel = SosLevel.acked
            sosSinceTs = System.currentTimeMillis()
            // 平台都回执了，模组队列里那条早就发走了，盯梢副本没用了
            outbox.confirm(lastSosHex)
        }
    }

    /**
     * 模组收下了之后又把它丢掉的报文（GM1003 存够 4 分钟没发出去，〔规 §3.1.13 注〕）。
     * App 那份补发队列在模组"收下"时就清了，不捡回来这条就静默消失 —— SOS 尤其不能这样。
     */
    private fun reclaimDropped() {
        outbox.reclaim(modem.drainDropped())
    }

    /**
     * 被捡回来的 SOS 不等 RECOVER 就该重推 —— 模组把它丢了不等于人不用救了。
     * 三条来路都归到这里：模组报告的丢弃、[Outbox.sweep] 的超时判定、掉电重推。
     */
    private fun reparkEmergency(nowMs: Long) {
        if (sosParked || lastSosHex.isEmpty()) return
        val head = outbox.peek(nowMs).firstOrNull() ?: return
        if (head.hex == lastSosHex) sosParked = true
    }

    data class SosResult(
        val send: SatSend,
        val frameHex: String,
        val hasFix: Boolean,
        /** 实际发出的次数，三次都没 ACK 时展示端要写清楚 */
        val attempts: Int,
        /** 仅三次无 ACK 时非空：下一条过顶还有多少秒 */
        val nextPassInS: Long?,
    ) {
        /** 三态之一。文案请走 [com.carpet.satdemo.core.link.SosCopy.statusLine]，别在 UI 里自己拼 */
        val result: SatResult get() = send.result

        val reason: SatFailReason? get() = send.reason

        val via: SatPath get() = send.via
    }

    /**
     * 最近一次 SOS 的三态结果。展示端不要自己解释它，
     * 走 [com.carpet.satdemo.core.link.SosCopy.statusLine] 生成文案（§6.1 红线一）。
     */
    val sosSend: SatSend get() = lastSosSend

    fun snapshot(): StatePayload {
        val tpiStatus = tpi?.status()
        val ephStatus = ephemeris?.status()
        return StatePayload(
            link = LinkState(
                primary = if (phase == LinkPhase.CELL || phase == LinkPhase.RECOVER) LinkPrimary.cell else LinkPrimary.sat,
                cellOk = phase == LinkPhase.CELL || phase == LinkPhase.RECOVER,
                cellDbm = null,
                satOk = modem.available && lock != null,
            ),
            sat = SatState(
                phase = satPhase(),
                // 终端都没了，上一次锁定的星名和 CNR 就是旧数据，再画出来等于骗人
                satName = if (modem.available) lock?.name else null,
                cnr = if (modem.available) lock?.cnr else null,
                nextPassInS = nextPassInS?.toInt(),
                moduleBattery = null,
                moduleOk = modem.available,
                queuePending = pending.size,
                // 队列口径按型号注入（E.10 #8）：容量和被挤掉的条数一起上屏，
                // 光给"待补发 N 条"没人知道离上限还差几条
                queueDropped = outbox.dropped.takeIf { outbox.dropped > 0 },
                // 容量不按 module_ok 门控：掉线时正是它最有用的时候（"50 条只剩 3 个位置"）
                queueCapacity = outbox.policy.capacity,
                // TPI 陈旧度是新的 §4 呈现字段：模组的位置是我们喂的，过期了下行就收不到（E.5）
                tpiAgeS = tpiStatus?.ageMs?.let { it / 1000 },
                tpiStale = tpiStatus?.let { it.state == TpiTracker.State.STALE || it.state == TpiTracker.State.NO_SOURCE },
                // 升级进度直接读写锁，屏上那排按钮的禁用态看这个（§5.2）
                upgradePct = modem.gate.progressPct,
                // 星历（E.10 #7）。文案在 App 侧就定稿，固件只负责画——它一编就可能把"去联网"说成"没卫星"
                ephState = ephStatus?.state,
                ephFail = ephStatus?.failMessage,
                nextPassDurS = ephStatus?.nextPass?.windowSec?.toInt(),
                nextPassMaxEl = ephStatus?.nextPass?.maxElevationDeg,
                aimAz = ephStatus?.aimAzimuthDeg,
            ),
            gnss = fix ?: GnssState(0.0, 0.0, altM = null, hdop = null, source = GnssSource.phone, fixAgeS = null),
            fence = fence,
            sos = SosState(
                sosLevel,
                sosSinceTs,
                sosAttempts.takeIf { it > 0 },
                // 星上收没收到 ≠ 平台收没收到，屏上要把这两件事分开画（§6.1 红线一）
                satAck = lastSosSend.result == SatResult.COMPLETE,
                fail = lastSosSend.reason?.name?.lowercase(),
            ),
            sentinel = sentinel,
            phoneBattery = phoneBatteryPct,
            demoMode = demoMode,
        )
    }

    private fun satPhase(): SatPhase = when {
        !modem.available -> SatPhase.idle
        phase == LinkPhase.PROBE -> SatPhase.searching
        lock != null -> SatPhase.locked
        pending.isNotEmpty() -> SatPhase.tx_pending
        else -> SatPhase.idle
    }

    private fun frameFlags(cellOk: Boolean): Int {
        var flags = 0
        if (cellOk) flags = flags or SosFrame.FLAG_DUAL
        val f = fix
        if (f != null && f.source != GnssSource.phone) flags = flags or SosFrame.FLAG_SRC_GT1005
        // 没定位和定位超过 30s 都算过期，展示端要说清楚
        if (f == null || (f.fixAgeS ?: 999.0) > 30.0) flags = flags or SosFrame.FLAG_STALE_FIX
        if (sentinel.armed) flags = flags or SosFrame.FLAG_SENTINEL_ARMED
        return flags
    }

    private fun emit(events: List<LinkEvent>, nowMs: Long): List<ScreenMessage> {
        phase = machine.phase
        if (events.any { it is LinkEvent.ProbeRequested }) probeRequested = true
        if (events.isEmpty()) return emptyList()
        return events.mapNotNull { toMessage(it, nowMs) }
    }

    private fun toMessage(e: LinkEvent, nowMs: Long): ScreenMessage? = when (e) {
        is LinkEvent.PhaseChanged -> event(
            nowMs,
            EventKind.link_switched,
            "from" to JsonPrimitive(e.from.name.lowercase()),
            "to" to JsonPrimitive(e.to.name.lowercase()),
            "at_ms" to JsonPrimitive(e.atMs),
        )
        is LinkEvent.FlushRequested -> event(
            nowMs,
            EventKind.track_reset,
            "pending" to JsonPrimitive(pending.size),
        )
        // 探测包是内部动作，展示端只关心相位变化，别把噪声推过去
        is LinkEvent.ProbeRequested, is LinkEvent.ProbeTimedOut -> null
    }

    private fun event(nowMs: Long, kind: EventKind, vararg fields: Pair<String, JsonElement>): ScreenMessage =
        ScreenMessage(
            t = MsgType.event,
            // 序号和事件 id 由发送方 DemoRuntime 统一盖。这里各留一个占位，
            // 是因为 engine 不知道自己排在整条链路的第几帧上。
            seq = 0,
            ts = nowMs,
            event = EventPayload(
                kind = kind,
                eventId = "",
                payload = JsonObject(linkedMapOf(*fields)),
            ),
        )

    companion object {
        /** 补发时每 tick 刷几条。真机上一条 SFQ 要过一次 BLE 写 */
        private const val DRAIN_BURST = 2

        /** SOS 确认横幅在展示端停留多久后收回 */
        private const val ACKED_BANNER_HOLD_MS = 12_000L

        /**
         * SOS 失败后**值得**留在 App 队列里等补发的成因 —— 判据是"一个字节都没写出去"。
         * [SatFailReason.TOO_LONG] 不在里面：报文超预算是内容问题，重排一百次也还是超，
         * 留在队列里只会一直堵着队首。
         */
        private val REQUEUE_REASONS = setOf(
            SatFailReason.UPGRADING,
            SatFailReason.NOT_PROVISIONED,
            SatFailReason.NO_REPLY,
            SatFailReason.QUEUE_FULL,
            SatFailReason.DROPPED_BY_TIMEOUT,
        )
    }
}
