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

    private val displays = mutableMapOf<Int, VirtualDisplay>()
    private val sinks = mutableMapOf<Int, ImageReader>()
    private val inputEngine = InputEngine()

    private val displayManager: DisplayManager by lazy {
        createShellContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    override fun createVirtualDisplay(name: String, width: Int, height: Int, dpi: Int): Int {
        return try {
            // Phase 1 doesn't need to read frames back yet — the ImageReader
            // surface is just a legal render target so the virtual display
            // has somewhere to draw. Frame capture (for OCR/matching) is a
            // later phase; swap this for a persistent reader + listener then.
            val sink = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

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

            val vd = displayManager.createVirtualDisplay(
                name, width, height, dpi, sink.surface, flags
            )

            val displayId = vd.display.displayId
            displays[displayId] = vd
            sinks[displayId] = sink
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
            exit == 0 && !output.contains("Error:", ignoreCase = true)
        } catch (e: Throwable) {
            Log.e(TAG, "launchAppExplicit failed", e)
            appendLog("LAUNCH EXCEPTION", e.stackTraceToString())
            false
        }
    }

    override fun releaseVirtualDisplay(displayId: Int) {
        displays.remove(displayId)?.release()
        sinks.remove(displayId)?.close()
        Log.i(TAG, "released virtual display id=$displayId")
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
        displays.values.forEach { it.release() }
        sinks.values.forEach { it.close() }
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
