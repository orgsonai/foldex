// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import com.zerotoship.foldex.ui.imageedit.model.EditPoint
import java.nio.ByteBuffer

/** 同梱したモデル revision 1 の入出力。モデルカードではなく実ファイルの形状に合わせる。 */
internal object MagicTouchTensor {
    const val SIZE = 512
    const val PIXELS = SIZE * SIZE
    const val INPUT_BYTES = PIXELS * 4 * Float.SIZE_BYTES
    const val OUTPUT_BYTES = PIXELS * Float.SIZE_BYTES

    /** RGB は 0..1、4 チャネル目はタップした 1 画素だけ 1 にする。 */
    fun writeInput(pixels: IntArray, point: EditPoint, target: ByteBuffer, checkCancelled: () -> Unit = {}) {
        require(pixels.size == PIXELS && target.capacity() == INPUT_BYTES)
        require(point.x.isFinite() && point.y.isFinite() && point.x in 0f..1f && point.y in 0f..1f)
        val x = (point.x * SIZE).toInt().coerceIn(0, SIZE - 1)
        val y = (point.y * SIZE).toInt().coerceIn(0, SIZE - 1)
        val seed = y * SIZE + x
        target.rewind()
        for (i in pixels.indices) {
            if (i % SIZE == 0) checkCancelled()
            val color = pixels[i]
            target.putFloat(((color ushr 16) and 255) / 255f)
            target.putFloat(((color ushr 8) and 255) / 255f)
            target.putFloat((color and 255) / 255f)
            target.putFloat(if (i == seed) 1f else 0f)
        }
        target.rewind()
    }

    /** revision 1 の配布物は sigmoid 適用済みの 1 チャネル確率を返す。 */
    fun readMask(output: ByteBuffer, checkCancelled: () -> Unit = {}): ByteArray {
        require(output.capacity() == OUTPUT_BYTES)
        output.rewind()
        return ByteArray(PIXELS) { i ->
            if (i % SIZE == 0) checkCancelled()
            val probability = output.float
            if (probability.isFinite() && probability >= 0.5f) 1 else 0
        }
    }
}
