package com.zz213119.virtualclicker.task

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 在画面指定区域里找一个小图标（多尺度掩码匹配，纯 Kotlin，无 OpenCV）：
 * 先把图标和画面都变成"白色/红色"二值掩码，再用 IoU 打分，对大小/背景/红点变化都比较宽容。
 * 参考图都是 2576x1159 的手机截图裁出来的，所以基准比例 = 画面高度 / 1159。
 */
object IconFinder {
    enum class Mode { WHITE, RED }

    data class Match(val nx: Float, val ny: Float, val score: Float, val second: Float)

    private fun on(c: Int, mode: Mode): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return when (mode) {
            Mode.WHITE -> minOf(r, g, b) > 185
            Mode.RED -> r > 120 && r - g > 60 && r - b > 45 && g < 120
        }
    }

    /** roi = (l,t,r,b) 归一化。返回最佳位置（图标中心的归一化坐标）及分数；找不到返回 null。 */
    fun find(
        frame: Bitmap,
        tmpl: Bitmap,
        mode: Mode,
        roi: FloatArray,
        scales: FloatArray = floatArrayOf(0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 1.1f, 1.25f, 1.4f)
    ): Match? {
        val work = if (frame.width != 960)
            Bitmap.createScaledBitmap(frame, 960, (frame.height * 960f / frame.width).roundToInt(), true)
        else frame
        val unit = work.height / 1159f

        val rx0 = (roi[0] * work.width).toInt().coerceIn(0, work.width - 1)
        val ry0 = (roi[1] * work.height).toInt().coerceIn(0, work.height - 1)
        val rx1 = (roi[2] * work.width).toInt().coerceIn(rx0 + 1, work.width)
        val ry1 = (roi[3] * work.height).toInt().coerceIn(ry0 + 1, work.height)
        val rw = rx1 - rx0
        val rh = ry1 - ry0
        val px = IntArray(rw * rh)
        work.getPixels(px, 0, rw, rx0, ry0, rw, rh)
        val mask = BooleanArray(px.size) { on(px[it], mode) }

        // 积分图：窗口内掩码点数
        val iw = rw + 1
        val integral = IntArray((rw + 1) * (rh + 1))
        for (y in 0 until rh) {
            var rowSum = 0
            for (x in 0 until rw) {
                if (mask[y * rw + x]) rowSum++
                integral[(y + 1) * iw + (x + 1)] = integral[y * iw + (x + 1)] + rowSum
            }
        }

        var bestScore = 0f
        var bestX = 0
        var bestY = 0
        var bestSize = 0
        var bestMap: FloatArray? = null
        var bestOutW = 0
        var bestOutH = 0

        for (k in scales) {
            val tw = max(8, (tmpl.width * unit * k).roundToInt())
            val th = max(8, (tmpl.height * unit * k).roundToInt())
            val outW = rw - tw + 1
            val outH = rh - th + 1
            if (outW <= 0 || outH <= 0) continue
            val t = Bitmap.createScaledBitmap(tmpl, tw, th, true)
            val tpx = IntArray(tw * th)
            t.getPixels(tpx, 0, tw, 0, 0, tw, th)
            val ones = ArrayList<Int>()
            for (i in tpx.indices) if (on(tpx[i], mode)) ones.add(i)
            if (ones.size < 6) continue
            val offs = IntArray(ones.size) { (ones[it] / tw) * rw + (ones[it] % tw) }
            val map = FloatArray(outW * outH)
            var localBest = 0f
            var lx = 0
            var ly = 0
            for (y in 0 until outH) {
                for (x in 0 until outW) {
                    val patch = integral[(y + th) * iw + (x + tw)] - integral[y * iw + (x + tw)] -
                        integral[(y + th) * iw + x] + integral[y * iw + x]
                    if (patch == 0) continue
                    val base = y * rw + x
                    var inter = 0
                    for (o in offs) if (mask[base + o]) inter++
                    val iou = inter.toFloat() / (offs.size + patch - inter)
                    map[y * outW + x] = iou
                    if (iou > localBest) { localBest = iou; lx = x; ly = y }
                }
            }
            if (localBest > bestScore) {
                bestScore = localBest; bestX = lx; bestY = ly; bestSize = max(tw, th)
                bestMap = map; bestOutW = outW; bestOutH = outH
            }
        }
        val map = bestMap ?: return null
        var second = 0f
        for (y in 0 until bestOutH) {
            for (x in 0 until bestOutW) {
                if (kotlin.math.abs(x - bestX) < bestSize && kotlin.math.abs(y - bestY) < bestSize) continue
                val v = map[y * bestOutW + x]
                if (v > second) second = v
            }
        }
        val cx = (rx0 + bestX + bestSize / 2f) / work.width
        val cy = (ry0 + bestY + bestSize / 2f) / work.height
        return Match(cx, cy, bestScore, second)
    }
}
