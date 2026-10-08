package com.zz213119.virtualclicker.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zz213119.virtualclicker.MainActivity

/** 任务结束/中断时给用户发的普通应用通知（高优先级，带提示音）。 */
object AlertNotifier {
    private const val CHANNEL_ID = "vc_alert"
    private const val NOTIFICATION_ID = 2001

    fun notify(ctx: Context, title: String, text: String) {
        if (!com.zz213119.virtualclicker.core.Prefs.notifyEnabled(ctx)) {
            LogWriter.write("NOTIFY OFF", "$title / $text")
            return
        }
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "任务提醒", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            LogWriter.write("NOTIFY SKIPPED", "no POST_NOTIFICATIONS permission: $title / $text")
            return
        }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        nm.notify(NOTIFICATION_ID, n)
        LogWriter.write("NOTIFY", "$title / $text")
    }
}
