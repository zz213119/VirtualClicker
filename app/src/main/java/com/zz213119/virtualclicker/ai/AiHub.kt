package com.zz213119.virtualclicker.ai

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.zz213119.virtualclicker.core.VirtualDisplayManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.zz213119.virtualclicker.service.AlertNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 全局只有一个 AI 对话和一个运行中的任务。任务放在应用级的协程里跑，
 * 界面退到后台或被销毁都不会取消它；任务结束或中断时（可开关）发通知。
 */
object AiHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var session: AiSession? = null
    private var job: Job? = null

    @Synchronized
    fun session(ctx: Context): AiSession =
        session ?: AiSession(ctx.applicationContext).also {
            it.restoreLatest()
            session = it
        }

    fun isRunning(): Boolean = job?.isActive == true

    @Synchronized
    fun start(ctx: Context, text: String, displayId: Int) {
        if (isRunning()) return
        val app = ctx.applicationContext
        val s = session(app)
        job = scope.launch {
            // AI 任务期间：让后台服务保持“忙”（虚拟屏不休眠、持唤醒锁）、应用本身不被挂起（CPU 唤醒锁 + 前台服务）。
            // 否则一息屏、一退出应用，虚拟屏约 1 分钟后就黑屏，AI 读到的就是黑屏。
            VirtualDisplayManager.setBusyTag("ai", true)
            val wl = runCatching {
                (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VirtualClicker:ai").apply { acquire(60 * 60_000L) }
            }.getOrNull()
            runCatching { app.startForegroundService(Intent(app, AiKeepAliveService::class.java)) }
            val beat = launch {
                while (isActive) {
                    delay(30_000)
                    VirtualDisplayManager.heartbeat()
                }
            }
            val r = try {
                s.ask(text, displayId)
            } finally {
                beat.cancel()
                VirtualDisplayManager.setBusyTag("ai", false)
                runCatching { if (wl?.isHeld == true) wl.release() }
                runCatching { app.stopService(Intent(app, AiKeepAliveService::class.java)) }
            }
            if (AiConfig.notifyDone(app)) {
                val body = r.text.replace("\n", " ").take(80).ifBlank { r.outcome.title }
                AlertNotifier.notify(app, r.outcome.title, body)
            }
        }
    }

    fun stop(ctx: Context) {
        session(ctx).stop()
    }
}
