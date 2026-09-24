package com.zz213119.virtualclicker.shizuku

import android.os.Process
import android.util.Log

class BackendUserService : IBackendService.Stub() {
    private val tag = "VirtualClicker.Backend"

    override fun ping(): String {
        return "backend alive; uid=${Process.myUid()}; pid=${Process.myPid()}"
    }

    override fun getUid(): Int = Process.myUid()

    override fun stop() {
        Log.i(tag, "stop requested")
        try {
            System.exit(0)
        } catch (_: Throwable) {
        }
    }
}
