package com.zz213119.virtualclicker.task

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.abs

/** 纯 Kotlin 的轻量画面比较工具：把区域缩成亮度网格，用平均绝对差判断"变没变"。 */
object FrameOps {

    fun decode(bytes: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    /** 归一化区域 (l,t,r,b) -> gw*gh 的亮度网格(0..255)。 */
    fun lumaGrid(
        bmp: Bitmap,
        l: Float = 0f, t: Float = 0f, r: Float = 1f, b: Float = 1f,
        gw: Int = 48, gh: Int = 48
    ): FloatArray {
        val x0 = (l * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val y0 = (t * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        val x1 = (r * bmp.width).toInt().coerceIn(x0 + 1, bmp.width)
        val y1 = (b * bmp.height).toInt().coerceIn(y0 + 1, bmp.height)
        val crop = Bitmap.createBitmap(bmp, x0, y0, x1 - x0, y1 - y0)
        val small = Bitmap.createScaledBitmap(crop, gw, gh, true)
        val px = IntArray(gw * gh)
        small.getPixels(px, 0, gw, 0, 0, gw, gh)
        return FloatArray(px.size) { i ->
            val c = px[i]
            0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
        }
    }

    /** 两个亮度网格的平均绝对差，0 = 完全一样。 */
    fun diff(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0f
        var sum = 0f
        for (i in 0 until n) sum += abs(a[i] - b[i])
        return sum / n
    }
}
