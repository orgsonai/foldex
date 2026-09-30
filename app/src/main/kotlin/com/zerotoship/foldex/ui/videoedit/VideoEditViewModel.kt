// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerotoship.foldex.R
import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.ui.videoedit.engine.ExportDestination
import com.zerotoship.foldex.ui.videoedit.engine.ExportProgress
import com.zerotoship.foldex.ui.videoedit.engine.ExportStage
import com.zerotoship.foldex.ui.videoedit.engine.MediaProbe
import com.zerotoship.foldex.ui.videoedit.engine.SourceStamps
import com.zerotoship.foldex.ui.videoedit.engine.VideoExportCoordinator
import com.zerotoship.foldex.ui.videoedit.engine.VideoExportOutcome
import com.zerotoship.foldex.ui.videoedit.model.CanvasAspect
import com.zerotoship.foldex.ui.videoedit.model.ClipRef
import com.zerotoship.foldex.ui.videoedit.model.ClipEditing
import com.zerotoship.foldex.ui.videoedit.model.ExportSettings
import com.zerotoship.foldex.ui.videoedit.model.LayerEditOps
import com.zerotoship.foldex.ui.videoedit.model.ProjectHistory
import com.zerotoship.foldex.ui.videoedit.model.VideoEditOps
import com.zerotoship.foldex.ui.videoedit.model.VideoExportError
import com.zerotoship.foldex.ui.videoedit.model.VideoExportFailure
import com.zerotoship.foldex.ui.videoedit.model.VideoProject
import com.zerotoship.foldex.ui.videoedit.model.VideoSaveNaming
import com.zerotoship.foldex.ui.videoedit.project.VideoDraft
import com.zerotoship.foldex.ui.videoedit.project.VideoDraftFailure
import com.zerotoship.foldex.ui.videoedit.project.VideoProjectStore
import com.zerotoship.foldex.ui.videoedit.project.videoDraftRoot
import com.zerotoship.foldex.ui.videoedit.service.VideoExportService
import com.zerotoship.foldex.ui.videoedit.service.VideoExportSignal
import com.zerotoship.foldex.ui.videoedit.service.VideoExportSignals
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 動画編集 (試作) の状態と操作。
 *
 * 編集の実体は [VideoProject] の差し替えだけで、素材のファイルには一切書き込まない。
 * 下書きは [VideoProjectStore]、書き出しは [VideoExportCoordinator] に任せ、ここは開始・保存の時機・結果の表示を持つ。
 * 音の再生は未実装。
 */
