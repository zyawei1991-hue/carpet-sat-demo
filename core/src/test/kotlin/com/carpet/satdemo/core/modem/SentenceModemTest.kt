package com.carpet.satdemo.core.modem

import com.carpet.satdemo.core.protocol.AccessService
import com.carpet.satdemo.core.protocol.SfiFailReason
import com.carpet.satdemo.core.protocol.SfiState
import com.carpet.satdemo.core.protocol.GeodeticPosition
import com.carpet.satdemo.core.protocol.GsatDecoder
import com.carpet.satdemo.core.protocol.GsatParseResult
import com.carpet.satdemo.core.protocol.GsatSentence
import com.carpet.satdemo.core.protocol.GsatSpeaker
import com.carpet.satdemo.core.protocol.ModuleCaps
import com.carpet.satdemo.core.protocol.ModuleModel
import com.carpet.satdemo.core.protocol.MsgLengthLevel
import com.carpet.satdemo.core.protocol.hexToBytes
import com.carpet.satdemo.core.protocol.toHex
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **到货前唯一能把整条协议链跑通的东西**（§8 近期四件事 #1、附录 E.10）。
 *
 * 关键在于断言的层次：这里不检查"App 以为模组会怎么回"，而是检查
 * 「[SentenceModem] 发出的字节」和「[FakeGsatModule] 按规范回的字节」能不能对上 ——
 * 假模组严格按〔规 §3.1.x〕的字段序号组包，所以这一组测试同时也是**对规范的一次可执行翻译**。
 * 真机到货那天，唯一变的是管道，这些断言应该原样成立。
 */
class SentenceModemTest {

    private class Fixture(
        val clock: MutableClock = MutableClock(),
        model: ModuleModel = ModuleModel.WORST_KNOWN,
        lengthLevel: MsgLengthLevel = MsgLengthLevel.L1,
        service: AccessService = AccessService.SHORT,
        cardId: String = "2025110100002",
    ) {
        private val pipes = InMemoryBytePipe.pair()
        val appPipe = pipes.first
        val modulePipe = pipes.second
        val fake = FakeGsatModule(modulePipe, lengthLevel = lengthLevel, service = service, model = model, cardId = cardId)
        val modem = SentenceModem(appPipe, model = model, clock = clock)

        init {
            fake.attachToAppSide()
        }

        fun bringUpLocated(): SentenceModem.BringUpOutcome = runBlocking {
            modem.updateLocation(120.1551, 30.2741)
            modem.bringUp()
        }
    }

    /** 手动推进的时间源，TPI 陈旧度和超时分支靠它，不用真等 */
    private class MutableClock : MonoClock {
        var now: Long = 1_700_000_000_000L
        override fun nowMs(): Long = now
        fun advance(ms: Long) {
            now += ms
        }
    }

    // ---- 上电会话 ----

    @Test
    fun `会话顺序是 读DII 读ICI 推位置 开PDI`() = runBlocking {
        val fx = Fixture()
        fx.modem.updateLocation(120.1551, 30.2741)
        assertIs<SentenceModem.BringUpOutcome.Ready>(fx.modem.bringUp())

        // 模组侧收到的语句序列。updateLocation 当场就发一条 TPI，bringUp 里还会再保证一次
        // （模组可能在这中间复位过，位置掉电即丢），所以开头那条 TPI 是预期内的
        val sent = fx.fake.received
        assertEquals(listOf("TPI", "GMO", "GMO", "TPI", "GMO"), sent.map { it.verb }, "会话序列：先落位置，再读信息、读 ICI、开会话")
        assertEquals("DII", sent[1].fields[0])
        assertEquals("ICI", sent[2].fields[0])
        assertEquals("1", sent[3].fields[0], "TPI 类别 1 = 设置（〔规 §3.1.7〕）")
        assertEquals("PDI", sent[4].fields[0])
        assertTrue(fx.modem.sessionReady)
    }

    @Test
    fun `没推成位置就不算会话就绪 也不许往发送方向写`() = runBlocking {
        val fx = Fixture()
        // 注意不调 updateLocation：TPI 是必配项（〔规 §3.1.7 注〕"用户必须配置该项"）
        assertIs<SentenceModem.BringUpOutcome.NeedsLocation>(fx.modem.bringUp())
        assertFalse(fx.modem.sessionReady)
        // ICI 读到了，预算是已知的 —— 但会话没就绪，写方向仍然一个字节都不发
        assertNotNull(fx.modem.caps)
        val blocked = fx.modem.enqueueSfq("0102A1A2")
        assertFalse(blocked.accepted)
        assertEquals(SatFailReason.NOT_PROVISIONED, blocked.reason)
        assertTrue(fx.fake.received.none { it.verb == "SFQ" }, "会话没就绪就入队，等于把 SOS 交给一个还没配好的模组")
    }

