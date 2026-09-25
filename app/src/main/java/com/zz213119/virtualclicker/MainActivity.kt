package com.zz213119.virtualclicker

import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
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
import com.zz213119.virtualclicker.service.AutoClickService
import com.zz213119.virtualclicker.shizuku.ShizukuController
import com.zz213119.virtualclicker.ui.AppPickerActivity

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
    private lateinit var resolutionSpinner: Spinner
    private lateinit var autoClickInterval: android.widget.EditText
    private lateinit var autoClickCount: android.widget.EditText
    private lateinit var autoClickStatus: TextView
    private var currentDisplayId: Int = -1
    private lateinit var previewSurfaceView: SurfaceView
    private var previewSurface: Surface? = null
    private lateinit var previewPlaceholder: TextView
    private lateinit var statusDot: View
    private lateinit var statusBadgeText: TextView
    private lateinit var manualControlHint: TextView
    private lateinit var previewContainer: View
    private lateinit var fullscreenCloseButton: TextView
    private var isFullscreen = false
    private var normalPreviewHeightPx = 0
    private var manualControlEnabled = false
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L

    // Invalidates an in-flight create operation when the user closes/reconfigures
    // the display before the binder call has returned.
    private var displayOperationGeneration = 0L

    // 虚拟屏使用可切换的 16:9 竖屏预设；SurfaceView 缓冲区会同步调整，
    // 避免虚拟屏输出和预览缓冲区尺寸不一致。
    private var displayWidth = 1080
    private var displayHeight = 1920
    private var displayDpi = 320

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
        resolutionSpinner = findViewById(R.id.resolutionSpinner)
        autoClickInterval = findViewById(R.id.autoClickInterval)
        autoClickCount = findViewById(R.id.autoClickCount)
        autoClickStatus = findViewById(R.id.autoClickStatus)
        previewSurfaceView = findViewById(R.id.virtualDisplaySurface)
        previewPlaceholder = findViewById(R.id.previewPlaceholder)
        statusDot = findViewById(R.id.statusDot)
        statusBadgeText = findViewById(R.id.statusBadgeText)
        manualControlHint = findViewById(R.id.manualControlHint)
        previewContainer = findViewById(R.id.previewContainer)
        fullscreenCloseButton = findViewById(R.id.fullscreenCloseButton)
        normalPreviewHeightPx = previewContainer.layoutParams.height
        fullscreenCloseButton.setOnClickListener { toggleFullscreenPreview() }
        previewSurfaceView.holder.setFixedSize(displayWidth, displayHeight)
        previewSurfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                previewSurface = holder.surface

                // Reattach the preview surface when the Activity/window recreates
                // it. The virtual display itself remains alive until explicitly
                // closed or the Activity is actually destroyed.
                val displayId = currentDisplayId
                if (displayId >= 0 && !isFinishing) {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            VirtualDisplayManager.setDisplaySurface(displayId, holder.surface)
                        }
                    }
                }
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

                // Detach the Surface instead of destroying the virtual display.
                // This prevents SurfaceFlinger from keeping a dead Surface attached
                // while allowing the target app/display to continue existing.
                val displayId = currentDisplayId
                if (displayId >= 0) {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            VirtualDisplayManager.setDisplaySurface(displayId, null)
                        }
                    }
                }
            }
        })

        val doubleTapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleFullscreenPreview()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                manualControlEnabled = !manualControlEnabled
                manualControlHint.text = if (manualControlEnabled) {
                    "手动控制：已开启 —— 直接在画面上点击/滑动操作虚拟屏；再长按关闭"
                } else {
                    "长按预览画面：开启/关闭应用内手动控制；双击放大"
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

        setupResolutionSpinner()

        findViewById<Button>(R.id.startAutoClick).setOnClickListener {
            startAutoClicker()
        }

        findViewById<Button>(R.id.stopAutoClick).setOnClickListener {
            stopAutoClicker()
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

        findViewById<Button>(R.id.closeVirtualDisplay).setOnClickListener {
            releaseCurrentDisplay()
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

    private fun toggleFullscreenPreview() {
        isFullscreen = !isFullscreen

        val hideIds = intArrayOf(
            R.id.title, R.id.subtitle, R.id.shizukuStatus, R.id.shizukuPermission,
            R.id.bindBackend, R.id.backendStatus, R.id.listApps, R.id.appList,
            R.id.launchVirtualDisplayLabel, R.id.resolutionLabel, R.id.resolutionSpinner,
            R.id.manualControlHint, R.id.launchRow, R.id.virtualDisplayStatus, R.id.inputTitle,
            R.id.inputHelp, R.id.inputRow, R.id.autoClickTitle, R.id.autoClickHelp,
            R.id.autoClickRow, R.id.autoClickButtons, R.id.autoClickStatus, R.id.testTap,
            R.id.testLongPress, R.id.releaseVirtualDisplay, R.id.inputTestStatus, R.id.roadmap
        )

        val lp = previewContainer.layoutParams
        if (isFullscreen) {
            lp.height = resources.displayMetrics.heightPixels
            for (id in hideIds) findViewById<View>(id)?.visibility = View.GONE
            fullscreenCloseButton.visibility = View.VISIBLE
        } else {
            lp.height = normalPreviewHeightPx
            for (id in hideIds) findViewById<View>(id)?.visibility = View.VISIBLE
            fullscreenCloseButton.visibility = View.GONE
        }
        previewContainer.layoutParams = lp
    }

    private data class ResolutionPreset(
        val label: String,
        val width: Int,
        val height: Int,
        val dpi: Int
    )

    private val resolutionPresets = listOf(
        ResolutionPreset("480p（480×854）", 480, 854, 160),
        ResolutionPreset("720p（720×1280）", 720, 1280, 240),
        ResolutionPreset("1080p（1080×1920）", 1080, 1920, 320)
    )

    private fun setupResolutionSpinner() {
        val labels = resolutionPresets.map { it.label }
        resolutionSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            labels
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        resolutionSpinner.setSelection(2, false)

        resolutionSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit

                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    applyResolution(resolutionPresets[position])
                }
            }
    }

    private fun applyResolution(preset: ResolutionPreset) {
        if (
            displayWidth == preset.width &&
            displayHeight == preset.height &&
            displayDpi == preset.dpi
        ) {
            previewSurfaceView.holder.setFixedSize(displayWidth, displayHeight)
            return
        }

        displayWidth = preset.width
        displayHeight = preset.height
        displayDpi = preset.dpi
        displayOperationGeneration++

        stopAutoClicker()

        val oldDisplayId = currentDisplayId
        currentDisplayId = -1
        setPreviewRunning(false)

        if (oldDisplayId >= 0) {
            val operation = displayOperationGeneration
            virtualDisplayStatus.text =
                "正在切换到 ${preset.label}，先释放当前虚拟屏…"
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    VirtualDisplayManager.release(oldDisplayId)
                }
                if (operation != displayOperationGeneration) return@launch
                previewSurfaceView.holder.setFixedSize(displayWidth, displayHeight)
                virtualDisplayStatus.text =
                    "已切换为 ${preset.label}，请重新点“启动到虚拟屏”"
            }
        } else {
            previewSurfaceView.holder.setFixedSize(displayWidth, displayHeight)
            virtualDisplayStatus.text = "下次启动将使用 ${preset.label}"
        }
    }

    private fun startAutoClicker() {
        val displayId = currentDisplayId
        if (displayId < 0) {
            autoClickStatus.text = "连点器：请先启动虚拟屏"
            return
        }

        val x = inputX.text.toString().trim().toFloatOrNull()
        val y = inputY.text.toString().trim().toFloatOrNull()
        if (x == null || y == null || x < 0f || y < 0f) {
            autoClickStatus.text = "连点器：请输入有效的 X / Y"
            return
        }

        val interval = inputIntOrNull(autoClickInterval.text.toString())
        val count = inputIntOrNull(autoClickCount.text.toString())
        if (interval == null || interval < 50) {
            autoClickStatus.text = "连点器：间隔最小 50ms"
            return
        }
        if (count == null || count < 0) {
            autoClickStatus.text = "连点器：次数必须是 0 或正整数"
            return
        }
        if (x > displayWidth || y > displayHeight) {
            autoClickStatus.text =
                "连点器：坐标超出当前 ${displayWidth}×${displayHeight} 虚拟屏"
            return
        }

        val intent = Intent(this, AutoClickService::class.java)
            .setAction(AutoClickService.ACTION_START)
            .putExtra(AutoClickService.EXTRA_DISPLAY_ID, displayId)
            .putExtra(AutoClickService.EXTRA_X, x)
            .putExtra(AutoClickService.EXTRA_Y, y)
            .putExtra(AutoClickService.EXTRA_INTERVAL_MS, interval.toLong())
            .putExtra(AutoClickService.EXTRA_REPEAT_COUNT, count)

        try {
            androidx.core.content.ContextCompat.startForegroundService(this, intent)
            autoClickStatus.text = if (count == 0) {
                "连点器：运行中 · ($x, $y) · ${interval}ms · 无限"
            } else {
                "连点器：运行中 · ($x, $y) · ${interval}ms · ${count}次"
            }
        } catch (t: Throwable) {
            autoClickStatus.text = "连点器启动失败：${t.message ?: "未知错误"}"
        }
    }

    private fun stopAutoClicker() {
        runCatching {
            stopService(
                Intent(this, AutoClickService::class.java)
                    .setAction(AutoClickService.ACTION_STOP)
            )
        }
        autoClickStatus.text = "连点器：已停止"
    }

    private fun inputIntOrNull(text: String): Int? =
        text.trim().toIntOrNull()

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

        if (currentDisplayId >= 0) {
            Toast.makeText(this, "当前已有虚拟屏，请先关闭后再启动", Toast.LENGTH_SHORT).show()
            return
        }

        val operation = ++displayOperationGeneration

        lifecycleScope.launch {
            virtualDisplayStatus.text = "连接虚拟屏后端…"

            val bound = withContext(Dispatchers.IO) { VirtualDisplayManager.ensureBound() }
            if (!bound) {
                if (operation == displayOperationGeneration) {
                    virtualDisplayStatus.text = "连接失败（确认 Shizuku 已授权本应用）"
                }
                return@launch
            }

            if (operation != displayOperationGeneration) return@launch

            virtualDisplayStatus.text = "创建虚拟屏…"
            val surface = previewSurface
            if (surface == null || !surface.isValid) {
                virtualDisplayStatus.text = "预览 Surface 尚未就绪，请稍后再试"
                return@launch
            }

            val displayId = withContext(Dispatchers.IO) {
                VirtualDisplayManager.createDisplayWithSurface(
                    "vc_display_1", displayWidth, displayHeight, displayDpi, surface
                )
            }

            if (displayId < 0) {
                if (operation == displayOperationGeneration) {
                    virtualDisplayStatus.text = "创建虚拟屏失败，查看日志目录与 Logcat tag VDUserService"
                }
                return@launch
            }

            // The user may have pressed close/aspect-ratio while createVirtualDisplay
            // was blocked in Binder. Do not orphan the newly-created display.
            if (operation != displayOperationGeneration || isFinishing) {
                withContext(Dispatchers.IO) {
                    VirtualDisplayManager.release(displayId)
                }
                return@launch
            }

            currentDisplayId = displayId
            setPreviewRunning(true)
            virtualDisplayStatus.text = "虚拟屏 #$displayId 已创建，正在启动 $pkg…"

            val ok = withContext(Dispatchers.IO) {
                VirtualDisplayManager.launch(pkg, displayId)
            }

            if (operation != displayOperationGeneration || isFinishing) {
                if (currentDisplayId == displayId) currentDisplayId = -1
                setPreviewRunning(false)
                withContext(Dispatchers.IO) {
                    VirtualDisplayManager.release(displayId)
                }
                return@launch
            }

            if (!ok) {
                currentDisplayId = -1
                setPreviewRunning(false)
                withContext(Dispatchers.IO) {
                    VirtualDisplayManager.release(displayId)
                }
            }

            virtualDisplayStatus.text = if (ok) {
                "已在虚拟屏 #$displayId 启动 $pkg，切回桌面看看它是否还在后台跑"
            } else {
                "启动失败，虚拟屏已自动释放；详细输出已保存到 Android/data/com.zz213119.virtualclicker/files/logs/"
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

        val x = readInputInt(inputX, displayWidth / 2)
        val y = readInputInt(inputY, displayHeight / 2)

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
        // Invalidate any create/launch coroutine that is still in flight.
        displayOperationGeneration++

        val displayId = currentDisplayId
        if (displayId < 0) {
            inputTestStatus.text = "当前没有受 VirtualClicker 管理的虚拟屏"
            return
        }

        // Clear the UI-owned id immediately so a second close or another
        // surface callback cannot release the same display twice.
        currentDisplayId = -1
        setPreviewRunning(false)
        stopAutoClicker()

        lifecycleScope.launch {
            virtualDisplayStatus.text = "正在彻底释放虚拟屏 #$displayId…"
            withContext(Dispatchers.IO) {
                VirtualDisplayManager.release(displayId)
            }
            virtualDisplayStatus.text = "已释放虚拟屏 #$displayId，系统显示资源正在完成清理"
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
        // Activity destruction is a real teardown point. Invalidate any
        // in-flight creation and ask the Shizuku backend to detach the
        // Surface and release the display before this Activity disappears.
        displayOperationGeneration++
        val displayId = currentDisplayId
        currentDisplayId = -1
        stopAutoClicker()
        if (displayId >= 0) {
            runCatching {
                VirtualDisplayManager.release(displayId)
            }.onFailure {
                android.util.Log.e("MainActivity", "final virtual display release failed", it)
            }
        }

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
