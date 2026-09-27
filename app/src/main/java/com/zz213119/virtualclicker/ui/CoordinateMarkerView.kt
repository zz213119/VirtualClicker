package com.zz213119.virtualclicker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * A small numbered coordinate marker used by the point picker.
 *
 * A normal tap creates a marker. Once created, holding the marker briefly
 * enters drag mode so the point can be adjusted without reopening the picker.
 */
class CoordinateMarkerView(
    context: Context,
    val number: Int,
    private val onMoved: (Float, Float) -> Unit
) : View(context) {

    var coordinateX: Float = 0f
        private set
    var coordinateY: Float = 0f
        private set

    private val density = resources.displayMetrics.density
    val markerSizePx: Int = (44f * density).toInt().coerceAtLeast(32)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xEEF5F5F5.toInt()
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xFF111111.toInt()
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF111111.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 19f * density
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var downViewX = 0f
    private var downViewY = 0f
    private var dragging = false
    private var longPressTriggered = false
    private var longPressRunnable: Runnable? = null

    init {
        isClickable = true
        isFocusable = true
        elevation = 50f * density
        contentDescription = "坐标点$number，长按可移动"
        layoutParams = android.widget.FrameLayout.LayoutParams(
            markerSizePx,
            markerSizePx
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = markerSizePx / 2f - strokePaint.strokeWidth
        canvas.drawCircle(markerSizePx / 2f, markerSizePx / 2f, radius, fillPaint)
        canvas.drawCircle(markerSizePx / 2f, markerSizePx / 2f, radius, strokePaint)

        val fontMetrics = textPaint.fontMetrics
        val baseline = markerSizePx / 2f - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText(number.toString(), markerSizePx / 2f, baseline, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val parent = parent as? View ?: return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downViewX = x
                downViewY = y
                dragging = false
                longPressTriggered = false

                val runnable = Runnable {
                    longPressTriggered = true
                    dragging = true
                    parent.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                }
                longPressRunnable = runnable
                postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY

                if (!longPressTriggered &&
                    (abs(dx) > touchSlop || abs(dy) > touchSlop)
                ) {
                    longPressRunnable?.let(::removeCallbacks)
                    longPressRunnable = null
                    return true
                }

                if (dragging) {
                    val minX = -width / 2f
                    val minY = -height / 2f
                    val maxX = parent.width - width / 2f
                    val maxY = parent.height - height / 2f

                    x = (downViewX + dx).coerceIn(minX, maxX)
                    y = (downViewY + dy).coerceIn(minY, maxY)

                    val centerX = (x + width / 2f).coerceIn(0f, parent.width.toFloat())
                    val centerY = (y + height / 2f).coerceIn(0f, parent.height.toFloat())
                    coordinateX = centerX / parent.width.coerceAtLeast(1) * parent.width
                    coordinateY = centerY / parent.height.coerceAtLeast(1) * parent.height
                    onMoved(centerX, centerY)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPressRunnable?.let(::removeCallbacks)
                longPressRunnable = null
                if (dragging) {
                    dragging = false
                }
                performClick()
                return true
            }
        }

        return true
    }

    fun setCoordinate(x: Float, y: Float) {
        coordinateX = x
        coordinateY = y
        val parent = parent as? View ?: return
        if (parent.width <= 0 || parent.height <= 0) return
        post {
            x = (coordinateX / parent.width * parent.width - width / 2f)
                .coerceIn(-width / 2f, parent.width - width / 2f)
            y = (coordinateY / parent.height * parent.height - height / 2f)
                .coerceIn(-height / 2f, parent.height - height / 2f)
        }
    }

    fun updateCoordinateFromPosition(parentWidth: Int, parentHeight: Int) {
        if (parentWidth <= 0 || parentHeight <= 0) return
        coordinateX = ((x + width / 2f) / parentWidth * parentWidth)
            .coerceIn(0f, parentWidth.toFloat())
        coordinateY = ((y + height / 2f) / parentHeight * parentHeight)
            .coerceIn(0f, parentHeight.toFloat())
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
