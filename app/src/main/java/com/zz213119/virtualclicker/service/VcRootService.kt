package com.zz213119.virtualclicker.service

import android.content.Intent
import android.os.IBinder
import com.topjohnwu.superuser.ipc.RootService

/** Root 启动模式：用 libsu 在 root 进程里跑同一个 VirtualDisplayUserService。 */
class VcRootService : RootService() {
    private var impl: VirtualDisplayUserService? = null

    override fun onBind(intent: Intent): IBinder {
        val s = VirtualDisplayUserService()
        impl = s
        return s
    }

    override fun onDestroy() {
        runCatching { impl?.destroy() }
        super.onDestroy()
    }
}
