// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.zerotoship.foldex.ui.imageedit.model.EditPoint
import com.zerotoship.foldex.ui.imageedit.model.EditSize
import com.zerotoship.foldex.ui.imageedit.model.SelectionSpec
import kotlinx.coroutines.CancellationException
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** 端末内の CPU だけで推論する。画像・利用統計の送信、実行時のダウンロードは行わない。 */
internal object ObjectSelectionEngine {
    fun select(
        context: Context,
        bitmap: Bitmap,
        point: EditPoint,
        canvas: EditSize,
        checkCancelled: () -> Unit,
    ): ObjectSelectionResult {
        var inputImage: Bitmap? = null
        return try {
            checkCancelled()
            val normalized = EditPoint(point.x / canvas.width, point.y / canvas.height)
            if (normalized.x !in 0f..1f || normalized.y !in 0f..1f) return ObjectSelectionResult.Empty
            val tapX = (normalized.x * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
            val tapY = (normalized.y * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
            if (Color.alpha(bitmap.getPixel(tapX, tapY)) == 0) return ObjectSelectionResult.Empty

            val size = MagicTouchTensor.SIZE
            val square = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            inputImage = square
            Canvas(square).apply {
                // モデルは RGB + 点指定を受け取る。透明部分の隠れた RGB を推論に渡さない。
                drawColor(Color.WHITE)
                drawBitmap(bitmap, null, RectF(0f, 0f, size.toFloat(), size.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            val pixels = IntArray(MagicTouchTensor.PIXELS)
            square.getPixels(pixels, 0, size, 0, 0, size, size)
            square.recycle()
            inputImage = null
            val input = ByteBuffer.allocateDirect(MagicTouchTensor.INPUT_BYTES).order(ByteOrder.nativeOrder())
            val output = ByteBuffer.allocateDirect(MagicTouchTensor.OUTPUT_BYTES).order(ByteOrder.nativeOrder())
            MagicTouchTensor.writeInput(pixels, normalized, input, checkCancelled)
            val model = context.assets.openFd(MODEL_ASSET).use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                    channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
                }
            }
            checkCancelled()
            val options = Interpreter.Options().setNumThreads(2).setUseXNNPACK(true)
            // 1 リクエストごとに閉じる。画面終了・取消でもネイティブメモリを残さない。
            Interpreter(model, options).use { interpreter ->
                check(interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(1, size, size, 4)))
                check(interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(1, size, size, 1)))
                check(interpreter.getInputTensor(0).dataType() == DataType.FLOAT32)
                check(interpreter.getOutputTensor(0).dataType() == DataType.FLOAT32)
                checkCancelled()
                interpreter.run(input, output)
            }
            checkCancelled()
            val mask = MagicTouchTensor.readMask(output, checkCancelled)
            val selection = MaskContours.trace(mask, size, size, canvas, checkCancelled)
                ?: return ObjectSelectionResult.Empty
            ObjectSelectionResult.Success(selection)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OutOfMemoryError) {
            ObjectSelectionResult.Failed
        } catch (_: LinkageError) {
            ObjectSelectionResult.Unavailable
        } catch (_: Exception) {
            ObjectSelectionResult.Failed
        } finally {
            inputImage?.recycle()
        }
    }

    const val MODEL_ASSET = "models/magic_touch.tflite"
}

internal sealed interface ObjectSelectionResult {
    data class Success(val selection: SelectionSpec) : ObjectSelectionResult
    data object Empty : ObjectSelectionResult
    data object MemoryLimited : ObjectSelectionResult
    data object Failed : ObjectSelectionResult
    data object Unavailable : ObjectSelectionResult
}
