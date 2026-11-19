package com.zz213119.virtualclicker.task

import android.graphics.Bitmap
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * 云异环偶尔会弹出：“提示 —— 您当前的网络不稳定，是否降到高清码率保证游戏体验”，
 * 带一个“本次启动不再提示”勾选框和“否 / 是”两个按钮。
 * 处理方式：勾选“本次启动不再提示”，再点“是”。一键开刷（实验）和半自动的店长特供循环都会调用这里。
 *
 * 位置优先用 OCR 读出来的文字定位；读不到时用量自截图的相对位置兜底（横屏 2576x1159）。
 */
object NetworkPrompt {
    private const val YES_X = 0.592f
    private const val YES_Y = 0.660f
    private const val BOX_X = 0.445f
    private const val BOX_Y = 0.521f

    /** OCR 文字里是不是这个弹窗。 */
    fun isPrompt(lines: List<GameOcr.Line>): Boolean {
        val j = lines.joinToString("") { it.text }.replace(" ", "")
        return (j.contains("网络不稳定") && (j.contains("码率") || j.contains("降到") || j.contains("不再提示"))) ||
            (j.contains("高清码率") && j.contains("游戏体验")) ||
            (j.contains("不再提示") && j.contains("码率"))
    }

    /**
     * 不用 OCR 的廉价预检（每帧都能做）：中间一块均匀的深灰面板 + 右边一个很亮的“是”按钮 + 左边一个中灰的“否”按钮。
     * 通过后才会再用 OCR 确认，避免每帧都跑 OCR。
     */
    fun looksLikePrompt(bmp: Bitmap): Boolean {
        val panel = FrameOps.boxGrid(bmp, 0.33f, 0.23f, 0.67f, 0.31f, 6, 2)
        val pm = panel.average()
        if (pm < 20.0 || pm > 65.0) return false
        if ((panel.maxOrNull() ?: 0f) - (panel.minOrNull() ?: 0f) > 18f) return false
        val yes = FrameOps.boxGrid(bmp, 0.55f, 0.62f, 0.63f, 0.70f, 3, 2).average()
        if (yes < 170.0) return false
        val no = FrameOps.boxGrid(bmp, 0.37f, 0.62f, 0.45f, 0.70f, 3, 2).average()
        return no in 50.0..120.0
    }

    private fun boxPos(l: GameOcr.Line): Pair<Float, Float> {
        val hN = (l.bottom - l.top).coerceAtLeast(0.02f)
        val ar = if (l.aspect > 0f) l.aspect else 1f
        val lead = l.text.trim().startsWith("本")      // OCR 没有把圆圈当成文字：圆圈在文字左边约 0.8 个字高
        val x = if (lead) l.left - 0.8f * hN / ar else l.left + 0.5f * hN / ar
        return x.coerceIn(0.02f, 0.98f) to l.cy
    }

    private fun patchLuma(bmp: Bitmap, x: Float, y: Float): Float =
        FrameOps.boxGrid(bmp, x - 0.012f, y - 0.02f, x + 0.012f, y + 0.02f, 2, 2).average().toFloat()

    private fun ocr(bmp: Bitmap): List<GameOcr.Line> = runCatching { GameOcr.read(bmp) }.getOrDefault(emptyList())

    /**
     * 处理弹窗。返回 true 表示弹窗已经消失。
     * 第一次尝试：勾选“本次启动不再提示”（点完看圆圈亮度有没有变，没变再点一下文字），然后点“是”。
     * 之后的尝试只补点“是”（不再点勾选框，免得把已经勾上的又点掉）。
     */
    suspend fun handle(
        frame: Bitmap,
        lines0: List<GameOcr.Line>?,
        tap: suspend (Float, Float) -> Boolean,
        capture: suspend () -> Bitmap?,
        log: (String) -> Unit,
    ): Boolean {
        var lines = lines0 ?: ocr(frame)
        if (!isPrompt(lines)) {
            log("NET PROMPT gate hit but OCR does not confirm: " + lines.take(8).joinToString("|") { it.text.take(16) })
            return false
        }
        log("NET PROMPT detected: " + lines.take(10).joinToString("|") { it.text.take(20) })
        var cur = frame
        for (attempt in 1..3) {
            if (attempt == 1) {
                val label = lines.firstOrNull { it.text.replace(" ", "").contains("不再提示") }
                val (bx, by) = if (label != null) boxPos(label) else (BOX_X to BOX_Y)
                val before = patchLuma(cur, bx, by)
                val t1 = tap(bx, by)
                delay(450)
                val f2 = capture()
                if (f2 != null) {
                    val after = patchLuma(f2, bx, by)
                    log("NET PROMPT tick box at (%.3f,%.3f) tap=%s luma %.1f -> %.1f".format(bx, by, t1, before, after))
                    cur = f2
                    if (abs(after - before) < 6f && label != null) {
                        // 圆圈没变化：有的实现要点文字才会勾选，再点一次文字本身
                        val t2 = tap(label.cx, label.cy)
                        delay(450)
                        log("NET PROMPT tick via label tap=$t2")
                    }
                }
            }
            val yes = lines.firstOrNull { it.text.trim() == "是" && it.cx > 0.5f }
            val (yx, yy) = if (yes != null) (yes.cx to yes.cy) else (YES_X to YES_Y)
            val t3 = tap(yx, yy)
            log("NET PROMPT tap yes at (%.3f,%.3f) tap=%s attempt=%d".format(yx, yy, t3, attempt))
            delay(900)
            val f3 = capture() ?: continue
            cur = f3
            lines = ocr(f3)
            if (!isPrompt(lines)) {
                log("NET PROMPT dismissed")
                return true
            }
        }
        log("NET PROMPT still showing after 3 attempts")
        return false
    }
}