@HiltViewModel
class VideoEditViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val savedState: SavedStateHandle,
) : ViewModel(), VideoEditActions {

    private val _state = MutableStateFlow(VideoEditUiState())
    val state: StateFlow<VideoEditUiState> = _state.asStateFlow()

    private val _events = Channel<VideoEditEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val history = ProjectHistory()

    /** ドラッグ・スライダー操作を始めた時点のプロジェクト。null なら操作中ではない。 */
    private var gestureBase: VideoProject? = null
    private var loadStarted = false

    private val exporter = VideoExportCoordinator(appContext)
    private var exportJob: Job? = null

    /** 通知へ最後に送った進み具合。同じ値を送り直さない。 */
    private var notifiedPercent = Int.MIN_VALUE

    private val draftStore = VideoProjectStore(videoDraftRoot(appContext.filesDir))

    /** 最後に保存を終えた下書き。作れなかったときは null。 */
    private var draft: VideoDraft? = null
    private var draftRevision = 0L
    private var draftSaveJob: Job? = null
    private val draftMutex = Mutex()
    private var closing = false

    // 画面の再生成・プロセスの終了の後も判断が変わらないよう、SavedStateHandle にも持つ。
    /** この画面で新しく作った下書きか。何も編集せずに閉じたら残さない。 */
    private var draftCreatedHere = savedState.get<Boolean>(CREATED_HERE) == true

    /** 編集か書き出し設定の変更をしたか。 */
    private var editedInSession = savedState.get<Boolean>(EDITED) == true

    init {
        // 通知の「取消」と、Android 15 以降の前景サービスの時間上限。書き出し中の画面だけが反応する。
        viewModelScope.launch {
            VideoExportSignals.events.collect { signal ->
                when (signal) {
                    VideoExportSignal.CANCEL_REQUESTED -> cancelExport()
                    VideoExportSignal.TIME_LIMIT ->
                        stopExport(VideoExportOutcome.Failed(VideoExportError(VideoExportFailure.TIME_LIMIT)))
                }
            }
        }
    }

    /**
     * 最初の読み込み。[draftId] があれば下書きを開き、無ければ [paths] の端末内のファイルを、渡された順にメイントラックへ並べる。
     * 画面が作り直されたときは、前に作った下書きを開き直す。
     */
    fun load(paths: List<String>, names: List<String>, draftId: String?) {
        if (loadStarted) return
        loadStarted = true
        viewModelScope.launch {
            if (withContext(Dispatchers.IO) { VideoExportCoordinator.recoverInterrupted(appContext) }) {
                _events.send(VideoEditEvent.Message(R.string.video_edit_export_interrupted))
            }
            val id = draftId ?: savedState.get<String>(DRAFT_ID)
            if (id != null) openDraft(id) else createProject(paths, names)
        }
    }

    private suspend fun openDraft(id: String) {
        when (val result = withContext(Dispatchers.IO) { draftStore.open(id) }) {
            is Result.Failure -> _events.send(VideoEditEvent.CloseWithMessage(draftOpenError(result.error)))
            is Result.Success -> {
                val opened = result.value.draft
                savedState[DRAFT_ID] = opened.id
                draftRevision = withContext(Dispatchers.IO) { draftStore.nextRevision(opened.id, opened.revision) } - 1
                installDraft(opened)
                _state.update { it.copy(loading = false, project = opened.project, exportSettings = opened.exportSettings) }
                if (result.value.recoveredPrevious) _events.send(VideoEditEvent.Message(R.string.video_draft_recovered))
                // 再リンクは未実装なので、欠けている素材があることだけを先に知らせる。書き出しは開始前に止まる。
                val missing = withContext(Dispatchers.IO) {
                    opened.project.assets.values.count { SourceStamps.read(appContext, it.source) == null }
                }
                if (missing > 0) _events.send(VideoEditEvent.Message(R.string.video_draft_missing_sources, listOf(missing.toString())))
            }
        }
    }

    private suspend fun createProject(paths: List<String>, names: List<String>) {
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

        val record = VideoDraft(newId(), 0L, System.currentTimeMillis(), draftName(project), project, _state.value.exportSettings)
        // 作成の途中で画面が作り直されても、2 つ目の下書きを作らないよう先に ID を残す。
        savedState[DRAFT_ID] = record.id
        savedState[CREATED_HERE] = true
        draftCreatedHere = true
        when (val created = withContext(Dispatchers.IO) { draftStore.create(record) }) {
            is Result.Success -> {
                draftRevision = 0L
                installDraft(created.value)
                // 作成を待つ間の編集を取りこぼさない。
                val current = _state.value
                if (current.project != record.project || current.exportSettings != record.exportSettings) scheduleDraft()
            }
            is Result.Failure -> {
                savedState.remove<String>(DRAFT_ID)
                _state.update { it.copy(draftStatus = VideoDraftStatus.FAILED) }
                _events.send(VideoEditEvent.Message(R.string.video_draft_create_failed))
            }
        }
    }

    private fun installDraft(record: VideoDraft) {
        draft = record
        _state.update { it.copy(hasDraft = true, draftStatus = VideoDraftStatus.SAVED) }
    }

    /** ファイル選択で選ばれた素材を追加する。音声は [atUs] (その時点の再生位置) に置く。 */
    fun addSources(sources: List<String>, atUs: Long, asLayer: Boolean = false) {
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
                assets.foldIndexed(project) { index, acc, asset ->
                    if (asLayer) LayerEditOps.add(acc, asset, clipIds[index], atUs)
                    else VideoEditOps.appendMedia(acc, asset, clipIds[index], atUs)
                }
            }
            if (asLayer) {
                select(ClipRef.Layer(clipIds.last()))
                openPanel(EditPanel.LAYER)
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
        val ref = selection ?: state.project.mainClipAt(timeUs)?.let { (index, _) -> ClipRef.Main(state.project.mainTrack[index].id) }
        val after = if (ref == null) state.project else ClipEditing.split(state.project, ref, timeUs, id)
        if (after == state.project) {
            _events.trySend(VideoEditEvent.Message(R.string.video_edit_split_failed))
            return
        }
        edit { after }
        select(when (selection) {
            is ClipRef.Layer -> ClipRef.Layer(id)
            is ClipRef.Audio -> ClipRef.Audio(id)
            is ClipRef.Text -> ClipRef.Text(id)
            else -> ClipRef.Main(id)
        })
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
        if (settings == _state.value.exportSettings) return
        _state.update { it.copy(exportSettings = settings) }
        markEdited()
        scheduleDraft()
    }

    // ---- 書き出し ----

    override fun requestExport() {
        val project = _state.value.project
        val folder = VideoSaveNaming.localFolder(project) ?: return
        val baseName = VideoSaveNaming.baseName(VideoSaveNaming.sourceAsset(project)?.name.orEmpty())
        startExport(ExportDestination.LocalFolder(File(folder), baseName))
    }

    /** 「場所を選んで書き出す」で作られた文書へ書き出す。 */
    fun exportToDocument(uri: Uri) {
        startExport(ExportDestination.Document(uri))
    }

    override fun cancelExport() {
        if (exportJob?.isActive != true) return
        stopExport(null)
        _events.trySend(VideoEditEvent.Message(R.string.video_edit_export_cancelled))
    }

    override fun dismissExportResult() {
        _state.update { if (it.export is ExportUiState.Finished) it.copy(export = null) else it }
    }

    private fun startExport(destination: ExportDestination) {
        if (exportJob?.isActive == true) return
        commit()
        val project = _state.value.project
        val settings = _state.value.exportSettings
        _state.update { it.copy(export = ExportUiState.Running(ExportProgress(ExportStage.CHECKING))) }
        notifiedPercent = Int.MIN_VALUE
        VideoExportService.start(appContext)
        exportJob = viewModelScope.launch {
            try {
                val outcome = exporter.export(project, settings, destination) { progress ->
                    _state.update { state ->
                        if (state.export is ExportUiState.Running) state.copy(export = ExportUiState.Running(progress)) else state
                    }
                    val percent = progress.percent ?: -1
                    if (percent != notifiedPercent) {
                        notifiedPercent = percent
                        VideoExportService.progress(appContext, percent)
                    }
                }
                // 取り消した直後に結果が返ってきても、取り消した表示を上書きしない。
                if (isActive) _state.update { it.copy(export = ExportUiState.Finished(outcome)) }
            } finally {
                VideoExportService.stop(appContext)
            }
        }
    }

    /** 書き出しを止め、[outcome] があれば結果として見せる。 */
    private fun stopExport(outcome: VideoExportOutcome?) {
        val job = exportJob?.takeIf { it.isActive } ?: return
        job.cancel()
        _state.update { state -> state.copy(export = outcome?.let { ExportUiState.Finished(it) }) }
    }

    // ---- 下書き ----

    /** 画面が裏に回ったとき。途中の操作を確定し、1 秒を待たずに保存する。 */
    fun flushDraftOnBackground() {
        if (closing || draft == null) return
        commit()
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch { persistDraft() }
    }

    override fun requestClose() {
        if (closing || _state.value.export is ExportUiState.Running) return
        commit()
        when {
            _state.value.loading -> _events.trySend(VideoEditEvent.Close)
            // 下書きを作れていないとき、編集していなければ失うものは無い。
            draft == null -> if (editedInSession) _state.update { it.copy(exitPrompt = true) } else _events.trySend(VideoEditEvent.Close)
            // 開いただけで何もしていない新しい下書きは、一覧に残さない。
            !editedInSession -> closeEditor(keepDraft = !draftCreatedHere)
            else -> _state.update { it.copy(exitPrompt = true) }
        }
    }

    override fun dismissExitPrompt() {
        _state.update { it.copy(exitPrompt = false) }
    }

    override fun closeEditor(keepDraft: Boolean) {
        if (closing) return
        closing = true
        draftSaveJob?.cancel()
        _state.update { it.copy(exitPrompt = false, closing = true) }
        viewModelScope.launch {
            val record = draft
            val okay = when {
                record == null -> true
                keepDraft -> persistDraft().also { saved ->
                    // 一覧からすぐ開き直せるよう、使用中の印を外す。
                    if (saved) withContext(Dispatchers.IO) { draftStore.release(record.id) }
                }
                else -> draftMutex.withLock { withContext(Dispatchers.IO) { draftStore.discard(record.id) }.isSuccess }
            }
            if (okay) {
                if (!keepDraft) savedState.remove<String>(DRAFT_ID)
                _events.send(VideoEditEvent.Close)
            } else {
                closing = false
                _state.update { it.copy(closing = false) }
                _events.send(VideoEditEvent.Message(R.string.video_draft_close_failed))
            }
        }
    }

    private fun scheduleDraft() {
        if (closing || draft == null) return
        draftSaveJob?.cancel()
        _state.update { it.copy(draftStatus = VideoDraftStatus.PENDING) }
        draftSaveJob = viewModelScope.launch {
            delay(DRAFT_SAVE_DELAY_MS)
            persistDraft()
        }
    }

    /** 今の内容を下書きに保存する。保存の途中で画面が消えても、書き込みは最後まで行う。 */
    private suspend fun persistDraft(): Boolean = draftMutex.withLock {
        val record = draft ?: return@withLock false
        val current = _state.value
        val name = draftName(current.project)
        if (record.project == current.project && record.exportSettings == current.exportSettings && record.name == name) {
            _state.update { it.copy(draftStatus = VideoDraftStatus.SAVED) }
            return@withLock true
        }
        val next = record.copy(
            revision = ++draftRevision,
            updatedAt = System.currentTimeMillis(),
            name = name,
            project = current.project,
            exportSettings = current.exportSettings,
        )
        when (val result = withContext(NonCancellable + Dispatchers.IO) { draftStore.save(next) }) {
            is Result.Success -> {
                draft = next
                // 保存している間に次の変更があれば、「保存中」のままにする。
                val latest = _state.value
                if (latest.project == next.project && latest.exportSettings == next.exportSettings) {
                    _state.update { it.copy(draftStatus = VideoDraftStatus.SAVED) }
                }
                true
            }
            is Result.Failure -> {
                _state.update { it.copy(draftStatus = VideoDraftStatus.FAILED) }
                false
            }
        }
    }

    private fun markEdited() {
        if (editedInSession) return
        editedInSession = true
        savedState[EDITED] = true
    }

    private fun draftName(project: VideoProject): String =
        VideoSaveNaming.sourceAsset(project)?.name ?: appContext.getString(R.string.video_edit_title)

    private fun draftOpenError(reason: VideoDraftFailure): Int = when (reason) {
        VideoDraftFailure.BUSY -> R.string.video_draft_open_busy
        VideoDraftFailure.UNSUPPORTED -> R.string.video_draft_open_unsupported
        VideoDraftFailure.CORRUPT -> R.string.video_draft_open_corrupt
        VideoDraftFailure.IO, VideoDraftFailure.CONFLICT, VideoDraftFailure.TOO_LARGE -> R.string.video_draft_open_failed
    }

    /** 新しいプロジェクトを反映し、消えたクリップの選択と、合わなくなったパネルを外す。確定した編集なので下書きの保存を予約する。 */
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
        markEdited()
        scheduleDraft()
    }

    private fun panelFits(panel: EditPanel, selection: ClipRef?): Boolean = when (panel) {
        EditPanel.TIMING -> selection != null
        EditPanel.CANVAS -> true
        EditPanel.TRANSFORM -> selection is ClipRef.Main || selection is ClipRef.Layer
        EditPanel.LAYER -> selection is ClipRef.Layer
        EditPanel.SOURCE_RANGE, EditPanel.SPEED -> selection is ClipRef.Main || selection is ClipRef.Layer
        EditPanel.VOLUME -> selection is ClipRef.Main || selection is ClipRef.Audio || selection is ClipRef.Layer
        EditPanel.TEXT -> selection is ClipRef.Text
    }

    private fun newId(): String = UUID.randomUUID().toString()

    override fun onCleared() {
        draftSaveJob?.cancel()
        // 保存中ならその完了を待ってから使用中の印を外し、一覧から開けるようにする。
        CoroutineScope(Dispatchers.IO).launch { draftMutex.withLock { draftStore.close() } }
        super.onCleared()
    }

    private companion object {
        const val DRAFT_ID = "video_draft_id"
        const val CREATED_HERE = "video_draft_created_here"
        const val EDITED = "video_draft_edited"
        const val DRAFT_SAVE_DELAY_MS = 1_000L
    }
}
