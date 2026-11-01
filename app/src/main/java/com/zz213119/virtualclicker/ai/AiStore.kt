package com.zz213119.virtualclicker.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 历史对话的本地存储：每个对话一个 <id>.json（完整内容）和 <id>.meta（标题和时间，用来快速列出历史）。 */
object AiStore {
    class Info(val id: String, val title: String, val updated: Long)

    private fun dir(c: Context): File = File(c.applicationContext.filesDir, "ai_chats").also { it.mkdirs() }

    fun newId(): String = System.currentTimeMillis().toString(36) + (Math.random() * 1296).toInt().toString(36)

    fun list(c: Context): List<Info> =
        (dir(c).listFiles { f -> f.name.endsWith(".meta") } ?: emptyArray())
            .mapNotNull { f ->
                runCatching {
                    val j = JSONObject(f.readText(Charsets.UTF_8))
                    Info(f.name.removeSuffix(".meta"), j.optString("title"), j.optLong("updated"))
                }.getOrNull()
            }
            .sortedByDescending { it.updated }

    fun save(c: Context, id: String, title: String, format: String, display: String, wire: JSONArray, rec: JSONArray = JSONArray()) {
        val now = System.currentTimeMillis()
        val body = JSONObject().put("id", id).put("title", title).put("updated", now)
            .put("format", format).put("display", display).put("wire", wire).put("rec", rec)
        File(dir(c), "$id.json").writeText(body.toString(), Charsets.UTF_8)
        File(dir(c), "$id.meta").writeText(JSONObject().put("title", title).put("updated", now).toString(), Charsets.UTF_8)
    }

    fun load(c: Context, id: String): JSONObject? =
        runCatching { JSONObject(File(dir(c), "$id.json").readText(Charsets.UTF_8)) }.getOrNull()

    fun delete(c: Context, id: String) {
        File(dir(c), "$id.json").delete()
        File(dir(c), "$id.meta").delete()
    }
}
