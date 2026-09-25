package com.zz213119.virtualclicker.service

import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Display-targeted input backend.
 *
 * Uses the Android built-in `input` shell command with -d DISPLAY_ID.
 * The command runs inside the Shizuku UserService process.
 */
class InputEngine {

    fun tap(displayId: Int, x: Float, y: Float): Boolean {
        return execute(
            "TAP",
            arrayOf("input", "-d", displayId.toString(), "tap", format(x), format(y))
        )
    }

    fun longPress(displayId: Int, x: Float, y: Float, durationMs: Int): Boolean {
        val duration = durationMs.coerceAtLeast(1)
        return execute(
            "LONG PRESS",
            arrayOf(
                "input", "-d", displayId.toString(), "swipe",
                format(x), format(y), format(x), format(y),
                duration.toString()
            )
        )
    }

    fun swipe(
        displayId: Int,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Int
    ): Boolean {
        val duration = durationMs.coerceAtLeast(1)
        return execute(
            "SWIPE",
            arrayOf(
                "input", "-d", displayId.toString(), "swipe",
                format(x1), format(y1), format(x2), format(y2),
                duration.toString()
            )
        )
    }

    private fun execute(section: String, command: Array<String>): Boolean {
        return try {
            val commandText = command.joinToString(" ")
            Log.i(TAG, "$section: $commandText")

            val process = ProcessBuilder(*command)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)

            if (!finished) {
                process.destroyForcibly()
                Log.e(TAG, "$section: timeout after ${COMMAND_TIMEOUT_MS}ms")
                LogWriter.write(section, "command=$commandText\nresult=TIMEOUT")
                return false
            }

            val exit = process.exitValue()
            val ok = exit == 0 && !output.contains("Error:", ignoreCase = true)

            Log.i(TAG, "$section: exit=$exit ok=$ok output=$output")
            LogWriter.write(
                section,
                "command=$commandText\nexit=$exit\nok=$ok\noutput=$output"
            )
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "$section failed", t)
            LogWriter.write(section, t.stackTraceToString())
            false
        }
    }

    private fun format(value: Float): String {
        return if (value % 1f == 0f) value.toInt().toString() else value.toString()
    }

    companion object {
        private const val TAG = "InputEngine"
        private const val COMMAND_TIMEOUT_MS = 10_000L
    }
}