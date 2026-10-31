package com.zz213119.virtualclicker.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.zz213119.virtualclicker.core.VirtualDisplayManager
import com.zz213119.virtualclicker.service.LogWriter
import com.zz213119.virtualclicker.task.FrameOps
import com.zz213119.virtualclicker.task.GameOcr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 一段对话。只读：唯一的工具是“读取虚拟屏当前画面上的文字（OCR）”，而且只读用户指定的那块虚拟屏，
 * 不读手机本体屏幕，也不做任何点击/滑动。支持 OpenAI 兼容和 Anthropic 两种接口格式（用系统自带的
 * HttpURLConnection + org.json，不引入额外依赖）。
 */
class AiSession(context: Context) {
    private val ctx = context.applicationContext
    /** 按服务商格式保存的完整对话（不含 system）。 */
    private val history = JSONArray()

    fun clear() {
        while (history.length() > 0) history.remove(0)
    }

    suspend fun ask(userText: String, displayId: Int, onStatus: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val format = AiConfig.format(ctx)
            val base = AiConfig.baseUrl(ctx).trim().trimEnd('/')
            val model = AiConfig.model(ctx).trim()
            val key = AiConfig.apiKey(ctx)
            if (base.isEmpty() || model.isEmpty() || key.isEmpty()) {
                return@withContext "还没配置完整：请点“配置”填写接口地址、密钥、模型。"
            }
            if (!base.startsWith("https://")) {
                return@withContext "接口地址必须是 https:// 开头（系统不允许明文 http）。"
            }
            val sys = AiConfig.systemPrompt(ctx).ifBlank { DEFAULT_SYSTEM }
            val shot = if (AiConfig.sendScreenshot(ctx) && displayId >= 0) screenshotBase64(displayId) else null
            LogWriter.write("AI", "ask format=$format model=$model displayId=$displayId screenshot=${shot != null} chars=${userText.length}")
            try {
                if (format == AiConfig.FORMAT_ANTHROPIC) askAnthropic(base, model, key, sys, userText, shot, displayId, onStatus)
                else askOpenAi(base, model, key, sys, userText, shot, displayId, onStatus)
            } catch (e: Throwable) {
                LogWriter.write("AI", "error ${e.javaClass.simpleName}: ${e.message?.take(300)}")
                "出错：${e.message ?: e.javaClass.simpleName}"
            }
        }

