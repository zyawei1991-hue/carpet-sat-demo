package com.carpet.satdemo.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.carpet.satdemo.core.engine.DemoEngine
import com.carpet.satdemo.core.link.LinkPhase
import com.carpet.satdemo.core.runtime.DemoRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 守护前台服务 —— **§5.3 硬要求 #2 的那半步：链路要能在锁屏之后继续活着。**
 *
 * [AppRuntime] 把引擎挂在 Application 上，那只解决了"Activity 重建不重连"。
 * 用户锁屏、把 App 挤到后台，系统过一会儿就把进程冻住：1Hz 那一圈停、圆屏那头变灰、
 * 电子围栏不再判越界。而车载守护的全部意义是"人不在屏幕前也照样看着"，
 * 所以必须有一个系统认账的常驻理由，而不是一句"我们的对象活得比页面久"。
 *
 * 常驻通知就是那个理由，也因此它得念对用户有用的东西：**链路到哪一步、队列压了几条**。
 * 一句"服务正在运行"是白占用户的通知栏。
 *
 * ⚠️ **这个类曾经把 App 打成首启必闪退**（两台手机、100% 复现）。根子不在服务本身，在**起来的时机**：
 * `MainActivity.onCreate` 里 `requestPermissions` 是异步的，可紧接着那句 `startMonitors()`
 * 就把守护拉起来了 —— 而 Android 10 起 `startForeground(..., TYPE_LOCATION)` 要求**调用那一刻**
 * 定位权限已经批下来，Android 14 对 `TYPE_CONNECTED_DEVICE` 同样要求手里真有一个连设备的权限。
 * 权限框还挂在屏幕上，系统直接抛 SecurityException，进程跟着走。
 * 两条规矩都在这里：**类型按此刻真的批下来的权限现挑**（[typesNow]），
 * **起不来就自己停掉并留下一行话**（[publish]），绝不硬闯。
 */
