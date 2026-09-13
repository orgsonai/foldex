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
        keepLocation: Boolean,
        targetBytes: Int?,
        checkCancelled: () -> Unit,
    ): Result<PreparedImage, ExportFailure> {
        var bitmap: Bitmap? = null
        var temp: File? = null
        try {
            checkCancelled()
            if (!original.isFile) return Result.Failure(ExportFailure.SOURCE_UNREADABLE)
            if (FileFingerprint.read(original, checkCancelled) != expectedOriginal) {
                return Result.Failure(ExportFailure.SOURCE_CHANGED)
            }
            val rendered = DocumentRenderer.render(doc, sources, checkCancelled = checkCancelled)
            bitmap = when (rendered) {
                is Result.Success -> rendered.value
                is Result.Failure -> return Result.Failure(when (rendered.error) {
                    is RenderFailure.MissingSource, is RenderFailure.UnreadableSource -> ExportFailure.SOURCE_UNREADABLE
                    RenderFailure.NotEnoughMemory -> ExportFailure.NOT_ENOUGH_MEMORY
                    else -> ExportFailure.RENDER_FAILED
                })
            }
            val pixels = bitmap
            val format = doc.output.format
            val quality = if (targetBytes != null && format.hasQuality) {
                val found = ImageEncoder.findQualityForTarget(targetBytes) { q ->
                    checkCancelled()
                    ImageEncoder.encodedSize(pixels, format, q)
                }
                if (!found.withinTarget) return Result.Failure(ExportFailure.TARGET_TOO_SMALL)
                found.quality
            } else doc.output.quality
            cacheDirectory.mkdirs()
            temp = File.createTempFile("image-export-", ".${format.extension}", cacheDirectory)
            val encoded = FileOutputStream(temp).use { output ->
                val result = ImageEncoder.encode(pixels, format, quality, output)
                output.flush()
                output.fd.sync()
                result
            }
            if (!encoded) return Result.Failure(ExportFailure.ENCODE_FAILED)
            bitmap.recycle()
            bitmap = null
            val metadataWarning = !ExifTransfer.copy(original, temp, keepLocation)
            checkCancelled()
            if (FileFingerprint.read(original, checkCancelled) != expectedOriginal) {
                return Result.Failure(ExportFailure.SOURCE_CHANGED)
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
            ) return Result.Failure(ExportFailure.INVALID_OUTPUT)
            // ヘッダーだけでなくデコーダーを通す。出力全体の Bitmap を再確保しない。
            val options = BitmapFactory.Options().apply {
                inSampleSize = ImageSource.sampleSizeFor(doc.outputSize.longEdge, 512)
            }
            val verified = BitmapFactory.decodeFile(temp.absolutePath, options)
                ?: return Result.Failure(ExportFailure.INVALID_OUTPUT)
            verified.recycle()
            val fingerprint = FileFingerprint.read(temp, checkCancelled)
            if (fingerprint.size <= 0) return Result.Failure(ExportFailure.INVALID_OUTPUT)
            if (targetBytes != null && fingerprint.size > targetBytes) {
                return Result.Failure(ExportFailure.TARGET_TOO_SMALL)
            }
            return Result.Success(PreparedImage(temp, fingerprint, metadataWarning)).also { temp = null }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OutOfMemoryError) {
            return Result.Failure(ExportFailure.NOT_ENOUGH_MEMORY)
        } catch (_: Exception) {
            return Result.Failure(ExportFailure.WRITE_FAILED)
        } finally {
            bitmap?.recycle()
            temp?.delete()
        }
    }
}
