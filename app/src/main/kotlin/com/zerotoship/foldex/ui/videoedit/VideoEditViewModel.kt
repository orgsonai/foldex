// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerotoship.foldex.R
import com.zerotoship.foldex.ui.videoedit.engine.MediaProbe
import com.zerotoship.foldex.ui.videoedit.model.CanvasAspect
import com.zerotoship.foldex.ui.videoedit.model.ClipRef
import com.zerotoship.foldex.ui.videoedit.model.ExportSettings
import com.zerotoship.foldex.ui.videoedit.model.ProjectHistory
import com.zerotoship.foldex.ui.videoedit.model.VideoEditOps
import com.zerotoship.foldex.ui.videoedit.model.VideoProject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/**
 * 動画編集 (試作) の状態と操作。
 *
 * 編集の実体は [VideoProject] の差し替えだけで、素材のファイルには一切書き込まない。
 * 試作版は画面の操作感を確かめるためのもので、再生・保存・書き出しは行わない。
 */
@HiltViewModel
class VideoEditViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : ViewModel(), VideoEditActions {

    private val _state = MutableStateFlow(VideoEditUiState())
    val state: StateFlow<VideoEditUiState> = _state.asStateFlow()

    private val _events = Channel<VideoEditEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val history = ProjectHistory()

    /** ドラッグ・スライダー操作を始めた時点のプロジェクト。null なら操作中ではない。 */
    private var gestureBase: VideoProject? = null
    private var loadStarted = false

    /** 起動時に渡された端末内のファイルを、渡された順にメイントラックへ並べる。 */
    fun load(paths: List<String>, names: List<String>) {
        if (loadStarted) return
        loadStarted = true
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                paths.mapIndexed { index, path -> MediaProbe.probe(appContext, path, names.getOrNull(index), newId()) }
            }
            var project = VideoProject()
            results.forEach { result ->
                val asset = result.asset
                if (asset == null) {
                    _events.send(VideoEditEvent.Message(R.string.video_edit_add_failed, listOf(result.name)))
                } else {
                    project = VideoEditOps.appendMedia(project, asset, newId(), project.durationUs)
                }
            }
            // 最初の映像が縦長なら、キャンバスも縦にしておく。
            val first = project.mainTrack.firstOrNull()?.let { project.assets[it.assetId] }
            if (first != null && first.heightPx > first.widthPx) project = project.copy(aspect = CanvasAspect.R9_16)
            _state.update { it.copy(loading = false, project = project) }
        }
    }

    /** ファイル選択で選ばれた素材を追加する。音声は [atUs] (その時点の再生位置) に置く。 */
    fun addSources(sources: List<String>, atUs: Long) {
        if (sources.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(adding = true) }
            val results = withContext(Dispatchers.IO) {
                sources.map { MediaProbe.probe(appContext, it, null, newId()) }
            }
            _state.update { it.copy(adding = false) }
            results.filter { it.asset == null }.forEach {
                _events.send(VideoEditEvent.Message(R.string.video_edit_add_failed, listOf(it.name)))
            }
            val assets = results.mapNotNull { it.asset }
            if (assets.isEmpty()) return@launch
            val clipIds = assets.map { newId() }
            edit { project ->
                assets.foldIndexed(project) { index, acc, asset -> VideoEditOps.appendMedia(acc, asset, clipIds[index], atUs) }
            }
        }
    }

    override fun undo() {
        commit()
        val previous = history.undo(_state.value.project) ?: return
        publish(previous)
    }

    override fun redo() {
        commit()
        val next = history.redo(_state.value.project) ?: return
        publish(next)
    }

    override fun select(ref: ClipRef?) {
        commit()
        _state.update { state ->
            state.copy(
                selection = ref,
                panel = state.panel?.takeIf { panelFits(it, ref) },
            )
        }
    }

    override fun openPanel(panel: EditPanel?) {
        commit()
        _state.update { it.copy(panel = panel?.takeIf { p -> panelFits(p, it.selection) }) }
    }

    override fun dismissNotice() {
        _state.update { it.copy(noticeVisible = false) }
    }

    override fun edit(transform: (VideoProject) -> VideoProject) {
        commit()
        val current = _state.value.project
        val next = transform(current)
        if (next == current) return
        history.record(current)
        publish(next)
    }

    override fun preview(transform: (VideoProject) -> VideoProject) {
        val current = _state.value.project
        if (gestureBase == null) gestureBase = current
        val next = transform(current)
        if (next != current) _state.update { it.copy(project = next) }
    }

    override fun commit() {
        val base = gestureBase ?: return
        gestureBase = null
        val current = _state.value.project
        if (base == current) return
        history.record(base)
        publish(current)
    }

    override fun splitAt(timeUs: Long) {
        commit()
        val state = _state.value
        val selection = state.selection
        val id = newId()
        val after = if (selection is ClipRef.Audio) {
            VideoEditOps.splitAudioAt(state.project, selection.id, timeUs, id)
        } else {
            VideoEditOps.splitMainAt(state.project, timeUs, id)
        }
        if (after == state.project) {
            _events.trySend(VideoEditEvent.Message(R.string.video_edit_split_failed))
            return
        }
        edit { after }
    }

    override fun deleteSelected() {
        val selection = _state.value.selection ?: return
        edit { VideoEditOps.delete(it, selection) }
    }

    override fun duplicateSelected() {
        val selection = _state.value.selection ?: return
        val id = newId()
        edit { VideoEditOps.duplicate(it, selection, id) }
    }

    override fun addText(atUs: Long) {
        val id = newId()
        val text = appContext.getString(R.string.video_edit_default_text)
        edit { VideoEditOps.addText(it, id, atUs, text) }
        _state.update { it.copy(selection = ClipRef.Text(id), panel = EditPanel.TEXT) }
    }

    override fun setExportSettings(settings: ExportSettings) {
        _state.update { it.copy(exportSettings = settings) }
    }

    override fun requestExport() {
        _events.trySend(VideoEditEvent.Message(R.string.video_edit_export_not_ready))
    }

    /** 新しいプロジェクトを反映し、消えたクリップの選択と、合わなくなったパネルを外す。 */
    private fun publish(project: VideoProject) {
        _state.update { state ->
            val selection = state.selection?.takeIf { project.contains(it) }
            state.copy(
                project = project,
                selection = selection,
                panel = state.panel?.takeIf { panelFits(it, selection) },
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                modified = history.canUndo,
            )
        }
    }

    private fun panelFits(panel: EditPanel, selection: ClipRef?): Boolean = when (panel) {
        EditPanel.CANVAS -> true
        EditPanel.SPEED, EditPanel.TRANSFORM -> selection is ClipRef.Main
        EditPanel.VOLUME -> selection is ClipRef.Main || selection is ClipRef.Audio
        EditPanel.TEXT -> selection is ClipRef.Text
    }

    private fun newId(): String = UUID.randomUUID().toString()
}
