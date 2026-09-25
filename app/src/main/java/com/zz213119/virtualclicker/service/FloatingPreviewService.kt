package com.zz213119.virtualclicker.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.zz213119.virtualclicker.MainActivity
import com.zz213119.virtualclicker.R
import com.zz213119.virtualclicker.core.VirtualDisplayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 图5那种“悬浮预览 + 竖排工具条”。虚拟屏的 Surface 直接挂在悬浮窗自己的
 * SurfaceView 上——不是从 MainActivity 搬运画面过来，是把虚拟屏重新创建
 * 一份，渲染目标换成这个悬浮窗的 Surface。
 */
class FloatingPreviewService : Service() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_DPI = "extra_dpi"

        private const val CHANNEL_ID = "virtual_clicker_floating"
        private const val NOTIFICATION_ID = 1001
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var windowManager: WindowManager
    private lateinit var rootView: View
    private lateinit var floatingCard: FrameLayout
    private lateinit var floatingSurface: SurfaceView
    private lateinit var floatingPackageLabel: TextView
    private lateinit var btnPlayPause: TextView
    private lateinit var layoutParams: WindowManager.LayoutParams

    private var displayId: Int = -1
    private var targetPackage: String = ""
    private var displayWidth = 1080
    private var displayHeight = 1920
    private var displayDpi = 320
    private var running = true
    private var cardHidden = false

    // Invalidates an in-flight create/attach operation when the floating
    // surface is recreated or the service is being destroyed.
    private var displayOperationGeneration = 0L

    // 拖动整个悬浮窗用
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartWinX = 0
    private var dragStartWinY = 0

    // 卡片区域内直接点击/滑动 = 操作虚拟屏
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            targetPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: targetPackage
            displayWidth = intent.getIntExtra(EXTRA_WIDTH, displayWidth)
            displayHeight = intent.getIntExtra(EXTRA_HEIGHT, displayHeight)
            displayDpi = intent.getIntExtra(EXTRA_DPI, displayDpi)
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!::rootView.isInitialized) {
            setupFloatingWindow()
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "虚拟屏悬浮预览", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("虚拟屏悬浮预览运行中")
            .setContentText(targetPackage)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun setupFloatingWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        rootView = LayoutInflater.from(this).inflate(R.layout.floating_preview, null)
        floatingCard = rootView.findViewById(R.id.floatingCard)
        floatingSurface = rootView.findViewById(R.id.floatingSurface)
        floatingPackageLabel = rootView.findViewById(R.id.floatingPackageLabel)
        floatingPackageLabel.text = targetPackage
        btnPlayPause = rootView.findViewById(R.id.btnPlayPause)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 200
        }

        windowManager.addView(rootView, layoutParams)

        floatingSurface.holder.setFixedSize(displayWidth, displayHeight)
        floatingSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                startVirtualDisplay(holder)
            }

            override fun surfaceChanged(
                holder: SurfaceHolder, format: Int, width: Int, height: Int
            ) {
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                displayOperationGeneration++
                val id = displayId
                if (id >= 0) {
                    serviceScope.launch {
                        kotlinx.coroutines.withContext(Dispatchers.IO) {
                            VirtualDisplayManager.setDisplaySurface(id, null)
                        }
                    }
                }
            }
        })

        wireTouchAndButtons()
    }

    private fun startVirtualDisplay(holder: SurfaceHolder) {
        val operation = ++displayOperationGeneration

        serviceScope.launch {
            val bound = kotlinx.coroutines.withContext(Dispatchers.IO) {
                VirtualDisplayManager.ensureBound()
            }
            if (!bound) return@launch

            val existingId = displayId
            if (existingId >= 0) {
                val attached = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    VirtualDisplayManager.setDisplaySurface(existingId, holder.surface)
                }
                if (!attached && displayId == existingId) {
                    displayId = -1
                } else {
                    return@launch
                }
            }

            if (operation != displayOperationGeneration) return@launch
            if (!holder.surface.isValid) return@launch

            val id = kotlinx.coroutines.withContext(Dispatchers.IO) {
                VirtualDisplayManager.createDisplayWithSurface(
                    "vc_floating", displayWidth, displayHeight, displayDpi, holder.surface
                )
            }
            if (id < 0) return@launch

            // Surface recreation/destruction or service teardown may have
            // happened while the Binder call was running. Release the
            // late-created display instead of leaving an orphan behind.
            if (operation != displayOperationGeneration) {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    VirtualDisplayManager.release(id)
                }
                return@launch
            }

            displayId = id

            val launched = kotlinx.coroutines.withContext(Dispatchers.IO) {
                VirtualDisplayManager.launch(targetPackage, id)
            }

            if (!launched || operation != displayOperationGeneration) {
                if (displayId == id) displayId = -1
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    VirtualDisplayManager.release(id)
                }
            }
        }
    }

    private fun wireTouchAndButtons() {
        // 拖动手柄：按住 ✥ 图标拖动整个悬浮窗
        val btnDrag = rootView.findViewById<TextView>(R.id.btnDrag)
        btnDrag.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    dragStartWinX = layoutParams.x
                    dragStartWinY = layoutParams.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = dragStartWinX + (event.rawX - dragStartRawX).toInt()
                    layoutParams.y = dragStartWinY + (event.rawY - dragStartRawY).toInt()
                    windowManager.updateViewLayout(rootView, layoutParams)
                    true
                }
                else -> false
            }
        }

        // 卡片区域：直接点击/滑动 = 操作虚拟屏（悬浮窗本身就是“手动控制”场景，不用再切模式）
        floatingSurface.setOnTouchListener { view, event ->
            val id = displayId
            if (id < 0) return@setOnTouchListener true

            val scaleX = displayWidth.toFloat() / view.width.coerceAtLeast(1)
            val scaleY = displayHeight.toFloat() / view.height.coerceAtLeast(1)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.x
                    touchDownY = event.y
                    touchDownTime = System.currentTimeMillis()
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - touchDownX
                    val dy = event.y - touchDownY
                    val distance = hypot(dx, dy)
                    val duration = (System.currentTimeMillis() - touchDownTime).coerceIn(1, 30000)
                    val startX = touchDownX * scaleX
                    val startY = touchDownY * scaleY
                    serviceScope.launch {
                        kotlinx.coroutines.withContext(Dispatchers.IO) {
                            if (distance < 24f) {
                                VirtualDisplayManager.tap(id, startX, startY)
                            } else {
                                VirtualDisplayManager.swipe(
                                    id, startX, startY,
                                    event.x * scaleX, event.y * scaleY, duration.toInt()
                                )
                            }
                        }
                    }
                }
            }
            true
        }

        btnPlayPause.setOnClickListener {
            running = !running
            btnPlayPause.text = if (running) "▶" else "⏸"
            // 任务引擎接进来之后，这里改成真正的“暂停/继续当前任务”。
        }

        rootView.findViewById<TextView>(R.id.btnZoomIn).setOnClickListener { resizeCard(1.15f) }
        rootView.findViewById<TextView>(R.id.btnZoomOut).setOnClickListener { resizeCard(1f / 1.15f) }

        rootView.findViewById<TextView>(R.id.btnSettings).setOnClickListener {
            val openApp = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(openApp)
        }

        rootView.findViewById<TextView>(R.id.btnHide).setOnClickListener {
            cardHidden = !cardHidden
            floatingCard.visibility = if (cardHidden) View.GONE else View.VISIBLE
        }

        rootView.findViewById<TextView>(R.id.btnClose).setOnClickListener {
            stopSelf()
        }
    }

    private fun resizeCard(factor: Float) {
        val lp = floatingCard.layoutParams
        val newWidth = (lp.width * factor).toInt().coerceIn(100, 260)
        val ratio = displayHeight.toFloat() / displayWidth.toFloat()
        lp.width = newWidth
        lp.height = (newWidth * ratio).toInt()
        floatingCard.layoutParams = lp
        windowManager.updateViewLayout(rootView, layoutParams)
    }

    override fun onDestroy() {
        // Explicit service teardown always releases the display. The
        // release path detaches the Surface before calling release().
        displayOperationGeneration++
        val id = displayId
        displayId = -1
        if (id >= 0) {
            runCatching {
                VirtualDisplayManager.release(id)
            }
        }
        if (::rootView.isInitialized) {
            runCatching { windowManager.removeView(rootView) }
        }
        super.onDestroy()
    }
}
