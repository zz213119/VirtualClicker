package com.zz213119.virtualclicker.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.superuser.Shell
import com.zz213119.virtualclicker.BuildConfig
import com.zz213119.virtualclicker.R
import com.zz213119.virtualclicker.core.Prefs
import com.zz213119.virtualclicker.core.VirtualDisplayManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var modeStatus: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var logStatus: TextView

    private val baseDir: File
        get() = File("/storage/emulated/0/Android/data/$packageName/files")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        VirtualDisplayManager.init(this)

        modeStatus = findViewById(R.id.modeStatus)
        batteryStatus = findViewById(R.id.batteryStatus)
        logStatus = findViewById(R.id.logStatus)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        // ---- 启动模式 ----
        val group = findViewById<RadioGroup>(R.id.modeGroup)
        group.check(if (Prefs.backendMode(this) == Prefs.MODE_ROOT) R.id.modeRoot else R.id.modeShizuku)
        group.setOnCheckedChangeListener { _, id ->
            val mode = if (id == R.id.modeRoot) Prefs.MODE_ROOT else Prefs.MODE_SHIZUKU
            if (mode != Prefs.backendMode(this)) {
                Prefs.setBackendMode(this, mode)
                lifecycleScope.launch(Dispatchers.IO) { VirtualDisplayManager.resetService() }
                Toast.makeText(this, "已切换，后台服务会按新模式重连", Toast.LENGTH_SHORT).show()
            }
            modeStatus.text = "状态：已切换，点“检测当前模式”确认"
        }
        findViewById<Button>(R.id.checkMode).setOnClickListener { checkMode() }

        // ---- 任务参数 ----
        findViewById<EditText>(R.id.hammerPeriod).setText(Prefs.hammerPeriodMs(this).toString())
        findViewById<EditText>(R.id.maxLoops).setText(Prefs.maxLoops(this).toString())
        findViewById<EditText>(R.id.staminaStop).setText(Prefs.staminaStop(this).toString())
        findViewById<Button>(R.id.saveParams).setOnClickListener {
            val hammer = findViewById<EditText>(R.id.hammerPeriod).text.toString().toLongOrNull()
            val loops = findViewById<EditText>(R.id.maxLoops).text.toString().toIntOrNull()
            val stamina = findViewById<EditText>(R.id.staminaStop).text.toString().toIntOrNull()
            if (hammer == null || loops == null || stamina == null) {
                Toast.makeText(this, "请填写完整的数字", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Prefs.saveTaskParams(this, hammer, loops, stamina)
            findViewById<EditText>(R.id.hammerPeriod).setText(Prefs.hammerPeriodMs(this).toString())
            findViewById<EditText>(R.id.maxLoops).setText(Prefs.maxLoops(this).toString())
            findViewById<EditText>(R.id.staminaStop).setText(Prefs.staminaStop(this).toString())
            Toast.makeText(this, "已保存，下次启动任务生效", Toast.LENGTH_SHORT).show()
        }

        // ---- 开关 ----
        bindSwitch(R.id.switchNotify, "notify", Prefs.notifyEnabled(this))
        bindSwitch(R.id.switchCpuLock, "cpu_wakelock", Prefs.cpuWakeLock(this))
        bindSwitch(R.id.switchKeepAwake, "keep_display_awake", Prefs.keepDisplayAwake(this)) {
            VirtualDisplayManager.setKeepDisplayAwake(it)
        }
        bindSwitch(R.id.switchDebug, "show_debug_tools", Prefs.showDebugTools(this))

        findViewById<Button>(R.id.openNotifSettings).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }
        findViewById<Button>(R.id.openBattery).setOnClickListener {
            val pm = getSystemService(PowerManager::class.java)
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, "已在白名单中", Toast.LENGTH_SHORT).show()
            } else {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                }
            }
        }

        // ---- 日志与诊断 ----
        findViewById<Button>(R.id.shareLog).setOnClickListener { shareLatestLog() }
        findViewById<Button>(R.id.clearLogs).setOnClickListener { clearLogs() }
        findViewById<Button>(R.id.resetBackendBtn).setOnClickListener {
            logStatus.text = "正在重置后台服务…"
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { VirtualDisplayManager.resetService() }
                logStatus.text = "后台服务已重置"
            }
        }

        // ---- 关于 ----
        findViewById<TextView>(R.id.versionText).text = "VirtualClicker  ${BuildConfig.VERSION_NAME}"
        findViewById<TextView>(R.id.githubLink).setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/zz213119/VirtualClicker")))
            }.onFailure { Toast.makeText(this, "没有可打开链接的应用", Toast.LENGTH_SHORT).show() }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBattery()
    }

    private fun bindSwitch(id: Int, key: String, initial: Boolean, onChange: ((Boolean) -> Unit)? = null) {
        findViewById<SwitchCompat>(id).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked ->
                Prefs.setBool(this@SettingsActivity, key, checked)
                onChange?.invoke(checked)
            }
        }
    }

    private fun refreshBattery() {
        val pm = getSystemService(PowerManager::class.java)
        batteryStatus.text =
            if (pm.isIgnoringBatteryOptimizations(packageName)) "电池优化：已在白名单（推荐）"
            else "电池优化：未加入白名单，息屏后可能被系统限制"
    }

    private fun checkMode() {
        modeStatus.text = "状态：检测中…"
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                if (Prefs.backendMode(this@SettingsActivity) == Prefs.MODE_ROOT) {
                    val ok = runCatching { Shell.getShell().isRoot }.getOrDefault(false)
                    if (ok) "状态：Root 可用" else "状态：Root 不可用或被拒绝"
                } else {
                    val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
                    val granted = running && runCatching {
                        Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
                    }.getOrDefault(false)
                    when {
                        !running -> "状态：Shizuku 未运行"
                        granted -> "状态：Shizuku 已运行 / 已授权"
                        else -> "状态：Shizuku 已运行 / 未授权"
                    }
                }
            }
            modeStatus.text = text
        }
    }

    private fun shareLatestLog() {
        val file = File(baseDir, "logs").listFiles()?.filter { it.isFile }?.maxByOrNull { it.lastModified() }
        if (file == null) {
            Toast.makeText(this, "还没有日志文件", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "分享日志 ${file.name}"))
        }.onFailure {
            logStatus.text = "分享失败：${it.message}"
        }
    }

    private fun clearLogs() {
        var ok = 0
        var failed = 0
        for (sub in listOf("logs", "shots")) {
            File(baseDir, sub).listFiles()?.forEach { f ->
                if (f.isFile) { if (f.delete()) ok++ else failed++ }
            }
        }
        logStatus.text = "已清理 $ok 个文件" + if (failed > 0) "，$failed 个无法删除（可能属于其他用户）" else ""
    }
}
