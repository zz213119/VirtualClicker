package com.zz213119.virtualclicker.ui

import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.zz213119.virtualclicker.service.ScriptRunnerService
import kotlin.random.Random

/**
 * 息屏显示：纯黑背景 + 暗灰色的时间、日期、电量和任务状态，亮度压到最低，系统保持唤醒（后台任务照常跑）。
 * OLED 屏纯黑像素不发光，效果和系统的息屏显示差不多；文字每分钟轻微位移一次，防止烧屏。
 * 双击屏幕或按返回键退出。
 */
class AodActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var hintText: TextView

    private val shiftRunnable = object : Runnable {
        override fun run() {
            val d = resources.displayMetrics.density
            content.translationX = Random.nextInt(-40, 41) * d
            content.translationY = Random.nextInt(-60, 61) * d
            handler.postDelayed(this, 60_000)
        }
    }
    private val statusRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 3_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.attributes = window.attributes.apply { screenBrightness = 0.01f }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        val gray = Color.rgb(150, 150, 150)
        val dim = Color.rgb(95, 95, 95)

        val time = TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "HH:mm"
            textSize = 96f
            setTextColor(gray)
            typeface = android.graphics.Typeface.create("sans-serif-thin", android.graphics.Typeface.NORMAL)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        val date = TextClock(this).apply {
            format24Hour = "M月d日 EEEE"
            format12Hour = "M月d日 EEEE"
            textSize = 18f
            setTextColor(gray)
            gravity = Gravity.CENTER
        }
        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(dim)
            gravity = Gravity.CENTER
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(time)
            addView(date, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (8 * resources.displayMetrics.density).toInt() })
            addView(statusText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (24 * resources.displayMetrics.density).toInt() })
        }
        hintText = TextView(this).apply {
            text = "双击屏幕退出"
            textSize = 12f
            setTextColor(Color.rgb(70, 70, 70))
            gravity = Gravity.CENTER
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(content, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(
                hintText,
                FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                    .apply { bottomMargin = (48 * resources.displayMetrics.density).toInt() }
            )
        }
        setContentView(root)

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                finish()
                return true
            }
        })
        root.setOnTouchListener { _, ev -> detector.onTouchEvent(ev); true }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })

        // 提示 6 秒后淡出
        handler.postDelayed({ hintText.animate().alpha(0f).setDuration(800).start() }, 6_000)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(shiftRunnable)
        handler.removeCallbacks(statusRunnable)
        handler.postDelayed(shiftRunnable, 60_000)
        handler.post(statusRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(shiftRunnable)
        handler.removeCallbacks(statusRunnable)
        super.onPause()
    }

    private fun refreshStatus() {
        val task = when (val id = ScriptRunnerService.activeTaskId) {
            null, "" -> "空闲"
            ScriptRunnerService.TASK_YIHUAN_SHOP -> "异环 · 店长特供 运行中"
            ScriptRunnerService.TASK_ONE_CLICK -> "异环 · 一键开刷 运行中"
            else -> "任务运行中"
        }
        val bat = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val charging = (bat?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1).let {
            it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
        }
        val batText = if (level >= 0) "${level * 100 / scale}%" + if (charging) " ⚡" else "" else ""
        statusText.text = listOf(task, batText).filter { it.isNotEmpty() }.joinToString("   ·   ")
    }
}
