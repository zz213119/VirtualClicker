package com.zz213119.virtualclicker.service

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Process
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.lang.reflect.Method

/**
 * Shizuku UserService target. Runs in its own process with shell UID
 * (or root UID once the Root backend lands), NOT the app's own UID —
 * that's what makes createVirtualDisplay / `am start --display` legal
 * without a rooted device.
 *
 * Requirements for Shizuku hosts:
 *  - public no-arg constructor (Shizuku instantiates via reflection)
 *  - must not touch the calling Activity's Context directly
 */
class VirtualDisplayUserService : IVirtualDisplayService.Stub() {

    // Binder callbacks may arrive concurrently. Use thread-safe maps so a
    // release racing with an input call cannot corrupt the service state.
    private val displays = ConcurrentHashMap<Int, VirtualDisplay>()
    private val sinks = ConcurrentHashMap<Int, ImageReader>()

    /** One target-app task belongs to one VirtualDisplay session. */
    private data class DisplaySession(
        val packageName: String,
        val taskId: Int
    )

    private val sessions = ConcurrentHashMap<Int, DisplaySession>()
    private val inputEngine = InputEngine()

    private val displayManager: DisplayManager by lazy {
        createShellContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    override fun createVirtualDisplay(name: String, width: Int, height: Int, dpi: Int): Int {
        // "盲" 版本：用一个自己持有、外部拿不到的 ImageReader 当渲染目标，
        // 画面直接被丢弃，只用于测试输入注入链路。
        val sink = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val displayId = createDisplayInternal(name, width, height, dpi, sink.surface)
        if (displayId >= 0) {
            sinks[displayId] = sink
        } else {
            sink.close()
        }
        return displayId
    }

    override fun createVirtualDisplayWithSurface(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: android.view.Surface
    ): Int {
        // 预览版本：直接把调用方（App 进程里的 SurfaceView）的 Surface 作为
        // 虚拟屏的渲染目标，画面就是实时预览，不需要跨进程搬运帧数据。
        return createDisplayInternal(name, width, height, dpi, surface)
    }

    override fun setVirtualDisplaySurface(
        displayId: Int,
        surface: android.view.Surface?
    ): Boolean {
        val vd = displays[displayId]
        if (vd == null) {
            Log.w(TAG, "setVirtualDisplaySurface rejected: displayId=${displayId} is not managed")
            appendLog(
                "SET SURFACE REJECTED",
                "displayId=${displayId}\\nreason=display_not_managed"
            )
            return false
        }

        if (surface != null && !surface.isValid) {
            Log.w(TAG, "setVirtualDisplaySurface rejected: invalid surface displayId=${displayId}")
            appendLog(
                "SET SURFACE REJECTED",
                "displayId=${displayId}\\nreason=invalid_surface"
            )
            return false
        }

        return runCatching {
            vd.setSurface(surface)
            appendLog(
                "SET SURFACE",
                "displayId=${displayId}\\naction=${if (surface == null) "detach" else "attach"}"
            )
            true
        }.onFailure {
            Log.e(TAG, "setVirtualDisplaySurface failed for displayId=${displayId}", it)
            appendLog(
                "SET SURFACE FAILED",
                "displayId=${displayId}\\n${it.stackTraceToString()}"
            )
        }.getOrDefault(false)
    }

    private fun createDisplayInternal(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: android.view.Surface
    ): Int {
        return try {
            val supportsTouch = 1 shl 6
            val destroyContentOnRemoval = 1 shl 8
            val trusted = 1 shl 10
            val ownDisplayGroup = 1 shl 11
            val alwaysUnlocked = 1 shl 12
            val touchFeedbackDisabled = 1 shl 13
            val ownFocus = 1 shl 14
            val deviceDisplayGroup = 1 shl 15
            val stealTopFocusDisabled = 1 shl 16

            var flags =
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    supportsTouch or
                    destroyContentOnRemoval or
                    trusted or
                    ownDisplayGroup or
                    alwaysUnlocked or
                    touchFeedbackDisabled

            if (Build.VERSION.SDK_INT >= 34) {
                flags = flags or ownFocus or deviceDisplayGroup or stealTopFocusDisabled
            }

            Log.i(TAG, "creating display with flags=0x" + flags.toString(16))
            appendLog(
                "CREATE DISPLAY",
                "uid=${Process.myUid()}\n" +
                    "pid=${Process.myPid()}\n" +
                    "package=${createShellContext().packageName}\n" +
                    "name=$name\nsize=${width}x${height}\ndpi=$dpi\n" +
                    "flags=0x${flags.toString(16)}"
            )

            if (!surface.isValid) {
                throw IllegalArgumentException("surface is invalid before createVirtualDisplay")
            }

            val vd = displayManager.createVirtualDisplay(
                name, width, height, dpi, surface, flags
            )

            val displayId = vd.display.displayId
            displays[displayId] = vd
            Log.i(TAG, "created virtual display id=$displayId ${width}x$height@$dpi")
            appendLog(
                "CREATE DISPLAY SUCCESS",
                "displayId=${displayId}\n" +
                    "actual=${vd.display.width}x${vd.display.height}\n" +
                    "rotation=${vd.display.rotation}"
            )
            displayId
        } catch (e: Throwable) {
            Log.e(TAG, "createVirtualDisplay failed", e)
            appendLog("CREATE DISPLAY FAILED", e.stackTraceToString())
            -1
        }
    }

    override fun launchApp(packageName: String, displayId: Int): Boolean {
        val activity = resolveLaunchActivity(packageName)
        if (activity == null) {
            Log.e(TAG, "no resolvable launcher activity for $packageName")
            return false
        }
        return launchAppExplicit(packageName, activity, displayId)
    }

    override fun launchAppExplicit(packageName: String, activityName: String, displayId: Int): Boolean {
        return try {
            // resolveLaunchActivity() may already return a fully-qualified
            // component such as com.example.app/.MainActivity. Avoid
            // accidentally prefixing the package twice.
            val component = if (activityName.startsWith("$packageName/")) {
                activityName
            } else {
                "$packageName/$activityName"
            }

            val cmd = arrayOf(
                "am", "start",
                "--display", displayId.toString(),
                "--activity-multiple-task",
                "-W",
                "-n", component
            )
            val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            val exit = proc.waitFor()

            val commandText = cmd.joinToString(" ")
            Log.i(TAG, "resolved activity=$activityName component=$component")
            Log.i(TAG, "am start exit=$exit output=$output")
            appendLog(
                "AM START",
                "command=$commandText\nexit=$exit\noutput=$output"
            )

            // `am start` can exit 0 even on some failures (e.g. permission
            // denied warnings); treat an explicit Error: line as failure too.
            val ok = exit == 0 && !output.contains("Error:", ignoreCase = true)
            if (ok) {
                val taskId = findTaskForPackageOnDisplay(packageName, displayId)
                if (taskId != null) {
                    sessions[displayId] = DisplaySession(packageName, taskId)
                    appendLog(
                        "TASK SESSION CREATED",
                        "displayId=$displayId\npackage=$packageName\ntaskId=$taskId"
                    )
                } else {
                    Log.w(TAG, "could not resolve launched task for package=$packageName displayId=$displayId")
                    appendLog(
                        "TASK SESSION NOT_FOUND",
                        "displayId=$displayId\npackage=$packageName"
                    )
                }
            }
            ok
        } catch (e: Throwable) {
            Log.e(TAG, "launchAppExplicit failed", e)
            appendLog("LAUNCH EXCEPTION", e.stackTraceToString())
            false
        }
    }

    override fun releaseVirtualDisplay(displayId: Int) {
        cleanupDisplay(displayId, "explicit_release")
    }

    /**
     * VirtualDisplay owns a Surface connection into SurfaceFlinger. Detach
     * that surface first, then release the VirtualDisplay, then close any
     * internal ImageReader sink. Removing the map entry first also prevents
     * new input calls from racing with a release.
     */
    private fun cleanupDisplay(displayId: Int, reason: String) {
        val session = sessions.remove(displayId)
        val vd = displays.remove(displayId)
        val sink = sinks.remove(displayId)

        if (session != null) {
            removeTask(session.taskId, session.packageName, displayId, reason)
        }

        if (vd == null && sink == null) {
            Log.i(TAG, "cleanupDisplay: displayId=${displayId} already clean reason=${reason}")
            appendLog(
                "RELEASE DISPLAY",
                "displayId=${displayId}\\nreason=${reason}\\nalready_clean=true"
            )
            return
        }

        appendLog(
            "RELEASE DISPLAY",
            "displayId=${displayId}\\nreason=${reason}\\n" +
                "hadVirtualDisplay=${vd != null}\\nhadImageReader=${sink != null}"
        )

        if (vd != null) {
            runCatching {
                vd.setSurface(null)
            }.onFailure {
                Log.w(TAG, "setSurface(null) failed for displayId=${displayId}", it)
                appendLog("RELEASE SET_SURFACE_FAILED", "displayId=${displayId}\\n${it.stackTraceToString()}")
            }

            runCatching {
                vd.release()
            }.onFailure {
                Log.e(TAG, "VirtualDisplay.release failed for displayId=${displayId}", it)
                appendLog("RELEASE FAILED", "displayId=${displayId}\\n${it.stackTraceToString()}")
            }
        }

        // The ImageReader must stay alive until the VirtualDisplay is detached.
        if (sink != null) {
            runCatching { sink.close() }
                .onFailure {
                    Log.w(TAG, "ImageReader.close failed for displayId=${displayId}", it)
                }
        }

        verifyDisplayReleased(displayId)
    }

    /**
     * VirtualDisplay release is asynchronous from the framework's point of
     * view. Poll briefly so the persistent log tells us whether the display
     * has actually disappeared from DisplayManager.
     */
    private fun verifyDisplayReleased(displayId: Int) {
        repeat(10) { attempt ->
            val stillPresent = runCatching {
                displayManager.getDisplay(displayId) != null
            }.getOrDefault(false)

            if (!stillPresent) {
                Log.i(TAG, "displayId=${displayId} fully released after ${attempt * 50}ms")
                appendLog(
                    "RELEASE DISPLAY SUCCESS",
                    "displayId=${displayId}\\nwaitMs=${attempt * 50}"
                )
                return
            }

            if (attempt < 9) {
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }

        Log.w(TAG, "displayId=${displayId} still visible after release wait")
        appendLog(
            "RELEASE DISPLAY STILL_VISIBLE",
            "displayId=${displayId}\\nwaitMs=500"
        )
    }

    /** Find the task belonging to this package on this exact display. */
    private fun findTaskForPackageOnDisplay(packageName: String, displayId: Int): Int? {
        repeat(20) { attempt ->
            val taskId = runCatching {
                val atmsClass = Class.forName("android.app.ActivityTaskManager")
                val getService = atmsClass.getDeclaredMethod("getService")
                val atms = getService.invoke(null) ?: return@runCatching null
                val getTasks = atms.javaClass.getMethod("getTasks", Int::class.javaPrimitiveType)
                val tasks = getTasks.invoke(atms, 100) as? List<*> ?: return@runCatching null
                tasks.firstNotNullOfOrNull { task ->
                    if (task == null) return@firstNotNullOfOrNull null
                    val taskDisplayId = readIntProperty(task, "displayId")
                        ?: return@firstNotNullOfOrNull null
                    if (taskDisplayId != displayId) return@firstNotNullOfOrNull null
                    val baseActivity = readObjectProperty(task, "baseActivity")
                        ?: readObjectProperty(task, "topActivity")
                    val taskPackage = if (baseActivity is android.content.ComponentName) {
                        baseActivity.packageName
                    } else null
                    if (taskPackage == packageName) readIntProperty(task, "taskId") else null
                }
            }.getOrNull()
            if (taskId != null && taskId >= 0) {
                Log.i(TAG, "resolved taskId=$taskId for package=$packageName displayId=$displayId")
                return taskId
            }
            if (attempt < 19) {
                try { Thread.sleep(100) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); return null }
            }
        }
        return null
    }

    /** Remove exactly the task owned by this display session. */
    private fun removeTask(taskId: Int, packageName: String, displayId: Int, reason: String): Boolean {
        if (taskId < 0) return false
        val removed = runCatching {
            val atmsClass = Class.forName("android.app.ActivityTaskManager")
            val getService = atmsClass.getDeclaredMethod("getService")
            val atms = getService.invoke(null) ?: return@runCatching false
            val removeTask = atms.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType)
            (removeTask.invoke(atms, taskId) as? Boolean) ?: false
        }.getOrElse {
            Log.e(TAG, "removeTask failed: taskId=$taskId", it)
            false
        }
        appendLog(
            "TASK SESSION RELEASE",
            "displayId=$displayId\npackage=$packageName\ntaskId=$taskId\nreason=$reason\nremoved=$removed"
        )
        return removed
    }

