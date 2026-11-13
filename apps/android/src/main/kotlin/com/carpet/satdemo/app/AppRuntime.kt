package com.carpet.satdemo.app

import android.app.Application
import android.content.Context
import com.carpet.satdemo.core.ai.AiEndpoint
import com.carpet.satdemo.core.ai.AiGateway
import com.carpet.satdemo.core.ai.AzureSpeech
import com.carpet.satdemo.core.ai.DialogBridge
import com.carpet.satdemo.core.ai.MicUplink
import com.carpet.satdemo.core.ai.OpenAiCompatProvider
import com.carpet.satdemo.core.ai.PermissionTier
import com.carpet.satdemo.core.ai.ReplySource
import com.carpet.satdemo.core.ai.SiliconFlowAsr
import com.carpet.satdemo.core.ai.SkillContext
import com.carpet.satdemo.core.ai.screenSceneOf
import com.carpet.satdemo.core.ai.skillContext
import com.carpet.satdemo.core.ai.Turn
import com.carpet.satdemo.core.ai.TurnFrom
import com.carpet.satdemo.core.ai.TurnScene
import com.carpet.satdemo.core.devices.Device
import com.carpet.satdemo.core.engine.DemoEngine
import com.carpet.satdemo.core.ephemeris.EphState
import com.carpet.satdemo.core.ephemeris.EphemerisSource
import com.carpet.satdemo.core.ephemeris.EphemerisTracker
import com.carpet.satdemo.core.ephemeris.SimulatedEphemerisSource
import com.carpet.satdemo.core.link.CellStatus
import com.carpet.satdemo.core.modem.RawLine
import com.carpet.satdemo.core.modem.SimulatedModem
import com.carpet.satdemo.core.modem.TpiTracker
import com.carpet.satdemo.core.protocol.CommandType
import com.carpet.satdemo.core.protocol.DialogDir
import com.carpet.satdemo.core.protocol.DialogOrigin
import com.carpet.satdemo.core.protocol.DialogPayload
import com.carpet.satdemo.core.protocol.DialogSource
import com.carpet.satdemo.core.protocol.MsgType
import com.carpet.satdemo.core.protocol.ScreenMessage
import com.carpet.satdemo.core.runtime.DemoRuntime
import com.carpet.satdemo.app.ui.Availability
import com.carpet.satdemo.app.ui.Capability
import com.carpet.satdemo.app.ui.PrivacyTier
import com.carpet.satdemo.app.ui.Scene
import com.carpet.satdemo.app.ui.ScreenDialogLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.carpet.satdemo.core.link.SosCopy
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * **连接层持有者，不属于任何页面。**
 *
 * §5.3 硬要求 #2 说"切场景时链路不能断"，靠的就是这件事：runtime、蜂窝/定位监听、
 * 展示端服务器都挂在这里，Tab 和场景怎么换都不重建它们。原来那版把这些字段直接写在
 * MainActivity 里，于是旋转一次屏幕就得重连一次 —— 演示时那一下正好会把展示端踢下线。
 *
 * 前台服务那一层现在接上了（[DemoGuardService]）：守护开关打开时它把进程钉住，
 * 常驻通知念的就是台账上那两件事 —— 链路和额度。只挂到 Activity 之上是不够的，
 * 锁屏久了系统会把进程冻住，那时候 1Hz 那一圈会停、圆屏那头会变灰。
 */
class AppRuntime(private val host: Application) {

    /**
     * 演示默认跑假卫星模块：GT1005 到货前手机就能整套演。
     * BLE 连上 GT1005 后工程页开关切到真模组（见 [SwitchableModem]）。
     */
    val simModem = SimulatedModem(passWindowMs = ALWAYS_VISIBLE)
    val modem = SwitchableModem(simModem) { msg -> runtimeRef?.log(msg) }

    /** GT1005 的 BLE 管道与连接状态（工程页展示） */
    val blePipe by lazy { BleBytePipe(host) }
    private val _bleState = MutableStateFlow("idle")
    val bleState: StateFlow<String> = _bleState

    fun bleConnect() {
        if (_bleState.value == "linked") {
            blePipe.disconnect()
            _bleState.value = "closed"
            return
        }
        _bleState.value = "connecting"
        blePipe.onRawLine { line ->
            if (line.direction != RawLine.Direction.FROM_MODULE) return@onRawLine
        }
        val ok = blePipe.connect()
        if (!ok) _bleState.value = "failed"
        // 连接结果靠回调推进：轮询 BleBytePipe.state 同步给 UI。
        // 终态是 linked/failed/scan-failed/closed —— 注意 connect() 先置 "scanning"，
        // 轮询条件若只认 "connecting" 首轮就会退出，UI 永远卡在扫描
        scope.launch {
            while (_bleState.value !in setOf("linked", "failed", "scan-failed", "closed")) {
                _bleState.value = blePipe.state
                kotlinx.coroutines.delay(500)
            }
        }
    }

    private val prefs = host.getSharedPreferences("demo", 0)

    /** 车况养护推荐的那份状态。判据、勾选、录入都在这，规则本身在 `:core` */
    val maint = MaintStore(prefs)

    /** 星历替身。开关切到厂商 SDK 后它就退场，但「模拟未过顶」那个开关还得能改口，所以留着引用 */
    private var simEph: SimulatedEphemerisSource? = null
    private var ephSource: EphemerisSource? = null

    /**
     * 引擎建在 [CarpetApp] 起来的那一刻，不建在页面里 —— §5.3 硬要求 #2 要的就是这件事。
     *
     * 星历那条日志回调用 [runtimeRef] 而不是直接读 `runtime`：这个 lambda 是在
     * `runtime` 还没装配完的时候构造的，直接读会把自己的初始化绕回去。
     */
    private var runtimeRef: DemoRuntime? = null
    val runtime: DemoRuntime = DemoRuntime(buildEngine()).also { runtimeRef = it }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var location: LocationMonitor? = null
    private var monitor: NetworkMonitor? = null

    /** UI 侧的开关状态。放在这里而不是 Composable 里，是为了切 Tab 之后开关不丢 */
    data class Flags(
        val simulatedOffline: Boolean = false,
        val noPassWindow: Boolean = false,
        val gt1005Offline: Boolean = false,
        val acceptScreens: Boolean = false,
        val demoMode: Boolean = false,
        /** 工程模式页开着（产品界面被整体换掉） */
        val engineering: Boolean = false,
        /** 线路原文窗。和上面那个不是一回事：工程页是"我们对发生之事的说法"，这一窗是"线路上真正来过什么" */
        val passthrough: Boolean = false,
        /** GT1005 BLE 已连接后，把卫星模组从模拟切到真模组（VendorSdkModem on BleBytePipe） */
        val bleModem: Boolean = false,
    )

