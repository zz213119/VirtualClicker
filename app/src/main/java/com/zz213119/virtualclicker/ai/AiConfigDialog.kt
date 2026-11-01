package com.zz213119.virtualclicker.ai

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
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
import com.zz213119.virtualclicker.R

/** AI 配置对话框：设置页和主页对话框里的“配置”都用它。服务商、地址、密钥、模型全部由用户自己填。 */
object AiConfigDialog {
    fun show(context: Context, onSaved: (() -> Unit)? = null) {
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val bgColor = context.getColor(R.color.vc_bg)
        val cardColor = context.getColor(R.color.vc_card)
        val textColor = context.getColor(R.color.vc_text)
        val secondaryColor = context.getColor(R.color.vc_text2)
        val primaryColor = context.getColor(R.color.vc_primary)
        val tonalColor = context.getColor(R.color.vc_tonal)
        val onTonalColor = context.getColor(R.color.vc_on_tonal)
        val outlineColor = context.getColor(R.color.vc_outline)

        fun rounded(fill: Int, stroke: Int = outlineColor, radius: Int = 14): GradientDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = px(radius).toFloat()
                setColor(fill)
                if (stroke != 0) setStroke(px(1), stroke)
            }

        fun fieldBackground() = rounded(cardColor, outlineColor, 10)

        fun label(t: String) = TextView(context).apply {
            text = t
            textSize = 12f
            setTextColor(secondaryColor)
            setTypeface(typeface, Typeface.BOLD)
        }