    private fun readIntProperty(target: Any, name: String): Int? {
        return runCatching {
            val field = target.javaClass.getField(name)
            field.getInt(target)
        }.getOrElse {
            runCatching {
                val field = target.javaClass.getDeclaredField(name)
                field.isAccessible = true
                field.getInt(target)
            }.getOrNull()
        }
    }

    private fun readObjectProperty(target: Any, name: String): Any? {
        return runCatching {
            val field = target.javaClass.getField(name)
            field.get(target)
        }.getOrElse {
            runCatching {
                val field = target.javaClass.getDeclaredField(name)
                field.isAccessible = true
                field.get(target)
            }.getOrNull()
        }
    }

    override fun tap(displayId: Int, x: Float, y: Float): Boolean {
        if (!isDisplayManaged(displayId)) {
            Log.w(TAG, "tap rejected: displayId=$displayId is not managed by this service")
            LogWriter.write(
                "INPUT REJECTED",
                "type=TAP\ndisplayId=$displayId\nreason=display_not_managed"
            )
            return false
        }
        return inputEngine.tap(displayId, x, y)
    }

    override fun longPress(displayId: Int, x: Float, y: Float, durationMs: Int): Boolean {
        if (!isDisplayManaged(displayId)) {
            Log.w(TAG, "longPress rejected: displayId=$displayId is not managed by this service")
            LogWriter.write(
                "INPUT REJECTED",
                "type=LONG_PRESS\ndisplayId=$displayId\nreason=display_not_managed"
            )
            return false
        }
        return inputEngine.longPress(displayId, x, y, durationMs)
    }

