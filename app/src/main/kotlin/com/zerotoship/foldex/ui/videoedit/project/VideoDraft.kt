// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.project

import com.zerotoship.foldex.ui.videoedit.model.ExportSettings
import com.zerotoship.foldex.ui.videoedit.model.VideoProject
import java.io.File

/**
 * 動画編集の下書き。編集内容と書き出し設定だけを持ち、素材はコピーせず場所を参照する (設計書 §10・§11)。
 * 元に戻す履歴と、指を離す前の途中の値は含めない (画像編集の下書きと同じ)。
 */
internal data class VideoDraft(
    val id: String,
    val revision: Long,
    val updatedAt: Long,
    val name: String,
    val project: VideoProject,
    val exportSettings: ExportSettings,
)

internal data class OpenedVideoDraft(val draft: VideoDraft, val recoveredPrevious: Boolean)

internal data class VideoDraftEntry(
    val id: String,
    val name: String,
    val updatedAt: Long,
    val bytes: Long,
    val clipCount: Int = 0,
    val durationUs: Long = 0L,
    val problem: VideoDraftFailure? = null,
    val inUse: Boolean = false,
)

internal enum class VideoDraftFailure { IO, CORRUPT, UNSUPPORTED, BUSY, CONFLICT, TOO_LARGE }

internal class VideoDraftException(val reason: VideoDraftFailure) : java.io.IOException(reason.name)

internal fun videoDraftRoot(filesDir: File): File = File(filesDir, "video-drafts")
