// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zerotoship.foldex.R
import com.zerotoship.foldex.ui.videoedit.model.AssetKind
import com.zerotoship.foldex.ui.videoedit.model.ClipRef
import com.zerotoship.foldex.ui.videoedit.model.ClipEditing
import com.zerotoship.foldex.ui.videoedit.engine.PreviewStatus
import com.zerotoship.foldex.ui.videoedit.model.ExportPreset
import com.zerotoship.foldex.ui.videoedit.model.FrameRate
import com.zerotoship.foldex.ui.videoedit.model.LayerEditOps
import com.zerotoship.foldex.ui.videoedit.model.VideoEditOps
import com.zerotoship.foldex.ui.videoedit.model.VideoSaveNaming
import com.zerotoship.foldex.ui.videoedit.model.formatClock
import com.zerotoship.foldex.ui.videoedit.model.formatTimecode
import com.zerotoship.foldex.ui.videoedit.panel.EditPanelHost
import com.zerotoship.foldex.ui.videoedit.panel.ExportProgressDialog
import com.zerotoship.foldex.ui.videoedit.panel.ExportResultDialog
import com.zerotoship.foldex.ui.videoedit.panel.ExportSheet
import com.zerotoship.foldex.ui.videoedit.timeline.TimelineState
import com.zerotoship.foldex.ui.videoedit.timeline.TimelineView

