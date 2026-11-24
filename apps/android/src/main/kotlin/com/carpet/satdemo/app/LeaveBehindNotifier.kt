package com.carpet.satdemo.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 断联提醒的通知栏出口。
 *
 * 为什么单开一条 channel，不塞进守护那条常驻通知：常驻那条是 `IMPORTANCE_LOW` ——
 * 低优先级既不响也不抬头，用户不主动拉通知栏就看不见。而断联这一条要的就是
 * 「人在隔壁房间也听得见」那一响。两件事的打扰等级本来就不同。
 *
 * 话术红线（§6.1，和 SOS 同一把尺）：只报判定得出来的事实 ——
 * **能说**「你走远之后那台设备断了、没跟着你走」，**不能说**「孩子还在车里」。
 * 后者是把推断当结论念，误报一次用户就把这个功能关了。
 */
object LeaveBehindNotifier {

    private const val CHANNEL = "leave-behind"
    private const val NOTI_ID = 42

    fun fire(ctx: Context, title: String, text: String) {
        val m = manager(ctx)
        m.createNotificationChannel(
            NotificationChannel(CHANNEL, "遗留告警", NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // 长文本样式：那一行"离开多少米、断了多久、该干什么"在折叠的单行里会被截成半句
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_guard_pin)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().setBigContentTitle(title).bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .build()
        // 通知权限没批（Android 13 起是运行时权限），系统直接丢这条 —— 不许抛
        runCatching { m.notify(NOTI_ID, n) }
    }

    /** 设备重新连上，那条报警就该自己消失。留着它，用户下次读到的是过期结论 */
    fun clear(ctx: Context) {
        runCatching { manager(ctx).cancel(NOTI_ID) }
    }

    private fun manager(ctx: Context) =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
