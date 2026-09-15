// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.engine

import android.net.Uri
import com.zerotoship.foldex.ui.videoedit.model.VideoExportError
import com.zerotoship.foldex.ui.videoedit.plan.PlanNotice
import com.zerotoship.foldex.ui.videoedit.plan.UnmetCondition
import java.io.File

/** 書き出し先。原本を上書きする先は作らない (設計書 §1-3)。 */
sealed interface ExportDestination {
    /** 端末内のフォルダ。同じフォルダの隠し一時ファイルへ直接書き出し、検証してから名前を確定する。 */
    data class LocalFolder(val directory: File, val baseName: String) : ExportDestination

    /** ACTION_CREATE_DOCUMENT で作った新しい文書。アプリ内の一時ファイルに書き出し、検証後に書き込んで読み戻す。 */
    data class Document(val uri: Uri) : ExportDestination
}

enum class ExportStage { CHECKING, ENCODING, VERIFYING, SAVING }

/** [percent] は Media3 が進み具合を出せないとき null。[attempt] は容量指定の書き出し直しの回数 (1 から)。 */
data class ExportProgress(val stage: ExportStage, val percent: Int? = null, val attempt: Int = 1)

sealed interface VideoExportOutcome {
    data class Saved(
        val displayName: String,
        /** 端末内に保存したときの絶対パス。SAF では null。 */
        val path: String?,
        val sizeBytes: Long,
        val unmet: List<UnmetCondition>,
        val notices: Set<PlanNotice>,
    ) : VideoExportOutcome

    data class Failed(val error: VideoExportError) : VideoExportOutcome
}
