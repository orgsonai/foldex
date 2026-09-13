// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import com.zerotoship.foldex.ui.imageedit.model.EditDocument
import com.zerotoship.foldex.ui.imageedit.model.EditSize
import com.zerotoship.foldex.ui.imageedit.model.Layer
import kotlin.math.roundToInt

internal enum class ImageWork { PREVIEW, EXPORT, SELECTION }

internal sealed interface RenderAdmission {
    data class Allowed(val targetLongEdge: Int?) : RenderAdmission
    data class Rejected(val suggestedLongEdge: Int?) : RenderAdmission
}

/** 確保前の保守的な概算。係数は端末実測による上限保証ではない。 */
internal object RenderBudget {
    const val MIB = 1024L * 1024
    const val MAX_OUTPUT_EDGE = 8192

    fun plan(doc: EditDocument, work: ImageWork, available: Long): RenderAdmission {
        val edge = when (work) {
            ImageWork.PREVIEW -> minOf(2048, doc.canvasSize.longEdge)
            ImageWork.SELECTION -> minOf(1024, doc.canvasSize.longEdge)
            ImageWork.EXPORT -> doc.outputSize.longEdge
        }
        val size = if (work == ImageWork.EXPORT) doc.outputSize else sizeFor(doc, work, edge)
        val fits = (work != ImageWork.EXPORT || edge <= MAX_OUTPUT_EDGE) &&
            requiredBytes(doc, size, work) <= available
        val fitted = if (fits) edge else fittingEdge(doc, work, minOf(edge, MAX_OUTPUT_EDGE), available)
        return if (fitted == null || (!fits && work == ImageWork.EXPORT)) {
            RenderAdmission.Rejected(fitted)
        } else {
            RenderAdmission.Allowed(if (work == ImageWork.EXPORT) null else fitted)
        }
    }

    fun requiredBytes(doc: EditDocument, size: EditSize, work: ImageWork): Long {
        val output = pixels(size)
        var scratch = 0L
        for (layer in doc.layers) {
            if (!layer.visible || layer.alpha <= 0f) continue
            val layerBytes = when (layer) {
                is Layer.Image -> {
                    val edge = sourceLongEdge(layer, size.width.toFloat() / doc.canvas.width)
                    val sample = sampleSizeFor(layer.sourceSize.longEdge, edge)
                    val sampled = EditSize(
                        ((layer.sourceSize.width.toLong() + sample - 1) / sample).toInt(),
                        ((layer.sourceSize.height.toLong() + sample - 1) / sample).toInt(),
                    )
                    // デコード、EXIF 変換、最終縮小の重なりとデコーダー作業領域。
                    multiply(pixels(sampled), 3)
                }
                is Layer.Drawing -> if (layer.strokes.isEmpty()) 0 else multiply(output, 2)
                // モザイク画素配列・選択のクリップマスク・文字等の描画領域を含む。
                else -> multiply(output, 2)
            }
            scratch = maxOf(scratch, layerBytes)
        }
        val postProcess = when (work) {
            ImageWork.PREVIEW -> output // 表示へ渡す際の GPU コピーの余地。
            ImageWork.EXPORT -> multiply(output, 3) // 圧縮器内部の作業領域。圧縮済みデータは保持しない。
            ImageWork.SELECTION -> 128 * MIB // テンソル・モデル・推論 arena・輪郭用。実機計測待ち。
        }
        return add(add(output, maxOf(scratch, postProcess)), 8 * MIB)
    }

    /** renderer と同じ必要解像度。切り抜き後も元画像全体をデコードする点を計上する。 */
    fun sourceLongEdge(layer: Layer.Image, outputScale: Float): Int {
        val ratio = layer.transform.scale * outputScale
        val requested = layer.sourceSize.longEdge * ratio
        if (!requested.isFinite() || requested <= 0) return layer.sourceSize.longEdge
        return requested.roundToInt().coerceIn(1, layer.sourceSize.longEdge)
    }

    fun sampleSizeFor(sourceLongEdge: Int, targetLongEdge: Int): Int {
        if (targetLongEdge <= 0 || sourceLongEdge <= targetLongEdge) return 1
        var sample = 1
        while (sample <= Int.MAX_VALUE / 2 && sourceLongEdge / (sample * 2) >= targetLongEdge) sample *= 2
        return sample
    }

    /** 2 のべき乗のデコード境界も再計算し、実際に予算内になる最大長辺を探す。 */
    fun fittingEdge(doc: EditDocument, work: ImageWork, maxEdge: Int, bytes: Long): Int? {
        // 画面や推論へ極端に粗い画像を渡して、成功に見せない。
        val minimum = when (work) {
            ImageWork.PREVIEW -> 256
            ImageWork.SELECTION -> 512
            ImageWork.EXPORT -> 16
        }
        var low = minOf(minimum, maxEdge)
        var high = maxEdge
        var best: Int? = null
        while (low <= high) {
            val mid = low + (high - low) / 2
            val size = sizeFor(doc, work, mid)
            if (requiredBytes(doc, size, work) <= bytes) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }

    fun sizeFor(doc: EditDocument, work: ImageWork, edge: Int): EditSize =
        if (work == ImageWork.EXPORT) doc.withOutputLongEdge(edge).outputSize
        else doc.canvasSize.fitLongEdge(edge, allowUpscale = false)

    private fun pixels(size: EditSize): Long = multiply(size.width.toLong() * size.height, 4)
    private fun multiply(value: Long, factor: Int): Long =
        if (value > Long.MAX_VALUE / factor) Long.MAX_VALUE else value * factor
    private fun add(a: Long, b: Long): Long = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
}

/** managed heap だけでは API 26+ の Bitmap の画素を数えられない。 */
internal data class ImageMemorySnapshot(
    val memoryClassBytes: Long,
    val managedFreeBytes: Long,
    val managedUsedBytes: Long,
    val nativeUsedBytes: Long,
    val systemAvailableBytes: Long,
    val systemThresholdBytes: Long,
    val lowRam: Boolean,
    val lowMemory: Boolean,
) {
    fun availableForWork(previewBytes: Long): Long {
        if (lowMemory) return 0
        val reserve = 24 * RenderBudget.MIB
        val editorLimit = minOf(memoryClassBytes * 3 / 4, (if (lowRam) 96 else 384) * RenderBudget.MIB)
        return minOf(
            editorLimit - previewBytes * 2,
            managedFreeBytes - reserve,
            memoryClassBytes * 2 - managedUsedBytes - nativeUsedBytes - previewBytes - reserve,
            (systemAvailableBytes - systemThresholdBytes).coerceAtLeast(0) / 4,
        ).coerceAtLeast(0)
    }
}
