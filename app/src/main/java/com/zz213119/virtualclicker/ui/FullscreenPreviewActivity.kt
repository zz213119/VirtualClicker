package com.zz213119.virtualclicker.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import com.zz213119.virtualclicker.core.VirtualDisplayManager

class FullscreenPreviewActivity : Activity() {

    companion object {
        const val EXTRA_DISPLAY_ID = "fullscreen_display_id"
        const val EXTRA_DISPLAY_WIDTH = "fullscreen_display_width"
        const val EXTRA_DISPLAY_HEIGHT = "fullscreen_display_height"
        const val EXTRA_POINT_PICK_MODE = "fullscreen_point_pick_mode"
    }

    private var displayId = -1
    private var displayWidth = 1080
    private var displayHeight = 1920
    private var pointPickMode = false
    private lateinit var surface: TextureView
    private var previewSurface: Surface? = null
    private lateinit var hint: TextView
    private lateinit var modeButton: TextView
    private lateinit var closeButton: TextView
    private var pressedControl = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
        displayWidth = intent.getIntExtra(EXTRA_DISPLAY_WIDTH, 1080).coerceAtLeast(1)
        displayHeight = intent.getIntExtra(EXTRA_DISPLAY_HEIGHT, 1920).coerceAtLeast(1)
        pointPickMode = intent.getBooleanExtra(EXTRA_POINT_PICK_MODE, false)

        requestedOrientation = if (displayWidth >= displayHeight) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        val preview = AspectRatioFrameLayout(
            this,
            displayWidth.toFloat() / displayHeight.toFloat()
        )
        surface = TextureView(this)
        preview.addView(
            surface,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            preview,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )

        hint = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            text = if (pointPickMode) "取点模式：点击画面自动添加动作" else "手动控制模式：直接拖动"
            setPadding(12, 0, 12, 0)
        }
        root.addView(
            hint,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 48,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = 12 }
        )

        modeButton = TextView(this).apply {
            text = if (pointPickMode) "结束取点" else "取点坐标"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setPadding(18, 0, 18, 0)
        }
        modeButton.setOnClickListener {
            if (pointPickMode) {
                CoordinatePickBus.listener = null
                finish()
            } else {
                pointPickMode = true
                modeButton.text = "结束取点"
                hint.text = "取点模式：点击画面自动添加动作"
            }
        }
        root.addView(
            modeButton,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 48,
                Gravity.TOP or Gravity.START
            ).apply { topMargin = 12; leftMargin = 16 }
        )

        closeButton = TextView(this).apply {
            text = "✕"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setOnClickListener {
                CoordinatePickBus.listener = null
                finish()
            }
        }
        root.addView(
            closeButton,
            FrameLayout.LayoutParams(72, 72, Gravity.TOP or Gravity.END).apply {
                topMargin = 8; rightMargin = 12
            }
        )

        setContentView(root)

        surface.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surfaceTexture: android.graphics.SurfaceTexture,
                width: Int,
                height: Int
            ) {
                surfaceTexture.setDefaultBufferSize(displayWidth, displayHeight)
                previewSurface = Surface(surfaceTexture)
                attachSurface(previewSurface!!)
            }

            override fun onSurfaceTextureSizeChanged(
                surfaceTexture: android.graphics.SurfaceTexture,
                width: Int,
                height: Int
            ) = Unit

            override fun onSurfaceTextureDestroyed(surfaceTexture: android.graphics.SurfaceTexture): Boolean {
                detachSurface()
                previewSurface?.release()
                previewSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: android.graphics.SurfaceTexture) = Unit
        }

        surface.setOnTouchListener { view, event ->
            val scaleX = displayWidth.toFloat() / view.width.coerceAtLeast(1)
            val scaleY = displayHeight.toFloat() / view.height.coerceAtLeast(1)

            if (pointPickMode) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    val x = (event.x * scaleX).coerceIn(0f, displayWidth - 1f)
                    val y = (event.y * scaleY).coerceIn(0f, displayHeight - 1f)
                    hint.text = "已取点：X=" + x.toInt() + "  Y=" + y.toInt()
                    CoordinatePickBus.listener?.invoke(x, y)
                }
                return@setOnTouchListener true
            }

            val transformed = MotionEvent.obtain(event)
            transformed.transform(Matrix().apply { setScale(scaleX, scaleY) })
            VirtualDisplayManager.injectMotionEvent(transformed, displayId)
            transformed.recycle()
            true
        }
    }

    private fun controlAt(rawX: Float, rawY: Float): Int {
        if (::closeButton.isInitialized) {
            val loc = IntArray(2)
            closeButton.getLocationOnScreen(loc)
            if (rawX >= loc[0] &&
                rawX < loc[0] + closeButton.width &&
                rawY >= loc[1] &&
                rawY < loc[1] + closeButton.height
            ) {
                return 1
            }
        }

        if (::modeButton.isInitialized) {
            val loc = IntArray(2)
            modeButton.getLocationOnScreen(loc)
            if (rawX >= loc[0] &&
                rawX < loc[0] + modeButton.width &&
                rawY >= loc[1] &&
                rawY < loc[1] + modeButton.height
            ) {
                return 2
            }
        }
        return 0
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // SurfaceView uses a separate rendering surface. Intercept the two
        // control areas at Activity level so Android/OEM surface composition
        // cannot accidentally route the first tap into the preview instead
        // of the button.
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedControl = controlAt(ev.rawX, ev.rawY)
                if (pressedControl != 0) return true
            }

            MotionEvent.ACTION_UP -> {
                val target = pressedControl
                pressedControl = 0
                if (target != 0) {
                    when (target) {
                        1 -> closeButton.performClick()
                        2 -> modeButton.performClick()
                    }
                    return true
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                pressedControl = 0
            }
        }

        return super.dispatchTouchEvent(ev)
    }

    private fun attachSurface(surface: android.view.Surface) {
        if (displayId >= 0 && surface.isValid) {
            Thread { VirtualDisplayManager.setDisplaySurface(displayId, surface) }.start()
        }
    }

    private fun detachSurface() {
        if (displayId >= 0) {
            Thread { VirtualDisplayManager.setDisplaySurface(displayId, null) }.start()
        }
    }

    override fun onDestroy() {
        CoordinatePickBus.listener = null
        detachSurface()
        previewSurface?.release()
        previewSurface = null
        super.onDestroy()
    }

    private class AspectRatioFrameLayout(
        context: Context,
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
}