    @Test
    fun `无效卡直接终止会话不选星`() = runBlocking {
        val fx = Fixture(cardId = "1")
        // 〔规 §3.1.4 注1〕1 为无效卡，无效卡的通信单元不进行选星操作
        val outcome = fx.modem.bringUp()
        assertIs<SentenceModem.BringUpOutcome.InvalidCard>(outcome)
        assertEquals("1", outcome.cardId)
        assertNull(fx.modem.caps)
        assertTrue(fx.fake.received.none { it.verb == "GMO" && it.fields[0] == "PDI" }, "无效卡不该去开 PDI")
    }

    @Test
    fun `模组不回话时报出是哪一条没回`() = runTest {
        val fx = Fixture()
        fx.fake.responsive = false
        fx.modem.updateLocation(120.1551, 30.2741)
        val outcome = fx.modem.bringUp()
        assertIs<SentenceModem.BringUpOutcome.NoReply>(outcome)
        assertEquals("DII", outcome.verb, "现场要能区分\"线没接好\"和\"模组没回某一条\"")
        assertFalse(fx.modem.sessionReady)
    }

    @Test
    fun `口没开时一个字节都不写`() = runBlocking {
        val fx = Fixture()
        fx.appPipe.close()
        assertIs<SentenceModem.BringUpOutcome.PipeClosed>(fx.modem.bringUp())
        assertFalse(fx.modem.available)
        assertTrue(fx.fake.received.isEmpty(), "口没开就别往串口上写东西")
    }

    // ---- 能力档 ----

    @Test
    fun `能力档从 ICI 字段里读出来而不是写死`() = runBlocking {
        val fx = Fixture(lengthLevel = MsgLengthLevel.L4, service = AccessService.LONG, model = ModuleModel.GM1001)
        fx.bringUpLocated()
        val caps = assertNotNull(fx.modem.caps)
        assertEquals(MsgLengthLevel.L4, caps.lengthLevel)
        assertEquals(430, caps.maxMessageBytes)
        assertEquals(AccessService.LONG, caps.service)
        // GM1001：500 条 / 掉电保存 / 无超时（E.6）
        assertEquals(500, caps.model.queueCapacity)
        assertTrue(caps.model.persistsAcrossPowerLoss)
        assertNull(caps.model.dropUnsentAfterMs)
    }

    @Test
    fun `最坏档默认值每一维都取不利的`() {
        val w = ModuleCaps.worstKnown()
        assertEquals(MsgLengthLevel.L1, w.lengthLevel)
        assertEquals(90, w.maxMessageBytes)
        assertEquals(AccessService.SHORT, w.service)
        assertEquals(50, w.model.queueCapacity)
        assertEquals(4 * 60_000L, w.model.dropUnsentAfterMs)
        assertFalse(w.canExpectSatelliteAck)
    }

    // ---- 报文预算与队列 ----

    @Test
    fun `超预算的报文根本不发出去`() = runBlocking {
        val fx = Fixture()
        fx.bringUpLocated()
        val before = fx.fake.received.size
        // 等级 1 = ≤90 B，这里给 100 字节（200 个 hex 字符）
        val over = fx.modem.enqueueSfq("00".repeat(100))
        assertFalse(over.accepted)
        assertEquals(SatFailReason.TOO_LONG, over.reason, "超预算要能说出是哪一维不对（E.4）")
        // 关键断言：**一个字节都没往模组发**，不是"发了然后被拒"
        assertEquals(before, fx.fake.received.size)
    }

    @Test
    fun `预算内的报文走完整往返`() = runBlocking {
        val fx = Fixture()
        fx.bringUpLocated()
        val stored = fx.modem.enqueueSfq("0102A1A2")
        assertTrue(stored.accepted)
        // 存进模组队列 ≠ 星上确认：三态里只能到 NO_ACK（§6.1 红线一）
        assertEquals(SatResult.NO_ACK, stored.result)
        assertEquals(SatPath.QUEUED, stored.via)
        assertEquals(1, stored.messageId, "SFI 给的报文 ID 要留着对账")
        val sfq = fx.fake.received.last { it.verb == "SFQ" }
        assertEquals(listOf("0", "0", "0102A1A2"), sfq.fields, "收信方 0=平台、优先级普通（〔规 §3.1.12 注1/注2〕）")
        assertEquals(1, fx.fake.queue.size)
    }

