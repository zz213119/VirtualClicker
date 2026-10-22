package com.zz213119.virtualclicker.task

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/** 中文 OCR（ML Kit 内置模型，离线）：读出画面上每一行文字及其中心位置（归一化 0..1）。 */
object GameOcr {
    data class Line(val text: String, val cx: Float, val cy: Float)

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    fun read(bmp: Bitmap): List<Line> {
        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)))
        return result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val r = line.boundingBox ?: return@mapNotNull null
            Line(line.text, r.exactCenterX() / bmp.width, r.exactCenterY() / bmp.height)
        }
    }
}