class DemoGuardService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = (application as CarpetApp).appRuntime
        channel()
        // 先把前台占住，再去订阅状态：Android 12 起 startForegroundService 之后只有 10 秒
        // 必须调用 startForeground，把它排到第一次状态变更之后，慢的时候会被系统杀掉
        if (!publish(app, app.status.value)) {
            // 前台都没占住，这个服务就没有存在的理由 —— 停自己，让 AppRuntime 走进程内那一圈
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch { app.status.collect { publish(app, it) } }
        return START_STICKY
    }

    /**
     * 占住前台。**这条路不许抛异常**：它是守护的实现手段，不是守护的理由 ——
     * 起不来时最不该发生的事就是把整个 App 一起带走，用户连"为什么没常驻"都看不到。
     *
     * 降级顺序是**从最想要的类型往下撤**：全类型 → 只留定位 → 不带类型。
     * 返回 false = 一次都没占住。
     */
    private fun publish(app: AppRuntime, st: DemoRuntime.Status): Boolean {
        val n = build(app, st)
        // null = 走两参数那个不带类型的重载。29 以下只有这一条路可走；
        // 34 上它是最后一道降级 —— 万一清单里声明的类型此刻一个都不被系统认账，
        // 不带类型至少有机会把进程钉住（钉不住也别抛，见上面那句 runCatching）
        val ladder: List<Int?> = when {
            Build.VERSION.SDK_INT >= 34 -> listOf(typesNow(), TYPE_LOCATION_ONLY, null)
            Build.VERSION.SDK_INT >= 29 -> listOf(typesNow(), TYPE_LOCATION_ONLY)
            else -> listOf(null)
        }
        for (t in ladder) {
            // 每一句 startForeground 都必须躺在 runCatching 里 —— 这条形状是 GuardStartTimingTest 在看
            val ok = runCatching {
                if (t != null && Build.VERSION.SDK_INT >= 29) startForeground(NOTI_ID, n, t) else startForeground(NOTI_ID, n)
            }.isSuccess
            if (ok) {
                manager().notify(NOTI_ID, n)
                return true
            }
        }
        app.runtime.log("守护没能常驻：${whyNot()}。1Hz 仍在跑，锁屏后会被系统冻住")
        return false
    }

    /** 权限没批的时候说什么。**不许说"守护已开启"** —— 那是假话，和 §6.1 同一个道理 */
    private fun whyNot(): String =
        if (!hasLocationPermission(this)) "还没拿到定位权限" else "系统拒绝了常驻类型"

    /** 类型按**此刻真的批下来的权限**挑，不是按 manifest 里写了什么挑 */
    private fun typesNow(): Int {
        var t = 0
        if (hasLocationPermission(this)) t = t or TYPE_LOCATION_ONLY
        if (Build.VERSION.SDK_INT >= 34 && hasBluetoothPermission(this)) {
            t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        return t
    }

    private fun build(app: AppRuntime, st: DemoRuntime.Status): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val u = app.usage()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_guard_pin)
            .setContentTitle(if (st.running) "守护运行中" else "守护已停，卫星报文不再补发")
            .setContentText(summary(st, u))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    /**
     * 通知栏那行字。**只念读得出来的东西**：额度分母在〔§9 #12〕还没确认，
     * 拿不到型号时宁可只报"待补发 N 条"，也不写一个猜出来的总数。
     */
    private fun summary(st: DemoRuntime.Status, u: DemoEngine.Usage): String {
        val link = when (st.phase) {
            LinkPhase.CELL -> "蜂窝"
            LinkPhase.SAT -> "卫星"
            else -> "切路中"
        }
        val quota = when {
            u.model == null || u.capacity <= 0 -> null
            else -> "${u.model} 剩 ${u.slotsLeft} 条"
        }
        return listOfNotNull(
            "链路 $link",
            "展示端 ${st.clients} 台",
            if (u.waiting > 0) "待补发 ${u.waiting} 条" else null,
            quota,
        ).joinToString(" · ")
    }

    private fun channel() {
        if (Build.VERSION.SDK_INT < 26) return
        manager().createNotificationChannel(
            NotificationChannel(CHANNEL, "卫星守护", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun manager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "guard"
        private const val NOTI_ID = 1

        /**
         * 和 `ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION` 同值。
         * 这里写数字而不是引常量：那个常量在 API 29 才有，直接引会让 26~28 上的
         * 类加载阶段就报 NoClassDefFoundError —— 而这正是我们要防的那种"一进来就崩"。
         */
        private const val TYPE_LOCATION_ONLY = 1 shl 2

        private fun granted(ctx: Context, vararg names: String): Boolean = names.any {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }

        fun hasLocationPermission(ctx: Context): Boolean = granted(
            ctx, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        /**
         * `connectedDevice` 这一档的前置条件不是"申报了权限"，而是**手里真有一个连设备的权限**
         * （Android 14 认的那张清单：BLUETOOTH_CONNECT / BLUETOOTH_SCAN / CHANGE_WIFI_STATE / …）。
         * 少一个，这次 startForeground 就会抛。
         */
        fun hasBluetoothPermission(ctx: Context): Boolean = granted(
            ctx, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
        )

        /**
         * 现在能不能把进程钉住。**判据只有一条**：至少有一个我们能如实申报、系统也认账的前台类型。
         * 定位和蓝牙都没批 → 类型是 0 → 别起。
         * [AppRuntime] 拿这一句决定"起服务"还是"走进程内那一圈"，这是全 App 唯一的入口。
         */
        fun canHold(ctx: Context): Boolean = Build.VERSION.SDK_INT < 29 ||
            hasLocationPermission(ctx) || hasBluetoothPermission(ctx)

        fun start(host: Context) {
            val i = Intent(host, DemoGuardService::class.java)
            // O 之后必须走 startForegroundService，否则服务一起来就被判为后台启动
            if (Build.VERSION.SDK_INT >= 26) host.startForegroundService(i) else host.startService(i)
        }

        fun stop(host: Context) = host.stopService(Intent(host, DemoGuardService::class.java))
    }
}