    override fun swipe(
        displayId: Int,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Int
    ): Boolean {
        if (!isDisplayManaged(displayId)) {
            Log.w(TAG, "swipe rejected: displayId=$displayId is not managed by this service")
            LogWriter.write(
                "INPUT REJECTED",
                "type=SWIPE\ndisplayId=$displayId\nreason=display_not_managed"
            )
            return false
        }
        return inputEngine.swipe(displayId, x1, y1, x2, y2, durationMs)
    }

    private fun isDisplayManaged(displayId: Int): Boolean {
        return displays.containsKey(displayId)
    }

    override fun destroy() {
        val ids = (displays.keys + sinks.keys).toSet()
        ids.forEach { cleanupDisplay(it, "user_service_destroy") }
        displays.clear()
        sinks.clear()
        Log.i(TAG, "VirtualDisplayUserService destroyed")
        Process.killProcess(Process.myPid())
    }

    /**
     * Shell-context PackageManager lookups are sometimes restricted on OEM
     * ROMs (MIUI/OriginOS/etc filter cross-user visibility), so this shells
     * out to `cmd package resolve-activity` instead of calling PackageManager
     * directly. Revisit if this proves unreliable on the iQOO build.
     */
    /**
     * Use the Android shell package context so DisplayManager/system_server sees
     * the same package identity as the Shizuku shell process.
     */
    private fun createShellContext(): Context {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val thread = runCatching {
            activityThreadClass.getDeclaredMethod("currentActivityThread").invoke(null)
        }.getOrNull() ?: activityThreadClass.getDeclaredMethod("systemMain").invoke(null)

        val base = activityThreadClass
            .getDeclaredMethod("getSystemContext")
            .invoke(thread) as Context

        return try {
            base.createPackageContext(
                "com.android.shell",
                Context.CONTEXT_IGNORE_SECURITY
            )
        } catch (t: Throwable) {
            Log.w(TAG, "createPackageContext(com.android.shell) failed; using wrapper", t)
            ShellContextWrapper(base)
        }
    }

