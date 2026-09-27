package com.zz213119.virtualclicker.script

import android.content.Context

/**
 * Persistent repository for multiple named scripts.
 *
 * Script names are the user-facing identifiers. Saving under an existing name
 * is intentionally separated from creating a new name so the editor can ask
 * for overwrite confirmation.
 */
object ScriptRepository {
    private const val PREFS = "script_repository"
    private const val KEY_LAST_SCRIPT = "last_script"
    private const val KEY_LAST_NAME = "last_script_name"
    private const val KEY_SCRIPT_NAMES = "script_names"
    private const val SCRIPT_KEY_PREFIX = "script_"

    fun loadLast(context: Context): ScriptDefinition {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        prefs.getString(KEY_LAST_NAME, null)?.let { lastName ->
            load(context, lastName)?.let { return it }
        }

        // Backward compatibility with the original single-script storage.
        prefs.getString(KEY_LAST_SCRIPT, null)?.let { raw ->
            ScriptJson.decode(raw)?.let { script ->
                saveOrUpdate(context, script)
                return script
            }
        }

        return ScriptDefinition(
            name = "我的第一个脚本",
            repeatCount = 1,
            actions = listOf(
                ScriptAction(ScriptActionType.CLICK, 540f, 960f)
            )
        )
    }

    fun load(context: Context, name: String): ScriptDefinition? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(SCRIPT_KEY_PREFIX + name, null)
            ?: return null
        return ScriptJson.decode(raw)
    }

    fun listNames(context: Context): List<String> {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_SCRIPT_NAMES, emptySet())
            .orEmpty()
            .toList()
            .sorted()
    }

    fun exists(context: Context, name: String): Boolean {
        return load(context, name) != null
    }

    /**
     * Save/update without asking. Used by point-pick autosave and script run.
     */
    fun saveOrUpdate(context: Context, script: ScriptDefinition) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val names = prefs.getStringSet(KEY_SCRIPT_NAMES, emptySet())
            .orEmpty()
            .toMutableSet()
        names += script.name

        prefs.edit()
            .putString(SCRIPT_KEY_PREFIX + script.name, ScriptJson.encode(script))
            .putStringSet(KEY_SCRIPT_NAMES, names)
            .putString(KEY_LAST_NAME, script.name)
            // Keep the legacy slot in sync for older builds/upgrades.
            .putString(KEY_LAST_SCRIPT, ScriptJson.encode(script))
            .apply()
    }

    fun saveLast(context: Context, script: ScriptDefinition) {
        saveOrUpdate(context, script)
    }
}
