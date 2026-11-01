package com.zz213119.virtualclicker.ai

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/** AI 配置对话框：设置页和主页对话框里的“配置”都用它。服务商、地址、密钥、模型全部由用户自己填。 */
object AiConfigDialog {
    fun show(context: Context, onSaved: (() -> Unit)? = null) {
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), px(4))
        }
        fun label(t: String) = TextView(context).apply {
            text = t
            textSize = 12f
            setPadding(0, px(10), 0, 0)
        }
        fun edit(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(context).apply {
            this.hint = hint
            setText(value)
            inputType = type
            textSize = 14f
            setSingleLine(type != (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        }

        val formats = listOf("OpenAI 兼容（/chat/completions）", "Anthropic（/v1/messages）")
        val spinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, formats)
            setSelection(if (AiConfig.format(context) == AiConfig.FORMAT_ANTHROPIC) 1 else 0)
        }
        val base = edit("例如 https://api.openai.com/v1 或 https://api.anthropic.com", AiConfig.baseUrl(context), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val actual = TextView(context).apply {
            textSize = 11f
            setPadding(0, px(2), 0, 0)
        }
        fun curFormat() = if (spinner.selectedItemPosition == 1) AiConfig.FORMAT_ANTHROPIC else AiConfig.FORMAT_OPENAI
        fun refreshActual() {
            val u = AiConfig.chatUrl(curFormat(), base.text.toString())
            actual.text = if (u.isEmpty()) "实际请求地址：（未填写）" else "实际请求地址：$u"
        }
        base.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { refreshActual() }
        })
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { refreshActual() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        refreshActual()
        val hint = TextView(context).apply {
            text = "提示：填基础地址（如 …/v1）会自动补全路径；如需原样使用，请在地址末尾加 # 号。"
            textSize = 11f
        }
        val key = edit(if (AiConfig.hasApiKey(context)) "已保存（留空则保持不变）" else "API 密钥", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val model = edit("模型名，按服务商文档填写", AiConfig.model(context)).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val listBtn = Button(context).apply {
            text = "列表"
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            setOnClickListener {
                // 用输入框里正在填的内容拉取；密钥留空就用已保存的
                val k = key.text.toString().trim().ifEmpty { AiConfig.apiKey(context) }
                pickModel(context, curFormat(), base.text.toString(), k) { chosen -> model.setText(chosen) }
            }
        }
        val modelRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(model)
            addView(listBtn)
        }
        val sys = edit("系统提示词（可留空，用默认的只读助手）", AiConfig.systemPrompt(context), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            minLines = 2
            maxLines = 5
        }
        val shot = CheckBox(context).apply {
            text = "模型支持识图：提问时直接附带虚拟屏截图（不勾选则只靠文字识别工具读屏；会多耗流量/费用）"
            isChecked = AiConfig.sendScreenshot(context)
            textSize = 13f
        }
        val numType = InputType.TYPE_CLASS_NUMBER
        val maxActions = edit("单次提问最多操作次数，0 = 不限制", AiConfig.maxActions(context).toString(), numType)
        val maxRounds = edit("单次提问最多连续调用几轮，0 = 不限制", AiConfig.maxRounds(context).toString(), numType)
        val ctxWindow = edit("模型上下文窗口（token），0 = 不自动压缩", AiConfig.ctxWindow(context).toString(), numType)
        val compressPct = edit("用到窗口的百分之几时自动压缩（10~95）", AiConfig.compressPct(context).toString(), numType)
        val notify = CheckBox(context).apply {
            text = "AI 任务结束或中断时发通知"
            isChecked = AiConfig.notifyDone(context)
            textSize = 13f
        }
        val note = TextView(context).apply {
            text = "说明：只作用于虚拟屏里的内容，不读也不碰手机本体屏幕；是否允许 AI 点击/滑动由设置页的另一个开关决定（默认只读）；密钥加密保存在本机；" +
                "提问时，识别出的文字（和可选的截图）会发送到你填写的服务商。接口地址需为 https。"
            textSize = 11f
            setPadding(0, px(10), 0, 0)
        }

        box.addView(label("接口格式")); box.addView(spinner)
        box.addView(label("接口地址（Base URL）")); box.addView(base); box.addView(actual); box.addView(hint)
        box.addView(label("API 密钥")); box.addView(key)
        box.addView(label("模型（点右边“列表”可从服务商拉取可用模型）")); box.addView(modelRow)
        box.addView(label("系统提示词")); box.addView(sys)
        box.addView(shot)
        box.addView(label("单次提问的操作上限（点击/长按/滑动/等待的总次数，0 = 不限制）")); box.addView(maxActions)
        box.addView(label("单次提问最多连续调用工具的轮数（0 = 不限制）")); box.addView(maxRounds)
        box.addView(label("模型上下文窗口（token，按你用的模型填；0 = 不自动压缩）")); box.addView(ctxWindow)
        box.addView(label("自动压缩的触发比例（%，默认 75）")); box.addView(compressPct)
        box.addView(notify)
        box.addView(note)

        val scroll = ScrollView(context).apply {
            addView(box, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(context)
            .setTitle("配置 AI 助手")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                AiConfig.save(
                    context,
                    if (spinner.selectedItemPosition == 1) AiConfig.FORMAT_ANTHROPIC else AiConfig.FORMAT_OPENAI,
                    base.text.toString(), model.text.toString(), sys.text.toString(), shot.isChecked,
                    maxActions.text.toString().toIntOrNull() ?: AiConfig.maxActions(context),
                    maxRounds.text.toString().toIntOrNull() ?: AiConfig.maxRounds(context),
                    ctxWindow.text.toString().toIntOrNull() ?: AiConfig.ctxWindow(context),
                    compressPct.text.toString().toIntOrNull() ?: AiConfig.compressPct(context),
                    notify.isChecked
                )
                if (key.text.isNotBlank()) AiConfig.setApiKey(context, key.text.toString())
                Toast.makeText(context, if (AiConfig.isReady(context)) "已保存" else "已保存，但地址/密钥/模型还没填全", Toast.LENGTH_SHORT).show()
                onSaved?.invoke()
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("清除密钥") { _, _ ->
                AiConfig.setApiKey(context, "")
                Toast.makeText(context, "密钥已清除", Toast.LENGTH_SHORT).show()
                onSaved?.invoke()
            }
            .show()
    }

    /** 可用模型列表：从服务商拉取，可搜索、可刷新，点一项就选中。 */
    private fun pickModel(context: Context, format: String, base: String, key: String, onPick: (String) -> Unit) {
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val ui = Handler(Looper.getMainLooper())
        var all: List<String> = emptyList()

        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(px(20), px(12), px(12), 0)
        }
        val title = TextView(context).apply {
            text = "可用模型列表"
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val refresh = Button(context).apply {
            text = "刷新"
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
        }
        head.addView(title)
        head.addView(refresh)

        val search = EditText(context).apply {
            hint = "搜索模型"
            setSingleLine(true)
            textSize = 14f
        }
        val count = TextView(context).apply {
            textSize = 12f
            setPadding(px(20), px(6), px(20), px(6))
        }
        val adapter = ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, ArrayList())
        val list = ListView(context).apply {
            this.adapter = adapter
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(320))
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), 0, px(16), 0)
            addView(search)
            addView(list)
            addView(count)
        }
        fun applyFilter() {
            val q = search.text.toString().trim().lowercase()
            val shown = if (q.isEmpty()) all else all.filter { it.lowercase().contains(q) }
            adapter.clear()
            adapter.addAll(shown)
            adapter.notifyDataSetChanged()
            count.text = "已显示 ${shown.size} 个模型（共 ${all.size} 个）"
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { applyFilter() }
        })
        val dlg = AlertDialog.Builder(context)
            .setCustomTitle(head)
            .setView(body)
            .setNegativeButton("关闭", null)
            .create()
        list.setOnItemClickListener { _, _, pos, _ ->
            adapter.getItem(pos)?.let { onPick(it) }
            dlg.dismiss()
        }
        fun load() {
            count.text = "正在获取模型列表…"
            Thread {
                val r = runCatching { AiModels.fetch(format, base, key) }
                ui.post {
                    r.onSuccess { all = it; applyFilter(); if (it.isEmpty()) count.text = "服务商没有返回模型，可以手动填写" }
                        .onFailure { count.text = "获取失败：${it.message ?: it.javaClass.simpleName}（可以手动填写模型名）" }
                }
            }.start()
        }
        refresh.setOnClickListener { load() }
        dlg.show()
        load()
    }
}
