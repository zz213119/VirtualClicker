package com.zz213119.virtualclicker.service

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Process
import android.util.Log
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

    private val displayManager: DisplayManager by lazy {
        systemContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    override fun createVirtualDisplay(name: String, width: Int, height: Int, dpi: Int): Int {
        return try {
            // Phase 1 doesn't need to read frames back yet — the ImageReader
            // surface is just a legal render target so the virtual display
            // has somewhere to draw. Frame capture (for OCR/matching) is a
            // later phase; swap this for a persistent reader + listener then.
            val sink = ImageReader.newInstance(width, height, ImageFormat.RGBA_8888, 2)

            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC

            val vd = displayManager.createVirtualDisplay(
                name, width, height, dpi, sink.surface, flags
            )

            val displayId = vd.display.displayId
            displays[displayId] = vd
            sinks[displayId] = sink
            Log.i(TAG, "created virtual display id=$displayId ${width}x$height@$dpi")
            displayId
        } catch (e: Throwable) {
            Log.e(TAG, "createVirtualDisplay failed", e)
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
            val cmd = arrayOf(
                "am", "start",
                "--display", displayId.toString(),
                "-n", "$packageName/$activityName"
            )
            val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            val exit = proc.waitFor()
            Log.i(TAG, "am start exit=$exit output=$output")
            // `am start` can exit 0 even on some failures (e.g. permission
            // denied warnings); treat an explicit Error: line as failure too.
            exit == 0 && !output.contains("Error:")
        } catch (e: Throwable) {
            Log.e(TAG, "launchAppExplicit failed", e)
            false
        }
    }

    override fun releaseVirtualDisplay(displayId: Int) {
        displays.remove(displayId)?.release()
        sinks.remove(displayId)?.close()
        Log.i(TAG, "released virtual display id=$displayId")
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
    private fun resolveLaunchActivity(packageName: String): String? {
        return try {
            val proc = ProcessBuilder(
                "cmd", "package", "resolve-activity", "--brief", packageName
            ).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            output.lines()
                .map { it.trim() }
                .lastOrNull { it.startsWith("$packageName/") }
        } catch (e: Throwable) {
            Log.e(TAG, "resolveLaunchActivity failed for $packageName", e)
            null
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
    }
}
