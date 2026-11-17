package com.zz213119.virtualclicker

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import com.zz213119.virtualclicker.service.LogWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记录闪退原因：
 * 1) 未捕获异常 → 把完整堆栈写进日志文件，再交给系统默认处理（照常闪退）；
 * 2) 每次进程启动时读取系统记录的“上一次进程为什么结束”（崩溃/被系统杀掉/内存不足/ANR…），写进日志。
 * 这样分享的日志里就能直接看到闪退原因，不用再去抓 logcat。
 */
class VcApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val rt = Runtime.getRuntime()
                LogWriter.writeSync(
                    "CRASH",
                    "thread=${t.name}\nheap used=${(rt.totalMemory() - rt.freeMemory()) / 1048576}MB max=${rt.maxMemory() / 1048576}MB\n$sw"
                )
            }
            previous?.uncaughtException(t, e)
        }
        runCatching { logLastExit() }
        registerLifecycleLog()
        startHangWatchdog()
    }

    @Volatile private var resumedCount = 0
    @Volatile private var lastLifecycle = "none"
    @Volatile private var lastLifecycleAt = SystemClock.uptimeMillis()

    /** 记录界面生命周期（按返回桌面 / 回到应用 / 窗口焦点），卡死日志里能对上“用户操作”的时刻。 */
    private fun registerLifecycleLog() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private fun mark(a: android.app.Activity, ev: String) {
                lastLifecycle = "${a.javaClass.simpleName}.$ev"
                lastLifecycleAt = SystemClock.uptimeMillis()
                LogWriter.write("LIFECYCLE", lastLifecycle)
            }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) = mark(a, "onCreate")
            override fun onActivityStarted(a: android.app.Activity) = mark(a, "onStart")
            override fun onActivityResumed(a: android.app.Activity) { resumedCount++; mark(a, "onResume") }
            override fun onActivityPaused(a: android.app.Activity) { resumedCount = (resumedCount - 1).coerceAtLeast(0); mark(a, "onPause") }
            override fun onActivityStopped(a: android.app.Activity) = mark(a, "onStop")
            override fun onActivitySaveInstanceState(a: android.app.Activity, o: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) = mark(a, "onDestroy")
        })
    }

    /** 所有线程的堆栈（主线程多取一些，其它线程取栈顶几行），限制总长度。 */
    private fun dumpThreads(): String {
        val sb = StringBuilder()
        val mainT = Looper.getMainLooper().thread
        val all = Thread.getAllStackTraces()
        all[mainT]?.let { st ->
            sb.append("== main ==\n")
            st.take(45).forEach { sb.append("    at ").append(it).append('\n') }
        }
        for ((t, st) in all) {
            if (t === mainT || st.isEmpty()) continue
            if (t.state == Thread.State.WAITING || t.state == Thread.State.TIMED_WAITING) {
                // 空闲等待的线程只记名字，省篇幅；真正在跑 / 被锁住的才展开
                if (st.size <= 12 && st[0].toString().let { it.contains("park") || it.contains("sleep") || it.contains("wait") || it.contains("nativePoll") }) {
                    sb.append("== ").append(t.name).append(" (").append(t.state).append(") idle\n")
                    continue
                }
            }
            sb.append("== ").append(t.name).append(" (").append(t.state).append(") ==\n")
            st.take(12).forEach { sb.append("    at ").append(it).append('\n') }
            if (sb.length > 9000) { sb.append("...truncated\n"); break }
        }
        return sb.toString()
    }

    /** 主线程的 schedstat：[占用 CPU 的纳秒, 在运行队列里等 CPU 的纳秒]。用来区分“被 CPU 饿住”和“卡在系统调用上”。 */
    private fun mainSched(): LongArray? = runCatching {
        val parts = java.io.File("/proc/self/task/${android.os.Process.myPid()}/schedstat").readText().trim().split(" ")
        longArrayOf(parts[0].toLong(), parts[1].toLong())
    }.getOrNull()

    /** 在监视线程里计时一次到系统进程的 binder 调用：它也很慢说明是系统进程(system_server)本身卡了，不是本应用。 */
    private fun binderProbeMs(): Long = runCatching {
        val t0 = SystemClock.uptimeMillis()
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.getMyMemoryState(ActivityManager.RunningAppProcessInfo())
        SystemClock.uptimeMillis() - t0
    }.getOrDefault(-1L)

    private fun sysInfo(): String {
        val rt = Runtime.getRuntime()
        val load = runCatching { java.io.File("/proc/loadavg").readText().trim() }.getOrDefault("?")
        return "loadavg=$load heap=${(rt.totalMemory() - rt.freeMemory()) / 1048576}/${rt.maxMemory() / 1048576}MB " +
            "foreground=${resumedCount > 0} lastLifecycle=$lastLifecycle(${SystemClock.uptimeMillis() - lastLifecycleAt}ms ago)"
    }

    /**
     * 主线程卡死监视：同时测两种延迟——
     * 1) 消息延迟：每 0.5 秒往主线程投一个普通消息，多久没被执行；
     * 2) 帧延迟：投一个 Choreographer 帧回调（前台时才算），多久没收到 vsync。
     * 超过 2 秒就记一次（2/4/6 秒各一次），写出是哪种延迟、所有线程的堆栈、系统负载和最近的生命周期事件。
     * 之前只看主线程堆栈，两次卡死的堆栈都是空闲(nativePollOnce)，看不出原因，所以加了帧延迟和全线程堆栈。
     */
    private fun startHangWatchdog() {
        val main = Handler(Looper.getMainLooper())
        val beat = AtomicLong(SystemClock.uptimeMillis())
        val frameBeat = AtomicLong(SystemClock.uptimeMillis())
        val pending = AtomicBoolean(false)
        val framePending = AtomicBoolean(false)
        Thread({
            var inHang = false
            var dumped = 0
            var hangFrom = 0L
            val samples = ArrayDeque<Triple<Long, Long, Long>>()   // 时间, 主线程CPU ns, 主线程等CPU ns
            while (true) {
                val now0 = SystemClock.uptimeMillis()
                mainSched()?.let { ss -> samples.addLast(Triple(now0, ss[0], ss[1])); while (samples.size > 12) samples.removeFirst() }
                if (!pending.getAndSet(true)) {
                    main.post { beat.set(SystemClock.uptimeMillis()); pending.set(false) }
                }
                val fg = resumedCount > 0
                if (!fg) frameBeat.set(now0)   // 不在前台时不算帧延迟
                else if (!framePending.getAndSet(true)) {
                    main.post {
                        android.view.Choreographer.getInstance().postFrameCallback {
                            frameBeat.set(SystemClock.uptimeMillis()); framePending.set(false)
                        }
                    }
                }
                try { Thread.sleep(500) } catch (_: InterruptedException) { return@Thread }
                val now = SystemClock.uptimeMillis()
                val msgLag = now - beat.get()
                val frameLag = now - frameBeat.get()
                val lag = maxOf(msgLag, frameLag)
                if (lag > 2000) {
                    if (!inHang) { inHang = true; dumped = 0; hangFrom = minOf(beat.get(), frameBeat.get()) }
                    if (dumped < 3 && lag > 2000L * (dumped + 1)) {
                        dumped++
                        runCatching {
                            val kind = when {
                                msgLag > 2000 && frameLag > 2000 -> "消息和帧都卡"
                                msgLag > 2000 -> "仅消息队列卡(帧正常)"
                                else -> "仅帧卡(消息正常，疑似 vsync/渲染)"
                            }
                            // 最近约 lag 毫秒里主线程：真正占用的 CPU、在运行队列里等 CPU 的时间
                            val old = samples.firstOrNull { now - it.first <= lag + 600 } ?: samples.firstOrNull()
                            val cur = mainSched()
                            val sched = if (old != null && cur != null)
                                "mainCpu=${(cur[0] - old.second) / 1_000_000}ms mainRunqWait=${(cur[1] - old.third) / 1_000_000}ms over ${now - old.first}ms" else "sched=?"
                            val probe = binderProbeMs()
                            LogWriter.write("UI HANG", "$kind msgLag=${msgLag}ms frameLag=${frameLag}ms (dump #$dumped)\n$sched binderProbe=${probe}ms\n${sysInfo()}\n${dumpThreads()}")
                        }
                    }
                } else if (inHang) {
                    inHang = false
                    LogWriter.write("UI HANG", "recovered after about ${SystemClock.uptimeMillis() - hangFrom}ms ${sysInfo()}")
                }
            }
        }, "vc-hang-watchdog").apply { isDaemon = true; start() }
    }


    private fun logLastExit() {
        LogWriter.write("PROCESS START", "pid=${android.os.Process.myPid()} build=${BuildConfig.VERSION_NAME}")
        if (Build.VERSION.SDK_INT < 30) return
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val list = am.getHistoricalProcessExitReasons(packageName, 0, 3)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val body = list.joinToString("\n") {
            "${fmt.format(Date(it.timestamp))} process=${it.processName} reason=${reasonName(it.reason)} status=${it.status} importance=${it.importance} desc=${it.description}"
        }
        if (body.isNotEmpty()) LogWriter.write("LAST EXITS", body)
    }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "UNKNOWN($r)"
    }
}
