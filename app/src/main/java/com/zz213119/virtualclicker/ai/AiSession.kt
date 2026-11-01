package com.zz213119.virtualclicker.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.zz213119.virtualclicker.core.Prefs
import com.zz213119.virtualclicker.core.VirtualDisplayManager
import com.zz213119.virtualclicker.script.ScriptAction
import com.zz213119.virtualclicker.script.ScriptActionType
import com.zz213119.virtualclicker.script.ScriptDefinition
import com.zz213119.virtualclicker.script.ScriptRepository
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
 * AI 对话（只作用于用户指定的那块虚拟屏，不碰手机本体屏幕）：
 *  - read_screen_text：读取画面文字（OCR），始终可用；
 *  - tap / long_press / swipe / wait：操作虚拟屏，只有在设置里单独打开“允许 AI 操作虚拟屏”才会提供给模型。
 * 支持 OpenAI 兼容和 Anthropic 两种接口格式（系统自带的 HttpURLConnection + org.json，不引入额外依赖）。
 * 对话会保存到本地（历史对话），上下文快到窗口的 N%（默认 75%）时自动让模型压缩成摘要。
 */
class AiSession(context: Context) {
    enum class Outcome(val title: String) {
        DONE("AI 任务完成"),
        STOPPED("AI 任务已中断（手动停止）"),
        LIMIT("AI 任务已中断（达到上限）"),
        ERROR("AI 任务出错中断")
    }

    class Result(val text: String, val outcome: Outcome)

    private val ctx = context.applicationContext

    /** 按服务商格式保存的完整对话（不含 system）。 */
    private var history = JSONArray()
    private var historyFormat = ""
    private val display = StringBuilder()
    private var chatId = AiStore.newId()
    private var title = ""

    @Volatile var listener: ((String) -> Unit)? = null
    @Volatile var statusListener: ((String) -> Unit)? = null
    @Volatile private var stopFlag = false
    private var actionCount = 0
    private var lastPromptTokens = 0
    private var lastHistoryChars = 0

    /** AI 实际执行成功的操作（归一化坐标 + 当时虚拟屏尺寸），可以随时存成脚本。task = 第几次提问。 */
    private class Rec(val type: String, val x: Float, val y: Float, val x2: Float, val y2: Float, val dur: Long, val w: Int, val h: Int, val task: Int)
    private val recorded = ArrayList<Rec>()
    private var taskNo = 0

    // ---------------- 对话管理 ----------------
    fun displayText(): String = synchronized(display) { display.toString() }
    fun currentTitle(): String = title

    private fun emit(t: String) {
        synchronized(display) { display.append(t) }
        listener?.invoke(t)
    }

    private fun status(t: String) { statusListener?.invoke(t) }

    /** 用户点了“停止”：当前这一步结束后不再继续，也不再执行后续操作。 */
    fun stop() { stopFlag = true }

    private fun resetState() {
        history = JSONArray()
        historyFormat = ""
        synchronized(display) { display.setLength(0) }
        title = ""
        lastPromptTokens = 0
        lastHistoryChars = 0
        recorded.clear()
        taskNo = 0
    }

    fun newChat() {
        saveNow()
        chatId = AiStore.newId()
        resetState()
    }

    fun restoreLatest() {
        val latest = AiStore.list(ctx).firstOrNull() ?: return
        open(latest.id)
    }