    // ---------------- OpenAI 兼容 ----------------
    private fun askOpenAi(
        base: String, model: String, key: String, sys: String, userText: String,
        shot: String?, displayId: Int, onStatus: (String) -> Unit
    ): String {
        val userMsg = JSONObject().put("role", "user")
        if (shot != null) {
            userMsg.put(
                "content", JSONArray()
                    .put(JSONObject().put("type", "text").put("text", userText))
                    .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$shot")))
            )
        } else userMsg.put("content", userText)
        history.put(userMsg)

        val url = "$base/chat/completions"
        repeat(MAX_ROUNDS) {
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys))
            for (i in 0 until history.length()) msgs.put(history.get(i))
            val body = JSONObject()
                .put("model", model)
                .put("messages", msgs)
                .put("tools", JSONArray().put(JSONObject().put("type", "function").put("function",
                    JSONObject().put("name", TOOL_NAME).put("description", TOOL_DESC)
                        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())))))
            onStatus("请求中…")
            val resp = post(url, mapOf("Authorization" to "Bearer $key"), body)
            val msg = resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            if (msg.isNull("content")) msg.put("content", "")
            history.put(msg)
            val calls = msg.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) return msg.optString("content").ifBlank { "(空回复)" }
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val name = call.getJSONObject("function").optString("name")
                onStatus("读取虚拟屏文字…")
                val result = runTool(name, displayId)
                history.put(JSONObject().put("role", "tool").put("tool_call_id", call.optString("id")).put("content", result))
            }
        }
        return "(工具调用次数过多，已停止)"
    }

    // ---------------- Anthropic ----------------
    private fun askAnthropic(
        base: String, model: String, key: String, sys: String, userText: String,
        shot: String?, displayId: Int, onStatus: (String) -> Unit
    ): String {
        val content = JSONArray()
        if (shot != null) {
            content.put(JSONObject().put("type", "image").put("source",
                JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", shot)))
        }
        content.put(JSONObject().put("type", "text").put("text", userText))
        history.put(JSONObject().put("role", "user").put("content", content))

        val url = if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
        val headers = mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
        repeat(MAX_ROUNDS) {
            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", 2048)
                .put("system", sys)
                .put("messages", history)
                .put("tools", JSONArray().put(JSONObject().put("name", TOOL_NAME).put("description", TOOL_DESC)
                    .put("input_schema", JSONObject().put("type", "object").put("properties", JSONObject()))))
            onStatus("请求中…")
            val resp = post(url, headers, body)
            val blocks = resp.getJSONArray("content")
            history.put(JSONObject().put("role", "assistant").put("content", blocks))
            val results = JSONArray()
            val text = StringBuilder()
            for (i in 0 until blocks.length()) {
                val b = blocks.getJSONObject(i)
                when (b.optString("type")) {
                    "text" -> text.append(b.optString("text"))
                    "tool_use" -> {
                        onStatus("读取虚拟屏文字…")
                        val r = runTool(b.optString("name"), displayId)
                        results.put(JSONObject().put("type", "tool_result").put("tool_use_id", b.optString("id")).put("content", r))
                    }
                }
            }
            if (results.length() == 0) return text.toString().ifBlank { "(空回复)" }
            history.put(JSONObject().put("role", "user").put("content", results))
        }
        return "(工具调用次数过多，已停止)"
    }

    // ---------------- 工具：只读 ----------------
    private fun runTool(name: String, displayId: Int): String {
        if (name != TOOL_NAME) return "未知工具：$name"
        if (displayId < 0) return "当前没有虚拟屏，无法读取。"
        val bytes = VirtualDisplayManager.captureFrame(displayId, 1280) ?: return "取帧失败（虚拟屏可能处于黑屏或已关闭）。"
        val bmp = FrameOps.decode(bytes) ?: return "画面解码失败。"
        val lines = runCatching { GameOcr.read(bmp) }.getOrElse { return "文字识别失败：${it.message}" }
        LogWriter.write("AI", "tool $TOOL_NAME lines=${lines.size}")
        if (lines.isEmpty()) return "(没有识别到文字)"
        return lines.take(150).joinToString("\n") { "%s (%.2f,%.2f)".format(it.text, it.cx, it.cy) }
    }

    private fun screenshotBase64(displayId: Int): String? {
        val bytes = VirtualDisplayManager.captureFrame(displayId, 1024) ?: return null
        val bmp = FrameOps.decode(bytes) ?: return null
        val out = ByteArrayOutputStream()
        val scaled = if (bmp.width > 1024) Bitmap.createScaledBitmap(bmp, 1024, bmp.height * 1024 / bmp.width, true) else bmp
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // ---------------- HTTP ----------------
    private fun post(url: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code ${text.take(300)}")
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val MAX_ROUNDS = 6
        private const val TOOL_NAME = "read_screen_text"
        private const val TOOL_DESC =
            "读取虚拟屏当前画面上的文字（OCR）。返回每行文字及其在画面中的归一化坐标 (x,y)：x 0=最左 1=最右，y 0=最上 1=最下。只读，不会点击或操作任何东西。"
        private const val DEFAULT_SYSTEM =
            "你是一个只读的屏幕助手，能看到的只有用户的“虚拟屏”（里面运行着某个应用或游戏）。需要了解屏幕内容时，调用 read_screen_text 工具读取文字。你不能点击或操作屏幕，只能观察和回答。请用简洁的中文回答。"
    }
}
