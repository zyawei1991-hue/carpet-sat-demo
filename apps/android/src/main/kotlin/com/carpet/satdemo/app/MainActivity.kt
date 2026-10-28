package com.carpet.satdemo.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.carpet.satdemo.core.engine.DemoEngine
import com.carpet.satdemo.core.ephemeris.EphState
import com.carpet.satdemo.core.ephemeris.EphemerisSource
import com.carpet.satdemo.core.ephemeris.EphemerisTracker
import com.carpet.satdemo.core.ephemeris.SimulatedEphemerisSource
import com.carpet.satdemo.core.modem.SimulatedModem
import com.carpet.satdemo.core.modem.TpiTracker
import com.carpet.satdemo.core.protocol.CommandType
import com.carpet.satdemo.core.runtime.DemoRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private val PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.POST_NOTIFICATIONS,
)

/**
 * 演示控制台。UI 全部代码搭，不引 androidx —— 这个页面只给彩排和救场用，
 * 正经的展示在圆屏上，不值得为它拉一整套依赖。
 */
class MainActivity : Activity() {

    /**
     * 手机上这一版跑的是假卫星模块：到货前就能拿手机整套演示，
     * 到货后把 modem 换成 [VendorSdkModem]，下面那几个开关一个都不用改。
     * 默认"一直在过境窗口内"，按 SOS 就能看到平台确认；
     * 关掉「模拟卫星未过顶」才演那条三次重发都没 ACK 的红字分支。
     */
    private val modem = SimulatedModem(passWindowMs = ALWAYS_VISIBLE)

    /**
     * 星历源。默认是替身：彩排时屏上必须每次都算得出窗口，不然讲不动。
     * 「星历：厂商 SDK」这个开关把它换成 [StarManagerEphemeris]，**重启生效**——
     * 引擎拿着的是构造期注入的 tracker，运行时换源会把已算出的窗口列表留在屏上骗人。
     */
    private var simEph: SimulatedEphemerisSource? = null

    /** 星历源本身也要留着：不用时 release 是〔SDK文 §5.3〕的要求，替身那边是空实现 */
    private var ephSource: EphemerisSource? = null

    private lateinit var runtime: DemoRuntime
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var engineBox: LinearLayout
    private lateinit var engineScroll: ScrollView
    private lateinit var engineView: TextView

    private var monitor: NetworkMonitor? = null
    private var location: LocationMonitor? = null

    /** 彩排用的假断网。现场没有屏蔽袋时靠它把链路打到 PROBE/SAT */
    private var simulatedOffline = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Context 在 onCreate 之前还没 attach，星历适配器又必须拿着它：引擎只能在这里建
        runtime = DemoRuntime(buildEngine())
        setContentView(buildUi())
        requestPermissions(PERMISSIONS, 1)

        scope.launch {
            runtime.status.collect { render(it) }
        }

        runtime.onCommand = { msg ->
            runtime.log("展示端指令 ${msg.command?.cmd}")
            if (msg.command?.cmd == CommandType.sos_press) runtime.pressSos()
        }

