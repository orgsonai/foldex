// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.ui.imageedit.model.EditDocument
import com.zerotoship.foldex.ui.imageedit.model.ImageFormat
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream

/** 描画・符号化・出力検査までを終えてから、保存先のファイルを触る。 */
internal class ImageExportCoordinator(private val cacheDirectory: File) {
    fun prepare(
        doc: EditDocument,
        sources: (String) -> ImageSource?,
        original: File,
        expectedOriginal: FileFingerprint,
        checkCancelled: () -> Unit,
    ): Result<PreparedImage, ExportPreparationFailure> {
        var bitmap: Bitmap? = null
        var temp: File? = null
        val targetBytes = doc.output.targetBytes
        fun failure(reason: ExportFailure) = Result.Failure(ExportPreparationFailure(reason))
        try {
            checkCancelled()
            if (!original.isFile) return failure(ExportFailure.SOURCE_UNREADABLE)
            if (FileFingerprint.read(original, checkCancelled) != expectedOriginal) {
                return failure(ExportFailure.SOURCE_CHANGED)
            }
            val rendered = DocumentRenderer.render(doc, sources, checkCancelled = checkCancelled)
            bitmap = when (rendered) {
                is Result.Success -> rendered.value
                is Result.Failure -> return failure(when (rendered.error) {
                    is RenderFailure.MissingSource, is RenderFailure.UnreadableSource -> ExportFailure.SOURCE_UNREADABLE
                    RenderFailure.NotEnoughMemory -> ExportFailure.NOT_ENOUGH_MEMORY
                    else -> ExportFailure.RENDER_FAILED
                })
            }
            val pixels = bitmap
            ExportPixels.apply(pixels, doc.output)
            val format = doc.output.format
            val metadata = ExifTransfer.read(original, doc.output.metadata)
            cacheDirectory.mkdirs()
            val outputFile = File.createTempFile("image-export-", ".${format.extension}", cacheDirectory)
            temp = outputFile
            var metadataWarning = false
            var lastBytes = 0L
            var lastQuality: Int? = null
            fun writeCandidate(quality: Int): Int {
                checkCancelled()
                val encoded = FileOutputStream(outputFile).use { output ->
                    val result = ImageEncoder.encode(pixels, format, quality, output)
                    output.flush()
                    output.fd.sync()
                    result
                }
                if (!encoded) throw PreparationAbort(ExportFailure.ENCODE_FAILED)
                metadataWarning = !ExifTransfer.apply(metadata, outputFile, doc.output.metadata, doc.outputSize)
                checkCancelled()
                lastBytes = outputFile.length()
                lastQuality = quality
                if (lastBytes <= 0) throw PreparationAbort(ExportFailure.INVALID_OUTPUT)
                return if (lastBytes <= Int.MAX_VALUE) lastBytes.toInt() else -1
            }
            val quality = if (targetBytes != null && format.hasQuality) {
                val found = ImageEncoder.findQualityForTarget(targetBytes, measure = ::writeCandidate)
                if (!found.withinTarget) return Result.Failure(ExportPreparationFailure(
                    ExportFailure.TARGET_TOO_SMALL, lastBytes,
                    OutputSizeAdvice.suggest(doc.outputSize, lastBytes, targetBytes),
                ))
                found.quality
            } else doc.output.quality
            if (lastQuality != quality) writeCandidate(quality)
            bitmap.recycle()
            bitmap = null
            checkCancelled()
            if (FileFingerprint.read(original, checkCancelled) != expectedOriginal) {
                return failure(ExportFailure.SOURCE_CHANGED)
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temp.absolutePath, bounds)
            val mime = when (format) {
                ImageFormat.JPEG -> "image/jpeg"
                ImageFormat.PNG -> "image/png"
                ImageFormat.WEBP -> "image/webp"
            }
            if (bounds.outWidth != doc.outputSize.width || bounds.outHeight != doc.outputSize.height ||
                bounds.outMimeType != mime
            ) return failure(ExportFailure.INVALID_OUTPUT)
            // ヘッダーだけでなくデコーダーを通す。出力全体の Bitmap を再確保しない。
            val options = BitmapFactory.Options().apply {
                inSampleSize = ImageSource.sampleSizeFor(doc.outputSize.longEdge, 512)
            }
            val verified = BitmapFactory.decodeFile(temp.absolutePath, options)
                ?: return failure(ExportFailure.INVALID_OUTPUT)
            verified.recycle()
            val fingerprint = FileFingerprint.read(temp, checkCancelled)
            if (fingerprint.size <= 0) return failure(ExportFailure.INVALID_OUTPUT)
            if (targetBytes != null && fingerprint.size > targetBytes) {
                return Result.Failure(ExportPreparationFailure(
                    ExportFailure.TARGET_TOO_SMALL, fingerprint.size,
                    OutputSizeAdvice.suggest(doc.outputSize, fingerprint.size, targetBytes),
                ))
            }
            return Result.Success(PreparedImage(temp, fingerprint, metadataWarning)).also { temp = null }
        } catch (abort: PreparationAbort) {
            return failure(abort.reason)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OutOfMemoryError) {
            return failure(ExportFailure.NOT_ENOUGH_MEMORY)
        } catch (_: Exception) {
            return failure(ExportFailure.WRITE_FAILED)
        } finally {
            bitmap?.recycle()
            temp?.delete()
        }
    }

    private class PreparationAbort(val reason: ExportFailure) : RuntimeException()
}
