package com.zz213119.virtualclicker.task

import android.graphics.Bitmap
import com.zz213119.virtualclicker.core.VirtualDisplayManager
import com.zz213119.virtualclicker.service.LogWriter
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 异环 里程碑 1：
 *  1. 点击店内的"店长特供"
 *  2. 等待页面打开（整帧变化 + 稳定）
 *  3. 在左侧列表反复下滑，直到列表不再变化（到顶，即 1-1 所在位置）
 *
 * 所有坐标都是相对虚拟屏的归一化比例(0..1)，来自 16:9 横屏截图的估算，
 * 阈值是首版经验值——每一步都会写日志并存截图，方便按实测调整。
 */
class YihuanShopTask(private val displayId: Int) {

    companion object {
        // "店长特供"按钮（店内场景）
        private const val SHOP_BTN_X = 0.652f
        private const val SHOP_BTN_Y = 0.510f

        // 左侧关卡列表区域与滑动轨迹
        private const val LIST_L = 0.01f
        private const val LIST_T = 0.14f
        private const val LIST_R = 0.17f
        private const val LIST_B = 0.95f
        private const val LIST_X = 0.095f
        private const val SWIPE_FROM_Y = 0.30f   // 手指从上往下拖 -> 列表回到更靠前的关卡
        private const val SWIPE_TO_Y = 0.85f
        private const val SWIPE_MS = 500
        private const val MAX_SWIPES = 15

        // 阈值（平均亮度差 0..255）
        private const val PAGE_CHANGE_MIN = 15f
        private const val STABLE_MAX = 2.0f
        private const val LIST_STATIC_MAX = 1.5f

        private const val SHOT_DIR =
            "/storage/emulated/0/Android/data/com.zz213119.virtualclicker/files/shots"
    }

    private var w = 1920
    private var h = 1080
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun log(msg: String) = LogWriter.write("YIHUAN", msg)

    private fun shot(bmp: Bitmap, tag: String) {
        runCatching {
            val dir = File(SHOT_DIR).apply { mkdirs() }
            File(dir, "yihuan-$stamp-$tag.jpg").outputStream().use {
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
            }
        }.onFailure { log("shot failed tag=$tag: ${it.message}") }
    }

    private fun grab(): Bitmap? {
        val bytes = VirtualDisplayManager.captureFrame(displayId, 960) ?: return null
        return FrameOps.decode(bytes)
    }

    private fun tap(nx: Float, ny: Float): Boolean =
        VirtualDisplayManager.longPress(displayId, nx * w, ny * h, 60)

    private fun swipeList(): Boolean =
        VirtualDisplayManager.swipe(
            displayId, LIST_X * w, SWIPE_FROM_Y * h, LIST_X * w, SWIPE_TO_Y * h, SWIPE_MS
        )

    suspend fun run(): Boolean {
        val size = VirtualDisplayManager.displaySize(displayId)
        if (size == null || size.size < 2) {
            log("FAIL: cannot read display size, displayId=$displayId")
            return false
        }
        w = size[0]; h = size[1]
        log("START displayId=$displayId size=${w}x$h")

        val base = grab()
        if (base == null) {
            log("FAIL: capture returned null (虚拟屏需由\"启动虚拟屏\"创建，才有内部 ImageReader)")
            return false
        }
        shot(base, "00-start")
        val baseGrid = FrameOps.lumaGrid(base)

        // 1+2: 点击"店长特供"并等待页面打开
        var last: Bitmap = base
        var opened = false
        for (attempt in 1..2) {
            val tapOk = tap(SHOP_BTN_X, SHOP_BTN_Y)
            log("tap shop button attempt=$attempt ok=$tapOk")
            var prev = baseGrid
            for (i in 1..12) {
                delay(600)
                val cur = grab() ?: continue
                last = cur
                val g = FrameOps.lumaGrid(cur)
                val dBase = FrameOps.diff(baseGrid, g)
                val dPrev = FrameOps.diff(prev, g)
                prev = g
                log("wait_page attempt=$attempt i=$i dBase=$dBase dPrev=$dPrev")
                if (dBase > PAGE_CHANGE_MIN && dPrev < STABLE_MAX) {
                    opened = true
                    break
                }
            }
            if (opened) break
        }
        shot(last, if (opened) "01-page" else "01-page-NOT-DETECTED")
        if (!opened) {
            log("FAIL: shop page not detected")
            return false
        }

        // 3: 左侧列表下滑到顶
        var prevList = FrameOps.lumaGrid(last, LIST_L, LIST_T, LIST_R, LIST_B)
        var staticCount = 0
        var reachedTop = false
        var swipes = 0
        while (swipes < MAX_SWIPES) {
            swipes++
            val ok = swipeList()
            delay(900)
            val cur = grab() ?: continue
            last = cur
            val g = FrameOps.lumaGrid(cur, LIST_L, LIST_T, LIST_R, LIST_B)
            val d = FrameOps.diff(prevList, g)
            prevList = g
            log("swipe #$swipes ok=$ok listDiff=$d static=$staticCount")
            if (d < LIST_STATIC_MAX) {
                staticCount++
                if (staticCount >= 2) { reachedTop = true; break }
            } else {
                staticCount = 0
            }
        }
        shot(last, "02-list-top")
        log("END reachedTop=$reachedTop swipes=$swipes")
        return reachedTop
    }
}
