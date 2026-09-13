// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.draft

import com.zerotoship.foldex.ui.imageedit.engine.ExportReceipt
import com.zerotoship.foldex.ui.imageedit.engine.FileFingerprint
import com.zerotoship.foldex.ui.imageedit.model.EditDocument
import java.io.File

/** 画像出力とは独立した編集記録。Undo の過去履歴と未確定プレビューは含めない。 */
internal data class ImageDraft(
    val id: String,
    val revision: Long,
    val updatedAt: Long,
    val name: String,
    val origin: DraftOrigin,
    val assets: Map<String, FileFingerprint>,
    val document: EditDocument,
    val dirty: Boolean,
    val pendingExport: ExportReceipt? = null,
    val pendingExportMetadataWarning: Boolean = false,
)

internal data class DraftOrigin(
    val localPath: String,
    val uri: String?,
    /** 描画用の不変素材とは別。原本の上書き競合を検出する。 */
    val fingerprint: FileFingerprint,
)

internal data class OpenedImageDraft(val draft: ImageDraft, val recoveredPrevious: Boolean)

internal data class ImageDraftEntry(
    val id: String,
    val name: String,
    val updatedAt: Long,
    val bytes: Long,
    val problem: DraftFailure? = null,
    val inUse: Boolean = false,
)

internal enum class DraftFailure { IO, CORRUPT, UNSUPPORTED, MISSING_SOURCE, BUSY, CONFLICT, TOO_LARGE }

internal class DraftException(val reason: DraftFailure) : java.io.IOException(reason.name)

internal fun imageDraftRoot(filesDir: File): File = File(filesDir, "image-drafts")