    @Test
    fun `模组说缓存已满时入队失败`() = runBlocking {
        val fx = Fixture()
        fx.bringUpLocated()
        fx.fake.refuseNewMessages = true
        val full = fx.modem.enqueueSfq("0102A1A2")
        assertFalse(full.accepted, "模组没确认收下，这条就得留在 App 队列里")
        assertEquals(SatFailReason.QUEUE_FULL, full.reason)
    }

    @Test
    fun `查询待发数量按型号夹住`() = runBlocking {
        val fx = Fixture(model = ModuleModel.GM1003)
        fx.bringUpLocated()
        assertEquals(0, fx.modem.queryQueueRemaining())
        fx.modem.enqueueSfq("0102A1A2")
        assertEquals(1, fx.modem.queryQueueRemaining())
        // 〔规 §3.1.14 例〕查询语句就是 $CCSFP,2,1,,
        val sfp = fx.fake.received.last { it.verb == "SFP" }
        assertEquals(listOf("2", "1", "", ""), sfp.fields)
    }

    // ---- TPI ----

    @Test
    fun `位置推给模组后能原样回显`() = runBlocking {
        val fx = Fixture()
        fx.bringUpLocated()
        val pushed = assertNotNull(fx.fake.pushedPosition)
        val hangzhou = GeodeticPosition.of(30.2741, 120.1551)
        assertEquals(hangzhou.latitude.field, pushed.latitude.field)
        assertEquals(hangzhou.longitude.field, pushed.longitude.field)
        assertNotNull(fx.fake.pushedEpochSec)
        assertEquals(fx.clock.now / 1000, fx.fake.pushedEpochSec, "TPI 的时间字段是 UNIX 秒（〔规 §3.1.7〕）")
    }

    @Test
    fun `经纬度传反会当场被类型拦住`() {
        // 这是那个必踩的坑的反证：SDK 的 updateLocation(经,纬) 与语句里的 (纬,经) 顺序相反。
        // 用裸 Double 传参传反了不会报错，只会安静地把位置挪到地球对面 —— 所以这里必须抛。
        assertFailsWith<IllegalArgumentException> { GeodeticPosition.of(120.1551, 30.2741) }
    }

    @Test
    fun `TPI 陈旧度按时间判定`() = runBlocking {
        val clock = MutableClock()
        val fx = Fixture(clock = clock)
        fx.bringUpLocated()
        assertFalse(fx.modem.tpiStale())
        clock.advance(SentenceModem.TPI_STALE_DEFAULT_MS + 1)
        assertTrue(fx.modem.tpiStale(), "模组复位或超时无更新时必须能在界面上看出来（E.5）")
    }

    @Test
    fun `readFix 是回显不是定位能力`() = runBlocking {
        val fx = Fixture()
        fx.bringUpLocated()
        val before = fx.fake.received.size
        val fix = assertNotNull(fx.modem.readFix())
        assertEquals(30.2741, fix.lat, 1e-3)
        // hdop 拿不到 —— 真机这颗如果没有自带 GNSS，这个函数读到的只是我们自己推的值
        assertNull(fix.hdop)
        assertTrue(fx.fake.received.size > before, "读位置要现问一次 DII，上电那份缓存里必然还没有位置")
    }

    // ---- 主动上报与坏帧 ----

    @Test
    fun `没人问的PDI当场分掉而不是排队等下一次问答`() = runBlocking {
        val fx = Fixture()
        val locks = ArrayList<SatLock?>()
        fx.modem.onSatelliteLock { locks += it }
        fx.bringUpLocated()
        val hadLocks = locks.size
        // 换一颗星再推：回调只在锁星信息**变化**时通知，同一条重复推不该刷屏
        fx.fake.report("PDI", listOf("120000.00", "302", "0", "00", "0", "1", "302", "44.0", "0", "0", "0"))
        assertTrue(locks.size > hadLocks, "PDI 应该立刻进锁星回调: $locks")
        val last = assertNotNull(locks.last())
        assertEquals("302", last.name)
        assertTrue(last.cnr > 40.0)
    }

    @Test
    fun `卫星忙碌时不能宣布可发射`() = runBlocking {
        val fx = Fixture()
        fx.bringUpLocated()
        // 〔规 §3.1.5 注2〕忙碌时不可发射；字段 4/5 是忙闲位
        fx.fake.report("PDI", listOf("120000.00", "301", "0", "01", "1", "1", "301", "42.5", "0", "0", "0"))
        val track = assertNotNull(fx.modem.lastTrack())
        assertTrue(track.hasSelectableSatellite)
        assertTrue(track.channelBusy)
        assertFalse(track.canTransmit)
    }

