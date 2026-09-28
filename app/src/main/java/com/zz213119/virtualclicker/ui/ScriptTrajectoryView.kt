package com.zz213119.virtualclicker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.zz213119.virtualclicker.script.ScriptAction
import com.zz213119.virtualclicker.script.ScriptActionType
import com.zz213119.virtualclicker.service.ScriptRunState
import kotlin.math.max

/**
 * Live, non-interactive trajectory overlay for the main Virtual Display preview.
 */
class ScriptTrajectoryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FF3B30.toInt()
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val anchorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFF3B30.toInt()
        style = Paint.Style.FILL
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD60A.toInt()
        style = Paint.Style.FILL
    }
    private val activeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 28f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3000000.toInt()
        style = Paint.Style.FILL
    }

    private var displayWidth = 1
    private var displayHeight = 1
    private var state = ScriptRunState()

    fun setDisplaySize(width: Int, height: Int) {
        displayWidth = width.coerceAtLeast(1)
        displayHeight = height.coerceAtLeast(1)
        invalidate()
    }

    fun updateState(newState: ScriptRunState) {
        state = newState
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!state.running || state.actions.isEmpty()) return

        drawRoute(canvas)
        drawActivePosition(canvas)
        drawBadge(canvas)
        postInvalidateOnAnimation()
    }

    private fun drawRoute(canvas: Canvas) {
        var lastX: Float? = null
        var lastY: Float? = null

        state.actions.forEachIndexed { index, action ->
            val start = anchorOf(action, true) ?: return@forEachIndexed
            val end = anchorOf(action, false) ?: start
            val startPx = toViewX(start.first)
            val startPy = toViewY(start.second)
            val endPx = toViewX(end.first)
            val endPy = toViewY(end.second)

            if (lastX != null && lastY != null) {
                canvas.drawLine(lastX!!, lastY!!, startPx, startPy, routePaint)
            }

            when (action.type) {
                ScriptActionType.SWIPE -> {
                    canvas.drawLine(startPx, startPy, endPx, endPy, routePaint)
                    drawArrow(canvas, startPx, startPy, endPx, endPy)
                }
                ScriptActionType.CLICK,
                ScriptActionType.LONG_PRESS -> {
                    canvas.drawCircle(startPx, startPy, 13f, anchorPaint)
                }
                ScriptActionType.WAIT -> Unit
            }

            if (action.type != ScriptActionType.WAIT) {
                drawNumber(canvas, index + 1, startPx, startPy)
                lastX = endPx
                lastY = endPy
            }
        }
    }

    private fun drawActivePosition(canvas: Canvas) {
        val action = state.actions.getOrNull(state.actionIndex) ?: return
        val start = anchorOf(action, true) ?: return
        val end = anchorOf(action, false) ?: start

        var x = start.first
        var y = start.second
        if (action.type == ScriptActionType.SWIPE) {
            val duration = state.actionDurationMs.coerceAtLeast(1L)
            val elapsed = (SystemClock.uptimeMillis() - state.actionStartedAtUptime).coerceAtLeast(0L)
            val progress = (elapsed.toDouble() / duration.toDouble()).coerceIn(0.0, 1.0)
            x = start.first + (end.first - start.first) * progress.toFloat()
            y = start.second + (end.second - start.second) * progress.toFloat()
        }

        val px = toViewX(x)
        val py = toViewY(y)
        canvas.drawCircle(px, py, 22f, activePaint)
        canvas.drawCircle(px, py, 22f, activeStroke)
    }

    private fun drawBadge(canvas: Canvas) {
        val roundText = if (state.totalRounds == 0) {
            "第 ${state.round} 轮 / 无限"
        } else {
            "第 ${state.round} / ${state.totalRounds} 轮"
        }
        val actionText = state.actionIndex.takeIf { it >= 0 }?.let { index ->
            val action = state.actions.getOrNull(index)
            val total = action?.repeatCount ?: state.actionRepeatCount
            val repeatText = if (total == 0) {
                "第 ${state.actionRepeatIndex} 次 / 无限"
            } else {
                "第 ${state.actionRepeatIndex} / ${max(total, 1)} 次"
            }
            "动作 ${index + 1} / ${state.actions.size} · $repeatText"
        } ?: "准备执行"

        val title = "脚本：${state.scriptName} · $roundText"
        val boxWidth = minOf(
            max(widthForText(title), widthForText(actionText)) + 28f,
            (width - 16f).coerceAtLeast(80f)
        )
        val rect = RectF(8f, 8f, boxWidth, 80f)
        canvas.drawRoundRect(rect, 16f, 16f, badgePaint)
        canvas.drawText(title, 20f, 36f, textPaint)
        canvas.drawText(actionText, 20f, 64f, textPaint)
    }

    private fun widthForText(text: String): Float = textPaint.measureText(text)

    private fun drawNumber(canvas: Canvas, number: Int, x: Float, y: Float) {
        val label = number.toString()
        val radius = if (label.length >= 3) 24f else 20f
        canvas.drawCircle(x, y, radius, activeStroke)
        val baseline = y - (textPaint.ascent() + textPaint.descent()) / 2f
        val textWidth = textPaint.measureText(label)
        canvas.drawText(label, x - textWidth / 2f, baseline, textPaint)
    }

    private fun drawArrow(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        val angle = kotlin.math.atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val length = 18f
        val left = angle + Math.PI * 0.8
        val right = angle - Math.PI * 0.8
        val path = Path().apply {
            moveTo(x2, y2)
            lineTo(
                x2 + (length * kotlin.math.cos(left)).toFloat(),
                y2 + (length * kotlin.math.sin(left)).toFloat()
            )
            moveTo(x2, y2)
            lineTo(
                x2 + (length * kotlin.math.cos(right)).toFloat(),
                y2 + (length * kotlin.math.sin(right)).toFloat()
            )
        }
        canvas.drawPath(path, activeStroke)
    }

    private fun anchorOf(action: ScriptAction, start: Boolean): Pair<Float, Float>? =
        when (action.type) {
            ScriptActionType.CLICK,
            ScriptActionType.LONG_PRESS ->
                action.x to action.y
            ScriptActionType.SWIPE ->
                if (start) action.x to action.y else action.x2 to action.y2
            ScriptActionType.WAIT -> null
        }

    private fun toViewX(x: Float): Float = x / displayWidth.toFloat() * width
    private fun toViewY(y: Float): Float = y / displayHeight.toFloat() * height

    override fun onTouchEvent(event: MotionEvent): Boolean = false
}
