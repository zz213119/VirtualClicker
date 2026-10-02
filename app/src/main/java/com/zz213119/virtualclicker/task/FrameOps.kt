package com.zz213119.virtualclicker.task

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.abs

/** 轻量画面工具：归一化区域 -> 亮度网格，比较"像不像"；以及简单的取色判断。 */
object FrameOps {

    fun decode(bytes: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    /** 归一化区域 (l,t,r,b) 的盒式平均亮度网格 gw*gh（0..255）。 */
    fun boxGrid(
        bmp: Bitmap,
        l: Float = 0f, t: Float = 0f, r: Float = 1f, b: Float = 1f,
        gw: Int = 32, gh: Int = 16
    ): FloatArray {
        val x0 = (l * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val y0 = (t * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        val x1 = (r * bmp.width).toInt().coerceIn(x0 + 1, bmp.width)
        val y1 = (b * bmp.height).toInt().coerceIn(y0 + 1, bmp.height)
        val w = x1 - x0
        val h = y1 - y0
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, x0, y0, w, h)
        val out = FloatArray(gw * gh)
        for (gy in 0 until gh) {
            val ya = gy * h / gh
            val yb = maxOf(ya + 1, (gy + 1) * h / gh).coerceAtMost(h)
            for (gx in 0 until gw) {
                val xa = gx * w / gw
                val xb = maxOf(xa + 1, (gx + 1) * w / gw).coerceAtMost(w)
                var sum = 0f
                var n = 0
                for (y in ya until yb) {
                    val row = y * w
                    for (x in xa until xb) {
                        val c = px[row + x]
                        sum += 0.299f * ((c shr 16) and 0xFF) +
                            0.587f * ((c shr 8) and 0xFF) +
                            0.114f * (c and 0xFF)
                        n++
                    }
                }
                out[gy * gw + gx] = if (n > 0) sum / n else 0f
            }
        }
        return out
    }

    /** 兼容旧调用。 */
    fun lumaGrid(
        bmp: Bitmap,
        l: Float = 0f, t: Float = 0f, r: Float = 1f, b: Float = 1f,
        gw: Int = 48, gh: Int = 48
    ): FloatArray = boxGrid(bmp, l, t, r, b, gw, gh)

    /** 两个亮度网格的平均绝对差，0 = 完全一样。 */
    fun diff(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0f
        var sum = 0f
        for (i in 0 until n) sum += abs(a[i] - b[i])
        return sum / n
    }

    /** 区域内"紫色高亮"像素占比（关卡列表里被选中的条目是紫色）。 */
    fun purpleFrac(bmp: Bitmap, l: Float, t: Float, r: Float, b: Float): Float {
        val x0 = (l * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val y0 = (t * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        val x1 = (r * bmp.width).toInt().coerceIn(x0 + 1, bmp.width)
        val y1 = (b * bmp.height).toInt().coerceIn(y0 + 1, bmp.height)
        val w = x1 - x0
        val h = y1 - y0
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, x0, y0, w, h)
        var hit = 0
        for (c in px) {
            val rr = (c shr 16) and 0xFF
            val gg = (c shr 8) and 0xFF
            val bb = c and 0xFF
            if (bb - gg > 45 && rr - gg > 20 && bb > 120) hit++
        }
        return hit.toFloat() / px.size
    }

    /** 以 (nx,ny) 为中心 5x5 的平均颜色是否为金黄色（点亮的星星）。 */
    fun isGoldAt(bmp: Bitmap, nx: Float, ny: Float): Boolean {
        val cx = (nx * bmp.width).toInt().coerceIn(2, bmp.width - 3)
        val cy = (ny * bmp.height).toInt().coerceIn(2, bmp.height - 3)
        var sr = 0; var sg = 0; var sb = 0; var n = 0
        for (y in cy - 2..cy + 2) for (x in cx - 2..cx + 2) {
            val c = bmp.getPixel(x, y)
            sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF; n++
        }
        val r = sr / n; val g = sg / n; val b = sb / n
        return r > 200 && g > 150 && b < 140 && (r - b) > 80
    }
}
