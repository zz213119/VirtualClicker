package com.zz213119.virtualclicker.service

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogWriter {

    private const val TAG = "VCLogWriter"
    private const val APP_PACKAGE = "com.zz213119.virtualclicker"

    @Synchronized
    fun write(section: String, body: String) {
        runCatching {
            val dir = File(
                "/storage/emulated/0/Android/data/",
                "$APP_PACKAGE/files/logs"
            )
            if (!dir.exists()) dir.mkdirs()
            if (!dir.isDirectory) throw IllegalStateException("Log directory is not a directory")

            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val file = File(dir, "virtualclicker-$date.log")
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            file.appendText("\n[$time] [$section]\n$body\n", Charsets.UTF_8)
        }.onFailure {
            Log.e(TAG, "failed to persist log", it)
        }
    }
}