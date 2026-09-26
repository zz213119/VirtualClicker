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
import com.zz213119.virtualclicker.script.ScriptAction
import com.zz213119.virtualclicker.script.ScriptActionType
import com.zz213119.virtualclicker.script.ScriptDefinition
import com.zz213119.virtualclicker.script.ScriptJson
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
 * Script engine MVP: click / long press / swipe / wait + whole-script repeat.
 * The target app stays inside Virtual Display; this service drives it through
 * the existing Shizuku UserService backend.
 */
class ScriptRunnerService : Service() {

    companion object {
        const val ACTION_RUN = "com.zz213119.virtualclicker.action.RUN_SCRIPT"
        const val ACTION_STOP = "com.zz213119.virtualclicker.action.STOP_SCRIPT"
        const val EXTRA_DISPLAY_ID = "extra_script_display_id"
        const val EXTRA_SCRIPT_JSON = "extra_script_json"
        private const val CHANNEL_ID = "virtual_clicker_script"
        private const val NOTIFICATION_ID = 1004
        private const val TAG = "ScriptRunnerService"

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scriptJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRunner()
                stopSelf()
            }

            ACTION_RUN -> {
                val displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
                val raw = intent.getStringExtra(EXTRA_SCRIPT_JSON).orEmpty()
                val script = ScriptJson.decode(raw)
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(displayId, script?.name ?: "未知脚本")
                )
                startRunner(displayId, script)
            }
        }
        return START_NOT_STICKY
    }

    private fun startRunner(displayId: Int, script: ScriptDefinition?) {
        scriptJob?.cancel()

        if (displayId < 0 || script == null || script.actions.isEmpty()) {
            Log.w(TAG, "script rejected")
            isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        isRunning = true
        LogWriter.write(
            "SCRIPT START",
            "name=" + script.name + ";displayId=" + displayId +
                ";actions=" + script.actions.size + ";repeatCount=" + script.repeatCount
        )

        scriptJob = serviceScope.launch {
            var rounds = 0
            try {
                if (!VirtualDisplayManager.ensureBound()) {
                    LogWriter.write("SCRIPT FAILED", "Shizuku UserService bind failed")
                    return@launch
                }

                while (isActive && (script.repeatCount == 0 || rounds < script.repeatCount)) {
                    for ((index, action) in script.actions.withIndex()) {
                        if (!isActive) break
                        val ok = executeAction(displayId, action)
                        Log.i(
                            TAG,
                            "action=" + (index + 1) + "/" + script.actions.size +
                                ";type=" + action.type + ";ok=" + ok
                        )
                        if (!ok) {
                            LogWriter.write(
                                "SCRIPT ACTION FAILED",
                                "name=" + script.name + ";round=" + (rounds + 1) +
                                    ";action=" + (index + 1) + ";type=" + action.type
                            )
                            return@launch
                        }
                    }
                    rounds++
                }
            } catch (_: CancellationException) {
                // Normal stop/restart path.
            } catch (t: Throwable) {
                Log.e(TAG, "script loop failed", t)
                LogWriter.write("SCRIPT EXCEPTION", t.stackTraceToString())
            } finally {
                LogWriter.write(
                    "SCRIPT END",
                    "name=" + script.name + ";rounds=" + rounds +
                        ";repeatCount=" + script.repeatCount
                )
                isRunning = false
                scriptJob = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun executeAction(displayId: Int, action: ScriptAction): Boolean {
        return when (action.type) {
            ScriptActionType.CLICK ->
                VirtualDisplayManager.tap(displayId, action.x, action.y)

            ScriptActionType.LONG_PRESS ->
                VirtualDisplayManager.longPress(
                    displayId,
                    action.x,
                    action.y,
                    action.durationMs.coerceIn(1L, 30000L).toInt()
                )

            ScriptActionType.SWIPE ->
                VirtualDisplayManager.swipe(
                    displayId,
                    action.x,
                    action.y,
                    action.x2,
                    action.y2,
                    action.durationMs.coerceIn(1L, 30000L).toInt()
                )

            ScriptActionType.WAIT -> {
                delay(action.durationMs)
                true
            }
        }
    }

    private fun stopRunner() {
        scriptJob?.cancel()
        scriptJob = null
        isRunning = false
        LogWriter.write("SCRIPT STOP", "manual_stop=true")
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification(displayId: Int, scriptName: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VirtualClicker 脚本",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val openApp = PendingIntent.getActivity(
            this,
            1004,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            1005,
            Intent(this, ScriptRunnerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VirtualClicker 脚本运行中")
            .setContentText(scriptName + " · D#" + displayId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        scriptJob?.cancel()
        scriptJob = null
        isRunning = false
        serviceScope.cancel()
        super.onDestroy()
    }
}