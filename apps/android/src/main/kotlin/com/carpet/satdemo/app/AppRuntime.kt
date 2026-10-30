package com.carpet.satdemo.app

import android.app.Application
import com.carpet.satdemo.core.ai.AiEndpoint
import com.carpet.satdemo.core.ai.AiGateway
import com.carpet.satdemo.core.ai.DialogBridge
import com.carpet.satdemo.core.ai.OpenAiCompatProvider
import com.carpet.satdemo.core.ai.PermissionTier
import com.carpet.satdemo.core.ai.ReplySource
import com.carpet.satdemo.core.ai.SkillContext
import com.carpet.satdemo.core.ai.skillContext
import com.carpet.satdemo.core.ai.Turn
import com.carpet.satdemo.core.ai.TurnFrom
import com.carpet.satdemo.core.devices.Device
import com.carpet.satdemo.core.engine.DemoEngine
import com.carpet.satdemo.core.ephemeris.EphState
import com.carpet.satdemo.core.ephemeris.EphemerisSource
import com.carpet.satdemo.core.ephemeris.EphemerisTracker
import com.carpet.satdemo.core.ephemeris.SimulatedEphemerisSource
import com.carpet.satdemo.core.link.CellStatus
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
     * 换真机是换 [VendorSdkModem]，下面那些开关一个都不用改。
     */
    val modem = SimulatedModem(passWindowMs = ALWAYS_VISIBLE)

    private val prefs = host.getSharedPreferences("demo", 0)

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
    )

    private val _flags = MutableStateFlow(Flags())
    val flags: StateFlow<Flags> = _flags

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

    /** 底部导航停在第几页。也放在这里，是为了转屏之后回到原来那一页 */
    private val _tab = MutableStateFlow(0)
    val tab: StateFlow<Int> = _tab

    fun setTab(i: Int) {
        _tab.value = i
    }

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
    }

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
        if (prev.acceptScreens != next.acceptScreens) runtime.setAcceptScreens(next.acceptScreens)
        if (prev.demoMode != next.demoMode) runtime.setDemoMode(next.demoMode)
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
    // AI 主理人。对话是首页两个主区之一（V1.7 重排），所以它跟链路一样挂在连接层，
    // 不挂在页面上 —— 用户发完问题去切 Tab，回答回来时不该丢上下文。
    // ─────────────────────────────────────────────────────────────────────────────

    private val _endpoint = MutableStateFlow(
        AiEndpoint(
            baseUrl = prefs.getString(KEY_AI_URL, "") ?: "",
            apiKey = prefs.getString(KEY_AI_KEY, "") ?: "",
            model = prefs.getString(KEY_AI_MODEL, "") ?: "",
        ),
    )
    val aiEndpoint: StateFlow<AiEndpoint> = _endpoint

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
     * 对话的唯一一条接线：入账 → 该不该触发技能 → 答 → 回镜给圆屏。
     * 逻辑本体在 `core/ai/DialogBridge`（那边才有真单测，彩排台和手机跑的也才是同一份），
     * 这里只填三个口子：事实表、往屏发一帧、宿主自己的协程作用域。
     */
    private val dialog = DialogBridge(
        gateway = ai,
        facts = { aiContext() },
        push = { d -> runtime.pushDialog(d) },
        launch = { body -> scope.launch { body() } },
        note = { line -> runtime.log(line) },
    )

    /** 唯一的那份对话时间线。圆屏和手机都只是它的两个入口 */
    val chat: StateFlow<List<Turn>> = dialog.log.turns

    /** 发出去的那句还在等回答。首屏那条占位气泡靠它，不然按了发送屏幕不动 */
    val aiThinking: StateFlow<Boolean> get() = dialog.thinking

    /** 工程模式/演示要用：屏在不在、能不能说话 */
    val screenDialog: Boolean get() = runtime.screenCanTalk

    private var aiLoop: Job? = null

    /**
     * 手机上问一句。[turnId] 由调用方给：圆屏起的那一轮必须沿用它那个 id，
     * 否则手机把问和答记成两轮，屏上那条追问也钉不回原来的对话。
     */
    fun askAi(question: String, via: DialogOrigin = DialogOrigin.phone, turnId: String? = null) {
        scope.launch { dialog.ask(question, via, turnId) }
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
        val next = AiEndpoint(url.trim(), key.trim(), model.trim())
        _endpoint.value = next
        aiProvider.setEndpoint(next)
        prefs.edit().putString(KEY_AI_URL, next.baseUrl).putString(KEY_AI_KEY, next.apiKey)
            .putString(KEY_AI_MODEL, next.model).apply()
        runtime.log(if (next.configured) "模型端点已配：${next.model}" else "模型端点未配齐，回到本机规则")
        greet()
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

    /** 圆屏上的按键动作。在 App 启动服务之后才会被填上 */
    internal fun wireCommands() {
        runtime.onDialog = { msg -> dialog.onScreenFrame(msg) }
        runtime.onCommand = { msg ->
            val cmd = msg.command?.cmd
            runtime.log("展示端指令 $cmd")
            when (cmd) {
                CommandType.sos_press -> runtime.pressSos()
                // 圆屏那颗键能长按 3 秒触发，就必须也能撤销：两边能力不对称时，
                // 用户在圆屏上按完会发现 App 里找不到那条"取消"
                CommandType.sos_cancel -> cancelSos()
                else -> Unit
            }
        }
    }

    fun release() {
        location?.stop()
        monitor?.stop()
        ephSource?.release()
        runtime.shutdown()
        scope.cancel()
    }

    companion object {
        /** 假模块的"永远在过境窗口内"。真机的窗口由星历算，不靠这个数 */
        private const val ALWAYS_VISIBLE = Long.MAX_VALUE / 4
        private const val KEY_VENDOR_EPH = "vendor_eph"
        private const val KEY_AI_URL = "ai_url"
        private const val KEY_AI_KEY = "ai_key"
        private const val KEY_AI_MODEL = "ai_model"
        private const val KEY_AI_TIER = "ai_tier"

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
