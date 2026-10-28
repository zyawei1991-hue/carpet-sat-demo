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
import com.carpet.satdemo.core.modem.SimulatedModem
import com.carpet.satdemo.core.protocol.CommandType
import com.carpet.satdemo.core.protocol.SatPhase
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
     * 到货后把 modem 换成 Uart/Ble 实现，下面那几个开关一个都不用改。
     * 默认"一直在过境窗口内"，按 SOS 就能看到平台确认；
     * 关掉「模拟卫星未过顶」才演那条三次重发都没 ACK 的红字分支。
     */
    private val modem = SimulatedModem(passWindowMs = ALWAYS_VISIBLE)
    private val runtime = DemoRuntime(DemoEngine(modem, DemoRuntime.DEFAULT_OWNER_ID))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var statusView: TextView
    private lateinit var logView: TextView

    private var monitor: NetworkMonitor? = null
    private var location: LocationMonitor? = null

    /** 彩排用的假断网。现场没有屏蔽袋时靠它把链路打到 PROBE/SAT */
    private var simulatedOffline = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        return root
    }

    private fun switch(label: String, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    private fun render(s: DemoRuntime.Status) {
        val snap = s.snapshot
        val sat = snap?.sat
        // 没锁到星时唯一有用的信息就是还有多少秒过顶；锁上了再显示倒计时就是噪声
        val waitPass =
            if (sat != null && sat.phase != SatPhase.locked) sat.nextPassInS?.takeIf { it > 0 } else null
        val satLine = when {
            sat == null -> "-"
            !sat.moduleOk -> "GT1005 离线"
            else -> listOfNotNull(
                sat.phase.toString() + (sat.satName?.let { " $it" } ?: ""),
                waitPass?.let { "距下次过顶 ${it}s" },
            ).joinToString("   ")
        }
        statusView.text = buildString {
            appendLine("链路  ${s.phase}   展示端 ${s.clients} 台   ${s.localIp ?: "-"}")
            appendLine("卫星  $satLine")
            appendLine(
                "定位  ${
                    snap?.let { "%.5f, %.5f  (%s)".format(it.gnss.lat, it.gnss.lon, it.gnss.source) } ?: "无"
                }",
            )
            appendLine("SOS   ${snap?.sos?.level ?: "-"}  上次结果 ${s.lastSos ?: "-"}")
            appendLine("服务  ${if (s.running) "运行中" else "未启动"}   离线待补发 ${runtime.engine.pending.size} 条")
        }
        logView.text = s.log.joinToString("\n")
    }

    override fun onDestroy() {
        location?.stop()
        monitor?.stop()
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
    }
}