    private val _flags = MutableStateFlow(Flags(acceptScreens = prefs.getBoolean(KEY_ACCEPT_SCREENS, false)))
    val flags: StateFlow<Flags> = _flags

    init {
        // 上一个版本这个开关只活到进程结束：每次重开 App 屏都被「拒绝所有展示端接入」挡回去，
        // 现场表现是"明明配对过，第二天又连不上"，而界面上那个开关还停留在关闭。
        // 配过的屏是用户的长期决定，不是本次演示的临时状态，所以它跟其它开关不一样，要落盘。
        if (_flags.value.acceptScreens) runtime.setAcceptScreens(true)
    }

    /** 家长设的隐私档。生效档位 = 底座默认与这一档里更严的那个（§6 第 3 条） */
    private val _privacyChoice = MutableStateFlow(PrivacyTier.Guarded)
    val privacyChoice: StateFlow<PrivacyTier> = _privacyChoice

    fun setPrivacyChoice(tier: PrivacyTier) {
        _privacyChoice.value = tier
        runtime.log("家长隐私档 → ${tier.label}，生效档位取更严的那个")
    }

    /** 当前场景。场景切换只改这个数，不动连接（§5.3 硬要求 #1/#2） */
    private val _scene = MutableStateFlow(Scene.Vehicle)
    val scene: StateFlow<Scene> = _scene

    /**
     * 底部导航停在哪一页。放在这里而不是 Composable 里，是为了转屏之后回到原来那一页。
     * **导航收敛（原任务 #7）**：页面间跳转只许走 [openTab]，不许散写 `setTab(3)` ——
     * 底栏重排一次，魔法数字就会把"去设备中心"送错页。
     */
    enum class Tab { Home, Vehicle, Family, Me }

    private val _tab = MutableStateFlow(Tab.Home)
    val tab: StateFlow<Tab> = _tab

    fun setTab(t: Tab) {
        _tab.value = t
    }

    /** 页面间跳转的唯一口。新加一个"去某某页"的入口，从这里过，别直接写枚举名 */
    fun openTab(t: Tab) = setTab(t)

    /** 首页卡片流的隐藏项与排序（§5.1「可排序、可隐藏」） */
    private val _hidden = MutableStateFlow<Set<String>>(emptySet())
    val hiddenCards: StateFlow<Set<String>> = _hidden

    private val _order = MutableStateFlow<List<String>>(emptyList())
    val cardOrder: StateFlow<List<String>> = _order

    val status: StateFlow<DemoRuntime.Status> get() = runtime.status

    /** 星历用的是厂商 SDK 吗。只影响下一次 buildEngine，见那个开关的注释 */
    val vendorEph: Boolean get() = prefs.getBoolean(KEY_VENDOR_EPH, false)

    fun sceneOn(next: Scene) {
        _scene.value = next
    }

    fun toggleCard(key: String, hidden: Boolean) {
        _hidden.value = if (hidden) _hidden.value + key else _hidden.value - key
    }

