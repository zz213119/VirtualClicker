package com.zz213119.virtualclicker

import android.content.Intent
import android.os.Bundle
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

    // 预览用固定分辨率，要跟 createDisplay 调用里传的 width/height 保持一致，
    // 否则虚拟屏渲染出来的画面跟 SurfaceView 缓冲区大小对不上，会被裁切/拉伸。
    private val displayWidth = 1080
    private val displayHeight = 1920
    private val displayDpi = 320

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
