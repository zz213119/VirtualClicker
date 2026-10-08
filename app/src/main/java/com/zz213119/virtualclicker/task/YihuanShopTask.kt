package com.zz213119.virtualclicker.task

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.zz213119.virtualclicker.core.VirtualDisplayManager
import com.zz213119.virtualclicker.service.LogWriter
import com.zz213119.virtualclicker.task.StaminaOcr.Stamina
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 异环 店长特供 自动刷（里程碑 2）：
 *  进入店长特供 → 列表滑到顶 → 读体力 → 选 1-1 → 开始营业 → 点锤子 →
 *  盯"达成营业额"那颗星变金 → 点左上退出 → 出现"挑战成功" → 点领取 → 回到店长特供 → 循环。
 *  体力读到 0（或体力不足以继续）时停止，由调用方发通知。
 *
 * 坐标/区域都是相对虚拟屏的归一化比例，来自你的截图；识别靠 assets/yihuan 下的参考图
 * 做"固定位置画面比对"，没有 OpenCV。每一步都写日志，失败时存截图。
 */
class YihuanShopTask(private val ctx: Context, private val displayId: Int) {

    data class Outcome(val ok: Boolean, val title: String, val text: String)

    private class Anchor(
        val name: String,
        val l: Float, val t: Float, val r: Float, val b: Float,
        val ref: FloatArray
    )

    companion object {
        // ---- 点击位置 ----
        private const val SHOP_BTN_X = 0.652f; private const val SHOP_BTN_Y = 0.510f   // 店内"店长特供"
        private const val ITEM11_X = 0.089f;   private const val ITEM11_Y = 0.344f     // 列表里的 1-1（列表在顶时）
        private const val START_X = 0.894f;    private const val START_Y = 0.931f      // 开始营业
        private const val HAMMER_X = 0.047f;   private const val HAMMER_Y = 0.412f     // 左侧锤子
        private const val EXIT_X = 0.024f;     private const val EXIT_Y = 0.043f       // 左上退出
        private const val CLAIM_X = 0.603f;    private const val CLAIM_Y = 0.776f      // 领取
        private const val QUIT_X = 0.395f;     private const val QUIT_Y = 0.776f       // 结算页"退出"
        private const val STAR1_X = 0.951f;    private const val STAR1_Y = 0.171f      // "达成营业额"星星

        // ---- 区域 (l,t,r,b) ----
        private val ITEM11_RECT = floatArrayOf(0.0129f, 0.3226f, 0.1638f, 0.3687f)
        private val SCORE_RECT = floatArrayOf(0.795f, 0.088f, 0.974f, 0.133f)
        private val STAMINA_RECT = floatArrayOf(0.81f, 0.02f, 0.905f, 0.072f)

        // ---- 列表滑动 ----
        private const val LIST_L = 0.01f; private const val LIST_T = 0.14f
        private const val LIST_R = 0.17f; private const val LIST_B = 0.95f
        private const val LIST_X = 0.095f
        private const val SWIPE_FROM_Y = 0.30f; private const val SWIPE_TO_Y = 0.85f
        private const val SWIPE_MS = 500
        private const val LIST_STATIC_MAX = 1.5f

        // ---- 阈值 ----
        private const val ANCHOR_MAX = 15f          // 锚点差异(平均亮度差)，匹配≈0~4，不匹配>30
        private const val PURPLE_MIN = 0.25f        // 1-1 被选中时紫色占比约 0.67
        private const val START_MIN_COST = 6        // "开始营业"最多消耗 6
        private const val PLAY_TIMEOUT_MS = 150_000L
        private const val HAMMER_HOLD_MS = 20L      // 每次点击按下到抬起的时间
        private const val POLL_MS = 1000L           // 检测分数/结算画面的间隔（放慢可降低发热）

        private const val SHOT_DIR =
            "/storage/emulated/0/Android/data/com.zz213119.virtualclicker/files/shots"
    }

    private val hammerPeriodMs = com.zz213119.virtualclicker.core.Prefs.hammerPeriodMs(ctx)
    private val maxLoops = com.zz213119.virtualclicker.core.Prefs.maxLoops(ctx)
    private val staminaStop = com.zz213119.virtualclicker.core.Prefs.staminaStop(ctx)

    private var w = 1920
    private var h = 1080
    private var loops = 0
    private var done = 0
    private var last: Bitmap? = null
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private val aShop by lazy { anchor("shop_title", 0.0086f, 0.0215f, 0.1293f, 0.0676f) }
    private val aResult by lazy { anchor("result_btns", 0.3147f, 0.7343f, 0.6853f, 0.8571f) }
    private val aHud by lazy { anchor("hud_panel", 0.7845f, 0.0353f, 0.9828f, 0.3425f) }

    private fun anchor(name: String, l: Float, t: Float, r: Float, b: Float): Anchor {
        val bmp = ctx.assets.open("yihuan/$name.png").use { BitmapFactory.decodeStream(it) }
        return Anchor(name, l, t, r, b, FrameOps.boxGrid(bmp))
    }

