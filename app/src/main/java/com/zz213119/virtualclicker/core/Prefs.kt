package com.zz213119.virtualclicker.core

import android.content.Context

/** 设置页的所有选项，都存在 SharedPreferences 里。 */
object Prefs {
    const val MODE_SHIZUKU = "shizuku"
    const val MODE_ROOT = "root"

    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences("vc_prefs", Context.MODE_PRIVATE)

    fun backendMode(c: Context): String =
        sp(c).getString("backend_mode", MODE_SHIZUKU) ?: MODE_SHIZUKU

    fun setBackendMode(c: Context, mode: String) =
        sp(c).edit().putString("backend_mode", mode).apply()

    // ---- 任务参数 ----
    fun hammerPeriodMs(c: Context): Long = sp(c).getLong("hammer_period_ms", 800L).coerceIn(200L, 5000L)
    fun maxLoops(c: Context): Int = sp(c).getInt("max_loops", 60).coerceIn(1, 1000)
    fun staminaStop(c: Context): Int = sp(c).getInt("stamina_stop", 0).coerceIn(0, 9999)
    fun saveTaskParams(c: Context, hammerMs: Long, loops: Int, stamina: Int) =
        sp(c).edit()
            .putLong("hammer_period_ms", hammerMs.coerceIn(200L, 5000L))
            .putInt("max_loops", loops.coerceIn(1, 1000))
            .putInt("stamina_stop", stamina.coerceIn(0, 9999))
            .apply()

    // ---- 开关 ----
    fun notifyEnabled(c: Context) = sp(c).getBoolean("notify", true)
    fun cpuWakeLock(c: Context) = sp(c).getBoolean("cpu_wakelock", true)
    fun keepDisplayAwake(c: Context) = sp(c).getBoolean("keep_display_awake", true)
    fun showDebugTools(c: Context) = sp(c).getBoolean("show_debug_tools", true)

    fun setBool(c: Context, key: String, value: Boolean) =
        sp(c).edit().putBoolean(key, value).apply()
}
