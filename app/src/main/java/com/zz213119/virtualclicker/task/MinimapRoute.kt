package com.zz213119.virtualclicker.task

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * 读小地图上的导航路线（在游戏里“追踪”一咖舍后，小地图上从人物到目标会出现一条青蓝色虚线）。
 * 思路参考开源项目 NTEPilot（只借鉴思路，代码自己写）：取小地图圆内的青蓝色像素，
 * 用离圆心最近的那一簇算出路线方向。angle：0=小地图正上方，顺时针为正（度）。
 * 目前只用于日志诊断，不参与控制。
 */
object MinimapRoute {
    // 小地图圆心/半径，归一化（圆心按屏宽、屏高分别归一；半径按屏宽）。量自虚拟屏 16:9 截图。
    private const val CX = 0.086f
    private const val CY = 0.129f
    private const val R_W = 0.0607f
    private const val INNER = 0.22f   // 屏蔽圆心附近的人物图标

    data class Result(val pixels: Int, val angle: Float?, val nearest: Float)

    fun analyze(bm: Bitmap): Result {
        val w = bm.width
        val h = bm.height
        val cx = CX * w
        val cy = CY * h
        val r = R_W * w
        val x0 = (cx - r).toInt().coerceAtLeast(0)
        val y0 = (cy - r).toInt().coerceAtLeast(0)
        val x1 = (cx + r).toInt().coerceAtMost(w - 1)
        val y1 = (cy + r).toInt().coerceAtMost(h - 1)
        val bw = x1 - x0 + 1
        val bh = y1 - y0 + 1
        if (bw <= 4 || bh <= 4) return Result(0, null, 0f)
        val px = IntArray(bw * bh)
        bm.getPixels(px, 0, bw, x0, y0, bw, bh)
        val hsv = FloatArray(3)
        val dxs = ArrayList<Float>()
        val dys = ArrayList<Float>()
        val ds = ArrayList<Float>()
        for (j in 0 until bh) {
            for (i in 0 until bw) {
                val dx = (x0 + i) - cx
                val dy = (y0 + j) - cy
                val d = hypot(dx, dy)
                if (d > r || d < r * INNER) continue
                Color.colorToHSV(px[j * bw + i], hsv)
                if (hsv[0] in 165f..215f && hsv[1] >= 0.45f && hsv[2] >= 0.45f) {
                    dxs.add(dx); dys.add(dy); ds.add(d)
                }
            }
        }
        val n = ds.size
        if (n < 8) return Result(n, null, 0f)
        val sorted = ds.sorted()
        val nearest = sorted.first()
        val limit = max(r * 0.28f, sorted[(n * 0.4f).toInt().coerceIn(0, n - 1)])
        var sx = 0f
        var sy = 0f
        var sw = 0f
        for (k in 0 until n) {
            if (ds[k] <= limit) {
                val wgt = 1f / max(ds[k], 1f)
                sx += dxs[k] * wgt
                sy += dys[k] * wgt
                sw += wgt
            }
        }
        if (sw <= 0f) return Result(n, null, nearest)
        val ang = Math.toDegrees(atan2((sx / sw).toDouble(), (-(sy / sw)).toDouble())).toFloat()
        return Result(n, (ang + 360f) % 360f, nearest)
    }
}
