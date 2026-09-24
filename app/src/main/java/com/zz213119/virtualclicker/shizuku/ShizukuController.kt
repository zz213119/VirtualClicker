package com.zz213119.virtualclicker.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import rikka.shizuku.Shizuku

class ShizukuController(
    private val packageName: String
) {
    companion object {
        private const val REQUEST_CODE = 1001
        private const val SERVICE_VERSION = 1
        private const val SERVICE_TAG = "virtualclicker-backend"
    }

    var service: IBackendService? = null
        private set

    private var connection: ServiceConnection? = null

    fun isShizukuAvailable(): Boolean = Shizuku.pingBinder()

    fun hasPermission(): Boolean =
        isShizukuAvailable() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun requestPermission() {
        if (!isShizukuAvailable()) return
        if (Shizuku.shouldShowRequestPermissionRationale()) return
        Shizuku.requestPermission(REQUEST_CODE)
    }

    fun bind(onChanged: (Boolean) -> Unit) {
        if (!isShizukuAvailable() || !hasPermission()) {
            onChanged(false)
            return
        }

        val args = Shizuku.UserServiceArgs(
            ComponentName(packageName, BackendUserService::class.java.name)
        )
            .version(SERVICE_VERSION)
            .tag(SERVICE_TAG)
            .processNameSuffix("backend")
            .daemon(true)

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = IBackendService.Stub.asInterface(binder)
                onChanged(service != null)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                onChanged(false)
            }
        }

        connection = conn
        Shizuku.bindUserService(args, conn)
    }

    fun unbind() {
        val conn = connection ?: return
        val args = Shizuku.UserServiceArgs(
            ComponentName(packageName, BackendUserService::class.java.name)
        )
            .version(SERVICE_VERSION)
            .tag(SERVICE_TAG)
            .processNameSuffix("backend")
            .daemon(true)
        runCatching { Shizuku.unbindUserService(args, conn, true) }
        connection = null
        service = null
    }
}
