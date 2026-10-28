package com.carpet.satdemo.simulator

import com.carpet.satdemo.core.engine.DemoEngine
import com.carpet.satdemo.core.ephemeris.EphemerisTracker
import com.carpet.satdemo.core.ephemeris.EphState
import com.carpet.satdemo.core.ephemeris.SimulatedEphemerisSource
import com.carpet.satdemo.core.link.CellStatus
import com.carpet.satdemo.core.link.LinkPolicy
import com.carpet.satdemo.core.modem.MonoClock
import com.carpet.satdemo.core.modem.SimulatedModem
import com.carpet.satdemo.core.modem.TpiTracker
import com.carpet.satdemo.core.protocol.GnssSource
import com.carpet.satdemo.core.protocol.ScreenMessage
import com.carpet.satdemo.core.runtime.DemoRuntime
import com.carpet.satdemo.core.screen.ScreenLinkServer
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * 桌面演示驱动器。不接手机、不接 GT1005，把 App 侧那套业务逻辑跑成一条能看的时间线。
 *
 * 起两个端口：
 *   8443  WebSocket，圆屏（或本目录 screen.html）连这里
 *   8090  HTTP，把 screen.html 吐给浏览器，方便没有硬件时调展示端 UI。
 *         不用 8080：那台机器上常有别的开发服务占着，两个监听混在一起排查半天
 *
 * 时间用假时钟：1 tick = 1 个假秒、真时间 600ms。判据窗口用的是彩排阈值
 * （见 [demoPolicy]），但过境周期按真的 12 分钟建模，所以「下一条过顶」倒计时是连贯的。
 *
 * `--hold N`      走到第 N 步结束时定格
 * `--freeze-sec S` 走到第 S 个假秒定格 —— 步内发生的相位变化用它抓
 * `--blocked`     演「卫星没过顶」：探测无 ACK、SOS 三次重发、只剩倒计时
 */
object DemoDriver {

    private val GOOD = CellStatus(hasInternetCapability = true, validated = true, signalLevel = 4)
    private val WEAK = CellStatus(hasInternetCapability = true, validated = true, signalLevel = 1)
    private val DEAD = CellStatus(hasInternetCapability = false, validated = false, signalLevel = 0)

    private data class Step(
        val seconds: Int,
        val label: String,
        val cell: CellStatus,
        /** false 演「模块掉线」（车断电一类），心跳就只能留在 App 侧队列 */
        val moduleOnline: Boolean = true,
    )

    private const val PASS_PERIOD_MS = 720_000L
    private const val PASS_WINDOW_MS = 240_000L

    /**
     * 假时钟起点距下次过顶 24 个假秒 —— 正好是第 4 步的结尾，
     * 于是「倒计时走完 → 窗口打开 → 锁定」连着发生，抓静帧不用凑时间。
     */
    private val T0 = run {
        val base = 1_795_200_000_000L
        base - base % PASS_PERIOD_MS + PASS_PERIOD_MS - 24_000
    }

