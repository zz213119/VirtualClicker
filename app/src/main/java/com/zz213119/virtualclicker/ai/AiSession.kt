package com.zz213119.virtualclicker.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.zz213119.virtualclicker.core.Prefs
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
 * 一段对话。工具只作用于用户指定的那块虚拟屏，不碰手机本体屏幕：
 *  - read_screen_text：读取画面文字（OCR），始终可用；
 *  - tap / long_press / swipe / wait：操作虚拟屏，只有在设置里单独打开“允许 AI 操作虚拟屏”才会提供给模型。
 * 支持 OpenAI 兼容和 Anthropic 两种接口格式（用系统自带的 HttpURLConnection + org.json，不引入额外依赖）。
 */
class AiSession(context: Context) {
    private val ctx = context.applicationContext
    /** 按服务商格式保存的完整对话（不含 system）。 */
    private val history = JSONArray()
    @Volatile private var stopFlag = false
    private var actionCount = 0
    private var onAction: (String) -> Unit = {}

    /** 用户点了“停止”：当前这一轮结束后不再继续，也不再执行后续操作。 */
    fun stop() { stopFlag = true }

    fun clear() {
        while (history.length() > 0) history.remove(0)
    }

    suspend fun ask(userText: String, displayId: Int, onAction: (String) -> Unit = {}, onStatus: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            stopFlag = false
            actionCount = 0
            this@AiSession.onAction = onAction
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
            val control = Prefs.aiControl(ctx)
            val sys = AiConfig.systemPrompt(ctx).ifBlank { if (control) SYSTEM_CONTROL else SYSTEM_READONLY }
            val shot = if (AiConfig.sendScreenshot(ctx) && displayId >= 0) screenshotBase64(displayId) else null
            LogWriter.write("AI", "ask format=$format model=$model displayId=$displayId control=$control screenshot=${shot != null} chars=${userText.length}")
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
        repeat(maxRounds()) {
            if (stopFlag) return "(已停止)"
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys))
            for (i in 0 until history.length()) msgs.put(history.get(i))
            val body = JSONObject()
                .put("model", model)
                .put("messages", msgs)
                .put("tools", openAiTools())
            onStatus("请求中…")
            val resp = post(url, mapOf("Authorization" to "Bearer $key"), body)
            val msg = resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            if (msg.isNull("content")) msg.put("content", "")
            history.put(msg)
            val calls = msg.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) return msg.optString("content").ifBlank { "(空回复)" }
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val fn = call.getJSONObject("function")
                val name = fn.optString("name")
                val args = runCatching { JSONObject(fn.optString("arguments").ifBlank { "{}" }) }.getOrDefault(JSONObject())
                onStatus("执行 $name…")
                val result = runTool(name, args, displayId)
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
        repeat(maxRounds()) {
            if (stopFlag) return "(已停止)"
            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", 2048)
                .put("system", sys)
                .put("messages", history)
                .put("tools", anthropicTools())
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
                        onStatus("执行 ${b.optString("name")}…")
                        val r = runTool(b.optString("name"), b.optJSONObject("input") ?: JSONObject(), displayId)
                        results.put(JSONObject().put("type", "tool_result").put("tool_use_id", b.optString("id")).put("content", r))
                    }
                }
            }
            if (results.length() == 0) return text.toString().ifBlank { "(空回复)" }
            history.put(JSONObject().put("role", "user").put("content", results))
        }
        return "(工具调用次数过多，已停止)"
    }

    // ---------------- 工具 ----------------
    private class ToolDef(val name: String, val desc: String, val props: JSONObject, val required: List<String>)

    private fun num(desc: String) = JSONObject().put("type", "number").put("description", desc)

    private fun toolDefs(): List<ToolDef> {
        val l = mutableListOf(ToolDef(TOOL_READ, TOOL_READ_DESC, JSONObject(), emptyList()))
        if (Prefs.aiControl(ctx)) {
            val xy = "x/y 为归一化坐标：x 0=最左 1=最右，y 0=最上 1=最下"
            l += ToolDef("tap", "点击虚拟屏上的一个位置。$xy。",
                JSONObject().put("x", num("横向位置 0~1")).put("y", num("纵向位置 0~1")), listOf("x", "y"))
            l += ToolDef("long_press", "长按虚拟屏上的一个位置。$xy。",
                JSONObject().put("x", num("横向位置 0~1")).put("y", num("纵向位置 0~1")).put("duration_ms", num("按住时长，毫秒，默认 800")), listOf("x", "y"))
            l += ToolDef("swipe", "在虚拟屏上滑动（从起点到终点）。$xy。",
                JSONObject().put("x1", num("起点横向 0~1")).put("y1", num("起点纵向 0~1"))
                    .put("x2", num("终点横向 0~1")).put("y2", num("终点纵向 0~1")).put("duration_ms", num("滑动时长，毫秒，默认 400")),
                listOf("x1", "y1", "x2", "y2"))
            l += ToolDef("wait", "等待一段时间再继续（比如等加载），最多 10 秒。",
                JSONObject().put("ms", num("等待毫秒数")), listOf("ms"))
        }
        return l
    }

    private fun schema(t: ToolDef) = JSONObject().put("type", "object").put("properties", t.props)
        .apply { if (t.required.isNotEmpty()) put("required", JSONArray(t.required)) }

    private fun openAiTools() = JSONArray().apply {
        toolDefs().forEach { t ->
            put(JSONObject().put("type", "function").put("function",
                JSONObject().put("name", t.name).put("description", t.desc).put("parameters", schema(t))))
        }
    }

    private fun anthropicTools() = JSONArray().apply {
        toolDefs().forEach { t -> put(JSONObject().put("name", t.name).put("description", t.desc).put("input_schema", schema(t))) }
    }

    private fun maxRounds() = if (Prefs.aiControl(ctx)) 15 else 6

    private fun runTool(name: String, args: JSONObject, displayId: Int): String {
        if (stopFlag) return "用户已停止。"
        if (displayId < 0) return "当前没有虚拟屏，无法操作。"
        return when (name) {
            TOOL_READ -> readScreen(displayId)
            "tap", "long_press", "swipe", "wait" -> {
                if (!Prefs.aiControl(ctx)) return "没有被允许操作虚拟屏（设置里的“允许 AI 操作虚拟屏”是关闭的）。"
                if (++actionCount > MAX_ACTIONS) return "已达到单次提问的操作上限（$MAX_ACTIONS 次），请让用户确认后再继续。"
                act(name, args, displayId)
            }
            else -> "未知工具：$name"
        }
    }

    private fun readScreen(displayId: Int): String {
        val bytes = VirtualDisplayManager.captureFrame(displayId, 1280) ?: return "取帧失败（虚拟屏可能处于黑屏或已关闭）。"
        val bmp = FrameOps.decode(bytes) ?: return "画面解码失败。"
        val lines = runCatching { GameOcr.read(bmp) }.getOrElse { return "文字识别失败：${it.message}" }
        LogWriter.write("AI", "tool $TOOL_READ lines=${lines.size}")
        if (lines.isEmpty()) return "(没有识别到文字)"
        return lines.take(150).joinToString("\n") { "%s (%.2f,%.2f)".format(it.text, it.cx, it.cy) }
    }

    private fun act(name: String, a: JSONObject, displayId: Int): String {
        val size = VirtualDisplayManager.displaySize(displayId) ?: return "拿不到虚拟屏尺寸，操作失败。"
        val w = size[0].toFloat()
        val h = size[1].toFloat()
        fun nx(k: String) = a.optDouble(k, Double.NaN).toFloat()
        fun ok(v: Float) = !v.isNaN() && v in 0f..1f
        val r: String
        when (name) {
            "tap" -> {
                val x = nx("x"); val y = nx("y")
                if (!ok(x) || !ok(y)) return "坐标必须是 0~1 之间的数。"
                val done = VirtualDisplayManager.tap(displayId, x * w, y * h)
                onAction("点击 (%.2f, %.2f)".format(x, y))
                r = if (done) "已点击" else "点击失败"
            }
            "long_press" -> {
                val x = nx("x"); val y = nx("y")
                if (!ok(x) || !ok(y)) return "坐标必须是 0~1 之间的数。"
                val d = a.optInt("duration_ms", 800).coerceIn(200, 5000)
                val done = VirtualDisplayManager.longPress(displayId, x * w, y * h, d)
                onAction("长按 (%.2f, %.2f) %dms".format(x, y, d))
                r = if (done) "已长按" else "长按失败"
            }
            "swipe" -> {
                val x1 = nx("x1"); val y1 = nx("y1"); val x2 = nx("x2"); val y2 = nx("y2")
                if (!ok(x1) || !ok(y1) || !ok(x2) || !ok(y2)) return "坐标必须是 0~1 之间的数。"
                val d = a.optInt("duration_ms", 400).coerceIn(100, 3000)
                val done = VirtualDisplayManager.swipe(displayId, x1 * w, y1 * h, x2 * w, y2 * h, d)
                onAction("滑动 (%.2f, %.2f)→(%.2f, %.2f)".format(x1, y1, x2, y2))
                r = if (done) "已滑动" else "滑动失败"
            }
            else -> {
                val ms = a.optInt("ms", 1000).coerceIn(100, 10_000)
                onAction("等待 ${ms}ms")
                Thread.sleep(ms.toLong())
                r = "已等待"
            }
        }
        LogWriter.write("AI", "action $name -> $r")
        Thread.sleep(450)   // 给界面一点反应时间
        return r
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
        private const val MAX_ACTIONS = 40
        private const val TOOL_READ = "read_screen_text"
        private const val TOOL_READ_DESC =
            "读取虚拟屏当前画面上的文字（OCR）。返回每行文字及其在画面中的归一化坐标 (x,y)：x 0=最左 1=最右，y 0=最上 1=最下。只读。"
        private const val SYSTEM_READONLY =
            "你是一个只读的屏幕助手，能看到的只有用户的“虚拟屏”（里面运行着某个应用或游戏）。需要了解屏幕内容时，调用 read_screen_text 工具读取文字。你不能点击或操作屏幕，只能观察和回答。请用简洁的中文回答。"
        private const val SYSTEM_CONTROL =
            "你是一个屏幕助手，只能看到并操作用户的“虚拟屏”（里面运行着某个应用或游戏），不能碰手机本体屏幕。" +
                "用 read_screen_text 读取画面文字和位置，用 tap / long_press / swipe 操作，用 wait 等待加载。" +
                "每做一步操作后，再用 read_screen_text 确认结果，不要盲目连续操作。" +
                "不要进行付费、购买、删除、转账、授权登录等不可逆操作，除非用户明确要求；拿不准时先问用户。请用简洁的中文回答。"
    }
}