        startMonitors()
    }

    /**
     * TPI 和星历都在真机上跑起来（E.10 #5 / #7）：
     * 前者每秒问一句"到点了没"、到点就把手机位置推进模组，
     * 后者把"下次过境还有多久 + 该把天线朝哪边"变成倒计时和方位角。
     * 这两条在桌面上已经演过，手机上第一次跑就是要看它们会不会自己打自己。
     */
    private fun buildEngine(): DemoEngine {
        val source: EphemerisSource = if (prefs().getBoolean(KEY_VENDOR_EPH, false)) {
            StarManagerEphemeris(this)
        } else {
            SimulatedEphemerisSource().also { simEph = it }
        }
        ephSource = source
        return DemoEngine(
            modem = modem,
            ownerId = DemoRuntime.DEFAULT_OWNER_ID,
            tpi = TpiTracker(modem),
            ephemeris = EphemerisTracker(source, log = { msg -> if (::runtime.isInitialized) runtime.log(msg) }),
        )
    }

    private fun prefs() = getSharedPreferences("demo", 0)

    private fun startMonitors() {
        location = LocationMonitor(this) { lat, lon, source, hdop, age ->
            runtime.onFix(lat, lon, source, hdop, age)
        }.also { it.start() }

        monitor = NetworkMonitor(this) { sample ->
            runtime.onCellSample(if (simulatedOffline) sample.copy(hasInternetCapability = false, validated = false) else sample)
        }.also { it.start(scope) }
    }

    private fun buildUi(): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(statusView)

        val sos = Button(this).apply {
            text = "SOS"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(200, 30, 30))
            textSize = 22f
            val h = (72 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
            setOnClickListener { runtime.pressSos() }
        }
        root.addView(sos, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = pad
            bottomMargin = pad
        })

        root.addView(switch("模拟断网（彩排用）") {
            simulatedOffline = it
            runtime.log(if (it) "已模拟断网" else "已恢复蜂窝")
        })
        root.addView(switch("模拟卫星未过顶") {
            modem.passWindowMs = if (it) 0 else ALWAYS_VISIBLE
            // 星历替身要跟着改口：屏上说"三分钟后过境"、模组却说"现在就能发"，
            // 这种自己打自己的帧在彩排时最容易漏，一旦被观众看到就是整场可信度的问题
            simEph?.state = if (it) EphState.CALCULATION_FAILED else EphState.SUCCESS
            runtime.log(if (it) "已切到「没过顶」：MCQ 全部 NO_ACK" else "已恢复：过境窗口常开")
        })
        root.addView(switch("模拟 GT1005 掉线") {
            modem.reachable = !it
            runtime.log(if (it) "GT1005 已断开，心跳只能留在 App 队列" else "GT1005 已恢复")
        })
        root.addView(switch("允许展示端接入") { runtime.setAcceptScreens(it) })
        root.addView(switch("哨兵模式") { runtime.armSentinel(it) })
        root.addView(switch("电子围栏：西湖景区") { runtime.setFence(it, "西湖景区") })
        root.addView(switch("演示模式") { runtime.setDemoMode(it) })
        root.addView(switch("星历：用厂商 SDK（重启生效）", prefs().getBoolean(KEY_VENDOR_EPH, false)) {
            prefs().edit().putBoolean(KEY_VENDOR_EPH, it).apply()
            // 不当场切换：引擎手里的 tracker 是构造期注入的，运行时换源会让屏上留着上一源的窗口
            runtime.log(if (it) "已记下：下次启动用厂商星历 SDK" else "已记下：下次启动回到星历替身")
        })
        root.addView(switch("工程模式日志窗") {
            engineBox.visibility = if (it) View.VISIBLE else View.GONE
            if (it) renderEngineWindow()
        })

        val start = Button(this).apply {
            text = "启动服务"
            setOnClickListener { runtime.start() }
        }
        root.addView(start)

        logView = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.GRAY)
        }
        root.addView(ScrollView(this).apply { addView(logView) })

        // 工程模式窗：默认收着，救场时打开。它和上面那个日志窗不是一回事 ——
        // 上面是"我们对发生之事的说法"，这里是"线路上真正来过什么"
        engineView = TextView(this).apply {
            textSize = 9f
            setTextColor(Color.LTGRAY)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        engineScroll = ScrollView(this).apply {
            addView(engineView)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        engineBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(Color.rgb(20, 20, 24))
            setPadding(4, 4, 4, 4)
            addView(engineScroll)
        }
        root.addView(engineBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = pad
        })
        return root
    }

    /** [checked] 先落定再接监听器：反过来的话"恢复现场"那一下会被当成一次用户操作 */
    private fun switch(label: String, checked: Boolean = false, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        isChecked = checked
        setOnCheckedChangeListener { _, now -> onChange(now) }
    }

    /** 那几行字的排版在 core 的 DemoRuntime.consoleText 里，和桌面彩排台共用一份 */
    private fun render(s: DemoRuntime.Status) {
        statusView.text = runtime.consoleText(s)
        logView.text = s.log.joinToString("\n")
        if (engineBox.visibility == View.VISIBLE) renderEngineWindow()
    }

    /**
     * 工程模式日志窗（E.10 #10）。1Hz 刷一次就够 —— 这一屏是给人读语句的，不是给人看动画的。
     *
     * 数据来源只有 core 那一个 [com.carpet.satdemo.core.diagnostic.PassthroughLog]：
     * 语句层实现喂线路原文，厂商 SDK 那路额外喂 `passthrough(String)`，
     * 模拟实现只喂注解。屏上不去分辨是哪一路，分辨的活儿在方向标签上。
     */
    private fun renderEngineWindow() {
        engineView.text = buildString {
            append(runtime.engineeringWindowHeader())
            append('\n')
            runtime.passthrough.snapshot().forEach { line ->
                append(line.render())
                append('\n')
            }
        }
        engineScroll.post { engineScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        location?.stop()
        monitor?.stop()
        ephSource?.release()
        runtime.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.none { it == PackageManager.PERMISSION_GRANTED }) {
            runtime.log("定位权限被拒 —— 车载端没有位置源，Demo 无法演示轨迹")
        }
    }

    companion object {
        /** 假模块的"永远在过境窗口内"。真机的窗口由星历算，不靠这个数 */
        private const val ALWAYS_VISIBLE = Long.MAX_VALUE / 4

        /** 星历源开关存在这里。只影响下一次 onCreate，理由见那个开关的注释 */
        private const val KEY_VENDOR_EPH = "vendor_eph"
    }
}
