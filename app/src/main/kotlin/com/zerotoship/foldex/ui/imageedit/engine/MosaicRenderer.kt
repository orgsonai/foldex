// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.zerotoship.foldex.ui.imageedit.model.Layer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** 一度に一粒だけ読む。原寸の画像全体を複製せず、履歴にも画素を残さない。 */
internal object MosaicRenderer {
    fun draw(bitmap: Bitmap, canvas: Canvas, layer: Layer.Mosaic, scale: Float, checkCancelled: () -> Unit) {
        val bounds = layer.area.bounds
        val block = layer.blockSizePx * scale
        val left = (bounds.left * scale).coerceIn(0f, bitmap.width.toFloat())
        val top = (bounds.top * scale).coerceIn(0f, bitmap.height.toFloat())
        val right = (bounds.right * scale).coerceIn(0f, bitmap.width.toFloat())
        val bottom = (bounds.bottom * scale).coerceIn(0f, bitmap.height.toFloat())
        if (right <= left || bottom <= top) return
        val columns = floor(left / block).toInt() until ceil(right / block).toInt()
        val rows = floor(top / block).toInt() until ceil(bottom / block).toInt()
        val maxWidth = (ceil(block).toInt() + 1).coerceAtMost(bitmap.width)
        val maxHeight = (ceil(block).toInt() + 1).coerceAtMost(bitmap.height)
        val pixels = IntArray(maxWidth * maxHeight)
        val paint = Paint().apply {
            // 半透明の粒を SRC_OVER で重ねると下の元画像が透けるので、画素を置換する。
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        }
        val checkpoint = canvas.save()
        try {
            canvas.clipPath(SelectionPaths.toPath(layer.area, scale))
            for (row in rows) {
                checkCancelled()
                val y0 = (row * block).roundToInt().coerceIn(0, bitmap.height)
                val y1 = ((row + 1) * block).roundToInt().coerceIn(0, bitmap.height)
                if (y1 <= y0) continue
                for (column in columns) {
                    val x0 = (column * block).roundToInt().coerceIn(0, bitmap.width)
                    val x1 = ((column + 1) * block).roundToInt().coerceIn(0, bitmap.width)
                    if (x1 <= x0) continue
                    val count = (x1 - x0) * (y1 - y0)
                    bitmap.getPixels(pixels, 0, x1 - x0, x0, y0, x1 - x0, y1 - y0)
                    paint.color = MosaicColors.average(pixels, count)
                    canvas.drawRect(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat(), paint)
                }
            }
        } finally {
            canvas.restoreToCount(checkpoint)
        }
    }
}
