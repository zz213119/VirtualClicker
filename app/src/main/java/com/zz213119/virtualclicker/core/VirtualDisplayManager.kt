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
            .daemon(false)          // Phase 1: tie lifecycle to the binding Activity.
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

    fun launch(packageName: String, displayId: Int): Boolean =
        runCatching { service?.launchApp(packageName, displayId) ?: false }
            .onFailure { Log.e(TAG, "launch failed", it) }
            .getOrDefault(false)

    fun release(displayId: Int) {
        runCatching { service?.releaseVirtualDisplay(displayId) }
            .onFailure { Log.e(TAG, "release failed", it) }
    }
}
