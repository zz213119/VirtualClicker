package com.zz213119.virtualclicker.ui

import android.app.Dialog
import android.content.pm.ActivityInfo
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
    private val onClosed: () -> Unit
) : Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L

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
        preview.addView(surface, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(preview, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ))

        val close = TextView(context).apply {
            text = "✕"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "关闭全屏手动控制"
            setOnClickListener { dismiss() }
        }
        root.addView(close, FrameLayout.LayoutParams(64.dp, 64.dp, Gravity.TOP or Gravity.END).apply {
            topMargin = 12.dp
            marginEnd = 16.dp
        })
        setContentView(root)

        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                activity.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        VirtualDisplayManager.setDisplaySurface(displayId, holder.surface)
                    }
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        })

        surface.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.x
                    touchDownY = event.y
                    touchDownTime = System.currentTimeMillis()
                }

                MotionEvent.ACTION_UP -> {
                    val scaleX = displayWidth.toFloat() / view.width.coerceAtLeast(1)
                    val scaleY = displayHeight.toFloat() / view.height.coerceAtLeast(1)
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
            true
        }
    }

    override fun show() {
        activity.requestedOrientation = if (displayWidth > displayHeight) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
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
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
