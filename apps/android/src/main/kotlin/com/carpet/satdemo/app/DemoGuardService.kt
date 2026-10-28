package com.carpet.satdemo.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
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
 */
class DemoGuardService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = (application as CarpetApp).appRuntime
        channel()
        // 先把前台占住，再去订阅状态：Android 12 起 startForegroundService 之后只有 10 秒
        // 必须调用 startForeground，把它排到第一次状态变更之后，慢的时候会被系统杀掉
        publish(app, app.status.value)
        scope.launch { app.status.collect { publish(app, it) } }
        return START_STICKY
    }

    private fun publish(app: AppRuntime, st: DemoRuntime.Status) {
        val n = build(app, st)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTI_ID, n, foregroundTypes())
        } else {
            startForeground(NOTI_ID, n)
        }
        manager().notify(NOTI_ID, n)
    }

    private fun foregroundTypes(): Int {
        var t = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        // connectedDevice 是 API 34 才有的常量，低版本传进去会当场抛异常
        if (Build.VERSION.SDK_INT >= 34) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
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

        fun start(host: Context) {
            val i = Intent(host, DemoGuardService::class.java)
            // O 之后必须走 startForegroundService，否则服务一起来就被判为后台启动
            if (Build.VERSION.SDK_INT >= 26) host.startForegroundService(i) else host.startService(i)
        }

        fun stop(host: Context) = host.stopService(Intent(host, DemoGuardService::class.java))
    }
}
