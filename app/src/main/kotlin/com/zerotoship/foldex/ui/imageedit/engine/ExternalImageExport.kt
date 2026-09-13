// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.core.model.StorageError
import com.zerotoship.foldex.core.model.StorageProvider
import com.zerotoship.foldex.core.model.WriteMode
import com.zerotoship.foldex.ui.imageedit.model.ImageFormat
import kotlinx.coroutines.CancellationException
import java.util.UUID

/** 元 URI への上書きは受け付けない。新規出力だけを書き、close 後に読み戻す。 */
internal class ExternalImageExport(private val storage: StorageProvider) {
    suspend fun deliver(
        prepared: PreparedImage,
        destination: FileUri,
        original: FileUri?,
        checkCancelled: () -> Unit = {},
    ): ImageExportResult {
        if (destination == original || destination is FileUri.Local) {
            return ImageExportResult.Failed(ExportFailure.DESTINATION_EXISTS)
        }
        val receipt = ExportReceipt(destination, prepared.fingerprint)
        try {
            checkCancelled()
            // SAF は ACTION_CREATE_DOCUMENT が作った新規の文書 URI だけを渡す。
            val mode = if (destination is FileUri.Saf) WriteMode.OVERWRITE else WriteMode.CREATE_NEW
            when (val opened = storage.openOutput(destination, mode)) {
                is Result.Success -> opened.value.use { output ->
                    prepared.file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkCancelled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.flush()
                    }
                }
                is Result.Failure -> return if (opened.error is StorageError.AlreadyExists)
                    ImageExportResult.Failed(ExportFailure.DESTINATION_EXISTS)
                else ImageExportResult.Unconfirmed(receipt)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // close で送信が完了する Provider もある。例外でも同じ URI を確認する。
            return verify(receipt, checkCancelled)
        }
        return verify(receipt, checkCancelled)
    }

    suspend fun verify(receipt: ExportReceipt, checkCancelled: () -> Unit = {}): ImageExportResult {
        try {
            checkCancelled()
            val input = when (val opened = storage.openInput(receipt.destination)) {
                is Result.Success -> opened.value
                is Result.Failure -> return ImageExportResult.Unconfirmed(receipt)
            }
            val actual = input.use { FileFingerprint.read(it, checkCancelled) }
            return if (actual == receipt.expected) ImageExportResult.Saved(receipt.destination.displayName())
            else ImageExportResult.Failed(ExportFailure.VERIFICATION_FAILED, receipt.destination.displayName())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ImageExportResult.Unconfirmed(receipt)
        }
    }

    companion object {
        fun newSibling(source: FileUri.Remote, displayName: String, format: ImageFormat): FileUri.Remote {
            val chars = displayName.substringBeforeLast('.', displayName).codePoints().limit(40).toArray()
            val base = String(chars, 0, chars.size).ifBlank { "image" }.replace('/', '_').replace('\\', '_')
            // FTP / WebDAV の CREATE_NEW は原子的な排他を保証しない。既存名の再利用を避ける。
            val name = "${base}_edited-${UUID.randomUUID()}.${format.extension}"
            val parent = source.path.substringBeforeLast('/', "")
            return source.copy(path = when {
                parent.isNotEmpty() -> "$parent/$name"
                source.path.startsWith('/') -> "/$name"
                else -> name
            })
        }
    }
}
