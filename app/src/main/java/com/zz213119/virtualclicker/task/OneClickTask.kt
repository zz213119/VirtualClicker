package com.zz213119.virtualclicker.task

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import com.zz213119.virtualclicker.core.VirtualDisplayManager
import com.zz213119.virtualclicker.service.LogWriter
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 实验功能「一键开刷」第一步：
 *  新建虚拟屏 → 启动异环云游戏 → 点击任意位置登录 → 开始游戏 →
 *  （若弹出）使用移动流量进行游戏 → （若排队）耐心等待 → 等待加载 → 进入游戏 → 等待加载 → 进入游戏世界 →\n *  都市闲趣 → 店长特供 → 地图传送 → 走到 ORIGEN 咖啡店柜台（出现“店长特供”交互选项为止）。
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

        // ---- 传送后移动（坐标均为归一化比例，量自录屏）----
        private const val JOY_X = 0.173f          // 左下角摇杆中心
        private const val JOY_Y = 0.754f
        private const val JOY_R = 0.12f           // 推满的位移 = 屏幕高度 * 0.12
        private const val CAM_Y = 0.35f           // 右侧空白区拖动转镜头
        private const val CAM_MIN_X = 0.50f
        private const val CAM_MAX_X = 0.82f
        private const val WALK_TIMEOUT_MS = 150_000L
        private val SIGN = Regex("ORIG|RIGEN|ORIGE")
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

    private fun asset(name: String): Bitmap =
        ctx.assets.open("yihuan/$name").use { android.graphics.BitmapFactory.decodeStream(it) }

    private suspend fun look(): Pair<Bitmap, List<GameOcr.Line>>? {
        val f = grab() ?: return null
        val lines = runCatching { GameOcr.read(f) }.getOrElse {
            log("ocr error: ${it.message}")
            emptyList()
        }
        return f to lines
    }

    private fun logOcr(tag: String, lines: List<GameOcr.Line>) =
        log("$tag ocr: " + lines.take(12).joinToString(" | ") { it.text.take(24) })

    /**
     * 第二段：进入游戏后 → 点左上角"都市大亨"房子 → 都市闲趣 → 店长特供 → 地图上点红色传送点 → 传送。
     * 图标没有文字，靠 IconFinder 找；有文字的都用 OCR 点文字本身。
     */
    private suspend fun navigateToShop(): YihuanShopTask.Outcome {
        val houseT = asset("house_icon.png")
        val markerT = asset("marker_ring.png")
        val roiHouse = floatArrayOf(0f, 0f, 0.42f, 0.30f)
        val roiMarker = floatArrayOf(0.30f, 0.30f, 0.66f, 0.70f)

        // ---- A. 点左上角房子，等待"都市大亨"界面 ----
        onStatus("打开都市大亨…")
        val tA = now()
        var houseTaps = 0
        while (true) {
            if (now() - tA > 120_000) return fail("没有找到左上角“都市大亨”图标，或点击后没有打开（可能被弹窗挡住）")
            val lk = look()
            if (lk == null) { delay(1500); continue }
            val (f, lines) = lk
            val text = lines.joinToString(" ") { it.text }
            if (text.contains("每日登录奖励") || text.contains("点击空白区域")) {
                log("A: daily reward popup, tap blank")
                tap(0.12f, 0.45f)
                delay(2500)
                continue
            }
            if (text.contains("甜梦等级") || text.contains("都市大亨")) { log("A: city tycoon screen"); shot(f, "nav-tycoon"); break }
            val m = IconFinder.find(f, houseT, IconFinder.Mode.WHITE, roiHouse)
            log("A: house icon match=" + (m?.let { "%.2f/%.2f at (%.3f,%.3f)".format(it.score, it.second, it.nx, it.ny) } ?: "none"))
            if (m != null && m.score >= 0.55f && houseTaps < 8) {
                if (houseTaps == 0) shot(f, "nav-hud")
                houseTaps++
                tap(m.nx, m.ny)
                delay(3000)
            } else {
                if (houseTaps == 0 && now() - tA > 20_000) logOcr("A", lines)
                delay(1500)
            }
        }

        // ---- B. 点"都市闲趣"，等待菜单 ----
        onStatus("选择都市闲趣…")
        val tB = now()
        var lastTapB = 0L
        var triesB = 0
        while (true) {
            if (now() - tB > 90_000) return fail("没有进入“都市闲趣”菜单")
            delay(1000)
            val lk = look() ?: continue
            val (f, lines) = lk
            val text = lines.joinToString(" ") { it.text }
            if (text.contains("店长特供")) { log("B: menu opened"); shot(f, "nav-menu"); break }
            if (now() - lastTapB > 4500) {
                val line = lines.firstOrNull { it.text.contains("都市闲趣") && !it.text.contains("MENU") }
                if (line != null) { log("B: tap 都市闲趣 by ocr (${line.cx},${line.cy})"); tap(line.cx, line.cy) }
                else { triesB++; log("B: ocr miss, fallback tap #$triesB"); logOcr("B", lines); tap(0.53f, 0.414f) }
                lastTapB = now()
            }
        }

        // ---- C. 点"店长特供"，等待地图 ----
        onStatus("选择店长特供…")
        val tC = now()
        var lastTapC = 0L
        while (true) {
            if (now() - tC > 120_000) return fail("点击“店长特供”后没有进入地图")
            delay(1000)
            val lk = look() ?: continue
            val (f, lines) = lk
            val text = lines.joinToString(" ") { it.text }
            if (text.contains("追踪") || text.contains("卷叶榕") || text.contains("商铺") || text.contains("传送")) {
                log("C: map opened"); shot(f, "nav-map"); break
            }
            if (now() - lastTapC > 5000) {
                val line = lines.firstOrNull { it.text.contains("店长特供") }
                if (line != null) { log("C: tap 店长特供 by ocr (${line.cx},${line.cy})"); tap(line.cx, line.cy) }
                else { log("C: ocr miss, fallback tap"); logOcr("C", lines); tap(0.827f, 0.506f) }
                lastTapC = now()
            }
        }

        // ---- D. 地图：点红色传送点，确认出现"维特海默塔"面板，再点"传送" ----
        onStatus("点击传送点…")
        val tD = now()
        var markerTaps = 0
        var teleportTapped = false
        while (!teleportTapped) {
            if (now() - tD > 120_000 || markerTaps > 8) return fail("没能在地图上选中传送点并点到“传送”")
            delay(1000)
            val lk = look() ?: continue
            val (f, lines) = lk
            val text = lines.joinToString(" ") { it.text }
            val go = lines.firstOrNull { it.text.trim() == "传送" || (it.text.contains("传送") && it.text.length <= 4) }
            if (go != null && (text.contains("维特海默塔") || text.contains("异象委托"))) {
                shot(f, "nav-teleport")
                log("D: tap 传送 (${go.cx},${go.cy})")
                tap(go.cx, go.cy)
                teleportTapped = true
                break
            }
            val m = IconFinder.find(f, markerT, IconFinder.Mode.RED, roiMarker)
            log("D: marker match=" + (m?.let { "%.2f/%.2f at (%.3f,%.3f)".format(it.score, it.second, it.nx, it.ny) } ?: "none"))
            if (m != null && m.score >= 0.28f && m.score >= m.second * 2f) {
                markerTaps++
                tap(m.nx, m.ny)
                delay(1500)
            } else if (now() - tD > 15_000) {
                // 匹配不上：退回到"以画面中心为基准的固定偏移"
                markerTaps++
                logOcr("D", lines)
                tap(0.459f, 0.510f)
                delay(1500)
            }
        }

        // ---- E. 等传送完成：连续几帧都不再是地图界面 ----
        onStatus("传送中…")
        val tE = now()
        var nonMap = 0
        while (true) {
            if (now() - tE > 150_000) return fail("点了“传送”后长时间没有离开地图")
            delay(1500)
            val lk = look() ?: continue
            val (f, lines) = lk
            val text = lines.joinToString(" ") { it.text }
            val isMap = text.contains("追踪") || text.contains("卷叶榕") || text.contains("传送") ||
                text.contains("维特海默塔") || text.contains("异象委托")
            if (isMap) nonMap = 0 else nonMap++
            if (nonMap >= 3) {
                shot(f, "nav-arrived")
                log("E: teleport finished")
                onStatus("已传送到维特海默塔")
                return walkToShop()
            }
        }
    }


    // ------------------------------------------------------------------
    // 传送之后：走到 ORIGEN 咖啡店柜台，直到出现店内的“店长特供”交互选项。
    // 录屏里的流程：拖右侧转镜头对准店招 → 左摇杆一直推向前约 10 秒（广场→红砖地→草坪→店门）
    // → 进店后微调到柜台前，右侧弹出“店长特供 / 管理店铺”。
    // 这里做成闭环：OCR 找店招“ORIGEN”→ 偏了就转镜头 → 居中就向前走一小段 → 再看。
    // ------------------------------------------------------------------

    /** 按住左摇杆 (dx,dy 为 -1..1 的方向，dy=-1 即向前) 持续 ms 毫秒，结束必抬手。 */
    private suspend fun pushJoystick(dx: Float, dy: Float, ms: Long): Boolean {
        val cx = JOY_X * w
        val cy = JOY_Y * h
        val r = JOY_R * h
        val tx = cx + dx * r
        val ty = cy + dy * r
        val t0 = SystemClock.uptimeMillis()
        if (!VirtualDisplayManager.touchEvent(displayId, MotionEvent.ACTION_DOWN, t0, cx, cy)) return false
        try {
            delay(40)
            VirtualDisplayManager.touchEvent(displayId, MotionEvent.ACTION_MOVE, t0, (cx + tx) / 2, (cy + ty) / 2)
            delay(40)
            val end = now() + ms
            var k = 0
            while (now() < end) {
                // 持续发 MOVE（轻微抖动 1px），避免部分游戏认为手指已静止/丢失
                VirtualDisplayManager.touchEvent(displayId, MotionEvent.ACTION_MOVE, t0, tx + (k++ % 2), ty)
                delay(80)
            }
        } finally {
            withContext(NonCancellable) {
                VirtualDisplayManager.touchEvent(displayId, MotionEvent.ACTION_UP, t0, tx, ty)
            }
        }
        return true
    }

    /** 在右侧空白区横向拖动 dxNorm（按屏幕宽度归一化，正=向右拖）来转镜头。 */
    private suspend fun dragCamera(dxNorm: Float) {
        val d = dxNorm.coerceIn(-0.30f, 0.30f)
        val x0 = if (d >= 0) CAM_MIN_X else CAM_MAX_X
        val x1 = (x0 + d).coerceIn(CAM_MIN_X - 0.02f, CAM_MAX_X + 0.02f)
        val y = CAM_Y * h
        val t0 = SystemClock.uptimeMillis()
        if (!VirtualDisplayManager.touchEvent(displayId, MotionEvent.ACTION_DOWN, t0, x0 * w, y)) return
        try {
            delay(40)
            val n = 8
            for (i in 1..n) {
                VirtualDisplayManager.touchEvent(displayId, MotionEvent.ACTION_MOVE, t0, (x0 + (x1 - x0) * i / n) * w, y)
                delay(30)
            }
            delay(40)
        } finally {
            withContext(NonCancellable) {
                VirtualDisplayManager.touchEvent(displayId, MotionEvent.ACTION_UP, t0, x1 * w, y)
            }
        }
        delay(500)   // 等镜头停稳
    }

    private fun findSign(lines: List<GameOcr.Line>): GameOcr.Line? =
        lines.firstOrNull { SIGN.containsMatchIn(it.text.uppercase(Locale.US)) }

    private fun findShopOption(lines: List<GameOcr.Line>): GameOcr.Line? =
        lines.firstOrNull { it.text.contains("店长特供") && it.cx in 0.50f..0.80f && it.cy in 0.30f..0.70f }

    private suspend fun walkToShop(): YihuanShopTask.Outcome {
        onStatus("前往店长特供…")
        delay(4000)   // 传送后的加载画面

        val t0 = now()
        var gain = -1.0f                 // 拖动 1 个屏宽 → 店招在画面里横向移动多少（拖右=场景左移，所以初值为负）
        var pendingCx = -1f              // 刚做过纯转镜头调整时，记下调整前店招位置用来校准 gain
        var pendingDx = 0f
        var sweepSteps = 0
        var everSawSign = false
        var lostWalks = 0
        var insideSteps = 0
        var walks = 0
        var shotSign = false
        var shotInside = false
        val insidePattern = arrayOf(
            floatArrayOf(0f, -1f, 900f),
            floatArrayOf(0.7f, -0.7f, 800f),
            floatArrayOf(0.7f, -0.7f, 800f),
            floatArrayOf(0f, -1f, 700f),
            floatArrayOf(-0.7f, -0.7f, 800f),
            floatArrayOf(1f, 0f, 600f)
        )

        while (true) {
            if (now() - t0 > WALK_TIMEOUT_MS) return fail("传送后 ${WALK_TIMEOUT_MS / 1000} 秒内没有走到店长特供柜台")
            val lk = look()
            if (lk == null) { delay(1000); continue }
            val (f, lines) = lk
            val text = lines.joinToString(" ") { it.text }

            // ---- 终点：店内出现“店长特供”交互选项 ----
            val opt = findShopOption(lines)
            if (opt != null) {
                shot(f, "walk-arrived")
                log("W: shop option found at (${opt.cx},${opt.cy}) after ${(now() - t0) / 1000}s walks=$walks")
                onStatus("已到达店长特供柜台")
                return YihuanShopTask.Outcome(true, "一键开刷：已到达柜台", "虚拟屏 #$displayId，已走到 ORIGEN 柜台，出现“店长特供”选项，后续点击进入并循环待接入。")
            }

            val inside = text.contains("库存") && text.contains("收入")
            val sign = findSign(lines)

            // ---- 店内：没看到选项，按一组方向小步试探柜台 ----
            if (inside && sign == null) {
                if (!shotInside) { shotInside = true; shot(f, "walk-inside") }
                if (insideSteps >= 24) return fail("进了店但没找到“店长特供”选项（试探了 $insideSteps 步）")
                val p = insidePattern[insideSteps % insidePattern.size]
                log("W: inside step #$insideSteps dir=(${p[0]},${p[1]}) ${p[2].toLong()}ms")
                pushJoystick(p[0], p[1], p[2].toLong())
                insideSteps++
                delay(300)
                continue
            }

            if (sign != null) {
                everSawSign = true
                lostWalks = 0
                sweepSteps = 0
                if (!shotSign) { shotSign = true; shot(f, "walk-sign") }
                // 校准镜头增益（只在上一步是纯转镜头时）
                if (pendingCx >= 0f && kotlin.math.abs(pendingDx) > 0.02f) {
                    val g = (sign.cx - pendingCx) / pendingDx
                    if (kotlin.math.abs(g) in 0.2f..3.0f) { gain = 0.5f * gain + 0.5f * g; log("W: camera gain=$gain (measured $g)") }
                }
                pendingCx = -1f
                val err = sign.cx - 0.5f
                if (kotlin.math.abs(err) > 0.10f) {
                    val dx = (-err / gain).coerceIn(-0.28f, 0.28f)
                    log("W: sign cx=${sign.cx} err=$err -> drag camera dx=$dx")
                    pendingCx = sign.cx
                    pendingDx = dx
                    dragCamera(dx)
                    continue
                }
                walks++
                log("W: sign centered cx=${sign.cx} cy=${sign.cy} -> walk forward #$walks")
                onStatus("走向 ORIGEN…")
                pushJoystick(0f, -1f, 1500)
                delay(300)
                continue
            }

            // ---- 看不到店招 ----
            pendingCx = -1f
            if (everSawSign && lostWalks < 4) {
                // 靠近后店招可能被遮挡/出画面，别乱转镜头，直接往前走
                lostWalks++
                walks++
                log("W: sign lost, keep walking #$lostWalks")
                pushJoystick(0f, -1f, 1200)
                delay(300)
                continue
            }
            if (sweepSteps >= 16) {
                logOcr("W", lines)
                return fail("转了一圈也没有在画面里找到“ORIGEN”店招")
            }
            sweepSteps++
            onStatus("寻找 ORIGEN 店招…")
            log("W: no sign, sweep camera #$sweepSteps")
            if (sweepSteps == 1) logOcr("W", lines)
            dragCamera(0.20f)
        }
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
        var tapDaily = 0
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
            val daily = joined.contains("每日登录奖励") || joined.contains("点击空白区域")
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
                daily -> {
                    // 每天第一次打开游戏会弹出"每日登录奖励"，点空白区域关掉；没弹出就不会走到这里
                    lastProgress = now()
                    if (++tapDaily > 10) return fail("“每日登录奖励”弹窗点不掉")
                    if (seen.add("daily")) shot(f, "daily")
                    onStatus("关闭每日登录奖励…")
                    log("tap blank to dismiss daily reward #$tapDaily")
                    tap(0.12f, 0.45f)
                    delay(2500)
                }
                hud || (enterTapped && sawPercentAfterEnter && noPercent >= 3 && !loadingText) -> {
                    shot(f, "done")
                    log("DONE hud=$hud")
                    onStatus("已进入游戏，前往店长特供…")
                    return navigateToShop()
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
