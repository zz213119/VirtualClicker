package com.zz213119.virtualclicker.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 从服务商拉取可用模型列表（OpenAI 兼容 GET /models，Anthropic GET /v1/models）。阻塞调用，请在后台线程里用。 */
object AiModels {
    fun fetch(format: String, rawBase: String, key: String): List<String> {
        val url = AiConfig.modelsUrl(format, rawBase)
        if (url.isEmpty()) throw IOException("请先填写接口地址")
        if (key.isEmpty()) throw IOException("请先填写 API 密钥")
        if (!url.startsWith("https://")) throw IOException("接口地址必须是 https://")
        val full = if (format == AiConfig.FORMAT_ANTHROPIC) "$url?limit=1000" else url
        val conn = (URL(full).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            if (format == AiConfig.FORMAT_ANTHROPIC) {
                setRequestProperty("x-api-key", key)
                setRequestProperty("anthropic-version", "2023-06-01")
            } else {
                setRequestProperty("Authorization", "Bearer $key")
            }
        }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code ${text.take(200)}")
            val root = JSONObject(text)
            val arr: JSONArray = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val v = arr.opt(i)
                val id = when (v) {
                    is String -> v
                    is JSONObject -> v.optString("id").ifBlank { v.optString("name") }
                    else -> ""
                }
                if (id.isNotBlank()) out.add(id)
            }
            return out.distinct().sorted()
        } finally {
            conn.disconnect()
        }
    }
}
