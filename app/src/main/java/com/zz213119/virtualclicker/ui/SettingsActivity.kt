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
        findViewById<EditText>(R.id.idleCloseMin).setText(Prefs.idleCloseMinutes(this).toString())
        findViewById<Button>(R.id.saveParams).setOnClickListener {
            val hammer = findViewById<EditText>(R.id.hammerPeriod).text.toString().toLongOrNull()
            val loops = findViewById<EditText>(R.id.maxLoops).text.toString().toIntOrNull()
            val stamina = findViewById<EditText>(R.id.staminaStop).text.toString().toIntOrNull()
            if (hammer == null || loops == null || stamina == null) {
                Toast.makeText(this, "请填写完整的数字", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Prefs.saveTaskParams(this, hammer, loops, stamina)
            val idle = findViewById<EditText>(R.id.idleCloseMin).text.toString().toIntOrNull() ?: 10
            Prefs.setIdleCloseMinutes(this, idle)
            VirtualDisplayManager.setIdleCloseMinutes(Prefs.idleCloseMinutes(this))
            findViewById<EditText>(R.id.idleCloseMin).setText(Prefs.idleCloseMinutes(this).toString())
            findViewById<EditText>(R.id.hammerPeriod).setText(Prefs.hammerPeriodMs(this).toString())
            findViewById<EditText>(R.id.maxLoops).setText(Prefs.maxLoops(this).toString())
            findViewById<EditText>(R.id.staminaStop).setText(Prefs.staminaStop(this).toString())
            Toast.makeText(this, "已保存，下次启动任务生效", Toast.LENGTH_SHORT).show()
        }

        // ---- 开关 ----
        findViewById<Button>(R.id.oneClickButton).setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1001)
            }
            val i = Intent(this, com.zz213119.virtualclicker.service.ScriptRunnerService::class.java)
                .setAction(com.zz213119.virtualclicker.service.ScriptRunnerService.ACTION_RUN_TASK)
                .putExtra(com.zz213119.virtualclicker.service.ScriptRunnerService.EXTRA_DISPLAY_ID, -1)
                .putExtra(
                    com.zz213119.virtualclicker.service.ScriptRunnerService.EXTRA_TASK_ID,
                    com.zz213119.virtualclicker.service.ScriptRunnerService.TASK_ONE_CLICK
                )
            androidx.core.content.ContextCompat.startForegroundService(this, i)
            Toast.makeText(this, "一键开刷已开始，进度见通知栏", Toast.LENGTH_LONG).show()
        }
        bindSwitch(R.id.switchAutoQuit, "auto_quit_game", Prefs.autoQuitGame(this))
        bindSwitch(R.id.switchNotify, "notify", Prefs.notifyEnabled(this))
        bindSwitch(R.id.switchCpuLock, "cpu_wakelock", Prefs.cpuWakeLock(this))
        bindSwitch(R.id.switchKeepAwake, "keep_display_awake", Prefs.keepDisplayAwake(this)) {
            VirtualDisplayManager.setKeepDisplayAwake(it)
        }
        bindSwitch(R.id.switchAutoScreenOff, "auto_screen_off", Prefs.autoScreenOff(this))
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

    private suspend fun exportLatestLog(): File? {
        val out = File(cacheDir, "share").apply { mkdirs() }
        // 1) 通过后台服务读取（应用自己没有日志目录的读写权限）
        if (VirtualDisplayManager.ensureBound()) {
            val name = VirtualDisplayManager.listLogs().lastOrNull()
            if (name != null) {
                val f = File(out, name)
                f.outputStream().use { os ->
                    var off = 0L
                    while (true) {
                        val chunk = VirtualDisplayManager.readLog(name, off, 256 * 1024) ?: break
                        if (chunk.isEmpty()) break
                        os.write(chunk)
                        off += chunk.size
                    }
                }
                if (f.length() > 0) return f
            }
        }
        // 2) 兜底：直接读文件
        val local = File(baseDir, "logs").listFiles()?.filter { it.isFile }?.maxByOrNull { it.lastModified() }
            ?: return null
        return runCatching { local.copyTo(File(out, local.name), overwrite = true) }.getOrNull()
    }

    private fun shareLatestLog() {
        logStatus.text = "正在读取日志…"
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { runCatching { exportLatestLog() }.getOrNull() }
            if (file == null) {
                logStatus.text = "没有读到日志：请先在主页点“连接后端”（后台服务需要已连接）"
                return@launch
            }
            runCatching {
                val uri = FileProvider.getUriForFile(this@SettingsActivity, "$packageName.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(send, "分享日志 ${file.name}"))
                logStatus.text = "已读取 ${file.name}（${file.length() / 1024} KB）"
            }.onFailure { logStatus.text = "分享失败：${it.message}" }
        }
    }

    private fun clearLogs() {
        logStatus.text = "正在清理…"
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) {
                if (VirtualDisplayManager.ensureBound()) VirtualDisplayManager.clearLogsRemote()
                else {
                    var c = 0
                    for (sub in listOf("logs", "shots")) {
                        File(baseDir, sub).listFiles()?.forEach { f -> if (f.isFile && f.delete()) c++ }
                    }
                    c
                }
            }
            logStatus.text = "已清理 $n 个文件"
        }
    }
}
