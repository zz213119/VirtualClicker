package com.zz213119.virtualclicker

import android.content.Intent
import android.os.Bundle
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

        refreshStatus()
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
            val displayId = withContext(Dispatchers.IO) {
                VirtualDisplayManager.createDisplay("vc_display_1", 1080, 1920, 320)
            }
            if (displayId < 0) {
                virtualDisplayStatus.text = "创建虚拟屏失败，查看 Logcat tag VDUserService"
                return@launch
            }

            virtualDisplayStatus.text = "虚拟屏 #$displayId 已创建，正在启动 $pkg…"
            val ok = withContext(Dispatchers.IO) {
                VirtualDisplayManager.launch(pkg, displayId)
            }
            virtualDisplayStatus.text = if (ok) {
                "已在虚拟屏 #$displayId 启动 $pkg，切回桌面看看它是否还在后台跑"
            } else {
                "启动失败，查看 Logcat tag VDUserService（am start 的输出会打印在里面）"
            }
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
