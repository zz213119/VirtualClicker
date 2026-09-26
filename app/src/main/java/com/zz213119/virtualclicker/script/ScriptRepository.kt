package com.zz213119.virtualclicker.script

import android.content.Context

object ScriptRepository {
    private const val PREFS = "script_repository"
    private const val KEY_LAST_SCRIPT = "last_script"

    fun loadLast(context: Context): ScriptDefinition {
        val raw = context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_SCRIPT, null)

        return raw?.let(ScriptJson::decode)
            ?: ScriptDefinition(
                name = "我的第一个脚本",
                repeatCount = 1,
                actions = listOf(
                    ScriptAction(ScriptActionType.CLICK, 540f, 960f)
                )
            )
    }

    fun saveLast(context: Context, script: ScriptDefinition) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_SCRIPT, ScriptJson.encode(script))
            .apply()
    }
}
