package com.zz213119.virtualclicker.core

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.zz213119.virtualclicker.BuildConfig
import com.zz213119.virtualclicker.service.IVirtualDisplayService
import com.zz213119.virtualclicker.service.VirtualDisplayUserService
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
            .version(1)
    }

    val isBound: Boolean get() = service != null

    /** Binds the UserService. Safe to call repeatedly; a live binding is reused. */
    suspend fun ensureBound(): Boolean {
        service?.let { return true }

        if (!Shizuku.pingBinder()) {
            Log.e(TAG, "Shizuku not running / not authorized")
            return false
        }

        return suspendCoroutine { cont ->
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    service = IVirtualDisplayService.Stub.asInterface(binder)
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
}
