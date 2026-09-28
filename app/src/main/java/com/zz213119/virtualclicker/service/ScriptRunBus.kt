package com.zz213119.virtualclicker.service

import com.zz213119.virtualclicker.script.ScriptAction
import java.util.concurrent.CopyOnWriteArrayList

data class ScriptRunState(
    val running: Boolean = false,
    val scriptName: String = "",
    val displayId: Int = -1,
    val actions: List<ScriptAction> = emptyList(),
    val round: Int = 0,
    val totalRounds: Int = 0,
    val actionIndex: Int = -1,
    val actionRepeatIndex: Int = 0,
    val actionRepeatCount: Int = 1,
    val actionDurationMs: Long = 0L,
    val actionStartedAtUptime: Long = 0L
)

object ScriptRunBus {
    private val listeners = CopyOnWriteArrayList<(ScriptRunState) -> Unit>()

    @Volatile
    private var current = ScriptRunState()

    fun register(listener: (ScriptRunState) -> Unit) {
        listeners.addIfAbsent(listener)
        runCatching { listener(current) }
    }

    fun unregister(listener: (ScriptRunState) -> Unit) {
        listeners.remove(listener)
    }

    fun publish(state: ScriptRunState) {
        current = state
        listeners.forEach { listener ->
            runCatching { listener(state) }
        }
    }

    fun currentState(): ScriptRunState = current
}
