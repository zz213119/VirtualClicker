package com.zz213119.virtualclicker.script

import org.json.JSONArray
import org.json.JSONObject

enum class ScriptActionType {
    CLICK,
    LONG_PRESS,
    SWIPE,
    WAIT
}

data class ScriptAction(
    val type: ScriptActionType,
    val x: Float = 0f,
    val y: Float = 0f,
    val x2: Float = 0f,
    val y2: Float = 0f,
    val durationMs: Long = 500L
)

data class ScriptDefinition(
    val name: String = "未命名脚本",
    val repeatCount: Int = 1,
    val actions: List<ScriptAction> = emptyList()
)

object ScriptJson {
    private const val VERSION = 1

    fun encode(script: ScriptDefinition): String {
        val root = JSONObject()
            .put("version", VERSION)
            .put("name", script.name)
            .put("repeatCount", script.repeatCount)

        val actions = JSONArray()
        script.actions.forEach { action ->
            actions.put(
                JSONObject()
                    .put("type", action.type.name)
                    .put("x", action.x.toDouble())
                    .put("y", action.y.toDouble())
                    .put("x2", action.x2.toDouble())
                    .put("y2", action.y2.toDouble())
                    .put("durationMs", action.durationMs)
            )
        }
        root.put("actions", actions)
        return root.toString()
    }

    fun decode(raw: String): ScriptDefinition? {
        return runCatching {
            val root = JSONObject(raw)
            val name = root.optString("name", "未命名脚本")
            val repeatCount = root.optInt("repeatCount", 1).coerceAtLeast(0)
            val jsonActions = root.optJSONArray("actions") ?: JSONArray()
            val actions = buildList {
                for (i in 0 until jsonActions.length()) {
                    val item = jsonActions.optJSONObject(i) ?: continue
                    val type = runCatching {
                        ScriptActionType.valueOf(item.optString("type"))
                    }.getOrNull() ?: continue
                    add(
                        ScriptAction(
                            type = type,
                            x = item.optDouble("x", 0.0).toFloat(),
                            y = item.optDouble("y", 0.0).toFloat(),
                            x2 = item.optDouble("x2", 0.0).toFloat(),
                            y2 = item.optDouble("y2", 0.0).toFloat(),
                            durationMs = item.optLong("durationMs", 500L).coerceAtLeast(0L)
                        )
                    )
                }
            }

            ScriptDefinition(
                name = name,
                repeatCount = repeatCount,
                actions = actions
            )
        }.getOrNull()
    }
}
