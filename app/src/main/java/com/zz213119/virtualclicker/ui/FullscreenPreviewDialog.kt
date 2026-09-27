package com.zz213119.virtualclicker.ui

import android.app.Dialog
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import com.zz213119.virtualclicker.core.VirtualDisplayManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/**
 * Immersive, letterboxed preview used for manual control. The virtual display
 * keeps its native aspect ratio while the surrounding area stays black, like a
 * desktop game preview rather than a stretched phone-sized SurfaceView.
 */
class FullscreenPreviewDialog(
    private val activity: ComponentActivity,
    private val displayId: Int,
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val onClosed: () -> Unit,
    private val onPointPicked: ((Float, Float) -> Unit)? = null,
    private val onPointMoved: ((Int, Float, Float) -> Unit)? = null,
    private val onSwipeRecorded: ((Float, Float, Float, Float, Long) -> Unit)? = null
) : Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L
    private var pointPickMode = onPointPicked != null
    private lateinit var coordinateHint: TextView
    private val coordinateMarkers = mutableListOf<CoordinateMarkerView>()
    private var nextMarkerNumber = 1
    private lateinit var previewContainer: AspectRatioFrameLayout

    private val touchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop }
    private var pickDownX = 0f
    private var pickDownY = 0f
    private var pickMoved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        // The preview window itself requests landscape when the Virtual Display
        // is landscape. This rotates only this fullscreen window; MainActivity
        // remains in its original portrait orientation after the window closes.
        window?.attributes = window?.attributes?.apply {
            screenOrientation = if (displayWidth >= displayHeight) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
        previewContainer = AspectRatioFrameLayout(
            context,
            displayWidth.toFloat() / displayHeight.toFloat()
        )
        val surface = SurfaceView(context)
        // Match the virtual display buffer exactly. Without this, some OEM
        // SurfaceView implementations keep the fullscreen window buffer size
        // and the VirtualDisplay output can be cropped or scaled incorrectly.
        surface.holder.setFixedSize(displayWidth, displayHeight)
        previewContainer.addView(surface, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(previewContainer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ))

        coordinateHint = TextView(context).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            text = if (pointPickMode) "取点模式：点击记录点击；拖动记录滑动" else "手动控制模式"
        }
        root.addView(
            coordinateHint,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                44.dp,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = 12.dp }
        )

        val modeButton = TextView(context).apply {
            text = if (pointPickMode) "结束取点" else "取点坐标"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setPadding(16.dp, 0, 16.dp, 0)
            contentDescription = "切换取点坐标模式"
            setOnClickListener {
                if (onPointPicked != null && pointPickMode) {
                    // In script-editor coordinate-pick mode, finishing the
                    // picker returns directly to the editor. Reopening
                    // "取点坐标" starts a fresh pick for the next action.
                    dismiss()
                } else {
                    pointPickMode = !pointPickMode
                    text = if (pointPickMode) "结束取点" else "取点坐标"
                    coordinateHint.text = if (pointPickMode) {
                        "取点模式：点击记录点击；拖动记录滑动"
                    } else {
                        "手动控制模式：点击/滑动会发送到目标应用"
                    }
                }
            }
        }
        root.addView(
            modeButton,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                44.dp,
                Gravity.TOP or Gravity.START
            ).apply {
                topMargin = 12.dp
                leftMargin = 16.dp
            }
        )

        val close = TextView(context).apply {
            text = "✕"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "关闭全屏预览"
            setOnClickListener { dismiss() }
        }
        root.addView(close, FrameLayout.LayoutParams(64.dp, 64.dp, Gravity.TOP or Gravity.END).apply {
            topMargin = 12.dp
            marginEnd = 16.dp
        })
        setContentView(root)
        previewContainer.post { repositionMarkers() }

        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                // Re-assert the exact virtual-display buffer size after SurfaceView
                // recreation. This prevents half-frame/cropped output on Android 16
                // OEM implementations.
                holder.setFixedSize(displayWidth, displayHeight)
                activity.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        VirtualDisplayManager.setDisplaySurface(displayId, holder.surface)
                    }
                }
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                activity.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        VirtualDisplayManager.setDisplaySurface(displayId, null)
                    }
                }
            }
        })

        @Suppress("ClickableViewAccessibility")
        surface.setOnTouchListener { view, event ->
            val scaleX = displayWidth.toFloat() / view.width.coerceAtLeast(1)
            val scaleY = displayHeight.toFloat() / view.height.coerceAtLeast(1)

            if (pointPickMode) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        pickDownX = (event.x * scaleX).coerceIn(0f, displayWidth - 1f)
                        pickDownY = (event.y * scaleY).coerceIn(0f, displayHeight - 1f)
                        pickMoved = false
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val x = event.x * scaleX
                        val y = event.y * scaleY
                        if (hypot(x - pickDownX, y - pickDownY) > touchSlop) {
                            pickMoved = true
                        }
                    }

                    MotionEvent.ACTION_UP -> {
                        val x = (event.x * scaleX).coerceIn(0f, displayWidth - 1f)
                        val y = (event.y * scaleY).coerceIn(0f, displayHeight - 1f)

                        if (pickMoved) {
                            val durationMs =
                                (event.eventTime - event.downTime).coerceAtLeast(1L)
                            coordinateHint.text =
                                "滑动：(" + pickDownX.toInt() + ", " + pickDownY.toInt() +
                                    ") → (" + x.toInt() + ", " + y.toInt() +
                                    ") · " + durationMs + "ms"
                            onSwipeRecorded?.invoke(
                                pickDownX,
                                pickDownY,
                                x,
                                y,
                                durationMs
                            )
                        } else {
                            coordinateHint.text =
                                "坐标：X=" + x.toInt() + "  Y=" + y.toInt() + " · 长按球球可移动"
                            addCoordinateMarker(x, y)
                            onPointPicked?.invoke(x, y)
                        }

                        pickMoved = false
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        pickMoved = false
                    }
                }
                return@setOnTouchListener true
            }

            // Forward the complete DOWN -> MOVE -> UP/CANCEL stream instead of
            // synthesizing one shell `input swipe` only after the finger lifts.
            // This makes dragging responsive and lets games receive real motion.
            val transformed = MotionEvent.obtain(event)
            transformed.transform(Matrix().apply { setScale(scaleX, scaleY) })
            val ok = VirtualDisplayManager.injectMotionEvent(transformed, displayId)
            transformed.recycle()

            if (!ok && event.actionMasked != MotionEvent.ACTION_MOVE) {
                coordinateHint.text = "输入注入失败，请查看日志"
            }
            true
        }
    }

    private fun addCoordinateMarker(x: Float, y: Float) {
        val number = nextMarkerNumber++
        val marker = CoordinateMarkerView(
            context,
            number,
            onMoved = { px, py ->
                val parentWidth = previewContainer.width.coerceAtLeast(1)
                val parentHeight = previewContainer.height.coerceAtLeast(1)
                val coordinateX = (px / parentWidth * displayWidth).coerceIn(0f, displayWidth - 1f)
                val coordinateY = (py / parentHeight * displayHeight).coerceIn(0f, displayHeight - 1f)
                coordinateHint.text =
                    "已调整：X=" + coordinateX.toInt() + "  Y=" + coordinateY.toInt() + " · 长按拖动中"
                onPointMoved?.invoke(number - 1, coordinateX, coordinateY)
            },
            onTapped = { px, py ->
                // A tap on an existing marker is still a valid picker tap.
                // Create another marker at exactly the same position.
                val parentWidth = previewContainer.width.coerceAtLeast(1)
                val parentHeight = previewContainer.height.coerceAtLeast(1)
                val coordinateX = (px / parentWidth * displayWidth).coerceIn(0f, displayWidth - 1f)
                val coordinateY = (py / parentHeight * displayHeight).coerceIn(0f, displayHeight - 1f)
                addCoordinateMarker(coordinateX, coordinateY)
                onPointPicked?.invoke(coordinateX, coordinateY)
            }
        )
        coordinateMarkers += marker
        previewContainer.addView(marker)
        marker.post {
            marker.setNormalizedPosition(
                x / displayWidth.coerceAtLeast(1),
                y / displayHeight.coerceAtLeast(1)
            )
        }
    }

    private fun repositionMarkers() {
        coordinateMarkers.forEach { marker ->
            marker.setNormalizedPosition(marker.normalizedX, marker.normalizedY)
        }
    }
    override fun show() {
        if (::previewContainer.isInitialized) {
            coordinateMarkers.forEach { previewContainer.removeView(it) }
        }
        coordinateMarkers.clear()
        nextMarkerNumber = 1
        super.show()
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
        }
    }

    override fun dismiss() {
        super.dismiss()
        onClosed()
    }

    private class AspectRatioFrameLayout(
        context: android.content.Context,
        private val aspectRatio: Float
    ) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
            val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
            var width = availableWidth
            var height = (width / aspectRatio).toInt()
            if (height > availableHeight) {
                height = availableHeight
                width = (height * aspectRatio).toInt()
            }
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
        }
    }

    private val Int.dp: Int
        get() = (this * context.resources.displayMetrics.density).toInt()
}
