package com.zz213119.virtualclicker.ai

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
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
        val key = edit(if (AiConfig.hasApiKey(context)) "已保存（留空则保持不变）" else "API 密钥", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val model = edit("模型名，按服务商文档填写", AiConfig.model(context))
        val sys = edit("系统提示词（可留空，用默认的只读助手）", AiConfig.systemPrompt(context), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            minLines = 2
            maxLines = 5
        }
        val shot = CheckBox(context).apply {
            text = "每次提问时附带虚拟屏截图（需要模型支持识图，会多耗流量/费用）"
            isChecked = AiConfig.sendScreenshot(context)
            textSize = 13f
        }
        val note = TextView(context).apply {
            text = "说明：只读取虚拟屏里的内容，不读手机本体屏幕，也不会点击或操作；密钥加密保存在本机；" +
                "提问时，识别出的文字（和可选的截图）会发送到你填写的服务商。接口地址需为 https。"
            textSize = 11f
            setPadding(0, px(10), 0, 0)
        }

        box.addView(label("接口格式")); box.addView(spinner)
        box.addView(label("接口地址（Base URL）")); box.addView(base)
        box.addView(label("API 密钥")); box.addView(key)
        box.addView(label("模型")); box.addView(model)
        box.addView(label("系统提示词")); box.addView(sys)
        box.addView(shot)
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
                    base.text.toString(), model.text.toString(), sys.text.toString(), shot.isChecked
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
}
