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

        // ---- 备用入口：HUD 右上角手机图标 → 手机菜单里的“都市大亨”（坐标量自虚拟屏截图）----
        private const val HOUSE_FALLBACK_MS = 15_000L   // 这么久没能通过左上角房子打开，就改走手机菜单
        private const val PHONE_X = 0.967f
        private const val PHONE_Y = 0.040f
        private const val PHONE_TYCOON_X = 0.863f       // 手机菜单里“都市大亨”图标
        private const val PHONE_TYCOON_Y = 0.423f
        private val PHONE_WORDS = arrayOf("商城", "活动", "环期赏令", "角色", "公告", "时间系统", "集市", "羁遇", "编队", "好友", "鉴别等级")

        // ---- 传送后移动（坐标均为归一化比例，量自录屏）----
        private const val JOY_X = 0.155f          // 左下角摇杆中心（量自虚拟屏截图）
        private const val JOY_Y = 0.783f
        private const val JOY_R = 0.12f           // 推满的位移 = 屏幕高度 * 0.12
        private const val CAM_Y = 0.35f           // 右侧空白区拖动转镜头
        private const val CAM_MIN_X = 0.50f
        private const val CAM_MAX_X = 0.82f
        private const val JUMP_X = 0.909f          // 右下“跳跃”键（跑步小人带箭头）
        private const val JUMP_Y = 0.725f
        private const val BLOCK_DIFF = 6.0f        // 走一段前后两帧的平均亮度差低于它，认为没前进（实际数值请看日志 frameDiff）
        private const val WALK_TIMEOUT_MS = 240_000L
        private const val MAX_WALKS = 30          // 最多走这么多段（每段约 1.2 秒），还没到柜台就放弃，免得越走越远
        private val SWEEP_SEQ = floatArrayOf(0.06f, -0.12f, 0.18f, -0.24f, 0.30f, -0.30f)   // 找店招时的转镜头顺序：围绕当前朝向左右交替、幅度逐步加大（镜头很灵敏，0.06≈24°）
        private val SIGN = Regex("[O0D]RIG|RIGE[NM]|ORIGE|CAFE|[I1L]GE[NMŃ]")
        private val DIST = Regex("(\\d{2,4})\\s*m(?![a-zA-Z])")   // 左上角任务提示里的“881m”
        private const val SPAWN_SIGN_X = 0.77f    // 镜头动画停稳后店招大约在画面横向 0.77 处（量自录屏，只在 OCR 读不出时当作初值）
        private const val GAIN0 = -3.5f             // 向右拖 1 个屏宽，画面内容向【左】移动约 3.5 个屏宽（实测：镜头很灵敏；日志里 dx=-0.049 → 画面右移 0.167）
        private const val SPAWN_TURN = 0.14f        // 传送落地后镜头正对纪念碑，店招在画面右侧之外；先向右拖这么多，店招会出现在画面右侧约 0.77 处（量自两次录屏）
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
        if (bmp == null || shots >= 40) return
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
        val roiHouse = floatArrayOf(0.04f, 0f, 0.32f, 0.16f)   // 房子在小地图旁边的顶部一排；缩小范围避免误配左侧菜单按钮
        val roiMarker = floatArrayOf(0.30f, 0.30f, 0.66f, 0.70f)

        // ---- A. 打开"都市大亨"：先点左上角房子；一段时间还没打开（图标被遮挡/没匹配上）就改走右上角手机菜单 ----
        onStatus("打开都市大亨…")
        val tA = now()
        var fallbackSince = tA
        var houseTaps = 0
        var phoneTaps = 0
        var lastPhoneTap = 0L
        var menuTaps = 0
        var lastMenuTap = 0L
        while (true) {
            if (now() - tA > 150_000) return fail("没能打开“都市大亨”（房子图标和手机菜单两种方式都没成功，可能被弹窗挡住）")
            val lk = look()
            if (lk == null) { delay(1500); continue }
            val (f, lines) = lk
            val text = lines.joinToString(" ") { it.text }
            if (text.contains("每日登录奖励") || text.contains("点击空白区域")) {
                log("A: daily reward popup, tap blank")
                tap(0.12f, 0.45f)
                fallbackSince = now()
                delay(2500)
                continue
            }
            val phoneMenu = PHONE_WORDS.count { text.contains(it) } >= 2
            if (!phoneMenu && (text.contains("甜梦等级") || text.contains("都市大亨"))) { log("A: city tycoon screen"); shot(f, "nav-tycoon"); break }

            // 手机菜单已打开：点里面的“都市大亨”
            if (phoneMenu) {
                if (now() - lastMenuTap > 2500) {
                    if (++menuTaps > 6) { shot(f, "nav-phone-fail"); return fail("手机菜单里点“都市大亨”多次没有反应") }
                    val line = lines.firstOrNull { it.text.contains("都市大亨") }
                    val x = line?.cx ?: PHONE_TYCOON_X
                    val y = if (line != null) line.cy - 0.05f else PHONE_TYCOON_Y   // OCR 只认到文字标签，图标在标签上方
                    if (menuTaps == 1) shot(f, "nav-phone-menu")
                    log("A: phone menu open, tap 都市大亨 (${if (line != null) "ocr" else "fixed"} $x,$y) #$menuTaps")
                    tap(x, y)
                    lastMenuTap = now()
                }
                delay(1500)
                continue
            }

            val useFallback = now() - fallbackSince > HOUSE_FALLBACK_MS || houseTaps >= 3
            if (useFallback) {
                if (now() - lastPhoneTap > 4500) {
                    if (++phoneTaps > 5) { shot(f, "nav-phone-fail"); return fail("点右上角手机图标多次也没能打开手机菜单") }
                    if (phoneTaps == 1) { shot(f, "nav-fallback"); onStatus("改用手机菜单打开都市大亨…") }
                    log("A: house route failed (taps=$houseTaps), tap phone icon #$phoneTaps")
                    tap(PHONE_X, PHONE_Y)
                    lastPhoneTap = now()
                    delay(2500)   // 等手机菜单动画
                } else delay(1000)
                continue
            }

            val m = IconFinder.find(f, houseT, IconFinder.Mode.WHITE, roiHouse)
            log("A: house icon match=" + (m?.let { "%.2f/%.2f at (%.3f,%.3f)".format(it.score, it.second, it.nx, it.ny) } ?: "none"))
            if (m != null && m.score >= 0.55f) {
                if (houseTaps == 0) shot(f, "nav-hud")
                houseTaps++
                tap(m.nx, m.ny)
                delay(3000)
            } else {
                if (houseTaps == 0 && now() - tA > 8_000) logOcr("A", lines)
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
        var tracked = false
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
            // 地图上先“追踪”一咖舍：追踪后小地图上会出现一条指向咖啡店的青蓝色虚线（供后面走路用，目前只记录）
            val trackBtn = lines.firstOrNull { it.text.trim() == "追踪" }
            if (!tracked && trackBtn != null && !text.contains("取消追踪")) {
                tracked = true
                log("D: tap 追踪 (${trackBtn.cx},${trackBtn.cy})")
                shot(f, "nav-track")
                tap(trackBtn.cx, trackBtn.cy)
                delay(1500)
                continue
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

    /** 按住左摇杆 (dx,dy 为 -1..1 的方向，dy=-1 即向前) 持续 ms 毫秒，结束必抬手；jump=true 时按住摇杆的同时用另一根手指点一下跳跃键（每段只跳一次）。 */
    private suspend fun pushJoystick(dx: Float, dy: Float, ms: Long, jump: Boolean = false): Boolean {
        val cx = JOY_X * w
        val cy = JOY_Y * h
        val r = JOY_R * h
        val tx = cx + dx * r
        val ty = cy + dy * r
        val jx = JUMP_X * w
        val jy = JUMP_Y * h
        val t0 = SystemClock.uptimeMillis()
        val one = intArrayOf(0)
        val two = intArrayOf(0, 1)
        if (!VirtualDisplayManager.touchMulti(displayId, MotionEvent.ACTION_DOWN, t0, one, floatArrayOf(cx), floatArrayOf(cy))) return false
        try {
            delay(40)
            VirtualDisplayManager.touchMulti(displayId, MotionEvent.ACTION_MOVE, t0, one, floatArrayOf((cx + tx) / 2), floatArrayOf((cy + ty) / 2))
            delay(40)
            val end = now() + ms
            var k = 0
            var jumped = false
            while (now() < end) {
                if (jump && !jumped) {
                    jumped = true   // 每段只跳一次：连跳容易在翻过矮台后又顶着墙跳，触发攀爬
                    VirtualDisplayManager.touchMulti(displayId, MotionEvent.ACTION_POINTER_DOWN, t0, two, floatArrayOf(tx, jx), floatArrayOf(ty, jy), 1)
                    delay(70)
                    VirtualDisplayManager.touchMulti(displayId, MotionEvent.ACTION_POINTER_UP, t0, two, floatArrayOf(tx, jx), floatArrayOf(ty, jy), 1)
                }
                // 持续发 MOVE（轻微抖动 1px），避免部分游戏认为手指已静止/丢失
                VirtualDisplayManager.touchMulti(displayId, MotionEvent.ACTION_MOVE, t0, one, floatArrayOf(tx + (k++ % 2)), floatArrayOf(ty))
                delay(80)
            }
        } finally {
            withContext(NonCancellable) {
                VirtualDisplayManager.touchMulti(displayId, MotionEvent.ACTION_UP, t0, one, floatArrayOf(tx), floatArrayOf(ty))
            }
        }
        return true
    }

    /** 左上角任务提示里的距离（米）；读不到返回 null。用来判断“往前走了却没前进”（被矮墙/长椅挡住）。 */
    private fun questDistance(lines: List<GameOcr.Line>): Int? {
        for (l in lines) {
            if (l.cx < 0.30f && l.cy in 0.25f..0.55f) {
                DIST.find(l.text)?.let { return it.groupValues[1].toIntOrNull() }
            }
        }
        return null
    }

    /**
     * 在右侧空白区横向拖动 dxNorm（按屏幕宽度归一化，正=向右拖，画面内容向左移）来转镜头；松手后镜头还会滑行约 1 秒，调用方要等画面停稳。
     * variant 0/1/2 是三种起点（高度、横向位置不同），上一种拖了没效果时换下一种重试。
     */
    private suspend fun dragCamera(dxNorm: Float, variant: Int = 0) {
        val d = dxNorm.coerceIn(-0.30f, 0.30f)
        val v = variant.coerceIn(0, 2)
        val startsPos = floatArrayOf(0.58f, 0.66f, 0.72f)    // 向右拖时的起点
        val startsNeg = floatArrayOf(0.80f, 0.78f, 0.74f)    // 向左拖时的起点
        val ys = floatArrayOf(0.42f, 0.30f, 0.52f)
        val x0 = if (d >= 0) (0.84f - d).coerceIn(0.52f, startsPos[v]) else maxOf(startsNeg[v], 0.52f - d).coerceAtMost(0.84f)
        val x1 = (x0 + d).coerceIn(0.50f, 0.86f)
        val y = ys[v] * h
        val t0 = SystemClock.uptimeMillis()
        if (!VirtualDisplayManager.touchEvent(displayId, MotionEvent.ACTION_DOWN, t0, x0 * w, y)) { log("W: drag DOWN rejected"); return }
        try {
            delay(60)
            val n = 12
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
    }

    private fun findSign(lines: List<GameOcr.Line>): GameOcr.Line? =
        lines.firstOrNull { SIGN.containsMatchIn(it.text.uppercase(Locale.US)) }

    /**
     * 找店招：先看整图 OCR 的结果；读不到就把画面中间一条带切成 3 块、各放大 3 倍再识别
     * （店招很小，还可能被路灯杆挡住一部分，整图识别经常读不出来）。返回整图归一化坐标。
     */
    private var lastDeepOcr = 0L

    private fun ocrSign(f: Bitmap, lines: List<GameOcr.Line>, deep: Boolean): GameOcr.Line? {
        findSign(lines)?.let { return it }
        // 分块放大识别很吃 CPU（会让游戏画面卡几秒），远处店招本来也读不出来：只在走近之后/找店招时做，且至少间隔 3 秒
        if (!deep || now() - lastDeepOcr < 3_000) return null
        lastDeepOcr = now()
        val top = 0.33f
        val bot = 0.67f
        for (x in floatArrayOf(0.4f, 0.2f, 0.6f, 0f)) {   // 先看中间偏右（落地后店招多在这一带），再看其它
            val x1n = (x + 0.4f).coerceAtMost(1f)
            val x0 = (x * f.width).toInt()
            val x1 = (x1n * f.width).toInt().coerceAtMost(f.width)
            val y0 = (top * f.height).toInt()
            val y1 = (bot * f.height).toInt()
            val up = runCatching {
                val crop = Bitmap.createBitmap(f, x0, y0, x1 - x0, y1 - y0)
                Bitmap.createScaledBitmap(crop, crop.width * 3, crop.height * 3, true)
            }.getOrNull() ?: continue
            val ls = runCatching { GameOcr.read(up) }.getOrDefault(emptyList())
            val hit = findSign(ls) ?: continue
            return GameOcr.Line(hit.text, x + hit.cx * (x1n - x), top + hit.cy * (bot - top))
        }
        return null
    }

    /** 等画面停稳（相隔 450ms 的两帧差很小）：镜头滑行、传送后的镜头动画结束后再继续。每次取帧都要临时切换虚拟屏输出，所以尽量少取。 */
    private suspend fun waitStable(minMs: Long, maxMs: Long): Bitmap? {
        val t = now()
        delay(minMs)
        var prev = grab()
        while (now() - t < maxMs) {
            delay(450)
            val cur = grab() ?: continue
            val p = prev
            if (p != null && FrameOps.diff(p, cur) < 4.0f) return cur
            prev = cur
        }
        return prev
    }

    private fun findShopOption(lines: List<GameOcr.Line>): GameOcr.Line? =
        lines.firstOrNull { it.text.contains("店长特供") && it.cx in 0.50f..0.80f && it.cy in 0.30f..0.70f }

    private suspend fun walkToShop(): YihuanShopTask.Outcome {
        onStatus("前往店长特供…")
        // 等传送后的场景真正出来并稳定：至少 7 秒（传送后有一段镜头动画，期间拖镜头会被忽略），
        // 画面不是黑的（UID 角标在加载画面上也会显示，不能只看它），且连续两帧基本不变
        val tw = now()
        while (now() - tw < 45_000) {
            delay(1000)
            if (now() - tw < 7_000) continue
            val st = waitStable(300, 4000)
            if (st != null && FrameOps.meanLuma(st) > 25f) break
            log("W: scene not ready yet (black/loading), keep waiting")
        }

        val t0 = now()
        var gain = GAIN0            // 拖动 1 个屏宽 → 画面内容横向移动多少个屏宽（负=向右拖内容向左移）；转镜头后用实测平移/前后两次读数校准
        var pos = -1f               // 店招在画面里的横向位置估计（OCR 读到就用 OCR，否则用初值+转镜头预测）
        var posFromOcr = false
        var everOcr = false
        var adjusts = 0
        var walks = 0
        var lostCount = 0           // 连续几轮没读到店招（读到过之后才计）
        var sweeping = false
        var sweepSteps = 0
        var insideSteps = 0
        var shotNoSign = false
        var shotInside = false
        var stuck = 0               // 连续几段“往前走了但任务距离没变”（被挡住了）
        var lastWalkDist: Int? = null
        var lastWasWalk = false
        var spawnTries = 0
        var distSameCount = 0       // 连续几段任务距离没变（爬墙时画面在变，光看画面差判断不出来）
        var lastBurstDiff = -1f     // 最近一段走路前后两帧的画面差
        var burstStartFrame: Bitmap? = null
        var lastBurstWasJump = false
        var jumpBudget = 2          // 最多靠跳翻过 2 处障碍（花坛矮墙、店门口低台）；到店门口后不再跳，免得对着墙跳触发攀爬
        var lastSignCy = 1f         // 最近一次读到店招的纵向位置，越小说明离店越近
        var pendingCx = -1f         // 刚做完转镜头、还没走路时，转之前 OCR 读到的店招位置，用来校准 gain
        var pendingDx = 0f
        // 进店后“店长特供”选项要往左走才会出现（实测）。选项只在靠近柜台的一小段距离内显示，跑过去只会闪 1~2 帧，
        // 所以每一步只走 0.3 秒左右（约 1~2 米）就停下看一眼；停在范围内选项会一直显示，不会漏掉。
        val insidePattern = ArrayList<FloatArray>().apply {
            repeat(3) { add(floatArrayOf(-0.7f, -0.7f, 300f)) }
            repeat(3) { add(floatArrayOf(-1f, 0f, 300f)) }
            repeat(2) { add(floatArrayOf(0f, -1f, 300f)) }
            repeat(3) { add(floatArrayOf(-0.5f, -0.9f, 300f)) }
            repeat(2) { add(floatArrayOf(-1f, 0f, 300f)) }
            repeat(2) { add(floatArrayOf(0.7f, -0.7f, 300f)) }
            repeat(2) { add(floatArrayOf(1f, 0f, 300f)) }
        }

        var lastTurnMoved = true    // 最近一次 turn() 之后画面是否真的变了（没变=拖动被游戏忽略了）

        /** 拖一次镜头；拖完画面没变化（被游戏忽略）就换一种起点重试，最多 3 次。返回实测的画面平移（null=测不出，仅供日志参考）。 */
        suspend fun turn(before: Bitmap, dx: Float): Float? {
            var attempt = 0
            var after: Bitmap? = null
            while (true) {
                dragCamera(dx, attempt)
                after = waitStable(600, 3000)
                val d = if (after != null) FrameOps.diff(before, after) else -1f
                lastTurnMoved = d >= 6.0f
                if (lastTurnMoved || attempt >= 2) {
                    if (!lastTurnMoved) log("W: drag dx=%.3f still had no effect after ${attempt + 1} tries (diff=%.1f)".format(dx, d))
                    break
                }
                log("W: drag dx=%.3f had no effect (diff=%.1f), retry with variant ${attempt + 1}".format(dx, d))
                attempt++
            }
            val aft = after ?: return null
            val s = FrameOps.shiftX(before, aft)
            if (s != null && kotlin.math.abs(dx) >= 0.04f && kotlin.math.abs(s) < 0.22f && lastTurnMoved) {
                val g = s / dx
                if (g in -8.0f..-1.5f) gain = 0.5f * gain + 0.5f * g
            }
            log("W: drag dx=%.3f measured shift=%s moved=$lastTurnMoved gain=%.2f".format(dx, s?.let { "%.3f".format(it) } ?: "n/a", gain))
            return s
        }

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
                return YihuanShopTask.Outcome(true, "一键开刷：已到达柜台", "虚拟屏 #$displayId，已走到 ORIGEN 柜台，出现“店长特供”选项，接下来进入并自动刷 1-1。")
            }

            val inside = text.contains("库存") && text.contains("收入")
            val sign = if (inside) null else ocrSign(f, lines, walks >= 2 || sweeping || everOcr)
            val mm = MinimapRoute.analyze(f)
            log("W: minimap route cyan=${mm.pixels} angle=" + (mm.angle?.let { "%.0f".format(it) } ?: "none") + " nearest=%.0f".format(mm.nearest))
            log("W: look walks=$walks inside=$inside sign=" + (sign?.let { "(%.3f,%.3f)".format(it.cx, it.cy) } ?: "none") +
                " pos=%.3f lost=$lostCount".format(pos))

            // ---- 卡住检测：上一步是往前走，但画面几乎没变（被矮墙/长椅挡住）→ 后面改成边走边跳 ----
            // 主要看走路前后两帧的差：真在跑，整个场景都在变；顶着墙，只剩角色原地动画。
            // 任务距离只做辅助（距离没变且画面变化也不大时也算卡住）；任务做完没有距离提示时，靠画面照样能判断。
            val dist = questDistance(lines)
            if (lastWasWalk) {
                val bs = burstStartFrame
                lastBurstDiff = if (bs != null) FrameOps.diff(bs, f) else -1f
                burstStartFrame = null
                val d = lastBurstDiff
                val distSame = dist != null && lastWalkDist != null && kotlin.math.abs(dist - lastWalkDist!!) <= 1
                if (distSame) distSameCount++ else if (dist != null) distSameCount = 0
                val blocked = d in 0f..BLOCK_DIFF || (distSame && d in 0f..(BLOCK_DIFF * 2)) || distSameCount >= 2
                if (blocked) {
                    stuck++
                    log("W: blocked? frameDiff=%.1f distSame=$distSame dist=$dist stuck=$stuck".format(d))
                } else {
                    if (stuck > 0) {
                        if (lastBurstWasJump) {
                            jumpBudget--
                            if (lastSignCy < 0.30f) jumpBudget = 0   // 已经在店门口附近翻过去了，后面不再跳
                        }
                        log("W: moving again frameDiff=%.1f dist=$dist, clear stuck, jumpBudget=$jumpBudget".format(d))
                    }
                    stuck = 0
                }
            }
            lastWasWalk = false

            // ---- 店内：没看到选项，按一组方向小步试探柜台 ----
            if (inside && sign == null) {
                if (!shotInside) { shotInside = true; shot(f, "walk-inside") }
                if (insideSteps >= 60) return fail("进了店但没找到“店长特供”选项（试探了 $insideSteps 步）")
                val p = insidePattern[insideSteps % insidePattern.size]
                log("W: inside step #$insideSteps dir=(${p[0]},${p[1]}) ${p[2].toLong()}ms")
                pushJoystick(p[0], p[1], p[2].toLong())
                insideSteps++
                delay(300)
                continue
            }

            if (sign != null) {
                if (!everOcr) { log("W: sign first read by OCR text=${sign.text}"); shot(f, "walk-sign") }
                // 用转镜头前后两次读数校准 gain
                if (pendingCx >= 0f && kotlin.math.abs(pendingDx) >= 0.05f) {
                    val g = (sign.cx - pendingCx) / pendingDx
                    if (g in -8.0f..-1.5f) { gain = 0.5f * gain + 0.5f * g; log("W: gain calibrated=%.2f (measured %.2f)".format(gain, g)) }
                }
                pendingCx = -1f
                everOcr = true
                posFromOcr = true
                pos = sign.cx
                lastSignCy = sign.cy
                lostCount = 0
                sweeping = false
                sweepSteps = 0
            } else if (pos < 0f) {
                // OCR 读不出店招：传送落地的画面基本固定，先按截图里的位置当初值，边走边靠 OCR 纠正
                posFromOcr = false
                log("W: sign not visible at spawn, open-loop turn dx=$SPAWN_TURN then assume sign at cx=$SPAWN_SIGN_X")
                if (!shotNoSign) { shotNoSign = true; shot(f, "walk-nosign") }
                turn(f, SPAWN_TURN)
                spawnTries++
                if (lastTurnMoved || spawnTries >= 3) pos = SPAWN_SIGN_X
                continue
            } else if (everOcr) {
                lostCount++
            }
            pendingCx = if (sign != null) sign.cx else -1f

            // ---- 找不到店招：从没读到过且已走了几段 / 读到过但连续丢了几轮 → 转镜头扫一遍用 OCR 找 ----
            if (!sweeping && ((!everOcr && (walks >= 3 || stuck >= 1)) || (stuck == 0 && everOcr && lostCount >= 3 && lastSignCy >= 0.30f))) {   // 已经走到店门口附近（店招在画面上方、被门框/镜头遮住）就别再转镜头找了，继续往里走
                sweeping = true
                sweepSteps = 0
                log("W: sign lost (everOcr=$everOcr walks=$walks lost=$lostCount), sweep camera to find it again")
                shot(f, "walk-lost")
                logOcr("W", lines)
            }
            if (sweeping && sign == null) {
                if (sweepSteps >= SWEEP_SEQ.size) { logOcr("W", lines); return fail("走到半路丢了“ORIGEN”店招，转镜头也没找回来（已走 $walks 段）") }
                val dx = SWEEP_SEQ[sweepSteps++]
                onStatus("寻找 ORIGEN 店招…")
                pendingCx = -1f
                turn(f, dx)
                continue
            }

            // ---- 把店招转到画面中间 ----
            val err = pos - 0.5f
            val wantDx = -err / gain * 0.9f
            // 拖动太短（<0.025）镜头不会动，等于白转；误差不大就直接往前走
            if (kotlin.math.abs(err) > 0.10f && kotlin.math.abs(wantDx) >= 0.025f && adjusts < 8) {
                adjusts++
                val dx = wantDx.coerceIn(-0.08f, 0.08f)
                log("W: sign cx=%.3f (%s) err=%.3f -> turn dx=%.3f #%d".format(pos, if (posFromOcr) "ocr" else "assumed", err, dx, adjusts))
                val s = turn(f, dx)
                pendingDx = dx
                if (sign == null) pendingCx = -1f
                if (lastTurnMoved) pos = (pos + (s ?: (gain * dx))).coerceIn(0f, 1f)   // 没测出来就按 gain 预测；下一轮 OCR 读到会覆盖；拖动被忽略就不改
                continue
            }

            // ---- 居中了：向前走一段 ----
            if (walks >= MAX_WALKS) {
                shot(f, "walk-toofar")
                return fail("走了 $walks 段还没到柜台，已停止（避免越走越远）")
            }
            adjusts = 0
            pendingCx = -1f
            walks++
            if (walks % 4 == 0) shot(f, "walk-$walks")
            // 卡住了：还有跳的额度就跳一下过去；没有额度（已经到店门口）就别跳，改成向右前/左前试探找门，卡得久了先后退一下再来
            val jumpMode = stuck >= 1 && jumpBudget > 0 && everOcr   // 还没见过店招就卡住，多半是朝向不对（比如对着纪念碑），不该跳
            val nearCafe = lastSignCy < 0.30f
            val lateral = when {
                stuck == 0 -> if (nearCafe) 0.3f else 0f    // 离店很近时路线稍微偏右：门洞在店招中心偏右，直走会撞上门框左边的墙并触发攀爬
                jumpMode -> if (stuck < 3) 0f else if ((stuck / 2) % 2 == 1) 0.7f else -0.7f
                else -> if (((stuck - 1) / 2) % 2 == 0) 0.7f else -0.7f    // 门在店招中心附近偏右，先向右试
            }
            if (!jumpMode && stuck >= 5 && stuck % 5 == 0) {
                log("W: stuck=$stuck, back off a bit")
                pushJoystick(0f, 1f, 900)
                stuck++
                delay(300)
                continue
            }
            log("W: walk forward #$walks (pos=%.3f %s) jump=$jumpMode lateral=$lateral dist=$dist budget=$jumpBudget".format(pos, if (posFromOcr) "ocr" else "assumed"))
            onStatus(if (jumpMode) "跳过障碍…" else "走向 ORIGEN…")
            lastWalkDist = dist
            lastWasWalk = true
            lastBurstWasJump = jumpMode
            pushJoystick(lateral, if (lateral == 0f) -1f else if (kotlin.math.abs(lateral) < 0.5f) -1f else -0.7f, 1200, jump = jumpMode)
            delay(300)
            burstStartFrame = f   // 走完这段后的画面直接用下一轮 look 取到的那帧来比，不再额外取一帧（每次取帧都会让预览/游戏卡一下）
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

        val pkg = com.zz213119.virtualclicker.core.Prefs.oneClickPackage(ctx)
        val native = com.zz213119.virtualclicker.core.Prefs.oneClickNative(ctx)
        log("game=" + (if (native) "异环(本地)" else "云异环") + " pkg=$pkg")
        onStatus(if (native) "启动异环…" else "启动云异环…")
        if (!VirtualDisplayManager.launch(pkg, displayId)) return fail("启动游戏失败（$pkg 没装或无法启动？）")
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
