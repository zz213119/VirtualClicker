package com.zz213119.virtualclicker

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import com.zz213119.virtualclicker.core.VirtualDisplayManager
import com.zz213119.virtualclicker.shizuku.ShizukuController
import com.zz213119.virtualclicker.ui.AppPickerActivity
import com.zz213119.virtualclicker.ui.FloatingPreviewService

class MainActivity : AppCompatActivity() {
    private lateinit var controller: ShizukuController
    private lateinit var shizukuStatus: TextView
    private lateinit var backendStatus: TextView
    private lateinit var appList: TextView
    private lateinit var virtualDisplayStatus: TextView
    private lateinit var inputTestStatus: TextView
    private lateinit var inputX: android.widget.EditText
    private lateinit var inputY: android.widget.EditText
    private lateinit var inputDuration: android.widget.EditText
    private var currentDisplayId: Int = -1
    private lateinit var previewSurfaceView: SurfaceView
    private var previewSurface: Surface? = null
    private lateinit var previewPlaceholder: TextView
    private lateinit var statusDot: View
    private lateinit var statusBadgeText: TextView
    private lateinit var manualControlHint: TextView
    private var manualControlEnabled = false
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L

    // 预览用固定分辨率，要跟 createDisplay 调用里传的 width/height 保持一致，
    // 否则虚拟屏渲染出来的画面跟 SurfaceView 缓冲区大小对不上，会被裁切/拉伸。
    // 改成 var：支持“切换为 4:3”按钮动态调整。
    private var displayWidth = 1080
    private var displayHeight = 1920
    private var displayDpi = 320
    private var is4x3 = false

