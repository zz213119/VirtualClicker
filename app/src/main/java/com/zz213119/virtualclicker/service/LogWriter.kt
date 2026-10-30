package com.zz213119.virtualclicker.service

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

/**
 * 日志写入。
 * 以前 write() 是同步加锁的：日志目录归后台服务所有，应用自己写不进去，每一行日志都要同步调用一次后台服务（binder），
 * 而且持着全局锁——任何线程（包括主线程）只要打日志就可能被卡住，界面表现为“突然卡死”。
 * 现在 write() 只把日志放进队列立即返回，由单独的后台线程去写；崩溃时用 writeSync() 同步写。
 */
object LogWriter {

    private const val TAG = "VCLogWriter"
    private const val APP_PACKAGE = "com.zz213119.virtualclicker"

    private class Entry(val time: Long, val section: String, val body: String)

    private val queue = LinkedBlockingQueue<Entry>(8000)
    private val persistLock = Any()

    init {
        Thread({
            while (true) {
                try {
                    persist(queue.take())
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (t: Throwable) {
                    Log.e(TAG, "log worker error", t)
                }
            }
        }, "vc-log-writer").apply { isDaemon = true; start() }
    }

    /** 不阻塞：入队即返回（队列满了就丢弃，宁可丢日志也不能卡住调用方）。 */
    fun write(section: String, body: String) {
        queue.offer(Entry(System.currentTimeMillis(), section, body))
    }

    /** 同步写（崩溃处理等必须马上落盘的场合）。 */
    fun writeSync(section: String, body: String) {
        persist(Entry(System.currentTimeMillis(), section, body))
    }

    private fun persist(e: Entry) {
        synchronized(persistLock) {
            runCatching {
                val dir = File(
                    "/storage/emulated/0/Android/data/",
                    "$APP_PACKAGE/files/logs"
                )
                if (!dir.exists()) dir.mkdirs()
                if (!dir.isDirectory) throw IllegalStateException("Log directory is not a directory")

                val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(e.time))
                val file = File(dir, "virtualclicker-$date.log")
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(e.time))
                file.appendText("\n[$time] [${e.section}]\n${e.body}\n", Charsets.UTF_8)
            }.onFailure {
                // 应用自己写不进日志目录（目录归后台服务所有）→ 转给后台服务写
                com.zz213119.virtualclicker.core.VirtualDisplayManager.appendLogRemote(e.section, e.body)
            }
        }
    }
}
