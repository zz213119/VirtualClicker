package com.zz213119.virtualclicker.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
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
    private val onPointPicked: ((Float, Float) -> Unit)? = null
) : Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L
    private var pointPickMode = onPointPicked != null
    private lateinit var coordinateHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

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
        val preview = AspectRatioFrameLayout(
            context,
            displayWidth.toFloat() / displayHeight.toFloat()
        )
        val surface = SurfaceView(context)
        // Match the virtual display buffer exactly. Without this, some OEM
        // SurfaceView implementations keep the fullscreen window buffer size
        // and the VirtualDisplay output can be cropped or scaled incorrectly.
        surface.holder.setFixedSize(displayWidth, displayHeight)
        preview.addView(surface, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(preview, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ))

        coordinateHint = TextView(context).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            text = if (pointPickMode) "取点模式：点击画面查看 X / Y" else "手动控制模式"
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
                pointPickMode = !pointPickMode
                text = if (pointPickMode) "结束取点" else "取点坐标"
                coordinateHint.text = if (pointPickMode) {
                    "取点模式：点击画面查看 X / Y"
                } else {
                    "手动控制模式：点击/滑动会发送到目标应用"
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

        surface.setOnTouchListener { view, event ->
            val scaleX = displayWidth.toFloat() / view.width.coerceAtLeast(1)
            val scaleY = displayHeight.toFloat() / view.height.coerceAtLeast(1)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.x
                    touchDownY = event.y
                    touchDownTime = System.currentTimeMillis()

                    if (pointPickMode) {
                        val x = (event.x * scaleX).coerceIn(0f, displayWidth - 1f)
                        val y = (event.y * scaleY).coerceIn(0f, displayHeight - 1f)
                        coordinateHint.text = "坐标：X=" + x.toInt() + "  Y=" + y.toInt()
                        onPointPicked?.invoke(x, y)
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (!pointPickMode) {
                        val distance = hypot(event.x - touchDownX, event.y - touchDownY)
                        val duration = (System.currentTimeMillis() - touchDownTime).coerceIn(1, 30_000)
                        activity.lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                if (distance < 24f) {
                                    VirtualDisplayManager.tap(
                                        displayId, touchDownX * scaleX, touchDownY * scaleY
                                    )
                                } else {
                                    VirtualDisplayManager.swipe(
                                        displayId,
                                        touchDownX * scaleX,
                                        touchDownY * scaleY,
                                        event.x * scaleX,
                                        event.y * scaleY,
                                        duration.toInt()
                                    )
                                }
                            }
                        }
                    }
                }
            }
            true
        }
    }

    override fun show() {
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
