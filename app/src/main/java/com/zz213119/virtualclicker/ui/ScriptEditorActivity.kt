package com.zz213119.virtualclicker.ui

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.zz213119.virtualclicker.R
import com.zz213119.virtualclicker.script.ScriptAction
import com.zz213119.virtualclicker.script.ScriptActionType
import com.zz213119.virtualclicker.script.ScriptDefinition
import com.zz213119.virtualclicker.script.ScriptJson
import com.zz213119.virtualclicker.script.ScriptRepository
import com.zz213119.virtualclicker.service.ScriptRunnerService
import com.zz213119.virtualclicker.ui.FullscreenPreviewDialog

class ScriptEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DISPLAY_ID = "extra_editor_display_id"
    }

    private data class RowViews(
        val root: LinearLayout,
        val spinner: Spinner,
        val x: EditText,
        val y: EditText,
        val x2: EditText,
        val y2: EditText,
        val duration: EditText
    )

    private lateinit var scriptName: EditText
    private lateinit var repeatCount: EditText
    private lateinit var displayInfo: TextView
    private lateinit var actionContainer: LinearLayout
    private lateinit var status: TextView

    private val rows = mutableListOf<RowViews>()
    private var displayId: Int = -1
    private var displayWidth: Int = 1080
    private var displayHeight: Int = 1920
    private var lastPickedX: Float? = null
    private var lastPickedY: Float? = null

    private val typeLabels = listOf("点击", "长按", "滑动", "等待")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_script_editor)

        scriptName = findViewById(R.id.scriptName)
        repeatCount = findViewById(R.id.scriptRepeatCount)
        displayInfo = findViewById(R.id.scriptDisplayInfo)
        actionContainer = findViewById(R.id.scriptActionContainer)
        status = findViewById(R.id.scriptStatus)

        displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
        displayWidth = intent.getIntExtra("extra_editor_display_width", 1080).coerceAtLeast(1)
        displayHeight = intent.getIntExtra("extra_editor_display_height", 1920).coerceAtLeast(1)
        displayInfo.text = if (displayId >= 0) {
            "当前目标：Virtual Display #" + displayId
        } else {
            "当前目标：未检测到 Virtual Display，请返回首页启动"
        }

        findViewById<Button>(R.id.addClickAction).setOnClickListener {
            addAction(ScriptAction(ScriptActionType.CLICK, 540f, 960f))
        }
        findViewById<Button>(R.id.addLongPressAction).setOnClickListener {
            addAction(
                ScriptAction(
                    type = ScriptActionType.LONG_PRESS,
                    x = 540f,
                    y = 960f,
                    durationMs = 1000L
                )
            )
        }
        findViewById<Button>(R.id.addSwipeAction).setOnClickListener {
            addAction(
                ScriptAction(
                    type = ScriptActionType.SWIPE,
                    x = 540f,
                    y = 1500f,
                    x2 = 540f,
                    y2 = 500f,
                    durationMs = 500L
                )
            )
        }
        findViewById<Button>(R.id.addWaitAction).setOnClickListener {
            addAction(ScriptAction(ScriptActionType.WAIT, durationMs = 1000L))
        }

        findViewById<Button>(R.id.pickScriptCoordinate).setOnClickListener {
            openCoordinatePicker()
        }

        findViewById<Button>(R.id.saveScript).setOnClickListener {
            saveCurrentScript()
        }

        findViewById<Button>(R.id.runScript).setOnClickListener {
            runCurrentScript()
        }

        findViewById<Button>(R.id.stopScript).setOnClickListener {
            stopScript()
        }

        loadScript()
    }

    private fun loadScript() {
        val script = ScriptRepository.loadLast(this)
        scriptName.setText(script.name)
        repeatCount.setText(script.repeatCount.toString())
        rows.clear()
        actionContainer.removeAllViews()
        script.actions.forEach(::addAction)
        updateStatus("已载入上次保存的脚本：" + script.actions.size + " 个动作")
    }

    private fun addAction(action: ScriptAction) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.bg_script_action)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "动作 " + (rows.size + 1)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val delete = Button(this).apply {
            text = "删除"
            setOnClickListener {
                val row = root.tag as? RowViews ?: return@setOnClickListener
                actionContainer.removeView(row.root)
                rows.remove(row)
                renumberRows()
            }
        }

        header.addView(title)
        header.addView(delete)
        root.addView(header)

        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            typeLabels
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        root.addView(spinner)

        val x = numberField("X", action.x)
        val y = numberField("Y", action.y)
        val x2 = numberField("终点 X", action.x2)
        val y2 = numberField("终点 Y", action.y2)
        val duration = numberField("时长 / 等待(ms)", action.durationMs)

        root.addView(x)
        root.addView(y)
        root.addView(x2)
        root.addView(y2)
        root.addView(duration)

        val row = RowViews(root, spinner, x, y, x2, y2, duration)
        root.tag = row
        rows.add(row)

        spinner.setSelection(typeToIndex(action.type), false)
        spinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit

                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    updateFieldsVisibility(row)
                }
            }

        actionContainer.addView(
            root,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        )

        updateFieldsVisibility(row)
    }

    private fun updateFieldsVisibility(row: RowViews) {
        val type = indexToType(row.spinner.selectedItemPosition)

        row.x.visibility = if (type == ScriptActionType.WAIT) View.GONE else View.VISIBLE
        row.y.visibility = if (type == ScriptActionType.WAIT) View.GONE else View.VISIBLE
        row.x2.visibility = if (type == ScriptActionType.SWIPE) View.VISIBLE else View.GONE
        row.y2.visibility = if (type == ScriptActionType.SWIPE) View.VISIBLE else View.GONE
        row.duration.visibility =
            if (type == ScriptActionType.CLICK) View.GONE else View.VISIBLE
    }

    private fun typeToIndex(type: ScriptActionType): Int = when (type) {
        ScriptActionType.CLICK -> 0
        ScriptActionType.LONG_PRESS -> 1
        ScriptActionType.SWIPE -> 2
        ScriptActionType.WAIT -> 3
    }

    private fun indexToType(index: Int): ScriptActionType = when (index) {
        1 -> ScriptActionType.LONG_PRESS
        2 -> ScriptActionType.SWIPE
        3 -> ScriptActionType.WAIT
        else -> ScriptActionType.CLICK
    }

    private fun collectScript(): ScriptDefinition {
        val actions = rows.map { row ->
            val type = indexToType(row.spinner.selectedItemPosition)
            ScriptAction(
                type = type,
                x = row.x.number(),
                y = row.y.number(),
                x2 = row.x2.number(),
                y2 = row.y2.number(),
                durationMs = row.duration.longNumber()
            )
        }

        return ScriptDefinition(
            name = scriptName.text.toString().trim().ifEmpty { "未命名脚本" },
            repeatCount = repeatCount.text.toString()
                .trim()
                .toIntOrNull()
                ?.coerceAtLeast(0)
                ?: 1,
            actions = actions
        )
    }

    private fun openCoordinatePicker() {
        if (displayId < 0) {
            updateStatus("无法取点：当前没有 Virtual Display")
            return
        }

        FullscreenPreviewDialog(
            activity = this,
            displayId = displayId,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            onClosed = {
                updateStatus(
                    if (lastPickedX != null && lastPickedY != null) {
                        "最近取点：X=" + lastPickedX!!.toInt() + " Y=" + lastPickedY!!.toInt()
                    } else {
                        "取点窗口已关闭"
                    }
                )
            },
            onPointPicked = { x, y ->
                lastPickedX = x
                lastPickedY = y
                runOnUiThread {
                    findViewById<TextView>(R.id.scriptCoordinateStatus).text =
                        "最近坐标：X=" + x.toInt() + "  Y=" + y.toInt() +
                            "（" + displayWidth + "×" + displayHeight + "）"
                    updateStatus("已取点：X=" + x.toInt() + " Y=" + y.toInt() + " · 目标应用不会被点击")
                }
            }
        ).show()
    }

    private fun saveCurrentScript() {
        val script = collectScript()
        ScriptRepository.saveLast(this, script)
        updateStatus("已保存：" + script.name + " · " + script.actions.size + " 个动作")
        Toast.makeText(this, "脚本已保存", Toast.LENGTH_SHORT).show()
    }

    private fun runCurrentScript() {
        if (displayId < 0) {
            updateStatus("无法运行：当前没有 Virtual Display")
            return
        }

        val script = collectScript()
        if (script.actions.isEmpty()) {
            updateStatus("无法运行：至少需要一个动作")
            return
        }

        ScriptRepository.saveLast(this, script)

        val intent = android.content.Intent(this, ScriptRunnerService::class.java)
            .setAction(ScriptRunnerService.ACTION_RUN)
            .putExtra(ScriptRunnerService.EXTRA_DISPLAY_ID, displayId)
            .putExtra(ScriptRunnerService.EXTRA_SCRIPT_JSON, ScriptJson.encode(script))

        try {
            ContextCompat.startForegroundService(this, intent)
            updateStatus(
                "脚本运行中：" + script.name + " · D#" + displayId + " · " +
                    script.actions.size + " 个动作"
            )
        } catch (t: Throwable) {
            updateStatus("脚本启动失败：" + (t.message ?: "未知错误"))
        }
    }

    private fun stopScript() {
        runCatching {
            startService(
                android.content.Intent(this, ScriptRunnerService::class.java)
                    .setAction(ScriptRunnerService.ACTION_STOP)
            )
        }
        updateStatus("脚本：已停止")
    }

    private fun updateStatus(text: String) {
        status.text = text
    }

    private fun numberField(hint: String, value: Number): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(if (value is Float && value == 0f) "" else value.toString())
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

    private fun EditText.number(): Float =
        text.toString().trim().toFloatOrNull() ?: 0f

    private fun EditText.longNumber(): Long =
        text.toString().trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L

    private fun renumberRows() {
        rows.forEachIndexed { index, row ->
            val header = row.root.getChildAt(0) as? LinearLayout
            val title = header?.getChildAt(0) as? TextView
            title?.text = "动作 " + (index + 1)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}