    @Test
    fun `无可用卫星时选择编号为零`() = runBlocking {
        val fx = Fixture()
        fx.bringUpLocated()
        fx.fake.report("PDI", listOf("120000.00", "0", "0", "00", "0", "0", "0", "0", "0", "0", "0"))
        val track = assertNotNull(fx.modem.lastTrack())
        assertFalse(track.hasSelectableSatellite)
        assertNull(track.bestLocked, "载噪比 0 是未锁定，不是信号弱（〔规 §3.1.5 字段8/10〕）")
    }

    @Test
    fun `校验和错误的帧被计数并丢弃`() = runBlocking {
        val fx = Fixture()
        fx.bringUpLocated()
        val before = fx.modem.badFrames
        fx.appPipe.injectFromPeer("\$GSSFI,2,9,*00\r\n".toByteArray())
        assertEquals(before + 1, fx.modem.badFrames, "读串一位必须能被看到，不能静默丢")
    }

    @Test
    fun `一条语句分两段到达也要拼起来`() = runBlocking {
        val fx = Fixture()
        fx.bringUpLocated()
        val frame = "\$GSPDI,120000.00,301,0,00,0,1,301,42.5,0,0,0"
        val good = "\$$frame*${GsatSentence.checksumOf(frame)}\r\n"
        val cut = good.length / 2
        fx.appPipe.injectFromPeer(good.substring(0, cut).toByteArray())
        val before = fx.modem.badFrames
        fx.appPipe.injectFromPeer(good.substring(cut).toByteArray())
        // 半截行不能被当坏帧丢掉，要留在缓冲里等下一段
        assertEquals(before, fx.modem.badFrames)
        assertNotNull(fx.modem.lastTrack())
    }

    // ---- 下行 ----

    @Test
    fun `短接入必须轮询才收得到下行`() = runBlocking {
        val fx = Fixture(service = AccessService.SHORT)
        fx.bringUpLocated()
        val got = ArrayList<String>()
        fx.modem.onDownlink { got += it }
        fx.fake.offlineInbox += hexToBytes("0102A1A2")
        fx.fake.offlineInbox += hexToBytes("0304")
        // 〔规 §3.1.10 注2〕查询一条接收一条，CES 的剩余数不含刚吐的那条
        assertTrue(fx.modem.drainOfflineMessages())
        assertEquals(listOf("0102A1A2", "0304"), got)
        assertEquals(2, fx.fake.received.count { it.verb == "CEQ" })
    }

    @Test
    fun `长接入不需要查询`() = runBlocking {
        val fx = Fixture(service = AccessService.LONG)
        fx.bringUpLocated()
        assertTrue(fx.modem.drainOfflineMessages())
        assertFalse(fx.fake.received.any { it.verb == "CEQ" }, "长接入不该发 CEQ")
    }

    @Test
    fun `平台消息与终端消息靠发信方号码区分`() {
        val raw = GsatSentence.build(GsatSpeaker.UNIT, "MCI", listOf("120000", "0", "", "0102A1A2"))
        val m = assertNotNull(GsatDecoder.mci(assertIs<GsatParseResult.Ok>(GsatSentence.parse(raw)).sentence))
        // 〔规 §3.1.11 注1〕收到平台消息，发信方号码为 0
        assertTrue(m.fromPlatform)
        assertEquals("0102A1A2", m.payload.toHex())
    }

    // ---- MCQ ----

    @Test
    fun `即时发送在语句层拒绝实现因为线格式没文档`() = runBlocking {
        val fx = Fixture()
        fx.bringUpLocated()
        assertFalse(fx.modem.supportsImmediateSend)
        val e = assertFailsWith<UnsupportedOperationException> { fx.modem.sendMcq("0102A1A2") }
        assertTrue(e.message!!.contains("MCQ"), e.message!!)
        assertTrue(fx.fake.received.none { it.verb == "MCQ" }, "宁可不发也不猜格式")
    }

    @Test
    fun `SOS 走 runCatching 时即时发送失败会落到 FAIL 而不是崩`() = runBlocking {
        // DemoEngine / SosCoordinator 都是用 runCatching 包 sendMcq 的，
        // 这条测试钉住"抛异常也不会把演示打断"这个前提
        val fx = Fixture()
        fx.bringUpLocated()
        val result = runCatching { fx.modem.sendMcq("0102A1A2") }.getOrDefault(SatResult.FAIL)
        assertEquals(SatResult.FAIL, result)
    }

