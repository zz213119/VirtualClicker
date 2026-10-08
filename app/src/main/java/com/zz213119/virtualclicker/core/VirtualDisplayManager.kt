package com.zz213119.virtualclicker.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.zz213119.virtualclicker.BuildConfig
import com.zz213119.virtualclicker.service.IVirtualDisplayService
import com.zz213119.virtualclicker.service.VirtualDisplayUserService
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import com.zz213119.virtualclicker.service.VcRootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * App-process singleton that owns the Shizuku UserService connection.
 * Everything here runs with the app's own UID; only calls that cross
 * into `service` run with shell UID inside the separate process.
 */
object VirtualDisplayManager {

    private const val TAG = "VirtualDisplayManager"

    @Volatile
    private var service: IVirtualDisplayService? = null

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, VirtualDisplayUserService::class.java.name)
        )
            .daemon(true)           // Keep the Shizuku UserService alive for background scripts.
            .processNameSuffix("vd_service")
            .debuggable(BuildConfig.DEBUG)
            .version(8)   // 每次修改 AIDL / UserService 代码都要 +1，否则 Shizuku 会继续复用旧的守护进程
    }

    val isBound: Boolean get() = service != null

    @Volatile
    private var appContext: Context? = null

    /** 当前这条连接是用哪种模式建的（重置时要按它来拆）。 */
    @Volatile
    private var boundMode: String = Prefs.MODE_SHIZUKU

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    private fun currentMode(): String =
        appContext?.let { Prefs.backendMode(it) } ?: Prefs.MODE_SHIZUKU

    private fun pushOptions() {
        val ctx = appContext ?: return
        runCatching { service?.setKeepDisplayAwake(Prefs.keepDisplayAwake(ctx)) }
    }

    fun setKeepDisplayAwake(enabled: Boolean) {
        runCatching { service?.setKeepDisplayAwake(enabled) }
    }

    @Volatile
    private var connection: ServiceConnection? = null

    /** 自上次重置后用这个后台进程创建过几块虚拟屏。 */
    @Volatile
    private var displaysSinceReset = 0

    /**
     * 彻底重启 Shizuku 后台服务进程（等价于你手动"重启一下"）。
     * 旧进程会释放它名下所有虚拟屏并退出，下次 ensureBound() 会拉起全新的进程。
     */
    suspend fun resetService() {
        val conn = connection
        val modeAtBind = boundMode
        service = null
        connection = null
        displaysSinceReset = 0
        if (conn != null) {
            if (modeAtBind == Prefs.MODE_ROOT) {
                withContext(Dispatchers.Main) {
                    runCatching { RootService.unbind(conn) }
                    appContext?.let { c ->
                        runCatching { RootService.stop(Intent(c, VcRootService::class.java)) }
                    }
                }
            } else {
                runCatching { Shizuku.unbindUserService(userServiceArgs, conn, true) }
                    .onFailure { Log.w(TAG, "unbindUserService(remove) failed", it) }
            }
        }
        kotlinx.coroutines.delay(1200)
    }

    /** 创建新虚拟屏之前调用：如果这个后台进程已经建过/销毁过虚拟屏，就先换一个全新的进程。 */
    suspend fun ensureFresh(): Boolean {
        if (displaysSinceReset > 0) resetService()
        return ensureBound()
    }

    /** Binds the UserService. Safe to call repeatedly; a live binding is reused. */
    suspend fun ensureBound(): Boolean {
        service?.let { return true }
        return if (currentMode() == Prefs.MODE_ROOT) bindRoot() else bindShizuku()
    }

    private suspend fun bindRoot(): Boolean {
        val ctx = appContext ?: return false
        val hasRoot = withContext(Dispatchers.IO) {
            runCatching { Shell.getShell().isRoot }.getOrDefault(false)
        }
        if (!hasRoot) {
            Log.e(TAG, "root not granted")
            return false
        }
        val intent = Intent(ctx, VcRootService::class.java)
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(20_000) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                            connection = this
                            boundMode = Prefs.MODE_ROOT
                            service = IVirtualDisplayService.Stub.asInterface(binder)
                            pushOptions()
                            Log.i(TAG, "Root service connected")
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onServiceDisconnected(name: ComponentName) {
                            Log.w(TAG, "Root service disconnected")
                            service = null
                        }
                    }
                    RootService.bind(intent, conn)
                }
            } ?: false
        }
    }

    private suspend fun bindShizuku(): Boolean {
        if (!Shizuku.pingBinder()) {
            Log.e(TAG, "Shizuku not running / not authorized")
            return false
        }

        return suspendCoroutine { cont ->
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    connection = this
                    boundMode = Prefs.MODE_SHIZUKU
                    service = IVirtualDisplayService.Stub.asInterface(binder)
                    pushOptions()
                    Log.i(TAG, "UserService connected")
                    cont.resume(true)
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    Log.w(TAG, "UserService disconnected")
                    service = null
                }
            }
            try {
                Shizuku.bindUserService(userServiceArgs, connection)
            } catch (e: Throwable) {
                Log.e(TAG, "bindUserService failed", e)
                cont.resume(false)
            }
        }
    }

    fun createDisplay(name: String, width: Int, height: Int, dpi: Int): Int =
        runCatching { service?.createVirtualDisplay(name, width, height, dpi) ?: -1 }
            .onFailure { Log.e(TAG, "createDisplay failed", it) }
            .getOrDefault(-1)
            .also { if (it >= 0) displaysSinceReset++ }

    /** 带实时预览版本：surface 通常来自 MainActivity 里 SurfaceView 的 SurfaceHolder。 */
    fun createDisplayWithSurface(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: android.view.Surface
    ): Int =
        runCatching {
            service?.createVirtualDisplayWithSurface(name, width, height, dpi, surface) ?: -1
        }
            .onFailure { Log.e(TAG, "createDisplayWithSurface failed", it) }
            .getOrDefault(-1)

    /**
     * Detach/reattach the rendering surface without destroying the virtual
     * display. Passing null intentionally leaves the display alive but with
     * no preview surface.
     */
    fun setDisplaySurface(displayId: Int, surface: android.view.Surface?): Boolean =
        runCatching {
            service?.setVirtualDisplaySurface(displayId, surface) ?: false
        }
            .onFailure { Log.e(TAG, "setDisplaySurface failed", it) }
            .getOrDefault(false)

    fun launch(packageName: String, displayId: Int): Boolean =
        runCatching { service?.launchApp(packageName, displayId) ?: false }
            .onFailure { Log.e(TAG, "launch failed", it) }
            .getOrDefault(false)

    fun release(displayId: Int) {
        runCatching { service?.releaseVirtualDisplay(displayId) }
            .onFailure { Log.e(TAG, "release failed", it) }
    }

    fun tap(displayId: Int, x: Float, y: Float): Boolean =
        runCatching { service?.tap(displayId, x, y) ?: false }
            .onFailure { Log.e(TAG, "tap failed", it) }
            .getOrDefault(false)

    fun longPress(displayId: Int, x: Float, y: Float, durationMs: Int): Boolean =
        runCatching { service?.longPress(displayId, x, y, durationMs) ?: false }
            .onFailure { Log.e(TAG, "longPress failed", it) }
            .getOrDefault(false)

    fun swipe(
        displayId: Int,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Int
    ): Boolean =
        runCatching {
            service?.swipe(displayId, x1, y1, x2, y2, durationMs) ?: false
        }.onFailure { Log.e(TAG, "swipe failed", it) }
            .getOrDefault(false)

    fun captureFrame(displayId: Int, maxWidth: Int): ByteArray? =
        runCatching { service?.captureFrame(displayId, maxWidth) }
            .onFailure { Log.e(TAG, "captureFrame failed", it) }
            .getOrNull()

    fun releaseAll() {
        runCatching { service?.releaseAllVirtualDisplays() }
            .onFailure { Log.e(TAG, "releaseAll failed", it) }
    }

    fun displaySize(displayId: Int): IntArray? =
        runCatching { service?.getDisplaySize(displayId) }
            .onFailure { Log.e(TAG, "displaySize failed", it) }
            .getOrNull()

    /** 通过 MotionEvent 直接注入的快速点击（不走 `input` 子进程）；按下→抬起之间 holdMs 由调用方控制。 */
    fun touchEvent(displayId: Int, action: Int, downTime: Long, x: Float, y: Float): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        val props = arrayOf(android.view.MotionEvent.PointerProperties().apply {
            id = 0
            toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
        })
        val px = x
        val py = y
        val coords = arrayOf(android.view.MotionEvent.PointerCoords().apply {
            this.x = px; this.y = py; pressure = 1f; size = 1f
        })
        val ev = android.view.MotionEvent.obtain(
            downTime, now, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        return try {
            injectMotionEvent(ev, displayId)
        } finally {
            ev.recycle()
        }
    }

    fun injectMotionEvent(event: android.view.MotionEvent, displayId: Int): Boolean =
        runCatching {
            service?.injectMotionEvent(event, displayId) ?: false
        }.onFailure { Log.e(TAG, "injectMotionEvent failed", it) }
            .getOrDefault(false)
}