    private fun anchorDiff(a: Anchor, f: Bitmap): Float =
        FrameOps.diff(FrameOps.boxGrid(f, a.l, a.t, a.r, a.b), a.ref)

    private fun isShop(f: Bitmap) = anchorDiff(aShop, f) <= ANCHOR_MAX
    private fun isResult(f: Bitmap) = anchorDiff(aResult, f) <= ANCHOR_MAX
    private fun isGameplay(f: Bitmap) = anchorDiff(aHud, f) <= ANCHOR_MAX

    private fun log(msg: String) = LogWriter.write("YIHUAN", "[loop $loops] $msg")

    private fun shot(bmp: Bitmap?, tag: String, force: Boolean = false) {
        if (bmp == null || (!force && loops > 1)) return
        runCatching {
            val dir = File(SHOT_DIR).apply { mkdirs() }
            File(dir, "yihuan-$stamp-L$loops-$tag.jpg").outputStream().use {
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
            }
        }.onFailure { log("shot failed tag=$tag: ${it.message}") }
    }

    private fun now() = SystemClock.elapsedRealtime()

    private suspend fun grab(maxW: Int = 960): Bitmap? {
        repeat(3) {
            val bytes = VirtualDisplayManager.captureFrame(displayId, maxW)
            val bmp = bytes?.let { FrameOps.decode(it) }
            if (bmp != null) { last = bmp; return bmp }
            delay(300)
        }
        return null
    }

    private suspend fun waitFor(timeoutMs: Long, intervalMs: Long = 1000, cond: (Bitmap) -> Boolean): Bitmap? {
        val end = now() + timeoutMs
        while (true) {
            val f = grab()
            if (f != null && cond(f)) return f
            if (now() >= end) return null
            delay(intervalMs)
        }
    }

    private fun tap(nx: Float, ny: Float): Boolean =
        VirtualDisplayManager.longPress(displayId, nx * w, ny * h, 60)

    private fun swipeList(): Boolean =
        VirtualDisplayManager.swipe(displayId, LIST_X * w, SWIPE_FROM_Y * h, LIST_X * w, SWIPE_TO_Y * h, SWIPE_MS)

    private fun fail(msg: String): Outcome {
        shot(last, "FAIL", force = true)
        log("FAIL: $msg")
        return Outcome(false, "异环任务中断", "$msg（已完成 $done 次）")
    }

    private fun exhausted(st: Stamina?, why: String): Outcome {
        log("STOP exhausted: $why stamina=$st")
        val s = st?.let { "当前体力 ${it.cur}/${it.max}。" } ?: ""
        return Outcome(true, "异环：体力已耗尽", "$s$why 共完成 $done 次。")
    }

    /** 读右上角体力，需连续两次读数一致才算数。读不出来返回 null。 */
    private suspend fun readStamina(): Stamina? {
        var prev: Stamina? = null
        repeat(5) {
            val bytes = VirtualDisplayManager.captureFrame(displayId, 1440)
            val bmp = bytes?.let { FrameOps.decode(it) }
            if (bmp != null) {
                val (raw, st) = runCatching {
                    StaminaOcr.read(bmp, STAMINA_RECT[0], STAMINA_RECT[1], STAMINA_RECT[2], STAMINA_RECT[3])
                }.getOrElse { "ERR ${it.message}" to null }
                log("ocr stamina raw='${raw.replace("\n", " ")}' parsed=$st")
                if (st != null) {
                    if (st == prev) return st
                    prev = st
                }
            }
            delay(350)
        }
        return null
    }

    /** 把左侧关卡列表滑到最顶。 */
    private suspend fun listToTop(maxSwipes: Int): Boolean {
        var f = grab() ?: return false
        var prev = FrameOps.boxGrid(f, LIST_L, LIST_T, LIST_R, LIST_B, 48, 48)
        var staticCount = 0
        for (i in 1..maxSwipes) {
            val ok = swipeList()
            delay(900)
            f = grab() ?: continue
            val g = FrameOps.boxGrid(f, LIST_L, LIST_T, LIST_R, LIST_B, 48, 48)
            val d = FrameOps.diff(prev, g)
            prev = g
            log("list swipe #$i ok=$ok diff=$d")
            if (d < LIST_STATIC_MAX) {
                staticCount++
                if (staticCount >= 2) return true
            } else staticCount = 0
        }
        return false
    }