    private val binderListener = Shizuku.OnBinderReceivedListener {
        refreshStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        shizukuStatus = findViewById(R.id.shizukuStatus)
        backendStatus = findViewById(R.id.backendStatus)
        appList = findViewById(R.id.appList)
        virtualDisplayStatus = findViewById(R.id.virtualDisplayStatus)
        inputTestStatus = findViewById(R.id.inputTestStatus)
        inputX = findViewById(R.id.inputX)
        inputY = findViewById(R.id.inputY)
        inputDuration = findViewById(R.id.inputDuration)
        previewSurfaceView = findViewById(R.id.virtualDisplaySurface)
        previewPlaceholder = findViewById(R.id.previewPlaceholder)
        statusDot = findViewById(R.id.statusDot)
        statusBadgeText = findViewById(R.id.statusBadgeText)
        manualControlHint = findViewById(R.id.manualControlHint)
        previewSurfaceView.holder.setFixedSize(displayWidth, displayHeight)
        previewSurfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                previewSurface = holder.surface
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                previewSurface = null
            }
        })

        val doubleTapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                enterFloatingMode()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                manualControlEnabled = !manualControlEnabled
                manualControlHint.text = if (manualControlEnabled) {
                    "手动控制：已开启 —— 直接在画面上点击/滑动操作虚拟屏；再长按关闭"
                } else {
                    "长按预览画面：开启/关闭应用内手动控制；双击悬浮出去"
                }
                Toast.makeText(
                    this@MainActivity,
                    if (manualControlEnabled) "手动控制已开启" else "手动控制已关闭",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })

        previewSurfaceView.setOnTouchListener { view, event ->
            doubleTapDetector.onTouchEvent(event)

            if (manualControlEnabled) {
                handleManualTouch(view, event)
            }
            true
        }

        findViewById<Button>(R.id.toggleAspectRatio).setOnClickListener {
            toggleAspectRatio()
        }

        controller = ShizukuController(packageName)

        Shizuku.addBinderReceivedListener(binderListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        findViewById<Button>(R.id.shizukuPermission).setOnClickListener {
            if (controller.isShizukuAvailable()) controller.requestPermission()
            else shizukuStatus.text = "Shizuku：未运行"
            refreshStatus()
        }

        findViewById<Button>(R.id.bindBackend).setOnClickListener {
            controller.bind { connected ->
                runOnUiThread {
                    if (connected) {
                        val info = runCatching { controller.service?.ping() }.getOrNull()
                        backendStatus.text = "后台后端：已连接\n$info"
                    } else {
                        backendStatus.text = "后台后端：连接失败"
                    }
                }
            }
        }

        findViewById<Button>(R.id.listApps).setOnClickListener {
            appPickerLauncher.launch(Intent(this, AppPickerActivity::class.java))
        }

        findViewById<Button>(R.id.launchVirtualDisplay).setOnClickListener {
            launchSelectedAppOnVirtualDisplay()
        }

        findViewById<Button>(R.id.testTap).setOnClickListener {
            sendTestTap()
        }

        findViewById<Button>(R.id.testLongPress).setOnClickListener {
            sendTestLongPress()
        }

        findViewById<Button>(R.id.releaseVirtualDisplay).setOnClickListener {
            releaseCurrentDisplay()
        }

        refreshStatus()
        setPreviewRunning(false)
    }

    private fun setPreviewRunning(running: Boolean) {
        previewPlaceholder.visibility = if (running) View.GONE else View.VISIBLE
        statusDot.setBackgroundResource(
            if (running) R.drawable.dot_running else R.drawable.dot_idle
        )
        statusBadgeText.text = if (running) "运行中" else "待机"
    }

    /**
     * 手动控制：把预览 SurfaceView 上的触摸坐标，按显示比例换算成虚拟屏坐标，
     * 转发给 VirtualDisplayManager。按下-抬起距离很小当点击，距离大当滑动。
     */
    private fun handleManualTouch(view: View, event: MotionEvent) {
        val displayId = currentDisplayId
        if (displayId < 0) return

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
                val distance = kotlin.math.hypot(dx, dy)
                val duration = (System.currentTimeMillis() - touchDownTime).coerceIn(1, 30000)

                val startX = touchDownX * scaleX
                val startY = touchDownY * scaleY

                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        if (distance < 24f) {
                            VirtualDisplayManager.tap(displayId, startX, startY)
                        } else {
                            val endX = event.x * scaleX
                            val endY = event.y * scaleY
                            VirtualDisplayManager.swipe(
                                displayId, startX, startY, endX, endY, duration.toInt()
                            )
                        }
                    }
                }
            }
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            startFloatingService()
        } else {
            Toast.makeText(this, "没有授予悬浮窗权限，无法开启悬浮预览", Toast.LENGTH_SHORT).show()
        }
    }

    private fun enterFloatingMode() {
        val pkg = selectedPackageName
        if (pkg == null) {
            Toast.makeText(this, "请先点“查看可添加应用”选一个", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "需要悬浮窗权限，去设置里允许一下", Toast.LENGTH_SHORT).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }

        startFloatingService()
    }

    private fun startFloatingService() {
        // 悬浮窗会自己重新创建一份虚拟屏（Surface 换成悬浮窗自己的），
        // App 内这份如果还在跑就先释放掉，避免两边各占一个虚拟屏。
        if (currentDisplayId >= 0) {
            val oldId = currentDisplayId
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { VirtualDisplayManager.release(oldId) }
            }
            currentDisplayId = -1
            setPreviewRunning(false)
        }

        val intent = Intent(this, FloatingPreviewService::class.java).apply {
            putExtra(FloatingPreviewService.EXTRA_PACKAGE_NAME, selectedPackageName)
            putExtra(FloatingPreviewService.EXTRA_WIDTH, displayWidth)
            putExtra(FloatingPreviewService.EXTRA_HEIGHT, displayHeight)
            putExtra(FloatingPreviewService.EXTRA_DPI, displayDpi)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        virtualDisplayStatus.text = "已切到悬浮预览，回桌面找那个浮动小窗"
        moveTaskToBack(true)
    }

    private fun toggleAspectRatio() {
        is4x3 = !is4x3
        if (is4x3) {
            displayWidth = 1200
            displayHeight = 1600
        } else {
            displayWidth = 1080
            displayHeight = 1920
        }
        previewSurfaceView.holder.setFixedSize(displayWidth, displayHeight)
        findViewById<Button>(R.id.toggleAspectRatio).text =
            if (is4x3) "切换为 16:9" else "切换为 4:3"

        if (currentDisplayId >= 0) {
            val oldId = currentDisplayId
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { VirtualDisplayManager.release(oldId) }
                currentDisplayId = -1
                setPreviewRunning(false)
                virtualDisplayStatus.text =
                    "已切换分辨率为 ${displayWidth}x$displayHeight，原虚拟屏已释放，请重新点“启动到虚拟屏”"
            }
        } else {
            virtualDisplayStatus.text = "下次启动将使用 ${displayWidth}x$displayHeight"
        }
    }

    /**
     * Phase 1 验证链路：绑定 UserService → 建虚拟屏 → 把选中的 App 启动进去。
     * 每一步都单独回填状态文字，方便你截图/贴 Logcat 定位卡在哪一步。
     */
    private fun launchSelectedAppOnVirtualDisplay() {
        val pkg = selectedPackageName
        if (pkg == null) {
            Toast.makeText(this, "请先点“查看可添加应用”选一个", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            virtualDisplayStatus.text = "连接虚拟屏后端…"

            val bound = withContext(Dispatchers.IO) { VirtualDisplayManager.ensureBound() }
            if (!bound) {
                virtualDisplayStatus.text = "连接失败（确认 Shizuku 已授权本应用）"
                return@launch
            }

            virtualDisplayStatus.text = "创建虚拟屏…"
            val surface = previewSurface
            val displayId = withContext(Dispatchers.IO) {
                if (surface != null) {
                    VirtualDisplayManager.createDisplayWithSurface(
                        "vc_display_1", displayWidth, displayHeight, displayDpi, surface
                    )
                } else {
                    VirtualDisplayManager.createDisplay(
                        "vc_display_1", displayWidth, displayHeight, displayDpi
                    )
                }
            }
            if (displayId < 0) {
                virtualDisplayStatus.text = "创建虚拟屏失败，查看 Logcat tag VDUserService"
                return@launch
            }

            virtualDisplayStatus.text = "虚拟屏 #$displayId 已创建，正在启动 $pkg…"
            val ok = withContext(Dispatchers.IO) {
                VirtualDisplayManager.launch(pkg, displayId)
            }
            if (ok) {
                currentDisplayId = displayId
                setPreviewRunning(true)
            }
            virtualDisplayStatus.text = if (ok) {
                "已在虚拟屏 #$displayId 启动 $pkg，切回桌面看看它是否还在后台跑"
            } else {
                "启动失败，详细输出已保存到 Android/data/com.zz213119.virtualclicker/files/logs/；同时也会写入 Logcat tag VDUserService"
            }
        }
    }

    private fun readInputInt(editText: android.widget.EditText, defaultValue: Int): Int {
        return editText.text.toString().trim().toIntOrNull() ?: defaultValue
    }

    private fun sendTestTap() {
        val displayId = currentDisplayId
        if (displayId < 0) {
            inputTestStatus.text = "请先成功启动一个虚拟屏应用"
            return
        }

        val x = readInputInt(inputX, 540)
        val y = readInputInt(inputY, 960)

        lifecycleScope.launch {
            inputTestStatus.text = "正在发送点击：display=$displayId ($x,$y)…"
            val ok = withContext(Dispatchers.IO) {
                VirtualDisplayManager.tap(displayId, x.toFloat(), y.toFloat())
            }
            inputTestStatus.text = if (ok) {
                "点击发送成功：display=$displayId ($x,$y)"
            } else {
                "点击发送失败，查看日志目录"
            }
        }
    }

    private fun sendTestLongPress() {
        val displayId = currentDisplayId
        if (displayId < 0) {
            inputTestStatus.text = "请先成功启动一个虚拟屏应用"
            return
        }

        val x = readInputInt(inputX, 540)
        val y = readInputInt(inputY, 960)
        val duration = readInputInt(inputDuration, 1000).coerceIn(1, 30000)

        lifecycleScope.launch {
            inputTestStatus.text = "正在发送长按：display=$displayId ($x,$y) ${duration}ms…"
            val ok = withContext(Dispatchers.IO) {
                VirtualDisplayManager.longPress(displayId, x.toFloat(), y.toFloat(), duration)
            }
            inputTestStatus.text = if (ok) {
                "长按发送成功：display=$displayId ($x,$y) ${duration}ms"
            } else {
                "长按发送失败，查看日志目录"
            }
        }
    }

    private fun releaseCurrentDisplay() {
        val displayId = currentDisplayId
        if (displayId < 0) {
            inputTestStatus.text = "当前没有受 VirtualClicker 管理的虚拟屏"
            return
        }

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                VirtualDisplayManager.release(displayId)
            }
            currentDisplayId = -1
            setPreviewRunning(false)
            virtualDisplayStatus.text = "已释放虚拟屏 #$displayId"
            inputTestStatus.text = "虚拟屏已释放"
        }
    }
    private val appPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val label = result.data?.getStringExtra(AppPickerActivity.EXTRA_LABEL)
            val pkg = result.data?.getStringExtra(AppPickerActivity.EXTRA_PACKAGE_NAME)
            selectedPackageName = pkg
            appList.text = if (pkg != null) "已选择：$label\n$pkg" else ""
        }
    }

    /** 供后续 Phase 1 的 createDisplay+launch 调用链使用。 */
    var selectedPackageName: String? = null
        private set

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        controller.unbind()
        super.onDestroy()
    }

    private fun refreshStatus() {
        val available = controller.isShizukuAvailable()
        val granted = runCatching { controller.hasPermission() }.getOrDefault(false)
        shizukuStatus.text = when {
            !available -> "Shizuku：未运行"
            granted -> "Shizuku：已运行 / 已授权"
            else -> "Shizuku：已运行 / 未授权"
        }
    }

}
