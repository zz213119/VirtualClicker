package com.zz213119.virtualclicker

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
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
                LogWriter.write(
                    "CRASH",
                    "thread=${t.name}\nheap used=${(rt.totalMemory() - rt.freeMemory()) / 1048576}MB max=${rt.maxMemory() / 1048576}MB\n$sw"
                )
            }
            previous?.uncaughtException(t, e)
        }
        runCatching { logLastExit() }
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
