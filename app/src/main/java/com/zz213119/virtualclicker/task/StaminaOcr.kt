package com.zz213119.virtualclicker.task

import android.graphics.Bitmap
import android.graphics.Canvas
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** 读取右上角"体力 当前/上限"（如 604/700）。使用 ML Kit 内置拉丁文模型，离线可用。 */
object StaminaOcr {

    data class Stamina(val cur: Int, val max: Int)

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val pattern = Regex("(\\d{1,4})\\s*[/\\\\|]\\s*(\\d{2,4})")

    /** 返回 (原始识别文本, 解析结果)。区域为归一化坐标。 */
    fun read(frame: Bitmap, l: Float, t: Float, r: Float, b: Float): Pair<String, Stamina?> {
        val x0 = (l * frame.width).toInt().coerceIn(0, frame.width - 1)
        val y0 = (t * frame.height).toInt().coerceIn(0, frame.height - 1)
        val x1 = (r * frame.width).toInt().coerceIn(x0 + 1, frame.width)
        val y1 = (b * frame.height).toInt().coerceIn(y0 + 1, frame.height)
        val crop = Bitmap.createBitmap(frame, x0, y0, x1 - x0, y1 - y0)
        val scaled = Bitmap.createScaledBitmap(crop, crop.width * 4, crop.height * 4, true)
        val pad = 24
        val padded = Bitmap.createBitmap(
            scaled.width + pad * 2, scaled.height + pad * 2, Bitmap.Config.ARGB_8888
        )
        Canvas(padded).apply {
            drawColor(scaled.getPixel(2, 2))
            drawBitmap(scaled, pad.toFloat(), pad.toFloat(), null)
        }
        val text = Tasks.await(recognizer.process(InputImage.fromBitmap(padded, 0))).text
        return text to parse(text)
    }

    fun parse(raw: String): Stamina? {
        val s = raw.replace('O', '0').replace('o', '0').replace('l', '1').replace('I', '1')
        val m = pattern.find(s) ?: return null
        val cur = m.groupValues[1].toIntOrNull() ?: return null
        val max = m.groupValues[2].toIntOrNull() ?: return null
        if (max < 50 || cur > max) return null
        return Stamina(cur, max)
    }
}