    fun open(id: String): Boolean {
        if (id != chatId) saveNow()
        val j = AiStore.load(ctx, id) ?: return false
        chatId = id
        resetState()
        title = j.optString("title")
        history = j.optJSONArray("wire") ?: JSONArray()
        historyFormat = j.optString("format")
        synchronized(display) { display.append(j.optString("display")) }
        j.optJSONArray("rec")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                recorded.add(Rec(o.optString("t"), o.optDouble("x").toFloat(), o.optDouble("y").toFloat(),
                    o.optDouble("x2").toFloat(), o.optDouble("y2").toFloat(), o.optLong("d"), o.optInt("w"), o.optInt("h"), o.optInt("k")))
            }
            taskNo = recorded.maxOfOrNull { it.task } ?: 0
        }
        return true
    }

    fun chats(): List<AiStore.Info> = AiStore.list(ctx)

    fun currentId(): String = chatId

    fun deleteChat(id: String) {
        AiStore.delete(ctx, id)
        if (id == chatId) {
            chatId = AiStore.newId()
            resetState()
        }
    }

    private fun saveNow() {
        if (title.isBlank() && history.length() == 0 && display.isEmpty()) return
        runCatching {
            val rec = JSONArray()
            for (r in recorded) rec.put(JSONObject().put("t", r.type).put("x", r.x.toDouble()).put("y", r.y.toDouble())
                .put("x2", r.x2.toDouble()).put("y2", r.y2.toDouble()).put("d", r.dur).put("w", r.w).put("h", r.h).put("k", r.task))
            AiStore.save(ctx, chatId, title.ifBlank { "(无标题)" }, historyFormat, displayText(), history, rec)
        }
            .onFailure { LogWriter.write("AI", "save chat failed: ${it.message}") }
    }

    // ---------------- 提问 ----------------
    suspend fun ask(userText: String, displayId: Int): Result = withContext(Dispatchers.IO) {
        stopFlag = false
        actionCount = 0
        taskNo++
        val format = AiConfig.format(ctx)
        val base = AiConfig.baseUrl(ctx).trim().trimEnd('/')
        val model = AiConfig.model(ctx).trim()
        val key = AiConfig.apiKey(ctx)
        if (base.isEmpty() || model.isEmpty() || key.isEmpty()) {
            val m = "还没配置完整：请点“配置”填写接口地址、密钥、模型。"
            emit("AI：$m\n\n")
            return@withContext Result(m, Outcome.ERROR)
        }
        if (!base.startsWith("https://")) {
            val m = "接口地址必须是 https:// 开头（系统不允许明文 http）。"
            emit("AI：$m\n\n")
            return@withContext Result(m, Outcome.ERROR)
        }
        if (historyFormat != format) {
            if (history.length() > 0) {
                emit("（接口格式已更换，之前的上下文已重置，只保留显示记录）\n")
                history = JSONArray()
            }
            historyFormat = format
        }
        if (title.isBlank()) title = userText.replace("\n", " ").take(24)
        emit("你：$userText\n")

        val control = Prefs.aiControl(ctx)
        val sys = AiConfig.systemPrompt(ctx).ifBlank { if (control) SYSTEM_CONTROL else SYSTEM_READONLY }
        val shot = if (AiConfig.sendScreenshot(ctx) && displayId >= 0) screenshotBase64(displayId) else null
        LogWriter.write("AI", "ask format=$format model=$model displayId=$displayId control=$control screenshot=${shot != null} chars=${userText.length} chat=$chatId")

        val result = try {
            if (format == AiConfig.FORMAT_ANTHROPIC) askAnthropic(base, model, key, sys, userText, shot, displayId)
            else askOpenAi(base, model, key, sys, userText, shot, displayId)
        } catch (e: Throwable) {
            LogWriter.write("AI", "error ${e.javaClass.simpleName}: ${e.message?.take(300)}")
            Result("出错：${e.message ?: e.javaClass.simpleName}", Outcome.ERROR)
        }
        stripImages()
        emit("AI：${result.text}\n\n")
        saveNow()
        status("")
        result
    }

    // ---------------- OpenAI 兼容 ----------------
    private fun askOpenAi(
        base: String, model: String, key: String, sys: String, userText: String,
        shot: String?, displayId: Int
    ): Result {
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
        val maxR = AiConfig.maxRounds(ctx)
        var round = 0
        while (maxR == 0 || round < maxR) {
            round++
            if (stopFlag) return Result("(已停止)", Outcome.STOPPED)
            maybeCompress(AiConfig.FORMAT_OPENAI, base, model, key)
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys))
            for (i in 0 until history.length()) msgs.put(history.get(i))
            val body = JSONObject()
                .put("model", model)
                .put("messages", msgs)
                .put("tools", openAiTools())
            val charsNow = history.toString().length
            status("请求中…")
            val resp = post(url, mapOf("Authorization" to "Bearer $key"), body)
            lastPromptTokens = resp.optJSONObject("usage")?.optInt("prompt_tokens", 0) ?: 0
            lastHistoryChars = charsNow
            val msg = resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            if (msg.isNull("content")) msg.put("content", "")
            history.put(msg)
            val calls = msg.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) return Result(msg.optString("content").ifBlank { "(空回复)" }, Outcome.DONE)
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val fn = call.getJSONObject("function")
                val name = fn.optString("name")
                val args = runCatching { JSONObject(fn.optString("arguments").ifBlank { "{}" }) }.getOrDefault(JSONObject())
                status("执行 $name…")
                val result = runTool(name, args, displayId)
                history.put(JSONObject().put("role", "tool").put("tool_call_id", call.optString("id")).put("content", result))
            }
        }
        return Result("已达到本次提问的轮数上限（$maxR 轮）。可以在“配置”里调大，或改为 0 表示不限制，再发一句话继续。", Outcome.LIMIT)
    }

    // ---------------- Anthropic ----------------
    private fun askAnthropic(
        base: String, model: String, key: String, sys: String, userText: String,
        shot: String?, displayId: Int
    ): Result {
        val content = JSONArray()
        if (shot != null) {
            content.put(JSONObject().put("type", "image").put("source",
                JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", shot)))
        }
        content.put(JSONObject().put("type", "text").put("text", userText))
        history.put(JSONObject().put("role", "user").put("content", content))

        val url = if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
        val headers = mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
        val maxR = AiConfig.maxRounds(ctx)
        var round = 0
        while (maxR == 0 || round < maxR) {
            round++
            if (stopFlag) return Result("(已停止)", Outcome.STOPPED)
            maybeCompress(AiConfig.FORMAT_ANTHROPIC, base, model, key)
            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", 2048)
                .put("system", sys)
                .put("messages", history)
                .put("tools", anthropicTools())
            val charsNow = history.toString().length
            status("请求中…")
            val resp = post(url, headers, body)
            val u = resp.optJSONObject("usage")
            lastPromptTokens = if (u == null) 0 else
                u.optInt("input_tokens", 0) + u.optInt("cache_read_input_tokens", 0) + u.optInt("cache_creation_input_tokens", 0)
            lastHistoryChars = charsNow
            val blocks = resp.getJSONArray("content")
            history.put(JSONObject().put("role", "assistant").put("content", blocks))
            val results = JSONArray()
            val text = StringBuilder()
            for (i in 0 until blocks.length()) {
                val b = blocks.getJSONObject(i)
                when (b.optString("type")) {
                    "text" -> text.append(b.optString("text"))
                    "tool_use" -> {
                        status("执行 ${b.optString("name")}…")
                        val r = runTool(b.optString("name"), b.optJSONObject("input") ?: JSONObject(), displayId)
                        results.put(JSONObject().put("type", "tool_result").put("tool_use_id", b.optString("id")).put("content", r))
                    }
                }
            }
            if (results.length() == 0) return Result(text.toString().ifBlank { "(空回复)" }, Outcome.DONE)
            history.put(JSONObject().put("role", "user").put("content", results))
        }
        return Result("已达到本次提问的轮数上限（$maxR 轮）。可以在“配置”里调大，或改为 0 表示不限制，再发一句话继续。", Outcome.LIMIT)
    }

    // ---------------- 上下文压缩 ----------------
    private fun maybeCompress(format: String, base: String, model: String, key: String) {
        val window = AiConfig.ctxWindow(ctx)
        if (window <= 0) return
        val limit = window.toLong() * AiConfig.compressPct(ctx) / 100
        val chars = history.toString().length
        val est = if (lastPromptTokens > 0) lastPromptTokens + maxOf(0, chars - lastHistoryChars) / 2L
        else chars / 2L + 600
        if (est < limit) return
        LogWriter.write("AI", "compress start est=$est limit=$limit window=$window pct=${AiConfig.compressPct(ctx)}")
        status("压缩上下文…")
        val cut = lastRealUserIndex(format)
        if (cut <= 0) {
            // 整段历史都属于当前这一次提问（一直在调工具）：只能把较早的工具结果缩短
            shrinkToolResults(format)
            lastPromptTokens = 0
            emit("（上下文已精简：较早的屏幕文字已省略）\n")
            return
        }
        val older = JSONArray()
        for (i in 0 until cut) older.put(history.get(i))
        val transcript = transcript(older, format).takeLast(60_000)
        val summary = runCatching { summarize(format, base, model, key, transcript) }
            .getOrElse {
                LogWriter.write("AI", "summarize failed: ${it.message?.take(200)}")
                "（较早的对话因压缩失败被省略）"
            }
        val fresh = JSONArray()
        fresh.put(JSONObject().put("role", "user").put("content", "【之前对话的摘要】\n$summary"))
        fresh.put(JSONObject().put("role", "assistant").put("content", "好的，我已了解之前的进展，请继续。"))
        for (i in cut until history.length()) fresh.put(history.get(i))
        val after = fresh.toString().length
        history = fresh
        lastPromptTokens = 0
        lastHistoryChars = after
        emit("（上下文已压缩：$chars → $after 字符）\n")
        LogWriter.write("AI", "compress done $chars -> $after chars")
    }

    private fun lastRealUserIndex(format: String): Int {
        for (i in history.length() - 1 downTo 0) {
            val m = history.getJSONObject(i)
            if (m.optString("role") != "user") continue
            if (format == AiConfig.FORMAT_OPENAI) return i
            val c = m.opt("content")
            if (c is String) return i
            if (c is JSONArray) {
                for (k in 0 until c.length()) if (c.getJSONObject(k).optString("type") == "text") return i
            }
        }
        return -1
    }

    private fun shrinkToolResults(format: String) {
        val placeholder = "[较早的工具结果已省略以节省上下文]"
        var seen = 0
        for (i in history.length() - 1 downTo 0) {
            val m = history.getJSONObject(i)
            if (format == AiConfig.FORMAT_OPENAI) {
                if (m.optString("role") == "tool" && ++seen > 2) m.put("content", placeholder)
            } else {
                val c = m.opt("content") as? JSONArray ?: continue
                for (k in c.length() - 1 downTo 0) {
                    val b = c.getJSONObject(k)
                    if (b.optString("type") == "tool_result" && ++seen > 2) b.put("content", placeholder)
                }
            }
        }
    }

    private fun textOf(c: Any?): String = when (c) {
        is String -> c
        is JSONArray -> buildString {
            for (k in 0 until c.length()) {
                val b = c.optJSONObject(k) ?: continue
                if (b.optString("type") == "text") append(b.optString("text"))
            }
        }
        else -> ""
    }

    private fun transcript(arr: JSONArray, format: String): String {
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val role = m.optString("role")
            val who = if (role == "user") "用户：" else "AI："
            if (format == AiConfig.FORMAT_OPENAI) {
                when (role) {
                    "user" -> sb.append("用户：").append(textOf(m.opt("content"))).append('\n')
                    "assistant" -> {
                        val t = textOf(m.opt("content"))
                        if (t.isNotBlank()) sb.append("AI：").append(t).append('\n')
                        val calls = m.optJSONArray("tool_calls")
                        if (calls != null) for (k in 0 until calls.length()) {
                            val f = calls.getJSONObject(k).optJSONObject("function")
                            sb.append("AI 调用工具：").append(f?.optString("name")).append(' ')
                                .append(f?.optString("arguments")?.take(120)).append('\n')
                        }
                    }
                    "tool" -> sb.append("工具结果：").append(m.optString("content").take(400)).append('\n')
                }
            } else {
                val c = m.opt("content")
                if (c is String) sb.append(who).append(c).append('\n')
                else if (c is JSONArray) for (k in 0 until c.length()) {
                    val b = c.getJSONObject(k)
                    when (b.optString("type")) {
                        "text" -> sb.append(who).append(b.optString("text")).append('\n')
                        "tool_use" -> sb.append("AI 调用工具：").append(b.optString("name")).append(' ')
                            .append(b.optJSONObject("input")?.toString()?.take(120)).append('\n')
                        "tool_result" -> sb.append("工具结果：").append(textOf(b.opt("content")).ifEmpty { b.opt("content")?.toString() ?: "" }.take(400)).append('\n')
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun summarize(format: String, base: String, model: String, key: String, transcript: String): String {
        val prompt = "下面是用户和一个屏幕助手（可以读取、操作用户的虚拟屏）的历史对话记录。请压缩成一份供后续继续对话使用的摘要：" +
            "保留用户的目标、已完成的操作及结果、屏幕上的关键信息（界面状态、重要文字和位置）、未完成的事项和用户的偏好；不要编造；" +
            "用中文，不超过 800 字。\n\n=====\n$transcript"
        return if (format == AiConfig.FORMAT_ANTHROPIC) {
            val url = if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
            val body = JSONObject().put("model", model).put("max_tokens", 1500)
                .put("system", "你是对话摘要助手。")
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            val resp = post(url, mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), body)
            textOf(resp.getJSONArray("content")).ifBlank { throw IOException("摘要为空") }
        } else {
            val body = JSONObject().put("model", model)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", "你是对话摘要助手。"))
                    .put(JSONObject().put("role", "user").put("content", prompt)))
            val resp = post("$base/chat/completions", mapOf("Authorization" to "Bearer $key"), body)
            resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").ifBlank { throw IOException("摘要为空") }
        }
    }

    /** 每次提问结束后把历史里的截图去掉（只留文字），省上下文，也避免存盘文件过大。 */
    private fun stripImages() {
        for (i in 0 until history.length()) {
            val m = history.optJSONObject(i) ?: continue
            if (m.optString("role") != "user") continue
            val c = m.opt("content") as? JSONArray ?: continue
            if (historyFormat == AiConfig.FORMAT_OPENAI) {
                var hasImage = false
                val text = StringBuilder()
                for (k in 0 until c.length()) {
                    val b = c.optJSONObject(k) ?: continue
                    if (b.optString("type") == "image_url") hasImage = true else text.append(b.optString("text"))
                }
                if (hasImage) m.put("content", text.toString() + "\n[截图已省略]")
            } else {
                val keep = JSONArray()
                var removed = false
                for (k in 0 until c.length()) {
                    val b = c.optJSONObject(k) ?: continue
                    if (b.optString("type") == "image") removed = true else keep.put(b)
                }
                if (removed) {
                    keep.put(JSONObject().put("type", "text").put("text", "[截图已省略]"))
                    m.put("content", keep)
                }
            }
        }
    }

    // ---------------- 工具 ----------------
    private class ToolDef(val name: String, val desc: String, val props: JSONObject, val required: List<String>)

    private fun num(desc: String) = JSONObject().put("type", "number").put("description", desc)
    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
    private fun bool(desc: String) = JSONObject().put("type", "boolean").put("description", desc)

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
            l += ToolDef("save_script",
                "把本对话里你已经执行过的点击/长按/滑动/等待保存成一个脚本，用户之后可以在脚本编辑器里查看、修改和运行。" +
                    "会在相邻的操作之间自动加入固定的等待（wait_ms，毫秒；用户说“0.5 秒”就填 500，说“不要间隔”就填 0）。" +
                    "用户要求把间隔改成别的数值时，用同样的名字、新的 wait_ms 和 overwrite=true 再保存一次即可。",
                JSONObject().put("name", str("脚本名称"))
                    .put("wait_ms", num("相邻操作之间自动加入的等待，毫秒，默认 1000，0 表示不加"))
                    .put("scope", str("last_task（默认，只存最近一次有操作的提问）或 all（存本对话里所有操作）"))
                    .put("overwrite", bool("同名脚本已存在时是否覆盖，默认 false")),
                listOf("name"))
        }
        return l
    }

    private fun schema(t: ToolDef): JSONObject {
        val o = JSONObject().put("type", "object").put("properties", t.props)
        if (t.required.isNotEmpty()) o.put("required", JSONArray(t.required))
        return o
    }

    private fun openAiTools() = JSONArray().apply {
        toolDefs().forEach { t ->
            put(JSONObject().put("type", "function").put("function",
                JSONObject().put("name", t.name).put("description", t.desc).put("parameters", schema(t))))
        }
    }

    private fun anthropicTools() = JSONArray().apply {
        toolDefs().forEach { t -> put(JSONObject().put("name", t.name).put("description", t.desc).put("input_schema", schema(t))) }
    }

    private fun runTool(name: String, args: JSONObject, displayId: Int): String {
        if (stopFlag) return "用户已停止。"
        if (displayId < 0) return "当前没有虚拟屏，无法操作。"
        return when (name) {
            TOOL_READ -> readScreen(displayId)
            "tap", "long_press", "swipe", "wait" -> {
                if (!Prefs.aiControl(ctx)) return "没有被允许操作虚拟屏（设置里的“允许 AI 操作虚拟屏”是关闭的）。"
                val limit = AiConfig.maxActions(ctx)
                actionCount++
                if (limit > 0 && actionCount > limit) return "已达到单次提问的操作上限（$limit 次），请让用户确认后再继续。"
                act(name, args, displayId)
            }
            "save_script" -> {
                if (!Prefs.aiControl(ctx)) return "没有被允许操作虚拟屏，也就没有可保存的操作。"
                saveScript(
                    args.optString("name"),
                    if (args.has("wait_ms")) args.optLong("wait_ms", 1000L) else 1000L,
                    args.optString("scope", "last_task"),
                    args.optBoolean("overwrite", false)
                )
            }
            else -> "未知工具：$name"
        }
    }

    /** 把已执行的操作存成脚本（和脚本编辑器里的脚本同一格式）。返回给 AI / 界面的说明。 */
    fun saveScript(name: String, waitMs: Long, scope: String, overwrite: Boolean): String {
        val n = name.trim()
        if (n.isEmpty()) return "脚本名称不能为空。"
        if (recorded.isEmpty()) return "还没有可保存的操作：本对话里 AI 还没有执行过点击/滑动。"
        val src = if (scope == "all") recorded.toList() else {
            val last = recorded.maxOf { it.task }
            recorded.filter { it.task == last }
        }
        val wait = waitMs.coerceIn(0L, 600_000L)
        val out = ArrayList<ScriptAction>()
        for (r in src) {
            val type = runCatching { ScriptActionType.valueOf(r.type) }.getOrNull() ?: continue
            val a = if (type == ScriptActionType.WAIT) ScriptAction(type, durationMs = r.dur)
            else ScriptAction(type, x = r.x * r.w, y = r.y * r.h, x2 = r.x2 * r.w, y2 = r.y2 * r.h, durationMs = r.dur)
            if (wait > 0 && out.isNotEmpty() && type != ScriptActionType.WAIT && out.last().type != ScriptActionType.WAIT) {
                out.add(ScriptAction(ScriptActionType.WAIT, durationMs = wait))
            }
            out.add(a)
        }
        if (out.isEmpty()) return "没有可保存的操作。"
        if (ScriptRepository.exists(ctx, n) && !overwrite) return "已存在同名脚本「$n」。换一个名字，或者设置 overwrite=true 覆盖。"
        ScriptRepository.saveOrUpdate(ctx, ScriptDefinition(name = n, repeatCount = 1, actions = out))
        val waits = out.count { it.type == ScriptActionType.WAIT }
        val msg = "已保存脚本「$n」：共 ${out.size} 个动作（其中等待 $waits 个），自动间隔 ${wait} 毫秒。可以在脚本编辑器里查看、修改和运行。"
        emit("  · $msg\n")
        LogWriter.write("AI", "save_script name=$n actions=${out.size} wait=$wait scope=$scope")
        saveNow()
        return msg
    }

    fun hasRecorded(): Boolean = recorded.isNotEmpty()

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
                if (done) recorded.add(Rec("CLICK", x, y, 0f, 0f, 50L, size[0], size[1], taskNo))
                emit("  · 点击 (%.2f, %.2f)\n".format(x, y))
                r = if (done) "已点击" else "点击失败"
            }
            "long_press" -> {
                val x = nx("x"); val y = nx("y")
                if (!ok(x) || !ok(y)) return "坐标必须是 0~1 之间的数。"
                val d = a.optInt("duration_ms", 800).coerceIn(200, 5000)
                val done = VirtualDisplayManager.longPress(displayId, x * w, y * h, d)
                if (done) recorded.add(Rec("LONG_PRESS", x, y, 0f, 0f, d.toLong(), size[0], size[1], taskNo))
                emit("  · 长按 (%.2f, %.2f) %dms\n".format(x, y, d))
                r = if (done) "已长按" else "长按失败"
            }
            "swipe" -> {
                val x1 = nx("x1"); val y1 = nx("y1"); val x2 = nx("x2"); val y2 = nx("y2")
                if (!ok(x1) || !ok(y1) || !ok(x2) || !ok(y2)) return "坐标必须是 0~1 之间的数。"
                val d = a.optInt("duration_ms", 400).coerceIn(100, 3000)
                val done = VirtualDisplayManager.swipe(displayId, x1 * w, y1 * h, x2 * w, y2 * h, d)
                if (done) recorded.add(Rec("SWIPE", x1, y1, x2, y2, d.toLong(), size[0], size[1], taskNo))
                emit("  · 滑动 (%.2f, %.2f)→(%.2f, %.2f)\n".format(x1, y1, x2, y2))
                r = if (done) "已滑动" else "滑动失败"
            }
            else -> {
                val ms = a.optInt("ms", 1000).coerceIn(100, 10_000)
                emit("  · 等待 ${ms}ms\n")
                recorded.add(Rec("WAIT", 0f, 0f, 0f, 0f, ms.toLong(), size[0], size[1], taskNo))
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
        private const val TOOL_READ = "read_screen_text"
        private const val TOOL_READ_DESC =
            "读取虚拟屏当前画面上的文字（OCR）。返回每行文字及其在画面中的归一化坐标 (x,y)：x 0=最左 1=最右，y 0=最上 1=最下。只读。"
        private const val SYSTEM_READONLY =
            "你是一个只读的屏幕助手，能看到的只有用户的“虚拟屏”（里面运行着某个应用或游戏）。需要了解屏幕内容时，调用 read_screen_text 工具读取文字。你不能点击或操作屏幕，只能观察和回答。请用简洁的中文回答。"
        private const val SYSTEM_CONTROL =
            "你是一个屏幕助手，只能看到并操作用户的“虚拟屏”（里面运行着某个应用或游戏），不能碰手机本体屏幕。" +
                "用 read_screen_text 读取画面文字和位置，用 tap / long_press / swipe 操作，用 wait 等待加载。" +
                "每做一步操作后，再用 read_screen_text 确认结果，不要盲目连续操作。" +
                "不要进行付费、购买、删除、转账、授权登录等不可逆操作，除非用户明确要求；拿不准时先问用户。" +
                "用户要求把操作保存成脚本时，调用 save_script（用户说“间隔 0.5 秒”就把 wait_ms 设为 500；要改间隔就用同名再保存一次并设 overwrite=true）。请用简洁的中文回答。"
    }
}
