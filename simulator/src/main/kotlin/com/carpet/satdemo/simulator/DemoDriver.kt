package com.carpet.satdemo.simulator

import com.carpet.satdemo.core.ai.AiEndpoint
import com.carpet.satdemo.core.ai.AiGateway
import com.carpet.satdemo.core.ai.AudioChunks
import com.carpet.satdemo.core.ai.DialogBridge
import com.carpet.satdemo.core.ai.LocalRuleProvider
import com.carpet.satdemo.core.ai.OpenAiCompatProvider
import com.carpet.satdemo.core.ai.skillContext
import com.carpet.satdemo.core.engine.DemoEngine
import com.carpet.satdemo.core.ephemeris.EphemerisTracker
import com.carpet.satdemo.core.ephemeris.EphState
import com.carpet.satdemo.core.ephemeris.SimulatedEphemerisSource
import com.carpet.satdemo.core.link.CellStatus
import com.carpet.satdemo.core.link.LinkPolicy
import com.carpet.satdemo.core.link.SosCopy
import com.carpet.satdemo.core.modem.MonoClock
import com.carpet.satdemo.core.modem.SimulatedModem
import com.carpet.satdemo.core.modem.TpiTracker
import com.carpet.satdemo.core.protocol.CommandType
import com.carpet.satdemo.core.protocol.DialogDir
import com.carpet.satdemo.core.protocol.DialogPayload
import com.carpet.satdemo.core.protocol.DialogSource
import com.carpet.satdemo.core.protocol.GnssSource
import com.carpet.satdemo.core.protocol.MsgType
import com.carpet.satdemo.core.protocol.ScreenMessage
import com.carpet.satdemo.core.protocol.UiCard
import com.carpet.satdemo.core.protocol.UiState
import com.carpet.satdemo.core.runtime.DemoRuntime
import com.carpet.satdemo.core.screen.ScreenLinkServer
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * `--card NAME`   把圆屏那张卡钉死（status/sat/dialog/emotion/clock），不跟脚本轮播
 * `--ai PATH`     仓库外那份端点配置（base/key/model 三行）；给了且齐才走真模型，否则本机规则
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
        /**
         * 这一步把定位挪到围栏圆外面去。**围栏越界以前在彩排里根本不会发生**：
         * setFence 只收名字和开关，而 `onFenceDistance` 一个调用方都没有，
         * 于是 fence_exit 这条事件和屏上那句「已越界」全是死字。
         */
        val outOfFence: Boolean = false,
        /**
         * 这一步冒充圆屏上行问一句话（真机上是车里那个麦）。
         * 对话卡**只能**由一句真答完的话切出来，脚本钉它就等于回到"画面在轮播"。
         */
        val ask: String? = null,
    )

    private const val PASS_PERIOD_MS = 720_000L

    /**
     * 窗口只给 24 个假秒，比真星座的 4 分钟短 —— 因为脚本一轮才 84 秒。
     * 窗口一长，第 5 步之后整轮都算「正在过境」，[com.carpet.satdemo.core.screen.CardPolicy]
     * 就把卫星卡压在屏上不放，对话卡和脸那张永远轮不上：那正好是这次要治的"轮播"的反面。
     * 相位由 [SimulatedModem.replayPassWindow] 每轮拨回开头，两轮之间演的是同一个故事。
     */
    private const val PASS_WINDOW_MS = 24_000L

    /** 每轮开头，下一次过顶还剩这么多假秒 —— 正好是第 4 步的结尾 */
    private const val OPEN_IN_MS = 24_000L

    /** 围栏那个圆：圆心就是脚本里那个"西湖景区"的停车位，半径 600 米（约 4 分钟步行） */
    private const val FENCE_LAT = 30.2741
    private const val FENCE_LON = 120.1551
    private const val FENCE_RADIUS_M = 600.0
    /** 外面那一点：往北 0.01° ≈ 1112 米，出界但不至于跑到另一个市，回放里看着仍是一段路 */
    private const val OUTSIDE_LAT = 30.2841

    /**
     * 假时钟起点距下次过顶 [OPEN_IN_MS] —— 正好是第 4 步的结尾，
     * 于是「倒计时走完 → 窗口打开 → 锁定」连着发生，抓静帧不用凑时间。
     */
    private val T0 = run {
        val base = 1_795_200_000_000L
        base - base % PASS_PERIOD_MS + PASS_PERIOD_MS - OPEN_IN_MS
    }

    /**
     * 脚本只管把**场面**演出来：蜂窝几格、模块在不在、车在围栏里外、按钮按没按。
     * 它不再决定屏上画哪张卡 —— 那个决定归 [com.carpet.satdemo.core.screen.CardPolicy]，
     * 和真 App 上手机做的是同一份决定。想钉住某张卡给 Bring-up 用，走 `--card`。
     */
    private val SCRIPT: List<Step> = listOf(
        Step(6, "城市道路：蜂窝主链路，轨迹 1Hz 上报", GOOD),
        Step(6, "进停车场：信号 1 格，判据过半，提前切 PROBE", WEAK),
        Step(6, "彻底没网：探测包没等到 ACK，切 SAT", DEAD),
        Step(6, "SAT：星还没过顶，只剩下一条过境倒计时", DEAD),
        Step(6, "过境窗口打开：锁住卫星，星名和 CNR 上来", DEAD),
        Step(8, "按下 SOS，卫星确认送达", DEAD),
        Step(4, "平台回执横幅停在屏上", DEAD),
        // 这一段演的不是链路，是「终端没电睡了」那种现场：模组掉线、屏上只剩一张脸
        Step(12, "GT1005 掉线：心跳只能留在 App 队列，一条不能丢", DEAD, moduleOnline = false),
        Step(12, "蜂窝恢复：进 RECOVER，把攒下的补发出去", GOOD),
        // 对话这张卡的文本不来自 state 而来自另一条 `t=dialog` 帧：脚本只负责把场面造出来，
        // 句子由 DialogBridge 的主动扫描推下来，卡由"刚刚有下行对话"这条优先序切过去
        Step(10, "到景区了，家长问了一句，答句直接上屏", GOOD, ask = "现在链路什么情况"),
        // 越界单独一步，接在对话后面：先是"人和屏说话"，再是"屏自己有事要说"，两种驱动源分开演
        Step(8, "车开出围栏：fence_exit 弹横幅，轨迹照旧走蜂窝", GOOD, outOfFence = true),
    )

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val hold = args.argAfter("--hold")
        val freezeAt = args.argAfter("--freeze-sec")
        val blocked = args.contains("--blocked")
        /**
         * `--card NAME` 把卡片钉死成那一张，不再跟着脚本轮播。
         * 给真圆屏 Bring-up 用的：一次只验一张卡，屏上不对至少不用先怀疑是脚本切走了
         */
        val pinCard = args.argTextAfter("--card")?.let { name ->
            UiCard.values().firstOrNull { it.name == name.lowercase() }
                ?: error("--card 只认 ${UiCard.values().joinToString("/")}")
        }
        /**
         * 时区偏移由这台机器给，固件那边不许猜：彩排机常常就是 UTC，而真手机是东八区。
         * 钟面拿整帧的 ts 加上它拆时分秒 —— 屏自己算不出来，它没有「区号」这个概念
         */
        val tzMin = ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds / 60
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
        /**
         * 指令的处理收在 [DemoRuntime.handleScreenCommand] 一处，真机和彩排台共用那一份 ——
         * 驱动器再写一个 when 的话，彩排演过的就不是装到手机上的那个东西。
         * 这里只留控制台回显：不打印的话，彩排时看不见屏那侧按下了什么。
         */
        runtime.onCommand = { msg ->
            println("cmd      ${msg.command?.cmd}  args=${msg.command?.args}")
        }
        runtime.onCancelResult = { r ->
            println("cancel   ${SosCopy.cancelLine(r.send, r.alreadyHanded)}")
        }
        /**
         * 对话接线，和 [com.carpet.satdemo.app.AppRuntime] 同一份 [DialogBridge]、同一张事实表。
         * 驱动器不接这一段，明天现场就只能拿手机当服务端 —— 而彩排台本来就是给"手机还没到手"用的。
         * `turn_id` 由屏那侧生成，这里沿用；主动提醒的冷却跟着假时钟走。
         */
        val host = CoroutineScope(coroutineContext + SupervisorJob())
        /**
         * 答句由谁来写：`--ai 文件` / 环境变量 `CARPET_AI_CONFIG` 给了一个三行齐的端点就走真模型，
         * 否则还是那份本机规则。
         *
         * 配置文件**放在仓库外面**（key 不进 git，也不打印），三行 `base=` `key=` `model=`。
         * 没配齐就明说「未接真源」—— 现场最尴尬的是屏上念了一句漂亮话，观众以为接了大模型。
         */
        val aiPath = args.argTextAfter("--ai") ?: System.getenv("CARPET_AI_CONFIG")
        val endpoint = readAiEndpoint(aiPath)
        val provider = if (endpoint.configured) OpenAiCompatProvider(endpoint) else LocalRuleProvider()
        println(
            if (endpoint.configured) "AI       真源 ${endpoint.model} @ ${endpoint.baseUrl}"
            else "AI       未接真源（本机规则话术）—— 给环境变量 \$CARPET_AI_CONFIG 或 --ai <base/key/model 三行> 才换真模型"
        )
        if (aiPath != null && !endpoint.configured) println("AI       配置文件 $aiPath 读不齐三行，按未配处理")
        val gateway = AiGateway(provider = provider, log = { line -> println("ai       $line") })
        /**
         * `--voice`：答句合成一句中文推到圆屏（v3 的 `audio_chunk`）。
         * 三件事决定这段声音出不出来：这个开关、那块屏报没报 v3、合成成没成 ——
         * 任何一件不成都**一个字节不发**，而不是发半段（发半段是"这句话讲了一半卡住"那种现场事故）。
         * 文字答句走 dialog 那一帧，跟这里没有任何依赖，所以语音失败永远扣不下答句。
         *
         * 单独一条线程、还必须是**串行**的那一条：合成要 0.6~2 秒，搁在协程上下文里就把假时钟
         * 一起卡住（脚本的 600 毫秒一跳会变成"看模型心情"）；串行则两段声音不会叠在一起放。
         */
        val voiceOn = args.contains("--voice")
        val voice = VoiceTts(
            script = repoFile("tools", "tts-say.ps1"),
            workDir = Paths.get(System.getProperty("java.io.tmpdir")),
            note = { line -> println("语音     $line"); runtime.log(line) },
        )
        val voicePool = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "carpet-voice").apply { isDaemon = true }
        }
        if (voiceOn) println("语音     开 —— 优先 Edge 晓伊（说的时候要有网），断网自动退 Windows 离线音")
        val dialog = DialogBridge(
            gateway = gateway,
            facts = { runtime.skillContext(scene = "车") },
            push = { d -> runtime.pushDialog(d) },
            launch = { body -> host.launch { body() } },
            note = { line -> println("对话     $line"); runtime.log(line) },
            nowMs = { clock.ms },
            onAnswer = { d ->
                if (voiceOn) voicePool.execute {
                    if (!server.audioReady) {
                        println("语音     不发：连上来的屏没报 v3（hello.protocols 里没这一档），只留文字")
                        return@execute
                    }
                    val pcm = voice.pcm(d.text) ?: return@execute
                    val frames = AudioChunks.commands(d.turnId, pcm, clock.ms)
                    if (frames.isEmpty()) {
                        println("语音     这句超出屏侧缓冲，整段不发（文字照旧在屏上）")
                        return@execute
                    }
                    println("语音     ${d.turnId} ${frames.size} 帧推给屏")
                    server.broadcast(frames)
                }
            },
        )
        runtime.onDialog = { msg -> dialog.onScreenFrame(msg) }

        /**
         * 键盘当嘴：这个窗口里敲一行回车 = 一句提问，走的是脚本那句一模一样的路
         * （dir=up 回镜给屏 → 出答句 → dir=down 再回镜）。彩排台上只有一句写死的
         * 「现在链路什么情况」，真要说"问点别的"得有这一条。
         * turn_id 带启动随机前缀：契约要求跨重启唯一，重放同一句不该被屏的去重吃掉。
         */
        val boot = kotlin.random.Random.nextInt(100, 1000)
        Thread {
            val rd = System.`in`.bufferedReader()
            var n = 0
            while (true) {
                val q = rd.readLine()?.trim() ?: break
                if (q.isEmpty()) continue
                val turnId = "k-$boot-${++n}"
                // 先落一行再投递：万一 stdin 根本没读到（管道/后台跑法读不到终端），这行能把
                // "没读到"和"读到但没送出去"分开
                println("键盘    $turnId ← $q")
                // 和链路回调那一跳同一种调法：`runtime.onDialog` 也是从非协程的 ws 线程直接进来的，
                // 桥里自己 launch。在这里再套一层 host.launch 反而排不上（试过：读到了、没跑）。
                dialog.onScreenFrame(
                    ScreenMessage(
                        t = MsgType.dialog, seq = 0, ts = clock.ms,
                        dialog = DialogPayload(
                            turnId = turnId, dir = DialogDir.up, text = q, source = DialogSource.asr,
                        ),
                    )
                )
                // 这一条是给屏的，不是给桥的：真机上问句就是屏自己起的，它当然知道自己问了什么；
                // 键盘这句住在手机这侧，桥又按契约不把屏起的那句回镜，于是屏收到的关于这一轮的
                // 第一帧直接就是答句 —— 它分辨不出"有人问过"和"主动播报"，答句会被正在念的那句
                // 挤掉（2026-11-05 上板实测：额度那句 746 毫秒就被一条脚本线挡在门外）。
                // 补一条同 turn_id 的上行回显，这个判断不必让屏猜。
                runtime.pushDialog(
                    DialogPayload(
                        turnId = turnId, dir = DialogDir.up, text = q, source = DialogSource.asr,
                    )
                )
            }
        }.apply { isDaemon = true; name = "ask-stdin" }.start()
        println("提问    在这个窗口敲一行回车就是问一句；屏那边首屏那条带会先「想」再「说」")

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
        if (pinCard != null) println("模式：卡片钉在 $pinCard（表情仍由策略给），时区偏移 $tzMin 分")

        runtime.onFix(FENCE_LAT, FENCE_LON, GnssSource.phone, 1.2, 2.0)
        runtime.setFence(true, "西湖景区", FENCE_LAT, FENCE_LON, FENCE_RADIUS_M)
        runtime.setDemoMode(true)
        runtime.onPhoneBattery(78)
        // 屏上画哪张卡不再由脚本交代：脚本给场面，[com.carpet.satdemo.core.screen.CardPolicy] 给决定。
        // 这里只交时区（这台机器的），和 Bring-up 用的那一张钉卡
        if (pinCard != null) runtime.setUi(UiState(pinCard, null, tzMin)) else runtime.setScreenStyle(tzMin = tzMin)

        var round = 0
        while (true) {
            round++
            // 每一轮都把窗口相位拨回开头：不然第 2 轮起就整轮碰不上过境，
            // 那张卫星卡、那次 SOS、那句横幅只在第一轮演得出来（详见 SimulatedModem.replayPassWindow）。
            // 星历那份必须和模组一起拨，拨完还要强制重算一次 —— 它缓存的是上一轮算出来的窗
            modem.replayPassWindow(OPEN_IN_MS)
            ephemeris.replayPassWindow(OPEN_IN_MS)
            engine.reseedEphemeris()
            val roundStartMs = clock.ms
            SCRIPT.forEachIndexed { i, step ->
                modem.reachable = step.moduleOnline
                // 「家长问了一句」得真问一句。对话卡不是脚本钉的，它靠"刚有一句答完了"切过去，
                // 而 turn_id 必须每轮唯一 —— 复用旧的会被屏那侧的去重当成重发吃掉
                step.ask?.let {
                    dialog.onScreenFrame(
                        ScreenMessage(
                            t = MsgType.dialog, seq = 0, ts = clock.ms,
                            dialog = DialogPayload(
                                turnId = "s-$round-$i", dir = DialogDir.up,
                                text = it, source = DialogSource.asr,
                            ),
                        )
                    )
                }
                repeat(step.seconds) {
                    runtime.onCellSample(step.cell)
                    // 位置每拍都喂：越界判定是"位置变了吗"的函数，只在脚本开头喂一次的话
                    // 那一步之后的 fence_exit / fence_enter 一个都不会触发
                    runtime.onFix(if (step.outOfFence) OUTSIDE_LAT else FENCE_LAT, FENCE_LON,
                        GnssSource.phone, 1.2, 2.0)
                    runtime.tickOnce()
                    clock.advance(1_000)
                    if (freezeAt != null && (clock.ms - T0) / 1000 >= freezeAt) {
                        println("定格在 t=${(clock.ms - T0) / 1000}s（第 ${i + 1} 步：${step.label}）相位 ${engine.phase}")
                        printConsole(runtime)
                        holdScreen(runtime)
                    }
                    if ((clock.ms - T0) / 1000 % 10 == 0L) dialog.scanProactive()
                    delay(TICK_MS)
                }
                val d = runtime.lastUiDecision
                println("step ${i + 1}  轮内 t=${(clock.ms - roundStartMs) / 1000}s  ${step.label}  ->  ${engine.phase}" +
                    "  屏=${d?.card}/${d?.emotion}｜${d?.why}")
                printConsole(runtime)
                if (step.label.contains("SOS")) runtime.pressSos().join()
                // 彩排台没有云端推送，平台那一路的回执就由脚本在这一步替它到达。
                // 注意这改的是**场面**（回执到没到），不是屏上画哪张卡 —— 那条仍然归 CardPolicy
                if (step.label.contains("回执")) runtime.platformAck()
                if (hold == i + 1) {
                    println("定格在第 ${i + 1} 步：${step.label}。界面保持这一帧。")
                    holdScreen(runtime)
                }
            }
            if (hold != null) {
                // `--hold` 是**步号**不是秒数。传了范围之外的值时，以前 main 一退就完事：
                // 进程还活着、端口还开着、1Hz 却停了 —— 屏 3 秒打灰、10 秒重连，
                // 现场看起来像链路坏了，其实是我自己把彩排台跑成了僵尸。宁可定格在最后一帧。
                if (hold !in SCRIPT.indices) {
                    println("--hold $hold 不在 1..${SCRIPT.size} 里（这一项是**步号**，不是秒数）。按最后一帧定格，1Hz 继续发。")
                    holdScreen(runtime)
                }
                return@runBlocking
            }
            if (freezeAt != null) return@runBlocking
            println("—— 第 $round 轮跑完，重播 ——")
        }
    }

    /** App 控制台那几行字，原样打出来：手机没到手之前，这就是装上去会看到的东西 */
    private fun printConsole(runtime: DemoRuntime) {
        runtime.consoleText().trimEnd().lines().forEach { println("        | $it") }
        // 最近几条运行时日志。工程模式窗在手机上，彩排台这边也得能看到同一批话，
        // 否则"屏发来的帧解不开"这种只在链路上出现的事件，桌面上永远是盲区
        runtime.status.value.log.takeLast(3).forEach { println("        > $it") }
    }

    private fun Array<String>.argAfter(name: String): Int? {
        val i = indexOf(name).takeIf { it >= 0 } ?: return null
        return getOrNull(i + 1)?.toIntOrNull()
    }

    private fun Array<String>.argTextAfter(name: String): String? {
        val i = indexOf(name).takeIf { it >= 0 } ?: return null
        return getOrNull(i + 1)
    }

    /**
     * 仓库里的文件。`gradlew :simulator:run` 的工作目录是 simulator 那一层，写死相对路径
     * 就会「找不到合成脚本」；从当前目录往上找第一个命中这一串的目录，从哪儿起都对。
     * 找不到就原样返回相对路径 —— 让 [VoiceTts] 打印它那一句「找不到」，别在这里编一个假绝对路径。
     */
    private fun repoFile(vararg parts: String): Path {
        val resolve = { dir: Path -> parts.fold(dir) { a, b -> a.resolve(b) } }
        val start = generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
            .firstOrNull { Files.exists(resolve(it)) }
        return resolve(start ?: Paths.get(""))
    }

    /**
     * 读仓库外面那份端点配置：三行 `base=` / `key=` / `model=`，# 开头算注释。
     *
     * 为什么不写进 sdkconfig 那一侧、也不放仓库里的 example：key 进过一次 git 就当它泄露过。
     * 读不到、读不齐都只返回"没配"，让 [AiGateway] 退本机规则并把「未接真源」打在开工第一屏上。
     * 这一句永远不打印 key。
     */
    private fun readAiEndpoint(path: String?): AiEndpoint {
        if (path.isNullOrBlank()) return AiEndpoint()
        val lines = runCatching { Paths.get(path).toFile().readLines() }.getOrNull() ?: return AiEndpoint()
        val kv = lines.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .associate { it.substringBefore('=').trim().lowercase() to it.substringAfter('=').trim() }
        return AiEndpoint(
            kv["base"].orEmpty(), kv["key"].orEmpty(), kv["model"].orEmpty(),
            // 第四行是可选项：`thinking=disabled` 关掉网关那侧的思考。为什么压在三行配置里而不是代码里：
            // 这个字段不是 OpenAI 标准，只有实测过的那家网关才该带（见 [AiEndpoint.disableThinking]）
            disableThinking = kv["thinking"].equals("disabled", ignoreCase = true),
        )
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
