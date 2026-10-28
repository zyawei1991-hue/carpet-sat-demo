package com.carpet.satdemo.app

import android.app.Application
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
        if (!runtime.status.value.running) setGuard(true)
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

    /** 设备台账（§8 R1 第一件事）。切 Tab、切场景都不会让它空一格 */
    val devices: StateFlow<List<Device>> get() = runtime.devicesSnapshot

    /** 额度与队列水位。**现场读模组**，不是写死的数（§9 #12/#16 那两个口径未定，所以只能读） */
    fun usage(): DemoEngine.Usage = runtime.engine.usage()

    fun allowScreen(screenId: String): Boolean = runtime.allowScreen(screenId)

    fun revokeScreen(screenId: String) = runtime.revokeScreen(screenId)

    fun allowedScreens(): Set<String> = runtime.allowedScreens()

    /**
     * 守护服务的总闸。**开的时候同时把进程钉住**（[DemoGuardService]）：
     * 连接层只挂在 Application 上时，锁屏久了系统照样冻住进程，1Hz 那一圈会停。
     */
    fun setGuard(on: Boolean) {
        if (on) {
            runtime.start()
            DemoGuardService.start(host)
        } else {
            DemoGuardService.stop(host)
            runtime.stop()
        }
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
        // 让 lazy 在进程起来时就落地一次：第一件事是接线展示端指令，
        // 否则用户先点圆屏、App 才第一次读 runtime，那条指令会被丢掉
        appRuntime.wireCommands()
    }
}
