// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import com.zerotoship.foldex.core.model.FileUri
import java.io.Closeable
import java.io.File

enum class ExportFailure {
    SOURCE_CHANGED, SOURCE_UNREADABLE, RENDER_FAILED, NOT_ENOUGH_MEMORY,
    ENCODE_FAILED, INVALID_OUTPUT, TARGET_TOO_SMALL, WRITE_FAILED,
    ATOMIC_REPLACE_UNAVAILABLE, DESTINATION_EXISTS, VERIFICATION_FAILED,
}

/** エンコードと検査の済んだ一時出力。保持する側が必ず close する。 */
class PreparedImage(
    val file: File,
    val fingerprint: FileFingerprint,
    val metadataWarning: Boolean,
) : Closeable {
    override fun close() { file.delete() }
}

data class ExportReceipt(val destination: FileUri, val expected: FileFingerprint)

sealed interface ImageExportResult {
    data class Saved(val displayPath: String) : ImageExportResult
    data class Failed(val reason: ExportFailure, val displayPath: String? = null) : ImageExportResult
    /** 出力を再送せず、この保存先を読み戻して確認するための記録。 */
    data class Unconfirmed(val receipt: ExportReceipt) : ImageExportResult
}