    private class ShellContextWrapper(base: Context) : android.content.ContextWrapper(base) {
        override fun getPackageName(): String = "com.android.shell"
        override fun getOpPackageName(): String = "com.android.shell"

        override fun getAttributionSource(): android.content.AttributionSource {
            return android.content.AttributionSource.Builder(Process.SHELL_UID)
                .setPackageName("com.android.shell")
                .build()
        }

        override fun getApplicationContext(): Context = this
    }
    private fun resolveLaunchActivity(packageName: String): String? {
        return try {
            val proc = ProcessBuilder(
                "cmd", "package", "resolve-activity", "--brief", packageName
            ).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            val exit = proc.waitFor()

            appendLog(
                "RESOLVE ACTIVITY",
                "package=$packageName\nexit=$exit\noutput=$output"
            )

            output.lines()
                .map { it.trim() }
                .lastOrNull { it.startsWith("$packageName/") }
        } catch (e: Throwable) {
            Log.e(TAG, "resolveLaunchActivity failed for $packageName", e)
            null
        }
    }

    /**
     * Persistent diagnostic output for the app's Shizuku backend.
     *
     * Shell/UserService writes here so the user can retrieve the complete
     * command output without opening Logcat. Logcat is still emitted in
     * parallel for development.
     */
    private fun appendLog(section: String, body: String) {
        runCatching {
            val dir = File(
                "/storage/emulated/0/Android/data/",
                "$APP_PACKAGE/files/logs"
            )
            if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
                throw IllegalStateException("cannot create log directory: " + dir.absolutePath)
            }

            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val file = File(dir, "virtualclicker-$date.log")
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

            file.appendText(
                "\n[$time] [$section]\n$body\n",
                Charsets.UTF_8
            )

            Log.i(TAG, "persistent log: " + file.absolutePath)
        }.onFailure {
            Log.e(TAG, "failed to write persistent log", it)
        }
    }
    private fun systemContext(): Context {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val systemMain: Method = activityThreadClass.getMethod("systemMain")
        val activityThread = systemMain.invoke(null)
        val getSystemContext: Method = activityThreadClass.getMethod("getSystemContext")
        return getSystemContext.invoke(activityThread) as Context
    }

    companion object {
        private const val TAG = "VDUserService"
        private const val APP_PACKAGE = "com.zz213119.virtualclicker"
    }
}
