package com.zz213119.virtualclicker.ai

import android.content.Context
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
            val r = s.ask(text, displayId)
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
