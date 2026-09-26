package com.zz213119.virtualclicker.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.max

/**
 * Measures itself to the requested aspect ratio while using the largest size
 * that fits the available bounds. This keeps the virtual display preview from
 * becoming a tall portrait card when the target display is landscape.
 */
class AspectRatioFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var aspectRatio = 9f / 16f

    fun setAspectRatio(width: Int, height: Int) {
        require(width > 0 && height > 0)
        aspectRatio = width.toFloat() / height.toFloat()
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)

        if (availableWidth == 0 || availableHeight == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val width = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> availableWidth
            else -> availableWidth
        }

        val heightForRatio = (width / max(aspectRatio, 0.01f)).toInt()
        val finalHeight = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> availableHeight
            MeasureSpec.AT_MOST -> minOf(heightForRatio, availableHeight)
            else -> heightForRatio
        }

        val finalWidth = (finalHeight * aspectRatio).toInt().coerceAtMost(width)

        super.onMeasure(
            MeasureSpec.makeMeasureSpec(finalWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(finalHeight, MeasureSpec.EXACTLY)
        )
    }
}