        fun edit(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT) =
            EditText(context).apply {
                this.hint = hint
                setText(value)
                inputType = type
                textSize = 14f
                setTextColor(textColor)
                setHintTextColor(secondaryColor)
                background = fieldBackground()
                setPadding(px(12), px(10), px(12), px(10))
                minimumHeight = px(52)
                val multiLine = (type and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
                setSingleLine(!multiLine)
                if (multiLine) {
                    minLines = 3
                    maxLines = 5
                    gravity = Gravity.TOP or Gravity.START
                }
            }

        fun section(title: String) = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cardColor, outlineColor, 16)
            setPadding(px(16), px(14), px(16), px(16))
            addView(TextView(context).apply {
                text = title
                textSize = 15f
                setTextColor(textColor)
                setTypeface(typeface, Typeface.BOLD)
            })
        }

        fun add(parent: LinearLayout, child: View, top: Int = 6) {
            parent.addView(child, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(top) })
        }

        fun addField(parent: LinearLayout, title: String, child: View, top: Int = 12) {
            add(parent, label(title), top)
            add(parent, child, 5)
        }

        val formats = listOf("OpenAI 兼容（/chat/completions）", "Anthropic（/v1/messages）")
        val spinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, formats)
            setSelection(if (AiConfig.format(context) == AiConfig.FORMAT_ANTHROPIC) 1 else 0)
            background = fieldBackground()
            setPadding(px(12), 0, px(12), 0)
            minimumHeight = px(52)
        }
        val base = edit("例如 https://api.openai.com/v1 或 https://api.anthropic.com",
            AiConfig.baseUrl(context),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val actual = TextView(context).apply {
            textSize = 12f
            setTextColor(primaryColor)
            setPadding(0, px(3), 0, 0)
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
            text = "填基础地址（如 …/v1）会自动补全路径；如需原样使用，请在地址末尾加 # 号。"
            textSize = 11f
            setTextColor(secondaryColor)
        }
        val key = edit(if (AiConfig.hasApiKey(context)) "已保存（留空则保持不变）" else "API 密钥",
            "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val model = edit("模型名，按服务商文档填写", AiConfig.model(context)).apply {
            layoutParams = LinearLayout.LayoutParams(0, px(52), 1f)
        }
        val listBtn = Button(context).apply {
            text = "列表"
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            minHeight = px(48)
            setPadding(px(12), 0, px(12), 0)
            setBackgroundResource(R.drawable.bg_btn_tonal)
            setTextColor(onTonalColor)
            setOnClickListener {
                val k = key.text.toString().trim().ifEmpty { AiConfig.apiKey(context) }
                pickModel(context, curFormat(), base.text.toString(), k) { chosen -> model.setText(chosen) }
            }
        }
        val modelRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(model)
            addView(listBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, px(48)
            ).apply { marginStart = px(8) })
        }

        val sys = edit("系统提示词（可留空，用默认的只读助手）",
            AiConfig.systemPrompt(context),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        val shot = CheckBox(context).apply {
            text = "模型支持识图：提问时附带虚拟屏截图。不勾选则通过文字识别读屏；截图会增加流量和费用。"
            isChecked = AiConfig.sendScreenshot(context)
            textSize = 13f
            setTextColor(textColor)
            buttonTintList = ColorStateList.valueOf(primaryColor)
            minHeight = px(52)
            setPadding(0, px(6), 0, px(6))
        }

        val numType = InputType.TYPE_CLASS_NUMBER
        val maxActions = edit("0 = 不限制", AiConfig.maxActions(context).toString(), numType)
        val maxRounds = edit("0 = 不限制", AiConfig.maxRounds(context).toString(), numType)
        val ctxWindow = edit("0 = 不自动压缩", AiConfig.ctxWindow(context).toString(), numType)
        val compressPct = edit("10～95", AiConfig.compressPct(context).toString(), numType)
        val notify = CheckBox(context).apply {
            text = "AI 任务结束或中断时发通知"
            isChecked = AiConfig.notifyDone(context)
            textSize = 13f
            setTextColor(textColor)
            buttonTintList = ColorStateList.valueOf(primaryColor)
            minHeight = px(48)
        }
        val note = TextView(context).apply {
            text = "只作用于虚拟屏里的内容，不读取也不操作手机本体屏幕。是否允许 AI 点击/滑动由设置页的另一个开关决定（默认只读）。密钥加密保存在本机；提问时，识别出的文字和可选截图会发送到你填写的服务商。接口地址需为 HTTPS。"
            textSize = 11f
            setTextColor(secondaryColor)
            background = rounded(tonalColor, 0, 12)
            setPadding(px(12), px(12), px(12), px(12))
        }

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        fun putSection(card: LinearLayout) {
            box.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = px(12) })
        }

        val connection = section("服务连接")
        addField(connection, "接口格式", spinner, 10)
        addField(connection, "接口地址（Base URL）", base)
        add(connection, actual, 3)
        add(connection, hint, 3)
        addField(connection, "API 密钥", key)
        addField(connection, "模型", modelRow)
        putSection(connection)

        val behavior = section("助手行为")
        addField(behavior, "系统提示词", sys, 10)
        add(behavior, shot, 8)
        putSection(behavior)

        val limits = section("操作限制")
        addField(limits, "单次提问最多执行多少次点击、长按、滑动或等待（0 = 不限制）", maxActions, 10)
        addField(limits, "单次提问最多连续调用工具的轮数（0 = 不限制）", maxRounds)
        putSection(limits)

        val contextSection = section("上下文管理")
        addField(contextSection, "模型上下文窗口（token，按所用模型填写）", ctxWindow, 10)
        addField(contextSection, "用到窗口的百分之几时自动压缩（10～95）", compressPct)
        putSection(contextSection)

        val notifications = section("通知与说明")
        add(notifications, notify, 8)
        add(notifications, note, 8)
        putSection(notifications)

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(px(14), px(8), px(14), px(8))
            setBackgroundColor(bgColor)
            addView(box, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        val titleView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22), px(18), px(22), px(8))
            addView(TextView(context).apply {
                text = "配置 AI 助手"
                textSize = 22f
                setTextColor(textColor)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = "连接模型服务，并设置助手的操作范围与上下文策略。"
                textSize = 12f
                setTextColor(secondaryColor)
                setPadding(0, px(4), 0, 0)
            })
        }

        val dialog = AlertDialog.Builder(context)
            .setCustomTitle(titleView)
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
            .create()

        dialog.setOnShowListener {
            listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
                .forEach { which ->
                    dialog.getButton(which)?.apply {
                        setTextColor(primaryColor)
                        minHeight = px(48)
                        isAllCaps = false
                    }
                }
            dialog.window?.apply {
                setBackgroundDrawable(rounded(cardColor, outlineColor, 20))
                setLayout(context.resources.displayMetrics.widthPixels - px(32), ViewGroup.LayoutParams.WRAP_CONTENT)
                setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }
        dialog.show()
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
            minHeight = px(48)
            setBackgroundResource(R.drawable.bg_btn_tonal)
            setTextColor(context.getColor(R.color.vc_on_tonal))
        }
        head.addView(title)
        head.addView(refresh)

        val search = EditText(context).apply {
            hint = "搜索模型"
            setSingleLine(true)
            textSize = 14f
            setTextColor(context.getColor(R.color.vc_text))
            setHintTextColor(context.getColor(R.color.vc_text2))
            background = GradientDrawable().apply {
                cornerRadius = px(10).toFloat()
                setColor(context.getColor(R.color.vc_card))
                setStroke(px(1), context.getColor(R.color.vc_outline))
            }
            setPadding(px(12), px(10), px(12), px(10))
            minimumHeight = px(52)
        }
        val count = TextView(context).apply {
            textSize = 12f
            setTextColor(context.getColor(R.color.vc_text2))
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
        dlg.window?.setBackgroundDrawable(GradientDrawable().apply {
            cornerRadius = px(20).toFloat()
            setColor(context.getColor(R.color.vc_card))
            setStroke(px(1), context.getColor(R.color.vc_outline))
        })
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(context.getColor(R.color.vc_primary))
        load()
    }
}
