package com.zz213119.virtualclicker.task

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import com.zz213119.virtualclicker.core.VirtualDisplayManager
import com.zz213119.virtualclicker.service.LogWriter
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 实验功能「一键开刷」第一步：
 *  新建虚拟屏 → 启动异环云游戏 → 点击任意位置登录 → 开始游戏 →
 *  （若弹出）使用移动流量进行游戏 → （若排队）耐心等待 → 等待加载 → 进入游戏 → 等待加载 → 进入游戏世界。
 *
 * 靠中文 OCR 识别每个界面上的文字并点击文字本身，不依赖固定坐标，换分辨率也能用。
 * 排队界面只等待，绝不点"切换"或"退出排队"。
 */
class OneClickTask(
    private val ctx: Context,
    private val onStatus: (String) -> Unit = {}
) {
    companion object {
        const val PKG = "com.pwrd.cloud.yh.laohu"
        private const val W = 1280
        private const val H = 720
        private const val DPI = 240
        private const val POLL_MS = 1500L
        private const val IDLE_TIMEOUT_MS = 10 * 60_000L          // 非排队阶段这么久没进展就放弃
        private const val QUEUE_TIMEOUT_MS = 3 * 60 * 60_000L     // 排队最多等 3 小时
        private const val MAX_TAPS = 8
        private val QUEUE_POS = Regex("第\\s*(\\d+)\\s*/\\s*(\\d+)\\s*名")
        private val PERCENT = Regex("(^|\\D)(\\d{1,3})\\s*%")
    }

    private var displayId = -1
    private var w = W
    private var h = H
    private var last: Bitmap? = null
    private var shots = 0
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun log(msg: String) = LogWriter.write("ONECLICK", msg)
    private fun now() = SystemClock.elapsedRealtime()

    private fun shot(bmp: Bitmap?, tag: String) {
        if (bmp == null || shots >= 20) return
        shots++
        runCatching {
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            VirtualDisplayManager.saveShot("oneclick-$stamp-$tag.jpg", baos.toByteArray())
        }
    }

    private fun fail(msg: String): YihuanShopTask.Outcome {
        shot(last, "FAIL")
        log("FAIL: $msg")
        return YihuanShopTask.Outcome(false, "一键开刷中断", msg)
    }

    private fun isBlank(bmp: Bitmap): Boolean {
        val g = FrameOps.boxGrid(bmp, 0f, 0f, 1f, 1f, 8, 4)
        return g.average() < 2.0
    }

    private suspend fun grab(): Bitmap? {
        repeat(3) {
            val bytes = VirtualDisplayManager.captureFrame(displayId, W)
            val bmp = bytes?.let { FrameOps.decode(it) }
            if (bmp != null && !isBlank(bmp)) { last = bmp; return bmp }
            delay(300)
        }
        return null
    }

    private suspend fun tap(nx: Float, ny: Float): Boolean {
        val x = nx * w
        val y = ny * h
        val t0 = SystemClock.uptimeMillis()
        val d = VirtualDisplayManager.touchEvent(displayId, MotionEvent.ACTION_DOWN, t0, x, y)
        delay(60)
        val u = VirtualDisplayManager.touchEvent(displayId, MotionEvent.ACTION_UP, t0, x, y)
        if (d && u) return true
        return VirtualDisplayManager.longPress(displayId, x, y, 60)
    }

    suspend fun run(): YihuanShopTask.Outcome {
        onStatus("连接后台服务…")
        if (!VirtualDisplayManager.ensureBound()) return fail("后台服务连接失败（检查启动模式和授权）")
        VirtualDisplayManager.releaseAll()
        delay(800)

        displayId = VirtualDisplayManager.createDisplay("vc_display_1", W, H, DPI)
        if (displayId < 0) return fail("创建虚拟屏失败")
        VirtualDisplayManager.oneClickDisplayId = displayId
        VirtualDisplayManager.displaySize(displayId)?.let { if (it.size >= 2) { w = it[0]; h = it[1] } }
        log("display created id=$displayId size=${w}x$h")

        onStatus("启动游戏…")
        if (!VirtualDisplayManager.launch(PKG, displayId)) return fail("启动游戏失败")
        delay(2500)

        var enterTapped = false
        var sawPercentAfterEnter = false
        var noPercent = 0
        var tapLogin = 0
        var tapStart = 0
        var tapNet = 0
        var tapEnter = 0
        var lastProgress = now()
        var queueSince = -1L
        var lastKey = ""
        val seen = HashSet<String>()

        while (true) {
            delay(POLL_MS)
            val f = grab()
            if (f == null) {
                if (queueSince < 0 && now() - lastProgress > IDLE_TIMEOUT_MS) return fail("长时间没有取到有效画面")
                continue
            }
            val lines = runCatching { GameOcr.read(f) }.getOrElse {
                log("ocr error: ${it.message}")
                emptyList()
            }
            val joined = lines.joinToString(" ") { it.text }

            val key = joined.take(160)
            if (key != lastKey) {
                lastKey = key
                log("ocr: " + lines.take(14).joinToString(" | ") { it.text.take(30) })
            }

            val inQueue = joined.contains("排队中") || joined.contains("退出排队") || joined.contains("目前正排在")
            val net = lines.firstOrNull { it.text.contains("使用移动流量") || it.text.contains("流量进行游戏") }
            val enter = lines.firstOrNull { it.text.contains("进入游戏") }
            val start = lines.firstOrNull { it.text.contains("开始游戏") }
            val login = lines.firstOrNull { it.text.contains("任意位置") || it.text.contains("点击任意") }
            val hud = joined.contains("猎人等级")
            val percentMatch = PERCENT.find(joined)
            val loadingText = joined.contains("游戏启动中") || joined.contains("请耐心等待")

            // ---- 排队：只等，最高优先级（背景里还能看到"开始游戏"，绝不能点）----
            if (inQueue) {
                if (queueSince < 0) { queueSince = now(); log("queue started"); shot(f, "queue") }
                lastProgress = now()
                val pos = QUEUE_POS.find(joined)
                onStatus(if (pos != null) "排队中 第${pos.groupValues[1]}/${pos.groupValues[2]}名" else "排队中，请耐心等待…")
                if (now() - queueSince > QUEUE_TIMEOUT_MS) return fail("排队超过 3 小时仍未进入")
                continue
            } else if (queueSince >= 0) {
                queueSince = -1
                lastProgress = now()
                log("queue ended")
            }

            when {
                net != null -> {
                    lastProgress = now()
                    if (++tapNet > MAX_TAPS) return fail("多次点击“使用移动流量进行游戏”没有反应")
                    if (seen.add("net")) shot(f, "net")
                    onStatus("选择使用移动流量…")
                    log("tap net (${net.cx},${net.cy}) #$tapNet")
                    tap(net.cx, net.cy)
                    delay(2500)
                }
                hud || (enterTapped && sawPercentAfterEnter && noPercent >= 3 && !loadingText) -> {
                    shot(f, "done")
                    log("DONE hud=$hud")
                    onStatus("已进入游戏")
                    return YihuanShopTask.Outcome(true, "一键开刷：已进入游戏", "虚拟屏 #$displayId，已进入游戏世界，可继续下一步。")
                }
                enter != null -> {
                    lastProgress = now()
                    if (++tapEnter > MAX_TAPS) return fail("多次点击“进入游戏”没有反应")
                    if (seen.add("enter")) shot(f, "enter")
                    onStatus("进入游戏…")
                    log("tap enter (${enter.cx},${enter.cy}) #$tapEnter")
                    tap(enter.cx, enter.cy)
                    enterTapped = true
                    sawPercentAfterEnter = false
                    noPercent = 0
                    delay(3000)
                }
                start != null -> {
                    lastProgress = now()
                    if (++tapStart > MAX_TAPS) return fail("多次点击“开始游戏”没有反应")
                    if (seen.add("start")) shot(f, "start")
                    onStatus("开始游戏…")
                    log("tap start (${start.cx},${start.cy}) #$tapStart")
                    tap(start.cx, start.cy)
                    delay(3000)
                }
                login != null -> {
                    lastProgress = now()
                    if (++tapLogin > MAX_TAPS) return fail("多次点击登录没有反应")
                    if (seen.add("login")) shot(f, "login")
                    onStatus("点击登录…")
                    log("tap login (center) #$tapLogin")
                    tap(0.5f, 0.5f)
                    delay(3000)
                }
                else -> {
                    if (percentMatch != null || loadingText) lastProgress = now()
                    if (enterTapped) {
                        if (percentMatch != null) { sawPercentAfterEnter = true; noPercent = 0 }
                        else if (sawPercentAfterEnter) noPercent++
                    }
                    val p = percentMatch?.groupValues?.get(2)
                    onStatus(if (p != null) "等待加载… $p%" else "等待加载…")
                    if (now() - lastProgress > IDLE_TIMEOUT_MS) return fail("${IDLE_TIMEOUT_MS / 60000} 分钟没有识别到任何已知界面")
                }
            }
        }
    }
}