    @Test
    fun `模组超时丢弃的报文必须被捡回 App 队列`() = runBlocking {
        val fx = Fixture(model = ModuleModel.GM1003)
        fx.bringUpLocated()
        val stored = fx.modem.enqueueSfq("0102A1A2")
        assertEquals(1, fx.modem.moduleQueueDepth, "模组收下之后我们还得记着它，否则丢了就找不回来")

        // 〔规 §3.1.13〕GM1003 存够 4 分钟没发出去，模组主动吐一条 state=4 reason=3
        fx.fake.report("SFI", listOf(SfiState.TRANSMIT_FAILED.code.toString(), stored.messageId!!.toString(), SfiFailReason.TIMEOUT.code.toString()))
        assertEquals(0, fx.modem.moduleQueueDepth)
        assertEquals(listOf("0102A1A2"), fx.modem.drainDropped(), "SOS 不能因为模组丢弃就静默消失（E.6）")
        assertTrue(fx.modem.drainDropped().isEmpty(), "取走一次就该清空")
    }

    @Test
    fun `发射成功的报文不再被当成待补发`() = runBlocking {
        val fx = Fixture()
        fx.bringUpLocated()
        val stored = fx.modem.enqueueSfq("0102A1A2")
        fx.fake.report("SFI", listOf(SfiState.SENT.code.toString(), stored.messageId!!.toString(), "0"))
        assertEquals(0, fx.modem.moduleQueueDepth)
        assertTrue(fx.modem.drainDropped().isEmpty())
    }

    // ---- 换传输不换协议 ----

    @Test
    fun `接上串口那条路也能把会话跑起来`() {
        // 演示"D7 的那根 USB 转 TTL"现在只是管道的事：
        // 把内存管道换成 LinePort + [UartBytePipe]，协议层的断言一条都不用改。
        val port = QueueBackedLinePort()
        val pipes = InMemoryBytePipe.pair()
        val appPipe = pipes.first
        val modulePipe = pipes.second
        val fake = FakeGsatModule(modulePipe)
        fake.attachToAppSide()
        appPipe.onBytes { bytes -> port.feed(String(bytes, Charsets.US_ASCII)) }
        port.onWrite = { line -> appPipe.write((line + "\r\n").toByteArray(Charsets.US_ASCII)) }

        val pipe = UartBytePipe(port, pollMs = 5)
        val modem = SentenceModem(pipe, clock = MutableClock())
        try {
            runBlocking {
                modem.updateLocation(120.1551, 30.2741)
                assertIs<SentenceModem.BringUpOutcome.Ready>(modem.bringUp())
                assertTrue(modem.enqueueSfq("0102A1A2").accepted)
            }
            assertEquals(1, fake.queue.size)
            assertTrue(port.written.any { it.startsWith("\$CCSFQ,0,0,0102A1A2*") }, port.written.toString())
        } finally {
            pipe.close()
        }
    }

    @Test
    fun `内存管道那一对必须接成对面而不是自己`() = runBlocking {
        // 接线接成回环时，SentenceModem 会读到自己刚发出去的那句话 —— 症状是"每条都超时"，
        // 排查方向却被引向协议。所以这个前提单独钉一条。
        val pipes = InMemoryBytePipe.pair()
        val got = ArrayList<String>()
        pipes.second.onBytes { got += String(it, Charsets.US_ASCII) }
        val echoed = ArrayList<String>()
        pipes.first.onBytes { echoed += String(it, Charsets.US_ASCII) }
        pipes.first.write(hostSentence("COP", listOf("2")))
        assertEquals(listOf("\$CCCOP,2*42\r\n"), got)
        assertTrue(echoed.isEmpty(), "发出去的东西不该出现在自己的接收回调里")
    }

    /** 一行一问的假串口：写出去的行走 onWrite，读上来的行由测试喂 */
    private class QueueBackedLinePort : LinePort {
        private val lines = LinkedBlockingQueue<String>()
        val written = ArrayList<String>()
        var onWrite: ((String) -> Unit)? = null

        @Volatile
        private var open = true

        override val isOpen: Boolean get() = open
        override fun readLine(timeoutMs: Long): String? = lines.poll(timeoutMs, TimeUnit.MILLISECONDS)
        override fun writeLine(line: String) {
            written += line
            onWrite?.invoke(line)
        }

        override fun close() {
            open = false
            lines += ""
        }

        fun feed(line: String) {
            if (open) lines += line
        }
    }
}
