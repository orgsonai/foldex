// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit

import androidx.annotation.StringRes
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
    /** 閉じるときに確認を出すか。元に戻せる操作が残っていれば true。 */
    val modified: Boolean = false,
    val noticeVisible: Boolean = true,
    val exportSettings: ExportSettings = ExportSettings(),
    /** 追加した素材を調べている間 true。 */
    val adding: Boolean = false,
)

/** 下部に開く設定パネル。比率以外はクリップを選んでいるときだけ開ける。 */
enum class EditPanel { SPEED, VOLUME, TRANSFORM, TEXT, CANVAS }

sealed interface VideoEditEvent {
    data class Message(@StringRes val resId: Int, val args: List<String> = emptyList()) : VideoEditEvent
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

    fun requestExport()

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
        }
    }
}
