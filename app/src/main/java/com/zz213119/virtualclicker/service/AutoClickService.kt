
package com.zz213119.virtualclicker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.zz213119.virtualclicker.MainActivity
import com.zz213119.virtualclicker.R
import com.zz213119.virtualclicker.core.VirtualDisplayManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Background single-point auto clicker.
 *
 * It sends display-targeted taps through the Shizuku UserService, so the
 * coordinates are injected into the selected Virtual Display rather than
 * the phone's physical screen.
 *
 * repeatCount == 0 means infinite.
 */
class AutoClickService : Service() {

    companion object {
        const val ACTION_START = "com.zz213119.virtualclicker.action.START_AUTO_CLICK"
        const val ACTION_STOP = "com.zz213119.virtualclicker.action.STOP_AUTO_CLICK"

        const val EXTRA_DISPLAY_ID = "extra_display_id"
        const val EXTRA_X = "extra_x"
        const val EXTRA_Y = "extra_y"
        const val EXTRA_INTERVAL_MS = "extra_interval_ms"
        const val EXTRA_REPEAT_COUNT = "extra_repeat_count"

        private const val CHANNEL_ID = "virtual_clicker_auto_click"
        private const val NOTIFICATION_ID = 1002
        private const val MIN_INTERVAL_MS = 50L
        private const val TAG = "AutoClickService"

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var clickJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopClicker()
                stopSelf()
            }

            ACTION_START -> {
                val displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
                val x = intent.getFloatExtra(EXTRA_X, 0f)
                val y = intent.getFloatExtra(EXTRA_Y, 0f)
                val intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, 500L)
                    .coerceAtLeast(MIN_INTERVAL_MS)
                val repeatCount = intent.getIntExtra(EXTRA_REPEAT_COUNT, 0).coerceAtLeast(0)

                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(displayId, x, y, intervalMs, repeatCount)
                )
                startClicker(displayId, x, y, intervalMs, repeatCount)
            }
        }

        return START_NOT_STICKY
    }

    private fun startClicker(
        displayId: Int,
        x: Float,
        y: Float,
        intervalMs: Long,
        repeatCount: Int
    ) {
        clickJob?.cancel()

        if (displayId < 0) {
            Log.w(TAG, "auto click rejected: invalid displayId=$$displayId")
            isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        isRunning = true
        LogWriter.write(
            "AUTO CLICK START",
            "displayId=$$displayId\\nx=$$x\\ny=$$y\\n" +
                "intervalMs=$$intervalMs\\nrepeatCount=$$repeatCount"
        )

        clickJob = serviceScope.launch {
            var completed = 0

            try {
                while (isActive && (repeatCount == 0 || completed < repeatCount)) {
                    val ok = VirtualDisplayManager.tap(displayId, x, y)
                    if (!ok) {
                        Log.w(TAG, "auto click failed at count=$${completed + 1}")
                        LogWriter.write(
                            "AUTO CLICK FAILED",
                            "displayId=$$displayId\\nx=$$x\\n" +
                                "y=$$y\\ncount=$${completed + 1}"
                        )
                        break
                    }

                    completed++

                    if (repeatCount == 0 || completed < repeatCount) {
                        delay(intervalMs)
                    }
                }
            } catch (_: CancellationException) {
                // Normal stop/restart path.
            } catch (t: Throwable) {
                Log.e(TAG, "auto click loop failed", t)
                LogWriter.write("AUTO CLICK EXCEPTION", t.stackTraceToString())
            } finally {
                LogWriter.write(
                    "AUTO CLICK END",
                    "displayId=$$displayId\\ncompleted=$$completed\\n" +
                        "repeatCount=$$repeatCount"
                )
                isRunning = false
                clickJob = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopClicker() {
        clickJob?.cancel()
        clickJob = null
        isRunning = false
        LogWriter.write("AUTO CLICK STOP", "manual_or_display_stop=true")
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification(
        displayId: Int,
        x: Float,
        y: Float,
        intervalMs: Long,
        repeatCount: Int
    ): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VirtualClicker 连点器",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val openApp = PendingIntent.getActivity(
            this,
            1002,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            1003,
            Intent(this, AutoClickService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val countText = if (repeatCount == 0) "无限" else repeatCount.toString()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VirtualClicker 连点器运行中")
            .setContentText("D#$$displayId · ($$x, $$y) · $${intervalMs}ms · $$countText 次")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openApp)
            .addAction(
                android.R.drawable.ic_media_pause,
                "停止",
                stopIntent
            )
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        clickJob?.cancel()
        clickJob = null
        isRunning = false
        serviceScope.cancel()
        super.onDestroy()
    }
}