/**
 * 動画編集。プレビューと書き出しに同じ描画計画を使う。
 *
 * 縦画面は上から: トップバー → プレビュー → 時刻と再生の行 → タイムライン → 設定パネル → 操作列。
 * 横画面はプレビューの右に設定パネルと操作列を置き、タイムラインは下に全幅で置く。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoEditScreen(
    state: VideoEditUiState,
    actions: VideoEditActions,
    snackbar: SnackbarHostState,
    onAddMedia: (AssetKind, Long, Boolean) -> Unit,
    /** 「場所を選んで書き出す」。引数は最初に出す保存名。 */
    onExportElsewhere: (String) -> Unit,
    timeline: TimelineState = remember { TimelineState() },
) {
    val project = state.project
    val durationUs = project.durationUs
    val preview = if (LocalInspectionMode.current) null else rememberPreviewController(project, timeline,
        allowed = state.export !is ExportUiState.Running && !state.exitPrompt && !state.closing)
    val playing = preview?.playing == true
    var showExport by remember { mutableStateOf(false) }
    var showSeek by remember { mutableStateOf(false) }
    var timelineExpanded by remember { mutableStateOf(false) }
    val panelScroll = key(state.panel, state.selection) { rememberScrollState() }

    fun seek(timeUs: Long) {
        preview?.pause()
        timeline.playheadUs = timeUs.coerceIn(0L, durationUs)
        preview?.seek(timeline.playheadUs)
    }

    LaunchedEffect(durationUs, timeline.scrubbing) {
        // トリム中に表示位置を動かすと、つかんだ端が指から逃げる。確定後に範囲へ戻す。
        if (!timeline.scrubbing && timeline.playheadUs > durationUs) timeline.playheadUs = durationUs
    }
    LaunchedEffect(state.loading) {
        if (!state.loading) timeline.requestFitAll()
    }
    BackHandler {
        when {
            state.panel != null -> actions.openPanel(null)
            timelineExpanded -> timelineExpanded = false
            state.selection != null -> actions.select(null)
            else -> actions.requestClose()
        }
    }

    @Composable
    fun PreviewArea(modifier: Modifier) {
        VideoPreview(
            project = project,
            timeline = timeline,
            coarse = playing || timeline.scrubbing,
            selectedTextId = (state.selection as? ClipRef.Text)?.id,
            onSelectText = { id -> preview?.pause(); actions.select(ClipRef.Text(id)) },
            onMoveText = { id, dx, dy ->
                actions.preview { current ->
                    VideoEditOps.updateText(current, id) { text ->
                        text.copy(
                            centerX = (text.centerX + dx).coerceIn(0f, 1f),
                            centerY = (text.centerY + dy).coerceIn(0f, 1f),
                        )
                    }
                }
            },
            onMoveTextEnd = actions::commit,
            selectedLayerId = (state.selection as? ClipRef.Layer)?.id,
            onSelectLayer = { preview?.pause(); actions.select(ClipRef.Layer(it)) },
            onMoveLayer = { id, dx, dy ->
                actions.preview { current -> LayerEditOps.update(current, id) { layer ->
                    layer.copy(centerX = (layer.centerX + dx).coerceIn(0f, 1f), centerY = (layer.centerY + dy).coerceIn(0f, 1f))
                } }
            },
            renderedPreview = if (preview != null && project.mainTrack.isNotEmpty() && preview.matches(project) &&
                preview.status != PreviewStatus.EMPTY && preview.status != PreviewStatus.FAILED &&
                timeline.playheadUs <= preview.durationUs) ({ CompositionPreview(preview) }) else null,
            modifier = modifier,
        )
    }

    @Composable
    fun Transport() {
        TransportBar(
            timeline = timeline,
            durationUs = durationUs,
            rate = project.frameRate,
            playing = playing,
            onStep = { frames ->
                preview?.pause()
                timeline.stepFrames(frames, project.frameRate, durationUs)
                preview?.seek(timeline.playheadUs)
            },
            onPlayPause = { preview?.togglePlay() },
            playEnabled = preview?.status == PreviewStatus.READY && preview.matches(project),
            onBoundary = { forward -> seek(ClipEditing.neighborBoundary(project, timeline.playheadUs, forward)) },
            onSeekDialog = { preview?.pause(); showSeek = true },
            loop = preview?.loop == true,
            onLoop = { preview?.updateLoop(it) },
        )
    }

    @Composable
    fun Timeline() {
        TimelineView(
            project = project,
            selection = state.selection,
            state = timeline,
            emptyHint = stringResource(R.string.video_edit_empty_hint),
            onSelect = actions::select,
            onPreview = actions::preview,
            onCommit = actions::commit,
            onEdit = actions::edit,
            onSplitAtPlayhead = { actions.splitAt(timeline.playheadUs) },
            onInteraction = { preview?.pause() },
        )
    }

    @Composable
    fun Panel() {
        EditPanelHost(project = project, selection = state.selection, panel = state.panel, actions = actions,
            playheadUs = timeline.playheadUs, onSeek = ::seek)
    }

    @Composable
    fun Toolbar() {
        ClipSelectionInfo(project, state.selection, ::seek)
        VideoEditToolbar(
            selection = state.selection,
            sourceRangeAvailable = (project.mainTrack.firstOrNull { it.id == state.selection?.id }
                ?: project.layers.firstOrNull { it.id == state.selection?.id }?.clip)
                ?.let { project.assets[it.assetId]?.kind == AssetKind.VIDEO } == true,
            audioAvailable = state.selection is ClipRef.Audio || (project.mainTrack.firstOrNull { it.id == state.selection?.id }
                ?: project.layers.firstOrNull { it.id == state.selection?.id }?.clip)?.let { project.assets[it.assetId]?.hasAudio } == true,
            canCut = ClipEditing.canCut(project, state.selection, timeline.playheadUs),
            activePanel = state.panel,
            enabled = !state.loading && !state.adding && !state.closing,
            onAction = { action ->
                preview?.pause()
                val atUs = timeline.playheadUs
                when (action) {
                    ToolAction.ADD_VIDEO -> onAddMedia(AssetKind.VIDEO, atUs, false)
                    ToolAction.ADD_IMAGE -> onAddMedia(AssetKind.IMAGE, atUs, false)
                    ToolAction.ADD_AUDIO -> onAddMedia(AssetKind.AUDIO, atUs, false)
                    ToolAction.ADD_LAYER_VIDEO -> onAddMedia(AssetKind.VIDEO, atUs, true)
                    ToolAction.ADD_LAYER_IMAGE -> onAddMedia(AssetKind.IMAGE, atUs, true)
                    ToolAction.LAYER -> actions.openPanel(togglePanel(state.panel, EditPanel.LAYER))
                    ToolAction.ADD_TEXT -> actions.addText(atUs)
                    ToolAction.CANVAS -> actions.openPanel(togglePanel(state.panel, EditPanel.CANVAS))
                    ToolAction.SPLIT -> actions.splitAt(atUs)
                    ToolAction.TIMING -> actions.openPanel(togglePanel(state.panel, EditPanel.TIMING))
                    ToolAction.TRIM_BEFORE, ToolAction.TRIM_AFTER -> state.selection?.let { ref ->
                        actions.edit { ClipEditing.trimToPlayhead(it, ref, atUs, action == ToolAction.TRIM_BEFORE) }
                        if (action == ToolAction.TRIM_BEFORE && ref is ClipRef.Main) {
                            ClipEditing.range(state.project, ref)?.let { seek(it.first) }
                        }
                    }
                    ToolAction.SPEED -> actions.openPanel(togglePanel(state.panel, EditPanel.SPEED))
                    ToolAction.SOURCE_RANGE -> {
                        if (state.panel != EditPanel.SOURCE_RANGE) {
                            (state.selection as? ClipRef.Layer)?.let { ref ->
                                project.layers.firstOrNull { it.id == ref.id }?.let { timeline.playheadUs = it.startUs }
                            }
                            (state.selection as? ClipRef.Main)?.let { ref ->
                                val index = project.mainIndexOf(ref.id)
                                if (index >= 0) timeline.playheadUs = project.mainStartUs(index)
                            }
                        }
                        actions.openPanel(togglePanel(state.panel, EditPanel.SOURCE_RANGE))
                    }
                    ToolAction.VOLUME -> actions.openPanel(togglePanel(state.panel, EditPanel.VOLUME))
                    ToolAction.TRANSFORM -> actions.openPanel(togglePanel(state.panel, EditPanel.TRANSFORM))
                    ToolAction.EDIT_TEXT -> actions.openPanel(togglePanel(state.panel, EditPanel.TEXT))
                    ToolAction.DUPLICATE -> actions.duplicateSelected()
                    ToolAction.DELETE -> actions.deleteSelected()
                    ToolAction.DESELECT -> actions.select(null)
                }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.video_edit_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!state.loading) {
                            val summary = stringResource(R.string.video_edit_summary, project.clipCount, formatClock(durationUs))
                            val status = draftStatusText(state.draftStatus)
                            Text(
                                if (status == null) summary else "$summary · $status",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (state.draftStatus == VideoDraftStatus.FAILED) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = actions::requestClose, enabled = !state.closing) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_back))
                    }
                },
                actions = {
                    IconButton(onClick = actions::undo, enabled = state.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.action_undo))
                    }
                    IconButton(onClick = actions::redo, enabled = state.canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.action_redo))
                    }
                    TextButton(
                        onClick = {
                            preview?.pause()
                            showExport = true
                        },
                        enabled = !state.loading && durationUs > 0L && state.export == null,
                    ) { Text(stringResource(R.string.video_edit_export)) }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            val landscape = maxWidth > maxHeight
            val panelMaxHeight = maxHeight * 0.3f
            val timelineMaxHeight = maxHeight * if (state.panel != null) 0.15f else if (landscape) 0.22f else 0.32f
            Column(Modifier.fillMaxSize()) {
                if (state.adding || preview?.status == PreviewStatus.PREPARING) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (preview?.status == PreviewStatus.FAILED) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.video_edit_preview_failed), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        TextButton(onClick = { preview.retry(timeline.playheadUs) }) { Text(stringResource(R.string.video_edit_preview_retry)) }
                    }
                }
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    landscape && !timelineExpanded -> {
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            Column(Modifier.weight(1f).fillMaxHeight()) {
                                PreviewArea(Modifier.weight(1f).fillMaxWidth())
                                Transport()
                            }
                            VerticalDivider()
                            Column(Modifier.width(320.dp).fillMaxHeight()) {
                                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(panelScroll)) { Panel() }
                                HorizontalDivider()
                                Toolbar()
                            }
                        }
                        HorizontalDivider()
                        TimelineControls(timeline, project.frameRate, timelineExpanded, { timelineExpanded = !timelineExpanded })
                        if (state.noticeVisible && state.panel == null && state.selection == null) EditingHint(timeline.movingClips, actions::dismissNotice)
                        Column(Modifier.fillMaxWidth().heightIn(max = timelineMaxHeight).verticalScroll(rememberScrollState())) { Timeline() }
                    }
                    else -> {
                        if (!timelineExpanded) PreviewArea(Modifier.weight(1f).fillMaxWidth())
                        Transport()
                        HorizontalDivider()
                        TimelineControls(timeline, project.frameRate, timelineExpanded, { timelineExpanded = !timelineExpanded })
                        if (state.noticeVisible && state.panel == null && state.selection == null) EditingHint(timeline.movingClips, actions::dismissNotice)
                        Column(Modifier.fillMaxWidth()
                            .then(if (timelineExpanded) Modifier.weight(1f) else Modifier.heightIn(max = timelineMaxHeight))
                            .verticalScroll(rememberScrollState())) { Timeline() }
                        if (state.panel != null) {
                            HorizontalDivider()
                            Column(Modifier.fillMaxWidth().heightIn(max = panelMaxHeight).verticalScroll(panelScroll)) {
                                Panel()
                            }
                        }
                        HorizontalDivider()
                        Toolbar()
                    }
                }
            }
        }
    }

    if (showSeek) SeekPositionDialog(timeline.playheadUs, durationUs, project.frameRate, ::seek, { showSeek = false })

    if (showExport) {
        val audioOnly = state.exportSettings.preset == ExportPreset.AUDIO_ONLY
        ExportSheet(
            settings = state.exportSettings,
            project = project,
            exportFolder = remember(project) { VideoSaveNaming.localFolder(project) },
            onChange = actions::setExportSettings,
            onExportHere = {
                showExport = false
                actions.requestExport()
            },
            onExportElsewhere = {
                showExport = false
                onExportElsewhere(VideoSaveNaming.suggestedName(project, audioOnly))
            },
            onDismiss = { showExport = false },
        )
    }

    when (val export = state.export) {
        is ExportUiState.Running -> ExportProgressDialog(progress = export.progress, onCancel = actions::cancelExport)
        is ExportUiState.Finished -> ExportResultDialog(outcome = export.outcome, onDismiss = actions::dismissExportResult)
        null -> Unit
    }

    if (state.exitPrompt) {
        VideoEditExitDialog(
            canKeepDraft = state.hasDraft,
            onKeep = { actions.closeEditor(keepDraft = true) },
            onDiscard = { actions.closeEditor(keepDraft = false) },
            onDismiss = actions::dismissExitPrompt,
        )
    }
}

