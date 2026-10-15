// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit

import androidx.annotation.StringRes
import com.zerotoship.foldex.ui.videoedit.engine.ExportProgress
import com.zerotoship.foldex.ui.videoedit.engine.VideoExportOutcome
import com.zerotoship.foldex.ui.videoedit.model.ClipRef
import com.zerotoship.foldex.ui.videoedit.model.ExportSettings
import com.zerotoship.foldex.ui.videoedit.model.VideoProject

data class VideoEditUiState(
    val loading: Boolean = true,
    val project: VideoProject = VideoProject(),
    val selection: ClipRef? = null,
    val panel: EditPanel? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** 元に戻せる操作が残っているか。 */
    val modified: Boolean = false,
    val noticeVisible: Boolean = true,
    val exportSettings: ExportSettings = ExportSettings(),
    /** 追加した素材を調べている間 true。 */
    val adding: Boolean = false,
    /** 書き出しの進み具合か結果。書き出していなければ null。 */
    val export: ExportUiState? = null,
    /** 下書きがあるか。作れなかったときは false で、閉じると編集内容は失われる。 */
    val hasDraft: Boolean = false,
    val draftStatus: VideoDraftStatus = VideoDraftStatus.NONE,
    /** 閉じる前の確認 (下書きを残す／削除する) を出すか。 */
    val exitPrompt: Boolean = false,
    /** 下書きの保存か削除を終えて閉じる途中。 */
    val closing: Boolean = false,
)

/** 下部に開く設定パネル。比率以外はクリップを選んでいるときだけ開ける。 */
enum class EditPanel { TIMING, SOURCE_RANGE, SPEED, VOLUME, TRANSFORM, TEXT, CANVAS, LAYER }

enum class VideoDraftStatus { NONE, PENDING, SAVED, FAILED }

sealed interface ExportUiState {
    data class Running(val progress: ExportProgress) : ExportUiState

    data class Finished(val outcome: VideoExportOutcome) : ExportUiState
}

sealed interface VideoEditEvent {
    data class Message(@StringRes val resId: Int, val args: List<String> = emptyList()) : VideoEditEvent

    data object Close : VideoEditEvent

    /** 下書きを開けなかったときなど、知らせてから画面を閉じる。 */
    data class CloseWithMessage(@StringRes val resId: Int) : VideoEditEvent
}

/** 画面から ViewModel への操作。Compose の Preview では [None] を渡す。 */
interface VideoEditActions {
    fun undo()

    fun redo()

    fun select(ref: ClipRef?)

    fun openPanel(panel: EditPanel?)

    fun dismissNotice()

    /** 履歴に 1 手として積む編集。 */
    fun edit(transform: (VideoProject) -> VideoProject)

    /** ドラッグ・スライダー・文字入力の途中の編集。履歴には [commit] で 1 手だけ積む。 */
    fun preview(transform: (VideoProject) -> VideoProject)

    fun commit()

    fun splitAt(timeUs: Long)

    fun deleteSelected()

    fun duplicateSelected()

    fun addText(atUs: Long)

    fun setExportSettings(settings: ExportSettings)

    /** 最初の端末内の素材と同じフォルダへ、新しい名前で書き出す。 */
    fun requestExport()

    fun cancelExport()

    fun dismissExportResult()

    /** 戻る操作。必要なら確認を出し、要らなければそのまま閉じる。 */
    fun requestClose()

    fun closeEditor(keepDraft: Boolean)

    fun dismissExitPrompt()

    companion object {
        val None: VideoEditActions = object : VideoEditActions {
            override fun undo() = Unit
            override fun redo() = Unit
            override fun select(ref: ClipRef?) = Unit
            override fun openPanel(panel: EditPanel?) = Unit
            override fun dismissNotice() = Unit
            override fun edit(transform: (VideoProject) -> VideoProject) = Unit
            override fun preview(transform: (VideoProject) -> VideoProject) = Unit
            override fun commit() = Unit
            override fun splitAt(timeUs: Long) = Unit
            override fun deleteSelected() = Unit
            override fun duplicateSelected() = Unit
            override fun addText(atUs: Long) = Unit
            override fun setExportSettings(settings: ExportSettings) = Unit
            override fun requestExport() = Unit
            override fun cancelExport() = Unit
            override fun dismissExportResult() = Unit
            override fun requestClose() = Unit
            override fun closeEditor(keepDraft: Boolean) = Unit
            override fun dismissExitPrompt() = Unit
        }
    }
}