    private val SCRIPT: List<Step> = listOf(
        Step(6, "城市道路：蜂窝主链路，轨迹 1Hz 上报", GOOD),
        Step(6, "进停车场：信号 1 格，判据过半，提前切 PROBE", WEAK),
        Step(6, "彻底没网：探测包没等到 ACK，切 SAT", DEAD),
        Step(6, "SAT：星还没过顶，只剩下一条过境倒计时", DEAD),
        Step(6, "过境窗口打开：锁住卫星，星名和 CNR 上来", DEAD),
        Step(8, "按下 SOS，卫星确认送达", DEAD),
        Step(4, "平台回执横幅停在屏上", DEAD),
        Step(12, "GT1005 掉线：心跳只能留在 App 队列，一条不能丢", DEAD, moduleOnline = false),
        Step(12, "蜂窝恢复：进 RECOVER，把攒下的补发出去", GOOD),
    )

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val hold = args.argAfter("--hold")
        val freezeAt = args.argAfter("--freeze-sec")
        val blocked = args.contains("--blocked")
        val record = args.indexOf("--record").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }?.let { Paths.get(it) }

        val clock = FakeClock(T0)
        val modem = SimulatedModem(
            clock = { clock.ms },
            passPeriodMs = PASS_PERIOD_MS,
            // --blocked 就是把窗口压成 0：永远不在过顶期，MCQ 全部 NO_ACK
            passWindowMs = if (blocked) 0 else PASS_WINDOW_MS,
            txLatencyMs = 0,
            sleep = { },
        )
        val server = ScreenLinkServer()
        // TPI 和星历都得有人持有，否则彩排演的是"没有这两条规则的 App"（E.10 #5/#7）
        val tpi = TpiTracker(modem, MonoClock { clock.ms })
        val ephemeris = SimulatedEphemerisSource(
            clock = MonoClock { clock.ms },
            passPeriodMs = PASS_PERIOD_MS,
            passWindowMs = if (blocked) 0 else PASS_WINDOW_MS,
            // --eph-offline 演 E.9 那条最容易说错的话：星历过期 ≠ 没卫星信号
            state = if (args.contains("--eph-offline")) EphState.NETWORK_ERROR else EphState.SUCCESS,
            online = !args.contains("--update-fail"),
        )
        val engine = DemoEngine(
            modem,
            ownerId = 12_061L,
            policy = demoPolicy(),
            tpi = tpi,
            ephemeris = EphemerisTracker(ephemeris, MonoClock { clock.ms }),
        )
        val runtime = DemoRuntime(engine, server, clock::now, heartbeatEvery = 3)
        server.accepting = true
        // 假时钟由下面的脚本推，不能再起内部那圈 1Hz
        runtime.start(autoTick = false)
        val sink = record?.let { ReplaySink(it) }
        runtime.onSend = { msgs -> sink?.write(msgs) }

        val port = serveUi(8090, record)
        println("展示端  http://127.0.0.1:$port/   (ws 接入点 ws://127.0.0.1:${ScreenLinkServer.PORT})")
        if (record != null) println("回放    http://127.0.0.1:$port/?replay=${record.fileName}   录到 ${record}")
        if (blocked) println("模式：卫星未过顶，所有 MCQ 都拿不到 ACK")
        if (args.contains("--eph-offline")) println("模式：星历 NETWORK_ERROR —— 屏上应该说「请联网更新」，不能说「没卫星信号」")
        if (args.contains("--update-fail")) println("模式：星历下载失败，只能靠本地缓存算窗口")

        runtime.onFix(30.2741, 120.1551, GnssSource.phone, 1.2, 2.0)
        runtime.setFence(true, "西湖景区")
        runtime.setDemoMode(true)
        runtime.onPhoneBattery(78)

        var round = 0
        while (true) {
            round++
            SCRIPT.forEachIndexed { i, step ->
                modem.reachable = step.moduleOnline
                repeat(step.seconds) {
                    runtime.onCellSample(step.cell)
                    runtime.tickOnce()
                    clock.advance(1_000)
                    if (freezeAt != null && (clock.ms - T0) / 1000 >= freezeAt) {
                        println("定格在 t=${(clock.ms - T0) / 1000}s（第 ${i + 1} 步：${step.label}）相位 ${engine.phase}")
                        printConsole(runtime)
                        holdScreen(runtime)
                    }
                    delay(TICK_MS)
                }
                println("step ${i + 1}  t=${(clock.ms - T0) / 1000}s  ${step.label}  ->  ${engine.phase}")
                printConsole(runtime)
                if (step.label.contains("SOS")) runtime.pressSos().join()
                if (hold == i + 1) {
                    println("定格在第 ${i + 1} 步：${step.label}。界面保持这一帧。")
                    holdScreen(runtime)
                }
            }
            if (hold != null || freezeAt != null) return@runBlocking
            println("—— 第 $round 轮跑完，重播 ——")
        }
    }

    /** App 控制台那几行字，原样打出来：手机没到手之前，这就是装上去会看到的东西 */
    private fun printConsole(runtime: DemoRuntime) {
        runtime.consoleText().trimEnd().lines().forEach { println("        | $it") }
    }

    private fun Array<String>.argAfter(name: String): Int? {        val i = indexOf(name).takeIf { it >= 0 } ?: return null
        return getOrNull(i + 1)?.toIntOrNull()
    }

    /**
     * 彩排阈值。判据本身和生产一致（见 protocol/link-policy.md），只把「持续多久才认定」
     * 的窗口压短 —— 观众等不起 20 秒确认 + 60 秒 ACK 超时。真机演示换回 LinkPolicy()。
     */
    private fun demoPolicy() = LinkPolicy(
        cellBadConfirmMs = 6_000,
        cellGoodConfirmMs = 6_000,
        lowSignalGraceMs = 5_000,
        probeIntervalCellMs = 10_000,
        probeIntervalSatMs = 3_000,
        satAckTimeoutMs = 6_000,
    )

    /** 假时钟停了，但 1Hz 那帧还得接着发 —— 否则展示端 3 秒就把链路图标变灰，截图里全是断线水印 */
    private suspend fun holdScreen(runtime: DemoRuntime): Nothing {
        while (true) {
            runtime.pushState()
            delay(1_000)
        }
    }

    private const val TICK_MS = 600L

    private fun serveUi(preferred: Int, record: Path?): Int {
        val html = Paths.get("simulator", "screen.html")
            .let { if (Files.exists(it)) it else Paths.get("screen.html") }
        val body = Files.readAllBytes(html)
        val rec = record
        // 8080 上常有别的开发服务，往后找一个空的，别为此把整个演示起不来
        for (port in preferred until preferred + 10) {
            val srv = try {
                HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
            } catch (e: IOException) {
                continue
            }
            srv.createContext("/") { ex ->
                val path = ex.requestURI.path.removeSuffix("/")
                val (bytes, type) = when {
                    path.isEmpty() -> body to "text/html; charset=utf-8"
                    // 只认这一个文件名，不做路径拼接，免得变成任意文件读
                    rec != null && path == "/${rec.fileName}" ->
                        Files.readAllBytes(rec) to "application/x-ndjson; charset=utf-8"
                    else -> {
                        ex.sendResponseHeaders(404, -1)
                        ex.close()
                        return@createContext
                    }
                }
                ex.responseHeaders.add("Content-Type", type)
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            srv.start()
            return port
        }
        error("$preferred 往后 10 个端口都被占了")
    }

    /** 出站消息录成 jsonl，展示端 UI 用 ?replay= 直接回放 */
    private class ReplaySink(private val file: Path) {
        private val lock = Any()

        init {
            file.parent?.let { Files.createDirectories(it) }
            Files.write(file, ByteArray(0), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        }

        fun write(messages: List<ScreenMessage>) = synchronized(lock) {
            val text = messages.joinToString("") { it.encode() + "\n" }
            Files.write(file, text.toByteArray(), StandardOpenOption.APPEND)
        }
    }

    class FakeClock(startMs: Long) {
        var ms = startMs
            private set

        fun advance(d: Long) {
            ms += d
        }

        fun now() = ms
    }
}
