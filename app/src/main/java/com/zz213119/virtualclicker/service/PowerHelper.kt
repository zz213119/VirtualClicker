package com.zz213119.virtualclicker.service

import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import java.lang.reflect.Method

/**
 * 电源相关的隐藏接口（思路来自 MAA-Meow 的 PowerController）：
 *  - pokeUserActivity：定时对虚拟屏所在的显示组"戳"一下用户活动，防止它因超时进入休眠
 *  - setPanelPower：只开关物理屏幕面板（SurfaceControl.setDisplayPowerMode），系统本身不休眠
 */
object PowerHelper {
    private const val POWER_MODE_OFF = 0
    private const val POWER_MODE_NORMAL = 2

    private val powerManager: Any? by lazy {
        runCatching {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "power") as IBinder
            Class.forName("android.os.IPowerManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        }.getOrNull()
    }

    private var userActivityMethod: Method? = null

    /** 返回是否调用成功。 */
    fun pokeUserActivity(displayId: Int): Boolean {
        val pm = powerManager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 31) {
                val m = userActivityMethod ?: pm.javaClass.getMethod(
                    "userActivity", Int::class.javaPrimitiveType, Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                ).also { userActivityMethod = it }
                m.invoke(pm, displayId, SystemClock.uptimeMillis(), 0, 0)
            } else {
                val m = userActivityMethod ?: pm.javaClass.getMethod(
                    "userActivity", Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                ).also { userActivityMethod = it }
                m.invoke(pm, SystemClock.uptimeMillis(), 0, 0)
            }
            true
        } catch (t: Throwable) {
            false
        }
    }

    private val displayControlClass: Class<*>? by lazy {
        runCatching {
            val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
            val create = factory.getDeclaredMethod(
                "createClassLoader", String::class.java, String::class.java, String::class.java,
                ClassLoader::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                String::class.java
            )
            val cp = android.system.Os.getenv("SYSTEMSERVERCLASSPATH")
            val loader = create.invoke(null, cp, null, null, ClassLoader.getSystemClassLoader(), 0, true, null) as ClassLoader
            val cls = loader.loadClass("com.android.server.display.DisplayControl")
            val load = Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
            load.isAccessible = true
            load.invoke(Runtime.getRuntime(), cls, "android_servers")
            cls
        }.getOrNull()
    }

    private fun physicalTokens(): List<IBinder> {
        val sc = Class.forName("android.view.SurfaceControl")
        val idsFromSc = runCatching { sc.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray }.getOrNull()
        val (ids, tokenOf) = if (idsFromSc != null) {
            val tm = sc.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
            idsFromSc to { id: Long -> tm.invoke(null, id) as? IBinder }
        } else {
            val dc = displayControlClass ?: return emptyList()
            val idsM = dc.getMethod("getPhysicalDisplayIds")
            val tm = dc.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
            (idsM.invoke(null) as? LongArray ?: return emptyList()) to { id: Long -> tm.invoke(null, id) as? IBinder }
        }
        return ids.toList().mapNotNull { tokenOf(it) }
    }

    private fun internalToken(): IBinder? {
        val sc = Class.forName("android.view.SurfaceControl")
        return runCatching {
            if (Build.VERSION.SDK_INT < 29) sc.getMethod("getBuiltInDisplay", Int::class.javaPrimitiveType).invoke(null, 0)
            else sc.getMethod("getInternalDisplayToken").invoke(null)
        }.getOrNull() as? IBinder
    }

    /** on=false 关闭物理屏幕面板，on=true 点亮。返回是否全部成功。 */
    fun setPanelPower(on: Boolean): Boolean {
        val mode = if (on) POWER_MODE_NORMAL else POWER_MODE_OFF
        val sc = Class.forName("android.view.SurfaceControl")
        val setMode = sc.getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType)
        val honor = Build.BRAND.equals("honor", ignoreCase = true) && Build.VERSION.SDK_INT >= 34
        val tokens: List<IBinder> =
            if (Build.VERSION.SDK_INT >= 29 && !honor) physicalTokens().ifEmpty { listOfNotNull(internalToken()) }
            else listOfNotNull(internalToken())
        if (tokens.isEmpty()) return false
        var all = true
        for (t in tokens) {
            all = all and runCatching { setMode.invoke(null, t, mode); true }.getOrDefault(false)
        }
        return all
    }
}