private fun togglePanel(current: EditPanel?, target: EditPanel): EditPanel? = if (current == target) null else target

@Composable
private fun draftStatusText(status: VideoDraftStatus): String? = when (status) {
    VideoDraftStatus.NONE -> null
    VideoDraftStatus.PENDING -> stringResource(R.string.video_draft_pending)
    VideoDraftStatus.SAVED -> stringResource(R.string.video_draft_saved)
    VideoDraftStatus.FAILED -> stringResource(R.string.video_draft_failed)
}

@Composable
private fun EditingHint(movingClips: Boolean, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (movingClips) R.string.video_edit_drag_hint else R.string.video_edit_scroll_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
            }
        }
    }
}

/** 時刻と、1 コマ送り・再生・全体表示の行。 */
@Composable
internal fun TransportBar(
    timeline: TimelineState,
    durationUs: Long,
    rate: FrameRate,
    playing: Boolean,
    onStep: (Int) -> Unit,
    onPlayPause: () -> Unit,
    playEnabled: Boolean,
    onBoundary: (Boolean) -> Unit,
    onSeekDialog: () -> Unit,
    loop: Boolean,
    onLoop: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasContent = durationUs > 0L
    @Composable
    fun Buttons() {
        IconButton(onClick = { onBoundary(false) }, enabled = hasContent) {
            Icon(Icons.Default.SkipPrevious, contentDescription = stringResource(R.string.video_edit_previous_cut))
        }
        IconButton(onClick = { onStep(-1) }, enabled = hasContent) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.video_edit_frame_back))
        }
        IconButton(onClick = onPlayPause, enabled = hasContent && playEnabled) {
            if (playing) {
                Icon(Icons.Default.Pause, contentDescription = stringResource(R.string.video_edit_pause))
            } else {
                Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.video_edit_play))
            }
        }
        IconButton(onClick = { onStep(1) }, enabled = hasContent) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.video_edit_frame_forward))
        }
        IconButton(onClick = { onBoundary(true) }, enabled = hasContent) {
            Icon(Icons.Default.SkipNext, contentDescription = stringResource(R.string.video_edit_next_cut))
        }
        IconToggleButton(checked = loop, onCheckedChange = onLoop, enabled = hasContent) {
            Icon(Icons.Default.Repeat, contentDescription = stringResource(R.string.video_edit_loop))
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth >= 520.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onSeekDialog, enabled = hasContent, modifier = Modifier.weight(1f)) {
                    Timecode(timeline, durationUs, rate)
                }
                Buttons()
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                TextButton(onClick = onSeekDialog, enabled = hasContent, modifier = Modifier.fillMaxWidth()) {
                    Timecode(timeline, durationUs, rate)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { Buttons() }
            }
        }
    }
}