    suspend fun run(): Outcome {
        val size = VirtualDisplayManager.displaySize(displayId)
        if (size == null || size.size < 2) return fail("读不到虚拟屏尺寸")
        w = size[0]; h = size[1]
        log("START displayId=$displayId size=${w}x$h")

        val f0 = waitFor(5000) { true } ?: return fail("取帧失败（虚拟屏需由\"启动虚拟屏\"创建）")
        shot(f0, "00-start")

        // 1. 进入店长特供
        if (!isShop(f0)) {
            var opened = false
            for (attempt in 1..3) {
                log("tap shop button attempt=$attempt ok=${tap(SHOP_BTN_X, SHOP_BTN_Y)}")
                if (waitFor(8000) { isShop(it) } != null) { opened = true; break }
            }
            if (!opened) return fail("未能打开店长特供页面")
        }
        log("in shop page")

        while (loops < maxLoops) {
            loops++
            val tLoop = now()

            // 2. 列表回到顶，读体力
            val top = listToTop(if (loops == 1) 15 else 4)
            log("list at top=$top")
            val st = readStamina()
            log("stamina=$st")
            if (st != null && st.cur <= staminaStop) return exhausted(st, "体力已读到 ${st.cur}（停止阈值 $staminaStop）。")

            // 3. 选中 1-1
            var selected = false
            for (i in 1..3) {
                log("tap 1-1 try=$i ok=${tap(ITEM11_X, ITEM11_Y)}")
                delay(900)
                val fr = grab() ?: continue
                val pf = FrameOps.purpleFrac(fr, ITEM11_RECT[0], ITEM11_RECT[1], ITEM11_RECT[2], ITEM11_RECT[3])
                log("purple=$pf")
                if (pf >= PURPLE_MIN) { selected = true; shot(fr, "01-selected"); break }
            }
            if (!selected) return fail("未能选中 1-1（列表位置可能不对）")

            // 4. 开始营业
            log("tap start ok=${tap(START_X, START_Y)}")
            val play = waitFor(30_000) { isGameplay(it) }
            if (play == null) {
                val onShop = last?.let { isShop(it) } == true
                val st2 = if (onShop) readStamina() else null
                if (st2 != null && st2.cur < START_MIN_COST) return exhausted(st2, "体力不足以开始营业。")
                return fail("点开始营业后没有进入营业画面")
            }
            shot(play, "02-gameplay")
            delay(1500)

            // 5. 连续快速点锤子，同时盯分数
            val tapJob = CoroutineScope(currentCoroutineContext()).launch {
                var shellFallback = false
                var n = 0
                var next = SystemClock.uptimeMillis()
                while (isActive) {
                    val x = HAMMER_X * w
                    val y = HAMMER_Y * h
                    if (!shellFallback) {
                        val t0 = SystemClock.uptimeMillis()
                        val down = VirtualDisplayManager.touchEvent(displayId, android.view.MotionEvent.ACTION_DOWN, t0, x, y)
                        delay(HAMMER_HOLD_MS)
                        val up = VirtualDisplayManager.touchEvent(displayId, android.view.MotionEvent.ACTION_UP, t0, x, y)
                        if (!down || !up) {
                            log("fast tap inject failed (down=$down up=$up), fallback to shell tap")
                            shellFallback = true
                        }
                    } else {
                        tap(HAMMER_X, HAMMER_Y)
                    }
                    n++
                    if (n == 1 || n % 20 == 0) log("hammer taps=$n shellFallback=$shellFallback")
                    next += hammerPeriodMs
                    val wait = next - SystemClock.uptimeMillis()
                    if (wait > 0) delay(wait) else next = SystemClock.uptimeMillis() // 落后了就重新对齐，绝不补点
                }
            }
            var reached = false
            var resultFrame: Bitmap? = null
            val tPlay = now()
            try {
                while (now() - tPlay < PLAY_TIMEOUT_MS) {
                    delay(POLL_MS)
                    val fr = grab() ?: continue
                    if (isResult(fr)) { resultFrame = fr; log("result screen appeared by itself"); break }
                    if (FrameOps.isGoldAt(fr, STAR1_X, STAR1_Y)) { reached = true; shot(fr, "03-reached"); break }
                }
            } finally {
                tapJob.cancel()
            }
            if (!reached && resultFrame == null) return fail("等了 ${PLAY_TIMEOUT_MS / 1000}s，营业额没达标")
            log("score reached=$reached after ${(now() - tPlay) / 1000}s")

            // 6. 左上退出 → 挑战成功
            if (resultFrame == null) {
                for (i in 1..2) {
                    log("tap exit try=$i ok=${tap(EXIT_X, EXIT_Y)}")
                    resultFrame = waitFor(6000) { isResult(it) }
                    if (resultFrame != null) break
                }
                if (resultFrame == null) return fail("点退出后没有出现挑战成功")
            }
            shot(resultFrame, "04-result")
            delay(800)

            // 7. 领取 → 回到店长特供
            log("tap claim ok=${tap(CLAIM_X, CLAIM_Y)}")
            val tClaim = now()
            var back = false
            var stuck = false
            while (now() - tClaim < 15_000) {
                delay(700)
                val fr = grab() ?: continue
                if (isShop(fr)) { back = true; break }
                if (isResult(fr) && now() - tClaim > 5000) { stuck = true; break }
            }
            if (stuck) {
                log("claim did not leave result screen -> tap quit")
                tap(QUIT_X, QUIT_Y)
                val s = waitFor(12_000) { isShop(it) }
                val st3 = if (s != null) readStamina() else null
                return exhausted(st3, "领取没有生效（体力可能不足以领取），已点退出。")
            }
            if (!back) return fail("领取后没有回到店长特供页面")

            done++
            log("loop done total=$done in ${(now() - tLoop) / 1000}s")
            delay(800)
        }
        return Outcome(true, "异环：已达循环上限", "已连续完成 $done 次（上限 $maxLoops），请检查体力。")
    }
}