    fun moveCard(key: String, up: Boolean) {
        val base = (_order.value + _scene.value.profile.cards).distinct()
        val i = base.indexOf(key)
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j < 0 || j >= base.size) return
        _order.value = base.toMutableList().apply { add(j, removeAt(i)) }
    }

    /** 首页卡片流的最终顺序：手工排过的排前面，其余按场景默认顺序，再减去隐藏的、减去首屏钉住的那两张 */
    fun orderedCards(scene: Scene): List<String> {
        val saved = _order.value.filter { scene.profile.cards.contains(it) }
        val rest = scene.profile.cards.filterNot { saved.contains(it) }
        return (saved + rest).filterNot { _hidden.value.contains(it) || PINNED.contains(it) }
    }

    /** 编辑卡片流时列的清单。**必须含被隐藏的**，否则隐藏的卡从管理页上也消失，就再也点不回来了 */
    fun managedCards(scene: Scene): List<String> = scene.profile.cards.filterNot { PINNED.contains(it) }

    /**
     * 能力可用性。§5.3 硬要求 #3「卡片按能力出现，不按菜单出现」的判据在这一处，
     * 也只有一个地方能声明"这件事现在是假的" —— 别在卡片里各写各的。
     *
     * 注意 **[Availability.Absent] 只留给"这条链路根本不存在"**：GT1005 掉线不是 Absent，
     * 那是卫星守护卡上那行红字，卡片必须还在（用户最需要看见它的就是这一刻）。
     */
    fun availability(c: Capability): Availability = when (c) {
        Capability.SatelliteLink -> Availability.Live
        // 星历替身给的是算得出来的窗口，但周期和卫星是编的，所以它是 Mocked 不是 Live
        Capability.Ephemeris -> if (vendorEph) Availability.Live else Availability.Mocked
        Capability.PositionSource -> if (locationActive) Availability.Live else Availability.Mocked
        Capability.Geofence -> Availability.Live
        Capability.Dock -> Availability.Live
        // 网关这条链路是真的（路由、权限拦截、按状态作答）；**模型是不是真的看有没有配端点**，
        // 那件事不在这里标，逐条回答上标 source —— 否则一条没配端点会把整块能力判成假
        Capability.Assistant -> Availability.Live
        Capability.Sentinel,
        Capability.VehicleBus, Capability.ChildDevice, Capability.HealthBand,
        Capability.AiAgent, Capability.Account,
        -> Availability.Mocked
    }

    private var locationActive = false

    private var monitorsUp = false

    /**
     * 起监听。**可以重复调用**：定位权限是异步批下来的，
     * 第一次调用时 [LocationMonitor.start] 会因为没权限直接空跑，批下来之后必须能再试一次。
     */
    fun startMonitors() {
        // 起来就把 1Hz 那圈跑上：状态卡要在首屏自己动，等用户按"启动服务"就变成又要学一遍旧控制台的操作。
        // 退到后台会被 stop 掉，所以这一句在 monitorsUp 之外，每次回到前台都能把自己接回来
        if (!runtime.status.value.running) setGuard(true) else retryGuard()
        if (!monitorsUp) {
            monitorsUp = true
            location = LocationMonitor(host) { lat, lon, source, hdop, age ->
                locationActive = true
                runtime.onFix(lat, lon, source, hdop, age)
            }
            monitor = NetworkMonitor(host) { sample: CellStatus ->
                runtime.onCellSample(
                    if (_flags.value.simulatedOffline) {
                        sample.copy(hasInternetCapability = false, validated = false)
                    } else {
                        sample
                    },
                )
            }.also { it.start(scope) }
        }
        location?.start()
        startAiLoop()
        // 端点存过、但网关换了口径或密钥过期：不点「试一次」就永远不报，首屏只写着"未接模型"。
        // 每次冷启动只自动试一次，**不为失败重试** —— 那会每十秒打一次网关
        if (!probedOnce) probeModel(quiet = true)
    }

    /** 这一进程里有没有自动试过端点。用户手动点「试一次」不受它限制 */
    private var probedOnce = false

    fun setFlag(next: Flags) {
        val prev = _flags.value
        _flags.value = next
        if (prev.simulatedOffline != next.simulatedOffline) {
            runtime.log(if (next.simulatedOffline) "已模拟断网" else "已恢复蜂窝")
        }
        if (prev.noPassWindow != next.noPassWindow) {
            modem.passWindowMs = if (next.noPassWindow) 0 else ALWAYS_VISIBLE
            // 星历替身要跟着改口：屏上说"三分钟后过境"、模组却说"现在就能发"，
            // 这种自己打自己的帧在彩排时最容易漏
            simEph?.state = if (next.noPassWindow) EphState.CALCULATION_FAILED else EphState.SUCCESS
            runtime.log(if (next.noPassWindow) "已切到「没过顶」：MCQ 全部 NO_ACK" else "已恢复：过境窗口常开")
        }
        if (prev.gt1005Offline != next.gt1005Offline) {
            modem.reachable = !next.gt1005Offline
            runtime.log(if (next.gt1005Offline) "GT1005 已断开，心跳只能留在 App 队列" else "GT1005 已恢复")
        }
        if (prev.acceptScreens != next.acceptScreens) {
            runtime.setAcceptScreens(next.acceptScreens)
            prefs.edit().putBoolean(KEY_ACCEPT_SCREENS, next.acceptScreens).apply()
        }
        if (prev.demoMode != next.demoMode) runtime.setDemoMode(next.demoMode)
        if (prev.bleModem != next.bleModem) switchBleModem(next.bleModem)
    }

    /** BLE 已连上且开关打开 → 换成真模组；关掉 → 回模拟模组 */
    private fun switchBleModem(on: Boolean) {
        if (on) {
            if (_bleState.value != "linked") {
                runtime.log("BLE 未连接，无法切真模组")
                return
            }
            val vendor = VendorSdkModem(blePipe)
            vendor.start()
            runCatching {
                scope.launch { vendor.bringUp() }
            }
            modem.switchTo(vendor, "已切到 GT1005 真模组（BLE）")
        } else {
            modem.switchTo(simModem, "已切回模拟模组")
        }
    }

    fun setVendorEph(on: Boolean) {
        prefs.edit().putBoolean(KEY_VENDOR_EPH, on).apply()
        // 不当场切换：引擎手里的 tracker 是构造期注入的，运行时换源会让屏上留着上一源的窗口
        runtime.log(if (on) "已记下：下次启动用厂商星历 SDK" else "已记下：下次启动回到星历替身")
    }

    fun pressSos() {
        _cancelLine.value = null
        runtime.pressSos()
    }

    /**
     * SOS 长按的三段倒计时。
     *
     * 状态**扣在 AppRuntime 而不是 Composable 里**：手指按着的时候切了 Tab，
     * 倒计时如果被一起销毁，用户会读成"按失败了"，然后真的再按一次 ——
     * 而那一次可能已经发出去了。§6.1 要求这条线可取消，可取消的前提是它有个不随页面消失的家。
     */
    data class Arm(val leftMs: Long) {
        val active: Boolean get() = leftMs > 0

        /** 已经走完的比例，给环用 */
        val fraction: Float get() = 1f - (leftMs.toFloat() / ARM_MS).coerceIn(0f, 1f)

        /** 三段里现在该亮到第几段（0=一段都没走完） */
        val segment: Int get() = ((ARM_MS - leftMs + SEG_MS - 1) / SEG_MS).toInt().coerceIn(0, 3)
    }

    private val _arm = MutableStateFlow(Arm(0))
    val arm: StateFlow<Arm> = _arm

    private var armJob: Job? = null

    /** 手指按下。走到 0 才算触发，中途抬手一律当没按过 */
    fun pressDownSos() {
        if (armJob != null) return
        armJob = scope.launch {
            var left = ARM_MS
            while (left > 0) {
                _arm.value = Arm(left)
                delay(TICK_MS)
                left -= TICK_MS
            }
            armJob = null
            _arm.value = Arm(0)
            pressSos()
        }
    }

    /** 抬手、划出、被系统抢走焦点 —— 三种中断走同一条路 */
    fun releaseSos() {
        val wasArmed = _arm.value.active
        armJob?.cancel()
        armJob = null
        _arm.value = Arm(0)
        if (wasArmed) runtime.log("长按中断，SOS 没发出")
    }

    /**
     * 撤销 SOS。结果那行人话存成 [cancelLine]（同样出自 [SosCopy]），
     * 它必须活在页面之外：撤销要等一次过境，用户中途去看别的 Tab 是常态。
     */
    private val _cancelLine = MutableStateFlow<String?>(null)
    val cancelLine: StateFlow<String?> = _cancelLine

    fun cancelSos() {
        _cancelLine.value = null
        runtime.cancelSos { r -> _cancelLine.value = SosCopy.cancelLine(r.send, r.alreadyHanded) }
    }

    /**
     * 一次性反馈。以前这类话直接进 `runtime.log`，而那条日志**只在工程模式页显示** ——
     * 也就是说用户按下"远程熄火""语音输入"之后，正常界面上什么都没发生。
     * 按 Material 的分工，瞬时反馈归 Snackbar，日志归控制台，两边各留一份。
     */
    private val _snack = MutableStateFlow<String?>(null)
    val snack: StateFlow<String?> = _snack

    /** 说一句话给用户听，同时把它记进工程日志 */
    fun say(line: String) {
        _snack.value = line
        runtime.log(line)
    }

    fun snackShown() {
        _snack.value = null
    }

    /** 设备台账（§8 R1 第一件事）。切 Tab、切场景都不会让它空一格 */
    val devices: StateFlow<List<Device>> get() = runtime.devicesSnapshot

    // ─────────────────────────────────────────────────────────────────────────────
    // AI 助手。对话是首页两个主区之一（V1.7 重排），所以它跟链路一样挂在连接层，
    // 不挂在页面上 —— 用户发完问题去切 Tab，回答回来时不该丢上下文。
    // ─────────────────────────────────────────────────────────────────────────────

    private val _endpoint = MutableStateFlow(loadEndpoint())
    val aiEndpoint: StateFlow<AiEndpoint> = _endpoint

    init {
        // Azure Speech F0（D13 正路）：region/key 从 BuildConfig 灌进 :core 的合成器。
        // 没配就是空串，AzureSpeech.configured 不过，NetVoice 自动跳过这条
        AzureSpeech.region = BuildConfig.AZURE_SPEECH_REGION
        AzureSpeech.speechKey = BuildConfig.AZURE_SPEECH_KEY
        // 硅基流动 ASR（手机侧语音输入）：同一个纪律，没配 key 麦克风就退回"未接"
        SiliconFlowAsr.apiKey = BuildConfig.SILICONFLOW_ASR_KEY
    }

    private val aiProvider = OpenAiCompatProvider(_endpoint.value)

    /**
     * 权限档默认只到 [PermissionTier.ReadLocal]：**能说话不等于能动手**。
     * 出网发送和不可逆动作要在「我的」里明确放开，SOS 那一档就算放开也不许由对话触发（§6.1）。
     */
    private val ai = AiGateway(
        provider = aiProvider,
        initialGranted = runCatching {
            PermissionTier.valueOf(prefs.getString(KEY_AI_TIER, PermissionTier.ReadLocal.name) ?: "ReadLocal")
        }.getOrDefault(PermissionTier.ReadLocal),
        log = { line -> runtime.log(line) },
    )

    /**
     * 当前权限档。**必须是 StateFlow**：这一档在「我的」页上就地改，普通 getter 改完界面不会重画，
     * 用户会以为点了没反应，然后连点五下 —— 每一下都写了一次 SharedPreferences。
     */
    private val _aiGranted = MutableStateFlow(ai.granted)
    val aiGranted: StateFlow<PermissionTier> = _aiGranted

    /**
     * 屏幕语音。**答句的嘴在手机上**：圆屏只负责放，合成和切块都在这台设备里
     * （见 [ScreenVoice] 那段为什么桌面有声音、手机没有）。
     */
    private val voice = ScreenVoice(
        host = host,
        // **每次用时重新读**：`runtime.server` 在每次 start 之后是一个新对象，
        // 抓一个引用下来，第二次启动之后的声音就会推给上一代那个已经关掉的服务器
        server = { runtimeRef?.server },
        log = { line -> runtimeRef?.log(line) },
        // 选过的引擎和音色是用户的长期决定，和"允许圆屏接入"一样要跨进程活着
        initialEngine = prefs.getString(KEY_VOICE_ENGINE, null),
        initialTone = VoiceTone.of(prefs.getString(KEY_VOICE_TONE, null)),
        initialFace = prefs.getString(KEY_VOICE_FACE, null),
    )

    /**
     * 对话的唯一一条接线：入账 → 该不该触发技能 → 答 → 回镜给圆屏。
     * 逻辑本体在 `core/ai/DialogBridge`（那边才有真单测，彩排台和手机跑的也才是同一份），
     * 这里只填三个口子：事实表、往屏发一帧、宿主自己的协程作用域。
     */
    private val dialog = DialogBridge(
        gateway = ai,
        facts = { aiContext() },
        push = { d ->
            _screenLine.value = ScreenDialogLine(d.source, System.currentTimeMillis())
            runtime.pushDialog(d)
        },
        launch = { body -> scope.launch { body() } },
        note = { line -> runtime.log(line) },
        // 屏起的那一轮归哪一页，读屏自己报上来的那个字段。屏没报过页就不归页 ——
        // 那种句子只在首页的总时间线里出现，比"猜一个亲子"诚实
        screenScene = { screenSceneOf(runtime.status.value.screenPage) },
        // 答句落地之后合成语音推给屏。**问句不回镜的那一侧也不会走到这儿**，
        // 所以屏上不会把车主自己问的那句又念一遍
        onAnswer = { d -> voice.speak(d.turnId, d.text, d.origin) },
    )

    /** 唯一的那份对话时间线。圆屏和手机都只是它的两个入口 */
    val chat: StateFlow<List<Turn>> = dialog.log.turns

    /** 发出去的那句还在等回答。首屏那条占位气泡靠它，不然按了发送屏幕不动 */
    val aiThinking: StateFlow<Boolean> get() = dialog.thinking

    /**
     * 上一次真模型没答上来的具体原因（404 / 超时 / 密钥…）。
     * 「未接模型」只是状态，车主还得知道下一步是改地址还是换网络，所以这一句要原样摆到界面上。
     */
    private val _aiError = MutableStateFlow<String?>(null)
    val aiError: StateFlow<String?> = _aiError

    /** 正在打那句自检请求。界面据此把按钮改成"正在试…"，避免连点发出五次 */
    private val _aiProbing = MutableStateFlow(false)
    val aiProbing: StateFlow<Boolean> = _aiProbing

    /**
     * 端点的初值。**出包时就该能直接用**（V1.18）：地址/密钥/模型烧在 BuildConfig 里，
     * 首次起来写进 prefs 一次，之后 prefs 优先 —— 工程模式里手改过的不被覆盖。
     *
     * 公开仓库里没有 `local.properties` 里的那一组，BuildConfig 三个字段是空串，
     * 这时行为与旧版一致：读 prefs，读不到就走本机规则。
     *
     * 种子用版本号当标记：换了一家网关/一把新密钥就改 [AI_SEED]，老机器上那份旧配置才会被顶掉。
     * 不能每次启动都盖 —— 那样工程模式里调的模型活不过一次重启。
     */
    private fun loadEndpoint(): AiEndpoint {
        val baked = AiEndpoint(BuildConfig.AI_BASE_URL, BuildConfig.AI_API_KEY, BuildConfig.AI_MODEL, disableThinking = true)
        // 我们发的是纯文本问答、不回收思维链；这家网关在思考模式下会要求把 thinking 回传，
        // 不回传就 400。默认关掉思考，网关不认这一项时 provider 会去掉重发
        if (baked.configured && prefs.getString(KEY_AI_SEED, null) != AI_SEED) {
            prefs.edit()
                .putString(KEY_AI_URL, baked.baseUrl)
                .putString(KEY_AI_KEY, baked.apiKey)
                .putString(KEY_AI_MODEL, baked.model)
                .putString(KEY_AI_SEED, AI_SEED)
                .apply()
        }
        return AiEndpoint(
            baseUrl = prefs.getString(KEY_AI_URL, "") ?: "",
            apiKey = prefs.getString(KEY_AI_KEY, "") ?: "",
            model = prefs.getString(KEY_AI_MODEL, "") ?: "",
            disableThinking = true,
        )
    }

    /**
     * 屏收到的最后一帧下行对话。首页那格镜像要照屏的规矩画出"手机在说 · …"那一行，
     * 而那行只由**屏收到的帧**决定（屏自己没收到，屏上就是空的），所以记在 push 的口子上。
     */
    private val _screenLine = MutableStateFlow<ScreenDialogLine?>(null)
    val screenLine: StateFlow<ScreenDialogLine?> = _screenLine

    /** 工程模式/演示要用：屏在不在、能不能说话 */
    val screenDialog: Boolean get() = runtime.screenCanTalk

    /** 屏谈下来的契约版本（0 = 没连上）。语音要 ≥3，这句话得能摆到界面上 */
    val screenProtocol: Int get() = runtime.screenProtocol

    /**
     * 最近一次答句**为什么没送到屏上**。null = 最近一次是好的，或者还没念过。
     *
     * 这一格存在的理由就是"没声音"这件事：屏只报到 v2 时旧实现静默返回，界面上什么都看不见。
     * 现在这条原因直接摆到设置页，而且它说的下一步是什么（刷固件 / 去配对 / 换引擎）。
     */
    val voiceIssue: StateFlow<String?> get() = voice.issue

    /** 这台手机念答句用的 TTS 引擎（系统引擎，不是桌面那侧的 Edge） */
    val voiceEngine: StateFlow<String?> get() = voice.engine

    /**
     * 最近一句是谁念的：`net` = 网络合成，`system` = 本机引擎。
     * 用户问"现在你接的是哪个声音"，这一格就是答案
     */
    val voiceSource: StateFlow<String?> get() = voice.source

    /** 这台手机上装了哪些中文引擎。换一台可能就有更甜的女声，不替用户认定默认最好 */
    val voiceEngines: StateFlow<List<VoiceEngine>> get() = voice.engines

    /**
     * 换了引擎之后 [voiceEngine] 报的是**新引擎自己的默认值**，不是用户选的那个包名。
     * 界面要能勾中"用户选过的那一台"，所以这里单独存一份选择（null = 系统默认）
     */
    private val _voiceChoice = MutableStateFlow(prefs.getString(KEY_VOICE_ENGINE, null))
    val voiceChoice: StateFlow<String?> = _voiceChoice

    /** 当前音色档。可爱女声不是换模型，是同一句合成成什么音高语速 */
    private val _voiceTone = MutableStateFlow(VoiceTone.of(prefs.getString(KEY_VOICE_TONE, null)))
    val voiceTone: StateFlow<VoiceTone> = _voiceTone

    /** 手机起头那一轮由哪个设备发声（owner 2026-11-11 拍的规矩）。默认圆屏，落盘 */
    private val _answerDevice = MutableStateFlow(
        run {
            val saved = prefs.getString(KEY_ANSWER_DEVICE, null)
            ScreenVoice.ScreenDevice.entries.firstOrNull { it.name == saved } ?: ScreenVoice.ScreenDevice.screen
        },
    )
    val answerDevice: StateFlow<ScreenVoice.ScreenDevice> = _answerDevice

    fun setAnswerDevice(next: ScreenVoice.ScreenDevice) {
        _answerDevice.value = next
        voice.setScreenDevice(next)
        prefs.edit().putString(KEY_ANSWER_DEVICE, next.name).apply()
        runtime.log("答句发声设备 → ${next.label}")
    }

    /** 这台引擎里的中文人声。空 = 引擎没报清单；这也是"默认给的是男声"最常见的坑 */
    val voiceFaces: StateFlow<List<VoiceFace>> get() = voice.faces

    /** 用户选过的发音人（引擎自己那个 name）。null = 引擎默认 */
    private val _voiceFaceChoice = MutableStateFlow(prefs.getString(KEY_VOICE_FACE, null))
    val voiceFaceChoice: StateFlow<String?> = _voiceFaceChoice

    /** 换发音人。同一台引擎里换人，比换引擎的动静小，通常也最接近"可爱女声" */
    fun setVoiceFace(name: String?) {
        _voiceFaceChoice.value = name
        prefs.edit().putString(KEY_VOICE_FACE, name).apply()
        voice.setFace(name)
    }

    /** 换语音引擎。[engineName] 为 null = 回到系统默认那台 */
    fun setVoiceEngine(engineName: String?) {
        _voiceChoice.value = engineName
        prefs.edit().putString(KEY_VOICE_ENGINE, engineName).apply()
        voice.setEngine(engineName)
    }

    /** 换音色档，立刻用新音高语速试听一句，用户不用再去点那颗试听键 */
    fun setVoiceTone(tone: VoiceTone) {
        _voiceTone.value = tone
        prefs.edit().putString(KEY_VOICE_TONE, tone.id).apply()
        voice.setTone(tone)
    }

    /** 本机试听有没有在念 */
    val voicePreviewing: StateFlow<Boolean> get() = voice.previewing

    /** 在手机上试听一句：不经过圆屏，用来先把"引擎有没有声"和"圆屏收没收"分开 */
    fun previewVoice(text: String = "我在。信号正常，卫星还有十二分钟过顶。") {
        voice.speakOnPhone(text)
    }

    /**
     * 模型端点自检：**直接打一次真请求**，不经过技能路由、不经过对话记录。
     *
     * 用户填了端点、界面上每次仍是「规则兜底」，却看不到原因 —— 因为旧版只有真问一句才会去试，
     * 而问的那句可能命中了技能、模型只是没轮上。这里用一句最小的自检文去试：通了就写一行日志，
     * 不通就把 HTTP 状态 / 超时 / DNS 原样摆到首页。圆屏在不在都不影响这一步。
     */
    fun probeModel(quiet: Boolean = false) {
        if (_aiProbing.value) return
        probedOnce = true
        if (!_endpoint.value.configured) {
            // 冷启动时不把"还没配"写成红色报错：空态由卡片上那句中性提示负责，
            // 红线只留给"配了也通不了"（那才是用户需要动手的）
            if (!quiet) {
                _aiError.value = missingOf(_endpoint.value)
                say("模型自检：${missingOf(_endpoint.value)}")
            }
            return
        }
        _aiProbing.value = true
        _aiError.value = null
        scope.launch {
            runtime.log("模型自检：用 ${_endpoint.value.model} 发一句最小请求")
            val answer = aiProvider.complete(
                facts = "（自检请求，不是真实车况）",
                history = emptyList(),
                question = "只回两个字：在。",
            )
            _aiProbing.value = false
            // 地址写错被自检纠回来：直接落盘，否则下一次开 App 还是那条错地址。
            // 这一步只在**真通了**之后做 —— 没通就改用户填的东西，等于替他瞎猜
            val fixed = aiProvider.correctedBase
            if (answer != null && fixed != null) {
                val current = _endpoint.value
                val next = current.copy(baseUrl = fixed)
                _endpoint.value = next
                aiProvider.setEndpoint(next)
                prefs.edit().putString(KEY_AI_URL, next.baseUrl).apply()
                runtime.log("模型自检：地址已按网关口径改成 $fixed")
            }
            _aiError.value = aiProvider.lastError
            runtime.log(
                if (answer != null) "模型自检：通了，回了「${answer.take(24)}」"
                else "模型自检：没通 —— ${aiProvider.lastError ?: "原因未知"}",
            )
        }
    }

    /**
     * 端点缺哪一项，**就说缺哪一项**。
     *
     * 旧的那句"地址 / 密钥 / 模型名少填了一项"在三项全空和三缺一时长得一模一样 ——
     * 而这正是现场最常见的一态（新装、或换了包名 SharedPreferences 没跟上）。
     * 车主照着这句话去改，改哪一项都是猜。
     */
    private fun missingOf(e: AiEndpoint): String {
        val miss = buildList {
            if (e.baseUrl.isBlank()) add("地址")
            if (e.apiKey.isBlank()) add("密钥")
            if (e.model.isBlank()) add("模型名")
        }
        return "这个版本没带 AI 连接信息（缺" + miss.joinToString("、") + "），先按手机上的规则回答"
    }

    private var aiLoop: Job? = null

    /**
     * 手机上问一句。[turnId] 由调用方给：圆屏起的那一轮必须沿用它那个 id，
     * 否则手机把问和答记成两轮，屏上那条追问也钉不回原来的对话。
     *
     * [scene] 是**从哪一页发的问**：车辆页发的问和它的答都只出现在车辆页那串里
     * （首页那条总时间线照常全显）。谁问的记给谁，不是"最近在看哪个 Tab"——
     * 回答回来时用户可能已经切走了，那一条也不该跑到别的页里去。
     */
    fun askAi(question: String, via: DialogOrigin = DialogOrigin.phone, turnId: String? = null, scene: TurnScene? = null) {
        scope.launch {
            dialog.ask(question, via, turnId, scene)
            // 每一次问完把失败原因带上来。成功时 provider 会清掉它，界面上的那句提示也就一起消失
            _aiError.value = ai.lastModelError
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 手机侧语音输入（硅基流动 ASR）。圆屏的麦克风链路在板子上还没修通（README §2：
    // muse_audio_read() 开机 5.7s 后就失败），所以"说话"这一半先落在手机上：
    // 按住麦克风 → 录 16k WAV → SenseVoiceSmall 转写 → 文本直接进对话，和打字同一入口。
    // 转写结果走 askAi 本身，不另开通道 —— 屏上镜像、技能路由全部白得。
    // ─────────────────────────────────────────────────────────────────────────────

    private val mic = MicRecorder()

    private val _listening = MutableStateFlow(false)
    /** 麦克风正在录音，输入框那枚按钮拿它画"按住说话中"的状态 */
    val listening: StateFlow<Boolean> = _listening

    private val _transcribing = MutableStateFlow(false)
    /** 转写进行中（松手后到出文本之间），UI 用它挡住重复按 */
    val transcribing: StateFlow<Boolean> = _transcribing

    /** ASR 有没有配好 key：没配的时候按钮还是"未接"那套 */
    val voiceInputConfigured: Boolean get() = SiliconFlowAsr.configured

    /** UI 层的权限询问：有没有录音权限（没有就由按钮去弹系统对话框） */
    fun micPermissionGranted(ctx: Context): Boolean = mic.hasPermission(ctx)

    /**
     * 按下麦克风。返回 false = 没权限或录音起不来，调用方直接给人话提示。
     */
    fun startVoiceInput(): Boolean {
        if (_transcribing.value) return false
        if (!voiceInputConfigured) {
            say("语音输入没配 key：local.properties 里加 siliconflow.asrKey 再打包")
            return false
        }
        val ok = mic.start()
        if (!ok) say("麦克风没起来：检查有没有给录音权限")
        _listening.value = ok
        return ok
    }

    /**
     * 松开麦克风：取 WAV → 后台转写 → 文本当一句用户提问走 [askAi]。
     * 录得太短（<0.4s）当成误触，不出题、不报错。
     */
    fun stopVoiceInput(scene: TurnScene? = null) {
        if (!_listening.value) return
        _listening.value = false
        val wav = mic.stop() ?: return
        // 16k/16bit/单声道 = 32KB/s。0.4s 以下基本是碰了一下
        if (wav.size < 32 * 1024 * 2 / 5) return
        _transcribing.value = true
        scope.launch(Dispatchers.IO) {
            val text = SiliconFlowAsr.transcribe(wav)
            _transcribing.value = false
            if (text.isNullOrBlank()) {
                say("没听清：转写失败或这句话是空的")
            } else {
                runtime.log("语音输入：「$text」")
                withContext(Dispatchers.Main) { askAi(text, scene = scene) }
            }
        }
    }

    /** 取消这次录音（用户拖出去松手），不出题 */
    fun cancelVoiceInput() {
        if (!_listening.value) return
        _listening.value = false
        mic.cancel()
    }

    /**
     * 工程模式的对话自检：**投一帧"圆屏说的话"进来，走的是真链路同一段代码**
     * （[DialogBridge.onScreenFrame]），不是另写一遍好让页面上有东西可点。
     *
     * 明天圆屏到货前这三颗键就能验完整条：入账 → 过闸 → 触发技能 → 镜像回屏。
     * 到货后表现不对，先按它一次，能立刻分清是链路的问题还是这段逻辑的问题。
     */
    fun simulateScreenQuestion(text: String, source: DialogSource = DialogSource.asr) {
        runtime.log("自检：模拟圆屏说「$text」（${source.name}）")
        dialog.onScreenFrame(
            ScreenMessage(
                t = MsgType.dialog,
                seq = 0,
                ts = System.currentTimeMillis(),
                dialog = DialogPayload(
                    turnId = "t-sim-" + System.nanoTime().toString(36),
                    dir = DialogDir.up,
                    text = text,
                    source = source,
                    origin = DialogOrigin.screen,
                ),
            ),
        )
    }

    private var greeted = false

    /**
     * 首屏那一句开场白。走记录而不是硬塞进 UI，是为了让它也镜像得到圆屏。
     *
     * **只在真接了模型之后才说**。没接的时候它是一句自我介绍，把首屏撑到三行，
     * 内容还是"我会答这五个词"—— 那五个词就在输入框上面一排，不需要再念一遍。
     * 空态由界面上那句"还没有对话"负责。
     */
    private fun greet() {
        if (greeted || !_endpoint.value.configured) return
        greeted = true
        dialog.log.append(
            Turn(
                from = TurnFrom.Ai,
                text = "我在。问「链路」「过境」「额度」「圆屏配对」「车在哪」都能答。",
                source = ReplySource.LocalRule,
                turnId = "t-greet",
            ),
        )
    }

    /** 端点配置。**只填不全就当作没配**：宁可显示"未接真源"，也不要现场等一次 12 秒超时 */
    fun setAiEndpoint(url: String, key: String, model: String) {
        val next = AiEndpoint(url.trim(), key.trim(), model.trim(), disableThinking = true)
        _endpoint.value = next
        aiProvider.setEndpoint(next)
        prefs.edit().putString(KEY_AI_URL, next.baseUrl).putString(KEY_AI_KEY, next.apiKey)
            .putString(KEY_AI_MODEL, next.model).apply()
        runtime.log(if (next.configured) "模型端点已配：${next.model}" else "模型端点未配齐，回到本机规则")
        // 上一版换完端点旧报错还挂在首页上：用户以为新端点也没通，其实还没试过
        _aiError.value = null
        greet()
        // 填完就立刻试一次，不必等用户先问一句才知道通没通
        if (next.configured) probeModel()
    }

    fun setAiGranted(tier: PermissionTier) {
        ai.setGranted(tier)
        _aiGranted.value = ai.granted
        prefs.edit().putString(KEY_AI_TIER, tier.name).apply()
    }

    /**
     * 技能清单 + 当前这一档准不准。**逐条摆出来**：只写一句"权限已放开"的话，
     * 用户不知道放开的是什么，出问题时也读不出是哪一条越了档。
     */
    data class AiSkillRow(val title: String, val needs: PermissionTier, val allowed: Boolean)

    fun aiSkills(): List<AiSkillRow> = ai.skills.all.map { AiSkillRow(it.title, it.needs, ai.can(it.id)) }

    /** 注册表 id → 人话名字。界面上只许出现后者：`dock.pair` 是我们的键名，不是车主的词 */
    fun skillTitle(id: String): String = ai.skills.all.firstOrNull { it.id == id }?.title ?: id

    /** 事实表：本体在 `core/ai/Facts`，和桌面彩排台共用同一份实现 */
    fun aiContext(): SkillContext =
        runtime.skillContext(_scene.value.label, _endpoint.value.configured)

    /** 主动扫描。10 秒一轮，够快也不至于把对话刷屏 */
    private fun startAiLoop() {
        if (aiLoop != null) return
        aiLoop = scope.launch {
            while (true) {
                delay(AI_SCAN_MS)
                dialog.scanProactive()
            }
        }
    }

    /** 额度与队列水位。**现场读模组**，不是写死的数（§9 #12/#16 那两个口径未定，所以只能读） */
    fun usage(): DemoEngine.Usage = runtime.engine.usage()

    /** 星历重算在跑。那张卡上的「重试」按下去得立刻有个形状变化回应，不然用户只会在三秒内再按两次 */
    private val _ephRetrying = MutableStateFlow(false)
    val ephRetrying: StateFlow<Boolean> = _ephRetrying

    /**
     * 「重试」按钮的实现：**只重算星历这一件事**，不重连模组、不清队列。
     * 结果必须回话，包括"还是不行"那一支 —— 静默的按钮比没有按钮更让人怀疑 App 坏了。
     */
    fun retryEphemeris() {
        if (_ephRetrying.value) return
        _ephRetrying.value = true
        runtime.retryEphemeris { status ->
            // 回调跑在 runtime 的 Default 线程上；say() 会写 1Hz 那条日志列表，
            // 那边同一时刻也在写，所以这句要挪回主线程，别让两条写撞出一行丢字
            scope.launch {
                _ephRetrying.value = false
                say(
                    when {
                        status == null -> "这一屏没有星历源，重试没有东西可算"
                        status.state == EphState.SUCCESS -> "星历重算完了，窗口出来了"
                        else -> "还是算不出来：" + (status.failMessage ?: "终端没回应")
                    },
                )
            }
        }
    }

    /**
     * 圆屏配对入口：**只负责"放开接入 + 把 screen_id 加进白名单 + 把接入点地址念出来"这三件事**，
     * 剩下的都在圆屏那侧（它自己是 WebSocket 客户端）。
     *
     * 这里刻意**不**做"设备自己冒出来"：那要 mDNS/BLE 广播，而 `firmware/screen/` 到今天还是空的，
     * 扫描结果的形状必须由真实固件定 —— 现在编一个发现协议，到货当天百分之百对不上。
     * 明天固件一起来，第一件事是往设备总线上 upsert 一行，这个入口换成"扫到的设备列表"，别的都不动。
     */
    fun pairScreen(screenId: String): Boolean {
        val id = screenId.trim()
        if (id.isEmpty()) return false
        val ok = runtime.allowScreen(id)
        say(if (ok) "已把 $id 放进白名单" else "白名单里已经有 $id")
        if (ok && !_flags.value.acceptScreens) setFlag(_flags.value.copy(acceptScreens = true))
        return ok
    }

    fun allowScreen(screenId: String): Boolean = runtime.allowScreen(screenId)

    fun revokeScreen(screenId: String) = runtime.revokeScreen(screenId)

    fun allowedScreens(): Set<String> = runtime.allowedScreens()

    /** 想要常驻、但那一刻权限还没批 → 只跑了进程内那一圈。这两个记的是"欠着的那半" */
    private var guardWanted = false
    private var guardNoted = false

    /**
     * 进程真被钉住了吗。**页面上凡是"守护"两个字都得读这一句**，不能读 `status.running`：
     * 后者只代表 1Hz 在转，而锁屏之后系统照样能把它冻住 —— 那正是这次闪退之后剩下的唯一风险。
     */
    val guardPinned: Boolean get() = guardWanted && DemoGuardService.canHold(host)

    /**
     * 守护服务的总闸。**开的时候同时把进程钉住**（[DemoGuardService]）：
     * 连接层只挂在 Application 上时，锁屏久了系统照样冻住进程，1Hz 那一圈会停。
     *
     * ⚠️ **钉进程这一步必须先有权限**。两台手机首启必闪退就是这么来的：`MainActivity.onCreate`
     * 里 `requestPermissions` 只是把系统对话框弹出去（异步），紧接着的 `startMonitors()` 就把
     * 前台服务拉起来，而 Android 10 起 `startForeground(..., TYPE_LOCATION)` 要求**调用那一刻**
     * 定位权限已经批下来 —— 系统抛 SecurityException，进程当场没了。V1.6 那个包没这问题，
     * 因为守护那时只在「我的」里手动开；V1.7 把它挪到启动路径上，顺手把崩溃也挪了过来。
     *
     * 所以这里分成两层，各自的条件不一样：
     * **进程内 1Hz 不要任何权限**，无条件起；**前台服务只在系统认账时起**（[DemoGuardService.canHold]），
     * 起不来就记下 [guardWanted] 等 [retryGuard] 补 —— 权限是异步批下来的，这条路必须能重试。
     */
    fun setGuard(on: Boolean) {
        guardWanted = on
        if (on) {
            runtime.start()
            retryGuard()
        } else {
            DemoGuardService.stop(host)
            runtime.stop()
        }
    }

    private fun retryGuard() {
        if (!guardWanted) return
        if (!DemoGuardService.canHold(host)) {
            if (!guardNoted) {
                guardNoted = true
                runtime.log("守护先只在 App 里跑：定位权限还没批，这时候起前台服务会被系统当场拒（上次闪退就是这一下）")
            }
            return
        }
        guardNoted = false
        DemoGuardService.start(host)
    }

    fun setSentinel(on: Boolean) = runtime.armSentinel(on)

    fun setFence(on: Boolean, name: String?) = runtime.setFence(on, name)

    fun runUpgrade() {
        runtime.firmwareUpgrade(onProgress = { pct -> runtime.log("模组升级中 $pct%") })
    }

    private fun buildEngine(): DemoEngine {
        val source: EphemerisSource = if (vendorEph) {
            StarManagerEphemeris(host)
        } else {
            SimulatedEphemerisSource().also { simEph = it }
        }
        ephSource = source
        return DemoEngine(
            modem = modem,
            ownerId = DemoRuntime.DEFAULT_OWNER_ID,
            tpi = TpiTracker(modem),
            ephemeris = EphemerisTracker(source, log = { msg -> runtimeRef?.log(msg) }),
        )
    }

    /**
     * 圆屏上的按键动作。在 App 启动服务之后才会被填上。
     *
     * **这里不再自己 dispatch**：那一张表在 [DemoRuntime.handleScreenCommand]，真机和彩排台共用。
     * 本机只管两件屏管不到的事 —— 清掉/写上首页那行撤销文案。
     */
    internal fun wireCommands() {
        runtime.onDialog = { msg -> dialog.onScreenFrame(msg) }
        // 屏上行的那段录音收齐了：封 WAV → 转写 → 合成一帧 dialog{dir:up} 回灌对话那条线。
        // turn_id 用屏起的那个（m- 前缀），重编号会坏掉问答配对；转写失败只出一句人话，不留半句
        runtime.onMicSegment = { seg ->
            scope.launch(Dispatchers.IO) {
                val text = SiliconFlowAsr.transcribe(MicUplink.pcmToWav(seg.pcm))
                if (text.isNullOrBlank()) {
                    withContext(Dispatchers.Main) { say("屏上那句话没转出来：没听清或转写失败") }
                } else {
                    withContext(Dispatchers.Main) { say("屏上说了：「$text」") }
                    dialog.onScreenFrame(
                        ScreenMessage(
                            t = MsgType.dialog,
                            seq = 0,
                            ts = System.currentTimeMillis(),
                            dialog = DialogPayload(
                                turnId = seg.turnId,
                                dir = DialogDir.up,
                                text = text,
                                source = DialogSource.asr,
                                origin = DialogOrigin.screen,
                            ),
                        ),
                    )
                }
            }
        }
        runtime.onCancelResult = { r -> _cancelLine.value = SosCopy.cancelLine(r.send, r.alreadyHanded) }
        runtime.onCommand = { msg ->
            // 屏上按出来的呼救和手机上按的是同一件事，那首页就不许留着上一条"撤销中"
            if (msg.command?.cmd == CommandType.sos_press) _cancelLine.value = null
        }
    }

    fun release() {
        location?.stop()
        monitor?.stop()
        ephSource?.release()
        voice.release()
        runtime.shutdown()
        scope.cancel()
    }

    companion object {
        /** 假模块的"永远在过境窗口内"。真机的窗口由星历算，不靠这个数 */
        private const val ALWAYS_VISIBLE = Long.MAX_VALUE / 4
        private const val KEY_VENDOR_EPH = "vendor_eph"
        private const val KEY_ACCEPT_SCREENS = "accept_screens"
        private const val KEY_AI_URL = "ai_url"
        private const val KEY_AI_KEY = "ai_key"
        private const val KEY_AI_MODEL = "ai_model"

        /** 烧进包的那套端点已经写进 prefs 的标记。改网关/换密钥时改这个值，老机器才会被顶掉 */
        private const val KEY_AI_SEED = "ai_seed"
        private const val AI_SEED = "1.20"
        private const val KEY_AI_TIER = "ai_tier"
        private const val KEY_VOICE_ENGINE = "voice_engine"
        private const val KEY_VOICE_TONE = "voice_tone"
        private const val KEY_VOICE_FACE = "voice_face"
        private const val KEY_ANSWER_DEVICE = "answer_device"

        /** 主动扫描的间隔。比 1Hz 慢一个量级：提醒是"这事值得说一句"，不是刷屏 */
        private const val AI_SCAN_MS = 10_000L

        /** 长按总量与"三段"。3 秒是〔规〕里那颗侧键的口径，三段是给眼睛看的进度 */
        const val ARM_MS = 3_000L
        const val SEG_MS = 1_000L
        private const val TICK_MS = 100L

        /**
         * 首屏钉死的两张卡（§5.1：SOS 和链路状态在 Tab1 首屏，不在设置里）。
         * 场景的卡片清单里也列了它们，所以流里要滤掉 —— 否则首页会出现两张 SOS 卡
         */
        val PINNED = setOf("sos", "link")
    }
}

/** 挂在 Application 上，这样 Activity 重建（旋转、深色模式切换）不会踢掉链路 */
class CarpetApp : Application() {
    val appRuntime: AppRuntime by lazy { AppRuntime(this).also { it.wireCommands() } }

    override fun onCreate() {
        super.onCreate()
        // **先装崩溃记录，再装配 runtime**：那次闪退就闪退在装配里（Providers 引了 Android
        // 没有的 java.net.http）。顺序反了，现场连一份栈都留不下，只剩用户一句"又闪退了"
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                File(filesDir, "crash-${System.currentTimeMillis()}.log").writeText("线程 ${t.name}\n$sw")
                // 只留最近 5 份，演示机上跑一整天也不许把内部存储写满
                filesDir.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".log") }
                    ?.sortedBy { it.name }?.dropLast(5)?.forEach { it.delete() }
            }
            // 自己这行绝不能抛，否则连系统那个"应用已停止"的对话框都看不到
            previous?.uncaughtException(t, e)
        }
        // 上一次异常退出留下的栈读进运行时日志 —— 手机上那一屏字，是我们在这台没有手机的机器上
        // 唯一读得到"真机上到底发生了什么"的通道
        val last = filesDir.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".log") }
            ?.maxByOrNull { it.name }
        // 让 lazy 在进程起来时就落地一次：第一件事是接线展示端指令，
        // 否则用户先点圆屏、App 才第一次读 runtime，那条指令会被丢掉
        appRuntime.wireCommands()
        last?.let { f ->
            val tail = runCatching { f.readText().lines().filter { it.isNotBlank() }.takeLast(2) }
                .getOrDefault(emptyList()).joinToString(" ← ")
            appRuntime.runtime.log("上次异常退出：$tail")
        }
    }
}