/** 再生位置が変わったときに、この表示だけを描き直す。 */
@Composable
private fun Timecode(timeline: TimelineState, durationUs: Long, rate: FrameRate, modifier: Modifier = Modifier) {
    Text(
        text = formatTimecode(timeline.playheadUs, rate) + " / " + formatTimecode(durationUs, rate),
        style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** 長い選択肢を同じ幅で縦に並べ、狭い画面や大きな文字でも崩れないようにする (画像編集と同じ作り)。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoEditExitDialog(canKeepDraft: Boolean, onKeep: () -> Unit, onDiscard: () -> Unit, onDismiss: () -> Unit) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.video_edit_exit_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 8.dp).semantics { heading() },
                )
                Text(
                    stringResource(if (canKeepDraft) R.string.video_edit_exit_body else R.string.video_edit_exit_body_unsaved),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                val actionModifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                if (canKeepDraft) {
                    Button(onClick = onKeep, modifier = actionModifier, shape = MaterialTheme.shapes.small) {
                        Text(stringResource(R.string.video_edit_exit_keep), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                    TextButton(onClick = onDismiss, modifier = actionModifier) {
                        Text(stringResource(R.string.video_edit_exit_back), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                } else {
                    Button(onClick = onDismiss, modifier = actionModifier, shape = MaterialTheme.shapes.small) {
                        Text(stringResource(R.string.video_edit_exit_back), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                }
                TextButton(
                    onClick = onDiscard,
                    modifier = actionModifier,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(
                        stringResource(if (canKeepDraft) R.string.video_edit_exit_discard_draft else R.string.video_edit_exit_discard),
                        Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Preview(name = "Transport", showBackground = true, widthDp = 360, locale = "ja")
@Composable
private fun TransportBarPreview() {
    MaterialTheme {
        TransportBar(
            timeline = remember { TimelineState() },
            durationUs = 90_000_000L,
            rate = FrameRate.FPS_30,
            playing = false,
            onStep = {},
            onPlayPause = {},
            playEnabled = true,
            onBoundary = {},
            onSeekDialog = {},
            loop = false,
            onLoop = {},
        )
    }
}

@Preview(name = "Exit dialog large text", showBackground = true, widthDp = 320, heightDp = 480, fontScale = 2f, locale = "ja")
@Composable
private fun VideoEditExitDialogPreview() {
    MaterialTheme { VideoEditExitDialog(canKeepDraft = true, onKeep = {}, onDiscard = {}, onDismiss = {}) }
}
