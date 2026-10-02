package com.zz213119.virtualclicker

import android.content.Intent
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.content.pm.PackageManager
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
import com.zz213119.virtualclicker.service.ScriptRunBus
import com.zz213119.virtualclicker.service.ScriptRunState
import com.zz213119.virtualclicker.ui.ScriptTrajectoryView
import com.zz213119.virtualclicker.shizuku.ShizukuController
import com.zz213119.virtualclicker.ui.AppPickerActivity
import com.zz213119.virtualclicker.ui.AspectRatioFrameLayout
import com.zz213119.virtualclicker.ui.FullscreenPreviewDialog
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    companion object {
        private const val STATE_DISPLAY_ID = "state_display_id"
        private const val STATE_SELECTED_PACKAGE = "state_selected_package"
        private const val STATE_SELECTED_LABEL = "state_selected_label"
        private const val STATE_DISPLAY_WIDTH = "state_display_width"
        private const val STATE_DISPLAY_HEIGHT = "state_display_height"
        private const val STATE_DISPLAY_DPI = "state_display_dpi"
        private const val STATE_DISPLAY_LANDSCAPE = "state_display_landscape"
        private const val STATE_GAME_MODE = "state_game_mode"
        private const val STATE_RESOLUTION_POSITION = "state_resolution_position"
        // Let the target Activity create its window before switching the
        // VirtualDisplay output from the internal sink to the preview Surface.
        private const val SURFACE_ATTACH_DELAY_MS = 800L

        private const val PREFS_NAME = "virtualclicker_preferences"
        private const val PREF_SELECTED_PACKAGE = "selected_package"
        private const val PREF_SELECTED_LABEL = "selected_label"
    }

    private val preferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    private val coordinateMarkers =
        mutableListOf<com.zz213119.virtualclicker.ui.CoordinateMarkerView>()

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
    private lateinit var pickCoordinateButton: Button
    private lateinit var pickCoordinateStatus: TextView
    private var pointPickMode = false
    private var currentDisplayId: Int = -1
    private lateinit var previewSurfaceView: SurfaceView
    private var previewSurface: Surface? = null
    private lateinit var previewPlaceholder: TextView
    private lateinit var statusDot: View
    private lateinit var statusBadgeText: TextView
    private lateinit var manualControlHint: TextView
    private lateinit var previewContainer: AspectRatioFrameLayout
    private lateinit var resolutionInfo: TextView
    private lateinit var displayModeButton: Button
    private lateinit var fullscreenCloseButton: TextView
    private lateinit var scriptTrajectoryView: ScriptTrajectoryView
    private var manualControlEnabled = false

    private var nextCoordinateMarkerNumber = 1
    private var manualTouchActive = false
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L
    // Invalidates an in-flight create operation when the user closes/reconfigures
    // the display before the binder call has returned.
    private var displayOperationGeneration = 0L

    // 480p/720p/1080p 表示短边质量。实际宽高根据目标应用自动识别
    // 为横屏或竖屏，并同步调整 SurfaceView 缓冲区和预览容器比例。
    private var displayWidth = 1080
    private var displayHeight = 1920
    private var displayDpi = 320
    private var displayLandscape = false
    private var gameMode = false
    private var detectedTargetLandscape: Boolean? = null

    private val binderListener = Shizuku.OnBinderReceivedListener {
        refreshStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        refreshStatus()
    }

    private val scriptRunListener: (ScriptRunState) -> Unit = { state ->
        runOnUiThread {
            if (!::scriptTrajectoryView.isInitialized) return@runOnUiThread
            scriptTrajectoryView.setDisplaySize(displayWidth, displayHeight)
            scriptTrajectoryView.updateState(state)
            scriptTrajectoryView.visibility =
                if (state.running && state.displayId == currentDisplayId) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
        }
    }

    override fun onStart() {
        super.onStart()
        ScriptRunBus.register(scriptRunListener)
    }

    override fun onStop() {
        ScriptRunBus.unregister(scriptRunListener)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        savedInstanceState?.let { state ->
            currentDisplayId = state.getInt(STATE_DISPLAY_ID, -1)
            displayWidth = state.getInt(STATE_DISPLAY_WIDTH, displayWidth)
            displayHeight = state.getInt(STATE_DISPLAY_HEIGHT, displayHeight)
            displayDpi = state.getInt(STATE_DISPLAY_DPI, displayDpi)
            displayLandscape = state.getBoolean(STATE_DISPLAY_LANDSCAPE, displayLandscape)
            gameMode = state.getBoolean(STATE_GAME_MODE, gameMode)
        }

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
        pickCoordinateButton = findViewById(R.id.pickCoordinate)
        pickCoordinateStatus = findViewById(R.id.pickCoordinateStatus)
        previewSurfaceView = findViewById(R.id.virtualDisplaySurface)
        previewPlaceholder = findViewById(R.id.previewPlaceholder)
        scriptTrajectoryView = findViewById(R.id.scriptTrajectoryView)
        scriptTrajectoryView.setDisplaySize(displayWidth, displayHeight)
        scriptTrajectoryView.visibility = View.GONE
        statusDot = findViewById(R.id.statusDot)
        statusBadgeText = findViewById(R.id.statusBadgeText)
        manualControlHint = findViewById(R.id.manualControlHint)
        previewContainer = findViewById(R.id.previewContainer)
        resolutionInfo = findViewById(R.id.resolutionInfo)
        displayModeButton = findViewById(R.id.displayModeButton)
        fullscreenCloseButton = findViewById(R.id.fullscreenCloseButton)

        selectedPackageName =
            savedInstanceState?.getString(STATE_SELECTED_PACKAGE)
                ?: preferences.getString(PREF_SELECTED_PACKAGE, null)

        val restoredLabel =
            savedInstanceState?.getString(STATE_SELECTED_LABEL)
                ?: preferences.getString(PREF_SELECTED_LABEL, null)
        if (selectedPackageName != null) {
            appList.text = "已选择：" + (restoredLabel ?: selectedPackageName) + "\n" + selectedPackageName
        }
        fullscreenCloseButton.visibility = View.GONE
        displayModeButton.setOnClickListener { toggleGameMode() }
        fullscreenCloseButton.setOnClickListener {
            manualControlEnabled = false
            manualTouchActive = false
            fullscreenCloseButton.visibility = View.GONE
            manualControlHint.text = "双击预览画面：进入本人手动控制；点左上角 ✕ 结束控制（不会关闭虚拟屏）"
            Toast.makeText(this, "已结束手动控制，虚拟屏仍保持运行", Toast.LENGTH_SHORT).show()
        }
        previewContainer.setAspectRatio(displayWidth, displayHeight)
        scriptTrajectoryView.setDisplaySize(displayWidth, displayHeight)
        previewSurfaceView.holder.setFixedSize(displayWidth, displayHeight)
        previewSurfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                previewSurface = holder.surface

                // Reattach a newly-created UI Surface only once it is stable.
                // The initial launch path attaches it explicitly after the
                // target Activity has started, so we avoid a create-time race.
                val displayId = currentDisplayId
                if (displayId >= 0 && !isFinishing) {
                    lifecycleScope.launch {
                        kotlinx.coroutines.delay(SURFACE_ATTACH_DELAY_MS)
                        val surface = previewSurface
                        if (currentDisplayId == displayId &&
                            surface === holder.surface &&
                            surface?.isValid == true &&
                            !isFinishing
                        ) {
                            withContext(Dispatchers.IO) {
                                VirtualDisplayManager.setDisplaySurface(displayId, surface)
                            }
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
                openFullscreenPreview()
                return true
            }
        })

        previewSurfaceView.setOnTouchListener { view, event ->
            if (pointPickMode) {
                handleCoordinatePick(view, event)
                return@setOnTouchListener true
            }

            doubleTapDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    manualTouchActive = manualControlEnabled
                    if (manualTouchActive) handleManualTouch(view, event)
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (manualTouchActive) handleManualTouch(view, event)
                    if (event.actionMasked == MotionEvent.ACTION_UP ||
                        event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        manualTouchActive = false
                    }
                }
            }
            true
        }

        setupResolutionSpinner()

        pickCoordinateButton.setOnClickListener {
            setPointPickMode(!pointPickMode)
        }

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

        findViewById<Button>(R.id.openScriptEditor).setOnClickListener {
            val intent = Intent(this, com.zz213119.virtualclicker.ui.ScriptEditorActivity::class.java)
                .putExtra(
                    com.zz213119.virtualclicker.ui.ScriptEditorActivity.EXTRA_DISPLAY_ID,
                    currentDisplayId
                )
                .putExtra("extra_editor_display_width", displayWidth)
                .putExtra("extra_editor_display_height", displayHeight)
            startActivity(intent)
        }

        findViewById<Button>(R.id.launchVirtualDisplay).setOnClickListener {
            launchSelectedAppOnVirtualDisplay()
        }

        findViewById<Button>(R.id.runYihuanShopTask).setOnClickListener {
            if (currentDisplayId < 0) {
                Toast.makeText(this, "请先启动虚拟屏，并让异环停在店内能看到\"店长特供\"的画面", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val taskIntent = Intent(this, com.zz213119.virtualclicker.service.ScriptRunnerService::class.java)
                .setAction(com.zz213119.virtualclicker.service.ScriptRunnerService.ACTION_RUN_TASK)
                .putExtra(com.zz213119.virtualclicker.service.ScriptRunnerService.EXTRA_DISPLAY_ID, currentDisplayId)
                .putExtra(
                    com.zz213119.virtualclicker.service.ScriptRunnerService.EXTRA_TASK_ID,
                    com.zz213119.virtualclicker.service.ScriptRunnerService.TASK_YIHUAN_SHOP
                )
            androidx.core.content.ContextCompat.startForegroundService(this, taskIntent)
            virtualDisplayStatus.text = "异环任务已启动：结果见日志与 files/shots 截图"
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

        val restoredDisplayId = currentDisplayId
        val displayStillExists = restoredDisplayId >= 0 &&
            (getSystemService(DisplayManager::class.java)?.getDisplay(restoredDisplayId) != null)

        if (!displayStillExists && savedInstanceState != null) {
            currentDisplayId = -1
        }

        val savedResolutionPosition = savedInstanceState?.getInt(STATE_RESOLUTION_POSITION, -1) ?: -1
        if (savedResolutionPosition in resolutionPresets.indices) {
            resolutionSpinner.setSelection(savedResolutionPosition, false)
            applySelectedResolutionGeometry(resolutionPresets[savedResolutionPosition])
            previewSurfaceView.holder.setFixedSize(displayWidth, displayHeight)
            previewContainer.setAspectRatio(displayWidth, displayHeight)
            updateResolutionInfo()
        } else {
            updateResolutionInfo()
        }

        updateDisplayModeButton()
        refreshStatus()
        setPreviewRunning(currentDisplayId >= 0)

        if (currentDisplayId >= 0) {
            virtualDisplayStatus.text = "虚拟屏 #" + currentDisplayId + " 正在后台运行 · " +
                displayWidth + "×" + displayHeight
        }
        previewContainer.post {
            coordinateMarkers.forEach { marker ->
                marker.setNormalizedPosition(marker.normalizedX, marker.normalizedY)
            }
        }
    }

    private fun setPreviewRunning(running: Boolean) {
        previewPlaceholder.visibility = if (running) View.GONE else View.VISIBLE
        statusDot.setBackgroundResource(
            if (running) R.drawable.dot_running else R.drawable.dot_idle
        )
        statusBadgeText.text = if (running) "运行中" else "待机"
    }

    /**
     * Convert a touch point on the preview view into the current Virtual
     * Display coordinate system and fill the same X/Y fields used by the
     * auto clicker.
     */
    private fun handleCoordinatePick(view: View, event: MotionEvent) {
        if (event.action != MotionEvent.ACTION_UP) return

        val viewWidth = view.width.coerceAtLeast(1)
        val viewHeight = view.height.coerceAtLeast(1)

        val x = (event.x / viewWidth.toFloat() * displayWidth)
            .roundToInt()
            .coerceIn(0, displayWidth - 1)
        val y = (event.y / viewHeight.toFloat() * displayHeight)
            .roundToInt()
            .coerceIn(0, displayHeight - 1)

        inputX.setText(x.toString())
        inputY.setText(y.toString())
        addCoordinateMarker(x.toFloat(), y.toFloat())

        pickCoordinateStatus.text =
            "已获取坐标：X=$x  Y=$y · ${displayWidth}×${displayHeight} · 长按球球可移动"

        Toast.makeText(
            this,
            "已获取坐标：($x, $y)",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun addCoordinateMarker(x: Float, y: Float) {
        val markerNumber = nextCoordinateMarkerNumber++
        val marker = com.zz213119.virtualclicker.ui.CoordinateMarkerView(
            this,
            markerNumber,
            onMoved = { markerPxX, markerPxY ->
                val parentWidth = previewContainer.width.coerceAtLeast(1)
                val parentHeight = previewContainer.height.coerceAtLeast(1)
                val coordinateX = (markerPxX / parentWidth * displayWidth)
                    .coerceIn(0f, (displayWidth - 1).toFloat())
                val coordinateY = (markerPxY / parentHeight * displayHeight)
                    .coerceIn(0f, (displayHeight - 1).toFloat())

                inputX.setText(coordinateX.roundToInt().toString())
                inputY.setText(coordinateY.roundToInt().toString())
                pickCoordinateStatus.text =
                    "已调整坐标：X=" + coordinateX.roundToInt() + "  Y=" + coordinateY.roundToInt() + " · 长按拖动中"
            },
            onTapped = { markerPxX, markerPxY ->
                // Tapping an existing marker adds another marker at the same
                // coordinate, so identical points are allowed.
                val parentWidth = previewContainer.width.coerceAtLeast(1)
                val parentHeight = previewContainer.height.coerceAtLeast(1)
                val coordinateX = (markerPxX / parentWidth * displayWidth)
                    .coerceIn(0f, (displayWidth - 1).toFloat())
                val coordinateY = (markerPxY / parentHeight * displayHeight)
                    .coerceIn(0f, (displayHeight - 1).toFloat())
                addCoordinateMarker(coordinateX, coordinateY)
                inputX.setText(coordinateX.roundToInt().toString())
                inputY.setText(coordinateY.roundToInt().toString())
            }
        )

        coordinateMarkers += marker
        previewContainer.addView(marker)

        marker.post {
            marker.setNormalizedPosition(
                x / displayWidth.coerceAtLeast(1),
                y / displayHeight.coerceAtLeast(1)
            )
        }
    }

    private fun clearCoordinateMarkers() {
        coordinateMarkers.forEach { previewContainer.removeView(it) }
        coordinateMarkers.clear()
        nextCoordinateMarkerNumber = 1
    }

    private data class ResolutionPreset(
        val label: String,
        val shortEdge: Int,
        val longEdge: Int,
        val dpi: Int
    )

    private val resolutionPresets = listOf(
        ResolutionPreset("480p", 480, 854, 160),
        ResolutionPreset("720p", 720, 1280, 240),
        ResolutionPreset("1080p", 1080, 1920, 320)
    )

    private fun setupResolutionSpinner() {
        val labels = resolutionPresets.map { it.label }
        resolutionSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, labels
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
        updateResolutionInfo()
    }

    private fun toggleGameMode() {
        gameMode = !gameMode

        if (gameMode) {
            // 4:3 changes only the Virtual Display geometry. The controller
            // Activity stays in its current orientation.
            displayLandscape = true
        } else {
            // Leaving 4:3 restores the target app's natural display geometry.
            detectedTargetLandscape?.let { landscape ->
                displayLandscape = landscape
            }
        }

        updateDisplayModeButton()
        val position = resolutionSpinner.selectedItemPosition.coerceIn(0, resolutionPresets.lastIndex)
        val preset = resolutionPresets[position]
        applySelectedResolutionGeometry(preset)

        if (currentDisplayId >= 0) {
            stopAutoClicker()
            val oldDisplayId = currentDisplayId
            currentDisplayId = -1
            setPreviewRunning(false)
            displayOperationGeneration++
            virtualDisplayStatus.text =
                "正在切换${if (gameMode) "游戏模式（4:3）" else "普通模式（自动比例）"}，先释放当前虚拟屏…"
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { VirtualDisplayManager.release(oldDisplayId) }
                previewSurfaceView.holder.setFixedSize(displayWidth, displayHeight)
                previewContainer.setAspectRatio(displayWidth, displayHeight)
                updateResolutionInfo()
                virtualDisplayStatus.text =
                    "已切换到${if (gameMode) "游戏模式（4:3）" else "普通模式"}，请重新点“启动到虚拟屏”"
            }
        } else {
            applySelectedResolutionGeometry(preset)
            previewSurfaceView.holder.setFixedSize(displayWidth, displayHeight)
            previewContainer.setAspectRatio(displayWidth, displayHeight)
            updateResolutionInfo()
            virtualDisplayStatus.text =
                "下次启动将使用${if (gameMode) "游戏模式（4:3）" else "普通模式（自动比例）"}"
        }
    }

    private fun updateDisplayModeButton() {
        displayModeButton.text =
            if (gameMode) "游戏模式：开启（4:3）" else "游戏模式：关闭（自动比例）"
    }

    private fun applySelectedResolutionGeometry(preset: ResolutionPreset) {
        if (gameMode) {
            val short = preset.shortEdge
            val long = short * 4 / 3
            displayWidth = if (displayLandscape) long else short
            displayHeight = if (displayLandscape) short else long
        } else {
            displayWidth = if (displayLandscape) preset.longEdge else preset.shortEdge
            displayHeight = if (displayLandscape) preset.shortEdge else preset.longEdge
        }
        displayDpi = preset.dpi
        if (::scriptTrajectoryView.isInitialized) {
            scriptTrajectoryView.setDisplaySize(displayWidth, displayHeight)
        }
    }

    private fun applyResolution(preset: ResolutionPreset) {
        val oldWidth = displayWidth
        val oldHeight = displayHeight
        applySelectedResolutionGeometry(preset)
        val newWidth = displayWidth
        val newHeight = displayHeight

        if (oldWidth == newWidth && oldHeight == newHeight && displayDpi == preset.dpi) {
            previewSurfaceView.holder.setFixedSize(displayWidth, displayHeight)
            previewContainer.setAspectRatio(displayWidth, displayHeight)
            updateResolutionInfo()
            return
        }

        displayOperationGeneration++
        stopAutoClicker()
        val oldDisplayId = currentDisplayId
        currentDisplayId = -1
        setPreviewRunning(false)

        if (oldDisplayId >= 0) {
            val operation = displayOperationGeneration
            virtualDisplayStatus.text =
                "正在切换到 ${preset.label}${orientationLabel()}，先释放当前虚拟屏…"
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { VirtualDisplayManager.release(oldDisplayId) }
                if (operation != displayOperationGeneration) return@launch
                previewSurfaceView.holder.setFixedSize(displayWidth, displayHeight)
                previewContainer.setAspectRatio(displayWidth, displayHeight)
                updateResolutionInfo()
                virtualDisplayStatus.text =
                    "已切换为 ${preset.label}${orientationLabel()}，请重新点“启动到虚拟屏”"
            }
        } else {
            previewSurfaceView.holder.setFixedSize(displayWidth, displayHeight)
            previewContainer.setAspectRatio(displayWidth, displayHeight)
            updateResolutionInfo()
            virtualDisplayStatus.text =
                "下次启动将使用 ${preset.label}${orientationLabel()}"
        }
    }

    private fun orientationLabel(): String = if (displayLandscape) " · 横屏" else " · 竖屏"

    private fun updateResolutionInfo() {
        if (!::resolutionSpinner.isInitialized || !::resolutionInfo.isInitialized) return
        val position = resolutionSpinner.selectedItemPosition.coerceIn(0, resolutionPresets.lastIndex)
        val preset = resolutionPresets[position]
        val ratioLabel = if (gameMode) "4:3" else "自动比例"
        resolutionInfo.text =
            "当前：${preset.label}（${displayWidth}×${displayHeight}）${orientationLabel()} · $ratioLabel"
        previewContainer.setAspectRatio(displayWidth, displayHeight)
    }

    private fun detectTargetLandscape(packageName: String): Boolean? {
        return runCatching {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
                ?: return@runCatching null
            val component = intent.component ?: return@runCatching null
            val info = packageManager.getActivityInfo(component, PackageManager.MATCH_DEFAULT_ONLY)
            when (info.screenOrientation) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE -> true
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT -> false
                else -> null
            }
        }.getOrNull()
    }

    private fun applyDetectedOrientation(packageName: String) {
        val landscape = detectTargetLandscape(packageName)
        detectedTargetLandscape = landscape
        if (landscape == null || gameMode) {
            updateResolutionInfo()
            return
        }
        if (displayLandscape != landscape) {
            displayLandscape = landscape
            val position = resolutionSpinner.selectedItemPosition.coerceIn(0, resolutionPresets.lastIndex)
            applyResolution(resolutionPresets[position])
        } else {
            updateResolutionInfo()
        }
    }

    /** Updates the main-preview hint while coordinate-picking is active. */
    private fun setPointPickMode(enabled: Boolean) {
        pointPickMode = enabled

        if (enabled) {
            manualControlHint.text =
                "取点模式：点击预览画面获取坐标，不会点击目标应用；再次点“结束取点”退出"
            pickCoordinateButton.text = "结束取点"
            pickCoordinateStatus.text =
                "取点模式：已开启 · 当前虚拟屏 ${displayWidth}×${displayHeight}"
        } else {
            pickCoordinateButton.text = "取点坐标（点击预览获取 X/Y）"
            pickCoordinateStatus.text = "取点模式：未开启"
            manualControlHint.text =
                "双击预览画面：进入本人手动控制；点左上角 ✕ 结束控制（不会关闭虚拟屏）"
        }
    }

    private fun openFullscreenPreview() {
        val displayId = currentDisplayId
        if (displayId < 0) {
            Toast.makeText(this, "请先启动虚拟屏应用", Toast.LENGTH_SHORT).show()
            return
        }

        FullscreenPreviewDialog(
            activity = this,
            displayId = displayId,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            onClosed = {
                val surface = previewSurface
                if (currentDisplayId == displayId && surface?.isValid == true) {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            VirtualDisplayManager.setDisplaySurface(displayId, surface)
                        }
                    }
                }
            }
        ).show()
    }
    private fun handleManualTouch(view: View, event: MotionEvent) {
        val displayId = currentDisplayId
        if (displayId < 0) return

        val scaleX = displayWidth.toFloat() / view.width.coerceAtLeast(1)
        val scaleY = displayHeight.toFloat() / view.height.coerceAtLeast(1)

        // Forward the complete touch stream to the Virtual Display so MOVE
        // events are continuous. The old implementation waited for ACTION_UP
        // and converted the whole gesture into one shell command, which added
        // noticeable latency and could lose swipes.
        val transformed = MotionEvent.obtain(event)
        transformed.transform(android.graphics.Matrix().apply {
            setScale(scaleX, scaleY)
        })
        val ok = VirtualDisplayManager.injectMotionEvent(transformed, displayId)
        transformed.recycle()

        if (!ok && event.actionMasked != MotionEvent.ACTION_MOVE) {
            inputTestStatus.text = "手动输入注入失败，请查看日志"
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
                "连点器：运行中 · (${x}, ${y}) · ${interval}ms · 无限"
            } else {
                "连点器：运行中 · (${x}, ${y}) · ${interval}ms · ${count}次"
            }
        } catch (t: Throwable) {
            autoClickStatus.text = "连点器启动失败：${t.message ?: "未知错误"}"
        }
    }

    /**
     * Phase 1 验证链路：绑定 UserService → 建虚拟屏 → 把选中的 App 启动进去。
     * 每一步都单独回填状态文字，方便你截图/贴 Logcat 定位卡在哪一步。
     */
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
            // Detect the target launch Activity before creating the display.
            // Landscape apps such as games therefore receive a landscape
            // Virtual Display and no longer get a giant portrait black area.
            val detectedLandscape = withContext(Dispatchers.Default) {
                detectTargetLandscape(pkg)
            }
            if (detectedLandscape != null) {
                detectedTargetLandscape = detectedLandscape
                displayLandscape = if (gameMode) true else detectedLandscape
            }
            val position = resolutionSpinner.selectedItemPosition
                .coerceIn(0, resolutionPresets.lastIndex)
            val preset = resolutionPresets[position]
            applySelectedResolutionGeometry(preset)
            previewSurfaceView.holder.setFixedSize(displayWidth, displayHeight)
            previewContainer.setAspectRatio(displayWidth, displayHeight)
            updateResolutionInfo()

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
            if (previewSurface?.isValid != true) {
                virtualDisplayStatus.text = "预览 Surface 尚未就绪，请稍后再试"
                return@launch
            }

            // First create a real display with an internal ImageReader sink.
            // Then launch the target Activity, and only after that switch the
            // output to our preview Surface. This isolates the launch/render
            // timing from the UI Surface lifecycle.
            val displayId = withContext(Dispatchers.IO) {
                VirtualDisplayManager.createDisplay(
                    "vc_display_1", displayWidth, displayHeight, displayDpi
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

            if (ok) {
                virtualDisplayStatus.text = "应用已启动，等待渲染 Surface…"
                kotlinx.coroutines.delay(SURFACE_ATTACH_DELAY_MS)

                val surfaceAfterLaunch = previewSurface
                if (operation != displayOperationGeneration ||
                    isFinishing ||
                    currentDisplayId != displayId
                ) {
                    withContext(Dispatchers.IO) {
                        VirtualDisplayManager.release(displayId)
                    }
                    return@launch
                }

                if (surfaceAfterLaunch?.isValid != true) {
                    currentDisplayId = -1
                    setPreviewRunning(false)
                    withContext(Dispatchers.IO) {
                        VirtualDisplayManager.release(displayId)
                    }
                    virtualDisplayStatus.text = "应用已启动，但预览 Surface 无效，虚拟屏已释放"
                    return@launch
                }

                val attached = withContext(Dispatchers.IO) {
                    VirtualDisplayManager.setDisplaySurface(displayId, surfaceAfterLaunch)
                }
                if (!attached) {
                    currentDisplayId = -1
                    setPreviewRunning(false)
                    withContext(Dispatchers.IO) {
                        VirtualDisplayManager.release(displayId)
                    }
                    virtualDisplayStatus.text = "预览 Surface 绑定失败，虚拟屏已释放"
                    return@launch
                }
            } else {
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
        clearCoordinateMarkers()

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

            if (pkg != null) {
                preferences.edit()
                    .putString(PREF_SELECTED_PACKAGE, pkg)
                    .putString(PREF_SELECTED_LABEL, label ?: pkg)
                    .apply()
            }

            if (pkg != null && currentDisplayId < 0) {
                applyDetectedOrientation(pkg)
            }
        }
    }

    /** 供后续 Phase 1 的 createDisplay+launch 调用链使用。 */
    var selectedPackageName: String? = null
        private set

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)

        // Physical rotation only relayouts the controller page. It must not
        // tear down the Shizuku UserService, Virtual Display, or target app.
        runCatching {
            previewContainer.setAspectRatio(displayWidth, displayHeight)
            previewSurfaceView.holder.setFixedSize(displayWidth, displayHeight)
            updateResolutionInfo()
        }.onFailure {
            android.util.Log.w("MainActivity", "configuration relayout failed", it)
        }

        setPreviewRunning(currentDisplayId >= 0)
        previewContainer.post {
            coordinateMarkers.forEach { marker ->
                marker.setNormalizedPosition(marker.normalizedX, marker.normalizedY)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_DISPLAY_ID, currentDisplayId)
        outState.putString(STATE_SELECTED_PACKAGE, selectedPackageName)
        outState.putString(
            STATE_SELECTED_LABEL,
            if (::appList.isInitialized) {
                appList.text?.toString()?.substringBefore("\n")?.removePrefix("已选择：")
            } else {
                null
            }
        )
        outState.putInt(STATE_DISPLAY_WIDTH, displayWidth)
        outState.putInt(STATE_DISPLAY_HEIGHT, displayHeight)
        outState.putInt(STATE_DISPLAY_DPI, displayDpi)
        outState.putBoolean(STATE_DISPLAY_LANDSCAPE, displayLandscape)
        outState.putBoolean(STATE_GAME_MODE, gameMode)
        outState.putInt(
            STATE_RESOLUTION_POSITION,
            if (::resolutionSpinner.isInitialized) resolutionSpinner.selectedItemPosition else 2
        )
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        val changingConfigurations = isChangingConfigurations

        if (!changingConfigurations) {
            // A real finish/teardown releases the virtual display. Rotation
            // does not: the backend and target app must keep running.
            displayOperationGeneration++
            val displayId = currentDisplayId
            currentDisplayId = -1
            stopAutoClicker()
            clearCoordinateMarkers()

            if (displayId >= 0) {
                runCatching {
                    VirtualDisplayManager.release(displayId)
                }.onFailure {
                    android.util.Log.e("MainActivity", "final virtual display release failed", it)
                }
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
