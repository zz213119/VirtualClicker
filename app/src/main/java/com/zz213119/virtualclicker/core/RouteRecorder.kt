package com.zz213119.virtualclicker.core

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 路线录制与回放（给“一键开刷”进店后走到柜台用）。
 *
 * 之前一直靠猜方向让角色从店门口走到柜台前（连续几版都走偏）。现在改成“示范一遍”：
 * 在“手动控制”全屏界面点【录制路线】，从店门口开始自己走到出现“店长特供”为止，再点【停止录制】，
 * 应用把这段时间里所有触摸（摇杆、拖动镜头、跳跃……）原样记下来（坐标按屏幕比例存，换分辨率也能用）。
 * 一键开刷进店后就把这段触摸原样回放一遍，之后再继续检测选项。
 */
object RouteRecorder {
    private const val PREFS = "vc_route"
    private const val KEY = "inside_route_v1"

    @Volatile
    var recording = false
        private set

    private val events = ArrayList<JSONObject>()
    private var startTime = -1L

    fun start() {
        synchronized(events) {
            events.clear()
            startTime = -1L
        }
        recording = true
    }

    /** 记录一个手动控制时的触摸事件（坐标是虚拟屏像素，这里换算成屏幕比例）。 */
    fun record(ev: MotionEvent, w: Int, h: Int) {
        if (!recording || ev.pointerCount < 1 || w <= 0 || h <= 0) return
        synchronized(events) {
            if (startTime < 0) startTime = ev.eventTime
            val o = JSONObject()
            o.put("t", ev.eventTime - startTime)
            o.put("a", ev.action)
            val ps = JSONArray()
            for (i in 0 until ev.pointerCount) {
                ps.put(JSONArray().put(ev.getPointerId(i)).put((ev.getX(i) / w).toDouble()).put((ev.getY(i) / h).toDouble()))
            }
            o.put("p", ps)
            events.add(o)
        }
    }

    /** 停止并保存，返回（事件数, 时长毫秒）。 */
    fun stopAndSave(ctx: Context): Pair<Int, Long> {
        recording = false
        val copy = synchronized(events) { ArrayList(events) }
        if (copy.isEmpty()) return 0 to 0L
        val last = copy.last()
        val lastMasked = last.getInt("a") and MotionEvent.ACTION_MASK
        if (lastMasked != MotionEvent.ACTION_UP && lastMasked != MotionEvent.ACTION_CANCEL) {
            // 停止时手指还按着：补一个取消事件，回放时不会留下按住不放的触点
            val c = JSONObject()
            c.put("t", last.getLong("t") + 30)
            c.put("a", MotionEvent.ACTION_CANCEL)
            c.put("p", last.getJSONArray("p"))
            copy.add(c)
        }
        val arr = JSONArray()
        copy.forEach { arr.put(it) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
        return copy.size to copy.last().getLong("t")
    }

    fun hasRoute(ctx: Context): Boolean =
        !ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null).isNullOrEmpty()

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    private class Rec(val t: Long, val action: Int, val ids: IntArray, val xs: FloatArray, val ys: FloatArray)

    private fun load(ctx: Context): List<Rec>? {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val ps = o.getJSONArray("p")
                val n = ps.length()
                Rec(
                    o.getLong("t"), o.getInt("a"),
                    IntArray(n) { ps.getJSONArray(it).getInt(0) },
                    FloatArray(n) { ps.getJSONArray(it).getDouble(1).toFloat() },
                    FloatArray(n) { ps.getJSONArray(it).getDouble(2).toFloat() },
                )
            }
        }.getOrNull()
    }

    /** 回放录制的路线到虚拟屏 [displayId]（尺寸 w×h）。返回 true 表示完整回放完。 */
    suspend fun replay(ctx: Context, displayId: Int, w: Int, h: Int, log: (String) -> Unit): Boolean {
        val list = load(ctx) ?: return false
        if (list.isEmpty()) return false
        val start = SystemClock.uptimeMillis()
        var lastRec: Rec? = null
        var finished = false
        try {
            for (e in list) {
                val wait = e.t - (SystemClock.uptimeMillis() - start)
                if (wait > 0) delay(wait)
                inject(displayId, start, e, w, h)
                lastRec = e
            }
            finished = true
        } finally {
            if (!finished) {
                // 被中途取消：给还按着的触点补一个取消，免得摇杆一直按住
                val lr = lastRec
                if (lr != null) withContext(NonCancellable) {
                    runCatching { inject(displayId, start, Rec(lr.t + 10, MotionEvent.ACTION_CANCEL, lr.ids, lr.xs, lr.ys), w, h) }
                }
            }
        }
        log("route replay events=${list.size} durationMs=${list.last().t} finished=$finished")
        return finished
    }

    private fun inject(displayId: Int, downTime: Long, e: Rec, w: Int, h: Int) {
        val n = e.ids.size
        val props = Array(n) {
            MotionEvent.PointerProperties().apply { id = e.ids[it]; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }
        val coords = Array(n) {
            MotionEvent.PointerCoords().apply { x = e.xs[it] * w; y = e.ys[it] * h; pressure = 1f; size = 1f }
        }
        val me = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), e.action, n, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try {
            VirtualDisplayManager.injectMotionEvent(me, displayId)
        } finally {
            me.recycle()
        }
    }
}
