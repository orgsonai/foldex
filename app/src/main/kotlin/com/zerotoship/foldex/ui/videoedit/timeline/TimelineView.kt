// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.timeline

import android.media.MediaMetadataRetriever
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.video.videoFrameMicros
import coil3.video.videoFrameOption
import com.zerotoship.foldex.R
import com.zerotoship.foldex.ui.videoedit.model.AssetKind
import com.zerotoship.foldex.ui.videoedit.model.AudioClip
import com.zerotoship.foldex.ui.videoedit.model.ClipRef
import com.zerotoship.foldex.ui.videoedit.model.FrameRate
import com.zerotoship.foldex.ui.videoedit.model.LayerEditOps
import com.zerotoship.foldex.ui.videoedit.model.US_PER_SECOND
import com.zerotoship.foldex.ui.videoedit.model.VideoEditOps
import com.zerotoship.foldex.ui.videoedit.model.VideoProject
import com.zerotoship.foldex.ui.videoedit.model.formatClock
import com.zerotoship.foldex.ui.videoedit.model.formatTimecode
import com.zerotoship.foldex.ui.videoedit.sourceModel
import com.zerotoship.foldex.ui.videoedit.engine.AudioWaveforms
import com.zerotoship.foldex.ui.videoedit.engine.WaveformChunk
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest

private val RULER_HEIGHT = 22.dp
private val MAIN_HEIGHT = 56.dp
private val TEXT_HEIGHT = 28.dp
private val AUDIO_HEIGHT = 36.dp
private val LANE_GAP = 4.dp
private val THUMB_WIDTH = 36.dp
private val HANDLE_WIDTH = 14.dp
private val HANDLE_TOUCH = 28.dp
private val SNAP_DISTANCE = 10.dp
private val EDGE_SCROLL = 48.dp
private val EDGE_STEP = 6.dp
private val RULER_MIN_SPACING = 72.dp
private const val MIN_FLING_VELOCITY = 300f
private const val THUMB_BUCKET_US = 100_000L
private const val THUMB_REQUEST_PX = 256

/**
 * タイムライン。再生位置は中央に固定し、タイムラインの方を動かす。
 *
 * 操作:
 *  - 1 本指の横ドラッグ = スクロール (離すと慣性で進む)。目盛りをタップ = その位置へ移動
 *  - 2 本指のピンチ = 時間軸の拡大縮小
 *  - タップ = クリップの選択 / 解除
 *  - 選んだクリップの端 = トリム (再生位置などに吸着し、軽い触覚で知らせる)
 *  - クリップを長押ししてドラッグ (移動モードでは選択中の中央をドラッグ) = 並べ替え・時間移動
 *
 * クリップ数に比例した Composable は作らない。描画は Canvas、サムネイルは見えている分だけ置く。
 */
@Composable
fun TimelineView(
    project: VideoProject,
    selection: ClipRef?,
    state: TimelineState,
    emptyHint: String,
    onSelect: (ClipRef?) -> Unit,
    onPreview: ((VideoProject) -> VideoProject) -> Unit,
    onCommit: () -> Unit,
    onEdit: ((VideoProject) -> VideoProject) -> Unit,
    onSplitAtPlayhead: () -> Unit,
    onInteraction: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val textIntervals = project.texts.map { LaneInterval(it.id, it.startUs, it.endUs) }
    val audioIntervals = project.audioTrack.map { LaneInterval(it.id, it.startUs, it.endUs) }
    var stableIntervals by remember { mutableStateOf(textIntervals to audioIntervals) }
    // 端をつかんでいる最中に段を詰め直すと、クリップが指から逃げる。指を離してから再配置する。
    LaunchedEffect(textIntervals, audioIntervals, state.scrubbing) {
        if (!state.scrubbing) stableIntervals = textIntervals to audioIntervals
    }
    val lanes = remember(density, stableIntervals, project.layers.map { it.id }) {
        density.lanesFor(stableIntervals.first, stableIntervals.second, project.layers.map { it.id })
    }
    val heightDp = with(density) { lanes.height.toDp() }
    var widthPx by remember { mutableFloatStateOf(0f) }
    var reorder by remember { mutableStateOf<ReorderDrag?>(null) }
    var startTrim by remember { mutableStateOf<MainStartTrim?>(null) }
    var snapLineUs by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    val fling = remember { JobHolder() }
    val haptics = LocalHapticFeedback.current
    val latestProject = rememberUpdatedState(project)
    val latestSelection = rememberUpdatedState(selection)
    val latestOnSelect = rememberUpdatedState(onSelect)
    val latestOnPreview = rememberUpdatedState(onPreview)
    val latestOnCommit = rememberUpdatedState(onCommit)
    val latestOnEdit = rememberUpdatedState(onEdit)
    val latestOnInteraction = rememberUpdatedState(onInteraction)
    val context = LocalContext.current
    val waveforms = remember(project.assets) { mutableStateMapOf<Pair<String, Long>, WaveformChunk>() }
    LaunchedEffect(project.assets, project.audioTrack, widthPx) {
        snapshotFlow {
            val layout = TimelineLayout(widthPx, state.playheadUs, state.pxPerSecond)
            project.audioTrack.flatMap { clip ->
                val from = maxOf(layout.visibleStartUs, clip.startUs)
                val to = minOf(layout.visibleEndUs, clip.endUs)
                if (to <= from) emptyList() else {
                    val first = (clip.sourceInUs + from - clip.startUs) / AudioWaveforms.CHUNK_US
                    val last = (clip.sourceInUs + to - clip.startUs - 1L) / AudioWaveforms.CHUNK_US
                    val near = ((clip.sourceInUs + state.playheadUs - clip.startUs) / AudioWaveforms.CHUNK_US).coerceIn(first, last)
                    (maxOf(first, near - 3L)..minOf(last, near + 4L)).map { clip.assetId to it }
                }
            }.distinct().take(8)
        }.collectLatest { requests ->
            for ((id, chunk) in requests) {
                val asset = project.assets[id] ?: continue
                if (id to chunk in waveforms) continue
                AudioWaveforms.read(context, asset, chunk)?.let {
                    if (waveforms.size >= 96) waveforms.remove(waveforms.keys.first())
                    waveforms[id to chunk] = it
                }
            }
        }
    }
    val scheme = MaterialTheme.colorScheme
    val colors = remember(scheme) {
        TimelineColors(
            tick = scheme.onSurfaceVariant,
            lane = scheme.surfaceContainer,
            mainClip = scheme.surfaceContainerHighest,
            onMainClip = scheme.onSurface,
            clipBorder = scheme.outlineVariant,
            textClip = scheme.tertiaryContainer,
            onTextClip = scheme.onTertiaryContainer,
            audioClip = scheme.secondaryContainer,
            onAudioClip = scheme.onSecondaryContainer,
            selection = scheme.primary,
            onSelection = scheme.onPrimary,
            playhead = scheme.onSurface,
            scrim = Color.Black.copy(alpha = 0.45f),
        )
    }
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall

    LaunchedEffect(state.fitRequest, widthPx) {
        if (widthPx > 0f) {
            val current = latestProject.value
            state.pxPerSecond = TimelineLayout.fitAllPxPerSecond(current.durationUs, widthPx, current.frameRate)
        }
    }

    val gestures = remember(state, lanes) {
        TimelineGestureContext(
            state = state,
            lanes = lanes,
            project = { latestProject.value },
            selection = { latestSelection.value },
            onSelect = { latestOnSelect.value(it) },
            onPreview = { latestOnPreview.value(it) },
            onCommit = { latestOnCommit.value() },
            onEdit = { latestOnEdit.value(it) },
            onReorder = { reorder = it },
            onStartTrim = { startTrim = it },
            onSnap = { snapLineUs = it },
            haptic = { haptics.performHapticFeedback(it) },
            startFling = { velocity ->
                fling.job?.cancel()
                fling.job = scope.launch { flingPlayhead(state, velocity) { latestProject.value.durationUs } }
            },
            stopFling = {
                fling.job?.cancel()
                fling.job = null
            },
            onInteraction = { latestOnInteraction.value() },
        )
    }

    // 読み上げ操作では、ドラッグの代わりに前後のクリップ選択と分割を使えるようにする。
    fun selectNeighbor(delta: Int) {
        val current = latestProject.value
        val list = current.mainTrack
        if (list.isEmpty()) return
        val index = (latestSelection.value as? ClipRef.Main)?.let { ref -> current.mainIndexOf(ref.id) } ?: -1
        val next = if (index < 0) {
            if (delta > 0) 0 else list.lastIndex
        } else {
            (index + delta).coerceIn(0, list.lastIndex)
        }
        latestOnSelect.value(ClipRef.Main(list[next].id))
        state.playheadUs = current.mainStartUs(next)
    }

    val description = stringResource(R.string.video_edit_timeline_description, project.clipCount, formatClock(project.durationUs))
    val previousLabel = stringResource(R.string.video_edit_select_previous)
    val nextLabel = stringResource(R.string.video_edit_select_next)
    val splitLabel = stringResource(R.string.video_edit_tool_split)

    Box(
        modifier
            .fillMaxWidth()
            .height(heightDp)
            .clipToBounds()
            .onSizeChanged { widthPx = it.width.toFloat() }
            .semantics {
                contentDescription = description
                customActions = listOf(
                    CustomAccessibilityAction(previousLabel) {
                        selectNeighbor(-1)
                        true
                    },
                    CustomAccessibilityAction(nextLabel) {
                        selectNeighbor(1)
                        true
                    },
                    CustomAccessibilityAction(splitLabel) {
                        onSplitAtPlayhead()
                        true
                    },
                )
            }
            .drawBehind {
                val layout = TimelineLayout(size.width, state.playheadUs, state.pxPerSecond)
                drawLanes(project, layout, lanes, colors, reordering = reorder != null, startTrim = startTrim)
                drawRuler(layout, project.frameRate, lanes, colors, measurer, labelStyle)
            }
            .pointerInput(gestures) {
                awaitEachGesture { timelineGesture(gestures) }
            },
    ) {
        if (reorder == null) MainThumbnails(project, state, widthPx, lanes, startTrim)
        Canvas(Modifier.fillMaxSize()) {
            val layout = TimelineLayout(size.width, state.playheadUs, state.pxPerSecond)
            val drag = reorder
            if (drag == null) {
                drawMainOverlays(project, layout, lanes, colors, measurer, labelStyle, startTrim)
            } else {
                drawReorder(project, layout, lanes, colors, measurer, labelStyle, drag)
            }
            if (lanes.hasText) drawTextClips(project, layout, lanes, colors, measurer, labelStyle)
            drawLayerClips(project, layout, lanes, colors, measurer, labelStyle)
            if (lanes.hasAudio) drawAudioClips(project, layout, lanes, colors, measurer, labelStyle, waveforms)
            if (drag == null) drawSelection(project, selection, layout, lanes, colors, startTrim)
            if (project.durationUs == 0L) {
                drawText(
                    measurer,
                    emptyHint,
                    topLeft = Offset(16.dp.toPx(), lanes.mainTop + 16.dp.toPx()),
                    style = labelStyle.copy(color = colors.tick),
                    maxLines = 2,
                    size = Size(max(size.width - 32.dp.toPx(), 1f), MAIN_HEIGHT.toPx()),
                )
            }
            drawPlayhead(colors)
            snapLineUs?.let { time ->
                val x = layout.xOf(time)
                drawLine(colors.selection, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            }
        }
    }
}

// ---- 構造 ----

private data class Lanes(
    val rulerBottom: Float,
    val mainTop: Float,
    val mainBottom: Float,
    val textTop: Float,
    val textBottom: Float,
    val audioTop: Float,
    val audioBottom: Float,
    val height: Float,
    val layers: Map<String, Pair<Float, Float>>,
    val texts: Map<String, Pair<Float, Float>>,
    val audio: Map<String, Pair<Float, Float>>,
) {
    val hasText: Boolean get() = textBottom > textTop
    val hasAudio: Boolean get() = audioBottom > audioTop
}

/** 文字と音声の段は、クリップがあるときだけ作る。 */
private fun Density.lanesFor(textIntervals: List<LaneInterval>, audioIntervals: List<LaneInterval>, layerIds: List<String>): Lanes {
    val gap = LANE_GAP.toPx()
    val rulerBottom = RULER_HEIGHT.toPx()
    val mainTop = rulerBottom + gap
    val mainBottom = mainTop + MAIN_HEIGHT.toPx()
    var layerBottom = mainBottom
    // 手前のレイヤーを上に表示し、重なり順が分かるようにする。
    val layerRows = layerIds.asReversed().associateWith {
        val top = layerBottom + gap
        layerBottom = top + AUDIO_HEIGHT.toPx()
        top to layerBottom
    }
    val textIndices = packTimelineLanes(textIntervals)
    val audioIndices = packTimelineLanes(audioIntervals)
    fun rows(indices: Map<String, Int>, top: Float, height: Float): Map<String, Pair<Float, Float>> = indices.mapValues { (_, row) ->
        val y = top + row * (height + gap)
        y to y + height
    }
    val textTop = if (textIndices.isNotEmpty()) layerBottom + gap else layerBottom
    val textRows = rows(textIndices, textTop, TEXT_HEIGHT.toPx())
    val textBottom = textRows.values.maxOfOrNull { it.second } ?: textTop
    val audioTop = if (audioIndices.isNotEmpty()) textBottom + gap else textBottom
    val audioRows = rows(audioIndices, audioTop, AUDIO_HEIGHT.toPx())
    val audioBottom = audioRows.values.maxOfOrNull { it.second } ?: audioTop
    return Lanes(rulerBottom, mainTop, mainBottom, textTop, textBottom, audioTop, audioBottom, audioBottom + gap, layerRows, textRows, audioRows)
}

private data class ReorderDrag(val from: Int, val fingerX: Float, val target: Int, val grabOffsetUs: Long)

private sealed interface Hit {
    data object Empty : Hit

    data object Ruler : Hit

    data class Clip(val ref: ClipRef) : Hit

    data class Handle(val ref: ClipRef, val start: Boolean) : Hit
}

private enum class Early { MOVED, RELEASED, MULTI }

private class TimelineColors(
    val tick: Color,
    val lane: Color,
    val mainClip: Color,
    val onMainClip: Color,
    val clipBorder: Color,
    val textClip: Color,
    val onTextClip: Color,
    val audioClip: Color,
    val onAudioClip: Color,
    val selection: Color,
    val onSelection: Color,
    val playhead: Color,
    val scrim: Color,
)

private class JobHolder {
    var job: Job? = null
}

/** ジェスチャ処理が読む最新の値と、呼び出す操作。pointerInput を作り直さずに済むよう関数で持つ。 */
private class TimelineGestureContext(
    val state: TimelineState,
    val lanes: Lanes,
    val project: () -> VideoProject,
    val selection: () -> ClipRef?,
    val onSelect: (ClipRef?) -> Unit,
    val onPreview: ((VideoProject) -> VideoProject) -> Unit,
    val onCommit: () -> Unit,
    val onEdit: ((VideoProject) -> VideoProject) -> Unit,
    val onReorder: (ReorderDrag?) -> Unit,
    val onStartTrim: (MainStartTrim?) -> Unit,
    val onSnap: (Long?) -> Unit,
    val haptic: (HapticFeedbackType) -> Unit,
    val startFling: (Float) -> Unit,
    val stopFling: () -> Unit,
    val onInteraction: () -> Unit,
) {
    fun movePlayheadByPx(px: Float) {
        val duration = project().durationUs.coerceAtLeast(0L)
        val delta = (px.toDouble() / state.pxPerSecond * US_PER_SECOND).roundToLong()
        state.playheadUs = (state.playheadUs + delta).coerceIn(0L, duration)
    }
}

// ---- サムネイル ----

@Composable
private fun MainThumbnails(project: VideoProject, state: TimelineState, widthPx: Float, lanes: Lanes, startTrim: MainStartTrim?) {
    if (widthPx <= 0f) return
    val context = LocalContext.current
    val density = LocalDensity.current
    val thumbWidthPx = with(density) { THUMB_WIDTH.toPx() }
    val mainHeight = with(density) { (lanes.mainBottom - lanes.mainTop).toDp() }
    val durations = remember(project.mainTrack) { project.mainTrack.map { it.durationUs } }
    val slots by remember(durations, widthPx, thumbWidthPx, startTrim) {
        derivedStateOf { thumbnailSlots(durations, TimelineLayout(widthPx, state.playheadUs, state.pxPerSecond), thumbWidthPx, startTrim = startTrim) }
    }
    slots.forEach { slot ->
        val clip = project.mainTrack.getOrNull(slot.clipIndex) ?: return@forEach
        val asset = project.assets[clip.assetId] ?: return@forEach
        // 分割直後の先頭を、区間外のキーフレームや 0.1 秒の丸めで同じ絵にしない。
        val exact = slot.localUs == 0L
        val sourceUs = if (asset.kind == AssetKind.VIDEO) {
            if (exact) clip.sourceInUs else (clip.sourceTimeAt(slot.localUs) / THUMB_BUCKET_US * THUMB_BUCKET_US).coerceAtLeast(clip.sourceInUs)
        } else 0L
        key(clip.id, slot.localUs, slot.widthUs) {
            val request = remember(asset.source, sourceUs, exact) {
                val builder = ImageRequest.Builder(context)
                    .data(sourceModel(asset.source))
                    .size(THUMB_REQUEST_PX)
                    .memoryCacheKey("videoedit-thumb:${asset.source}#$sourceUs#$exact")
                    .crossfade(false)
                if (asset.kind == AssetKind.VIDEO) {
                    builder.videoFrameMicros(sourceUs).videoFrameOption(if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
                builder.build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .offset {
                        val layout = TimelineLayout(widthPx, state.playheadUs, state.pxPerSecond)
                        IntOffset(layout.xOf(slot.timelineUs).roundToInt(), lanes.mainTop.roundToInt())
                    }
                    .size(
                        width = with(density) { (slot.widthUs.toDouble() / US_PER_SECOND * state.pxPerSecond).toFloat().toDp() },
                        height = mainHeight,
                    ),
            )
        }
    }
}

// ---- 描画 ----

private fun DrawScope.drawLanes(project: VideoProject, layout: TimelineLayout, lanes: Lanes, colors: TimelineColors, reordering: Boolean, startTrim: MainStartTrim?) {
    drawRect(colors.lane, Offset(0f, lanes.mainTop), Size(size.width, lanes.mainBottom - lanes.mainTop))
    if (lanes.hasText) drawRect(colors.lane, Offset(0f, lanes.textTop), Size(size.width, lanes.textBottom - lanes.textTop))
    if (lanes.hasAudio) drawRect(colors.lane, Offset(0f, lanes.audioTop), Size(size.width, lanes.audioBottom - lanes.audioTop))
    if (reordering) return
    // サムネイルが読み込まれる前に見える下地。
    var start = 0L
    project.mainTrack.forEachIndexed { index, clip ->
        val displayStart = start + (startTrim?.offsetFor(index) ?: 0L)
        val left = layout.xOf(displayStart)
        val right = layout.xOf(displayStart + clip.durationUs)
        start += clip.durationUs
        if (right >= 0f && left <= size.width) {
            drawRect(colors.mainClip, Offset(left, lanes.mainTop), Size(right - left, lanes.mainBottom - lanes.mainTop))
        }
    }
}

private fun DrawScope.drawRuler(
    layout: TimelineLayout,
    rate: FrameRate,
    lanes: Lanes,
    colors: TimelineColors,
    measurer: TextMeasurer,
    style: TextStyle,
) {
    val step = rulerStepUs(layout.pxPerSecond, rate, RULER_MIN_SPACING.toPx())
    val tickTop = lanes.rulerBottom - 6.dp.toPx()
    val labelStyle = style.copy(color = colors.tick)
    val labelSize = Size(RULER_MIN_SPACING.toPx(), lanes.rulerBottom)
    var timeUs = layout.visibleStartUs.coerceAtLeast(0L) / step * step
    while (timeUs <= layout.visibleEndUs) {
        val x = layout.xOf(timeUs)
        drawLine(colors.tick, Offset(x, tickTop), Offset(x, lanes.rulerBottom), strokeWidth = 1.dp.toPx())
        drawText(measurer, rulerLabel(timeUs, step, rate), topLeft = Offset(x + 3.dp.toPx(), 0f), style = labelStyle, maxLines = 1, size = labelSize)
        timeUs += step
    }
}

/** 秒の区切りは「分:秒」、コマ単位に拡大しているときの途中は「15f」のようにコマ数で出す。 */
private fun rulerLabel(timeUs: Long, stepUs: Long, rate: FrameRate): String {
    if (stepUs >= US_PER_SECOND) return formatClock(timeUs)
    val framesIntoSecond = rate.frameAt(timeUs) - rate.frameAt(timeUs / US_PER_SECOND * US_PER_SECOND)
    return if (framesIntoSecond == 0L) formatClock(timeUs) else "${framesIntoSecond}f"
}

private fun DrawScope.drawMainOverlays(
    project: VideoProject,
    layout: TimelineLayout,
    lanes: Lanes,
    colors: TimelineColors,
    measurer: TextMeasurer,
    style: TextStyle,
    startTrim: MainStartTrim?,
) {
    val top = lanes.mainTop
    val height = lanes.mainBottom - lanes.mainTop
    val border = 1.dp.toPx()
    var start = 0L
    project.mainTrack.forEachIndexed { index, clip ->
        val clipStart = start + (startTrim?.offsetFor(index) ?: 0L)
        start += clip.durationUs
        val left = layout.xOf(clipStart)
        val right = layout.xOf(clipStart + clip.durationUs)
        if (right < 0f || left > size.width) return@forEachIndexed
        drawRect(colors.clipBorder, Offset(left, top), Size(right - left, height), style = Stroke(border))
        // 同じ素材を分割しても、番号・使用区間・境界から別の区間だと分かるようにする。
        val labelHeight = 18.dp.toPx()
        val labelLeft = max(left, 0f)
        if (right - labelLeft > 32.dp.toPx()) {
            drawRect(colors.scrim, Offset(labelLeft, top + height - labelHeight), Size(right - labelLeft, labelHeight))
            drawClipLabel(
                "${index + 1}  ${formatTimecode(clip.sourceInUs, project.frameRate)}–${formatTimecode(clip.sourceOutUs, project.frameRate)}",
                labelLeft, top + height - labelHeight, right - labelLeft, labelHeight,
                Color.White, measurer, style,
            )
        }
        drawLine(colors.clipBorder, Offset(left, top), Offset(left, top + height), strokeWidth = 3.dp.toPx())
        // フェードは上端から斜めに下りる三角形で示す。
        if (clip.fadeInUs > 0L) {
            val edge = layout.xOf(clipStart + clip.fadeInUs)
            val path = Path().apply {
                moveTo(left, top)
                lineTo(edge, top)
                lineTo(left, top + height)
                close()
            }
            drawPath(path, colors.scrim)
        }
        if (clip.fadeOutUs > 0L) {
            val edge = layout.xOf(clipStart + clip.durationUs - clip.fadeOutUs)
            val path = Path().apply {
                moveTo(right, top)
                lineTo(edge, top)
                lineTo(right, top + height)
                close()
            }
            drawPath(path, colors.scrim)
        }
        if (clip.speed != 1.0 && right - left > 56.dp.toPx()) {
            val badgeSize = Size(48.dp.toPx(), 16.dp.toPx())
            val badgeLeft = max(left, 0f) + 4.dp.toPx()
            val badgeTop = top + 4.dp.toPx()
            drawRoundRect(colors.scrim, Offset(badgeLeft, badgeTop), badgeSize, CornerRadius(4.dp.toPx()))
            drawText(
                measurer,
                String.format(Locale.US, "%.2f×", clip.speed),
                topLeft = Offset(badgeLeft + 4.dp.toPx(), badgeTop),
                style = style.copy(color = Color.White),
                maxLines = 1,
                size = badgeSize,
            )
        }
    }
}

/** 並べ替え中: 動かしているクリップを除いた並びを前詰めで描き、差し込み位置に線を引く。 */
private fun DrawScope.drawReorder(
    project: VideoProject,
    layout: TimelineLayout,
    lanes: Lanes,
    colors: TimelineColors,
    measurer: TextMeasurer,
    style: TextStyle,
    drag: ReorderDrag,
) {
    val top = lanes.mainTop
    val height = lanes.mainBottom - lanes.mainTop
    val radius = CornerRadius(6.dp.toPx())
    val gap = 2.dp.toPx()
    val moving = project.mainTrack.getOrNull(drag.from) ?: return
    var start = 0L
    var seen = 0
    var insertionX = layout.xOf(0L)
    project.mainTrack.forEachIndexed { index, clip ->
        if (index == drag.from) return@forEachIndexed
        if (seen == drag.target) {
            insertionX = layout.xOf(start)
            start += moving.durationUs
        }
        val left = layout.xOf(start)
        val right = layout.xOf(start + clip.durationUs)
        if (right >= 0f && left <= size.width) {
            val width = max(right - left - gap, 1f)
            drawRoundRect(colors.mainClip, Offset(left, top), Size(width, height), radius)
            drawClipLabel(project.assets[clip.assetId]?.name.orEmpty(), left, top, width, height, colors.onMainClip, measurer, style)
        }
        start += clip.durationUs
        seen++
    }
    if (drag.target >= seen) insertionX = layout.xOf(start)
    drawLine(colors.selection, Offset(insertionX, top - gap), Offset(insertionX, top + height + gap), strokeWidth = 3.dp.toPx())
    val width = (moving.durationUs.toDouble() / US_PER_SECOND * layout.pxPerSecond).toFloat().coerceAtLeast(1f)
    val left = drag.fingerX - (drag.grabOffsetUs.toDouble() / US_PER_SECOND * layout.pxPerSecond).toFloat()
    drawRoundRect(colors.selection, Offset(left, top), Size(width, height), radius)
    drawClipLabel(project.assets[moving.assetId]?.name.orEmpty(), left, top, width, height, colors.onSelection, measurer, style)
}

private fun DrawScope.drawTextClips(
    project: VideoProject,
    layout: TimelineLayout,
    lanes: Lanes,
    colors: TimelineColors,
    measurer: TextMeasurer,
    style: TextStyle,
) {
    val radius = CornerRadius(6.dp.toPx())
    project.texts.forEach { clip ->
        val (top, bottom) = lanes.texts[clip.id] ?: return@forEach
        val height = bottom - top
        val left = layout.xOf(clip.startUs)
        val right = layout.xOf(clip.endUs)
        if (right < 0f || left > size.width) return@forEach
        val width = max(right - left, 1f)
        drawRoundRect(colors.textClip, Offset(left, top), Size(width, height), radius)
        drawClipLabel(clip.text, left, top, width, height, colors.onTextClip, measurer, style)
    }
}

private fun DrawScope.drawAudioClips(
    project: VideoProject,
    layout: TimelineLayout,
    lanes: Lanes,
    colors: TimelineColors,
    measurer: TextMeasurer,
    style: TextStyle,
    waveforms: Map<Pair<String, Long>, WaveformChunk>,
) {
    val radius = CornerRadius(6.dp.toPx())
    val barStep = 3.dp.toPx()
    val barWidth = 1.5.dp.toPx()
    val barColor = colors.onAudioClip.copy(alpha = 0.55f)
    project.audioTrack.forEach { clip ->
        val (top, bottom) = lanes.audio[clip.id] ?: return@forEach
        val height = bottom - top
        val middle = top + height / 2f
        val maxAmplitude = height / 2f - 3.dp.toPx()
        val left = layout.xOf(clip.startUs)
        val right = layout.xOf(clip.endUs)
        if (right < 0f || left > size.width) return@forEach
        val width = max(right - left, 1f)
        drawRoundRect(colors.audioClip, Offset(left, top), Size(width, height), radius)
        val gain = if (clip.muted) 0f else clip.volume.coerceIn(0f, 2f)
        var x = max(left, 0f)
        val end = min(right, size.width)
        while (x < end) {
            val timeUs = layout.timeAt(x)
            val sourceUs = clip.sourceInUs + (timeUs - clip.startUs)
            val level = waveforms[clip.assetId to sourceUs / AudioWaveforms.CHUNK_US]?.peakAt(sourceUs)
            val amplitude = ((level ?: 0f) * gain * fadeFactor(clip, timeUs)).coerceIn(0f, 1f) * maxAmplitude
            if (level != null) drawLine(barColor, Offset(x, middle - amplitude), Offset(x, middle + amplitude), strokeWidth = barWidth)
            x += barStep
        }
        drawClipLabel(project.assets[clip.assetId]?.name.orEmpty(), left, top, width, height, colors.onAudioClip, measurer, style)
    }
}

private fun DrawScope.drawClipLabel(
    text: String,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    color: Color,
    measurer: TextMeasurer,
    style: TextStyle,
) {
    val right = left + width
    val visibleLeft = max(left, 0f)
    if (text.isEmpty() || right - visibleLeft < 16.dp.toPx()) return
    val padding = 6.dp.toPx()
    clipRect(left = visibleLeft, top = top, right = right, bottom = top + height) {
        drawText(
            measurer,
            text,
            topLeft = Offset(visibleLeft + padding, top + 4.dp.toPx()),
            style = style.copy(color = color),
            maxLines = 1,
            size = Size(max(right - visibleLeft - padding, 1f), height),
        )
    }
}

private fun DrawScope.drawSelection(
    project: VideoProject,
    selection: ClipRef?,
    layout: TimelineLayout,
    lanes: Lanes,
    colors: TimelineColors,
    startTrim: MainStartTrim?,
) {
    val ref = selection ?: return
    val (startUs, endUs) = timeRangeOf(project, ref, startTrim) ?: return
    val top = when (ref) {
        is ClipRef.Main -> lanes.mainTop
        is ClipRef.Text -> lanes.texts[ref.id]?.first ?: return
        is ClipRef.Layer -> lanes.layers[ref.id]?.first ?: return
        is ClipRef.Audio -> lanes.audio[ref.id]?.first ?: return
    }
    val bottom = when (ref) {
        is ClipRef.Main -> lanes.mainBottom
        is ClipRef.Text -> lanes.texts[ref.id]?.second ?: return
        is ClipRef.Layer -> lanes.layers[ref.id]?.second ?: return
        is ClipRef.Audio -> lanes.audio[ref.id]?.second ?: return
    }
    val left = layout.xOf(startUs)
    val right = layout.xOf(endUs)
    val height = bottom - top
    val handle = HANDLE_WIDTH.toPx()
    val corner = CornerRadius(4.dp.toPx())
    val grip = 2.dp.toPx()
    drawRect(colors.selection, Offset(left, top), Size(max(right - left, 1f), height), style = Stroke(2.dp.toPx()))
    drawRoundRect(colors.selection, Offset(left - handle, top), Size(handle, height), corner)
    drawRoundRect(colors.selection, Offset(right, top), Size(handle, height), corner)
    drawLine(colors.onSelection, Offset(left - handle / 2f, top + height * 0.3f), Offset(left - handle / 2f, bottom - height * 0.3f), strokeWidth = grip)
    drawLine(colors.onSelection, Offset(right + handle / 2f, top + height * 0.3f), Offset(right + handle / 2f, bottom - height * 0.3f), strokeWidth = grip)
}

private fun DrawScope.drawPlayhead(colors: TimelineColors) {
    val x = size.width / 2f
    drawLine(colors.playhead, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
    drawCircle(colors.playhead, radius = 4.dp.toPx(), center = Offset(x, 4.dp.toPx()))
}

private fun fadeFactor(clip: AudioClip, timeUs: Long): Float {
    val fadeIn = if (clip.fadeInUs > 0L) ((timeUs - clip.startUs).toFloat() / clip.fadeInUs).coerceIn(0f, 1f) else 1f
    val fadeOut = if (clip.fadeOutUs > 0L) ((clip.endUs - timeUs).toFloat() / clip.fadeOutUs).coerceIn(0f, 1f) else 1f
    return min(fadeIn, fadeOut)
}

// ---- 当たり判定 ----

private fun hitTest(
    position: Offset,
    project: VideoProject,
    layout: TimelineLayout,
    lanes: Lanes,
    selection: ClipRef?,
    handleTouchPx: Float,
): Hit {
    val x = position.x
    val y = position.y
    if (y < lanes.rulerBottom) return Hit.Ruler
    val inMain = y >= lanes.mainTop && y <= lanes.mainBottom
    val inText = lanes.hasText && y >= lanes.textTop && y <= lanes.textBottom
    val inAudio = lanes.hasAudio && y >= lanes.audioTop && y <= lanes.audioBottom
    val layerId = lanes.layers.entries.firstOrNull { y >= it.value.first && y <= it.value.second }?.key
    fun inRow(rows: Map<String, Pair<Float, Float>>, id: String): Boolean = rows[id]?.let { y >= it.first && y <= it.second } == true
    // 選択中クリップの端 (トリム用の取っ手) を最優先で当てる。短いクリップでは中央で左右を分ける。
    if (selection != null) {
        val range = timeRangeOf(project, selection)
        val inLane = when (selection) {
            is ClipRef.Main -> inMain
            is ClipRef.Text -> inRow(lanes.texts, selection.id)
            is ClipRef.Layer -> layerId == selection.id
            is ClipRef.Audio -> inRow(lanes.audio, selection.id)
        }
        if (range != null && inLane) {
            val startX = layout.xOf(range.first)
            val endX = layout.xOf(range.second)
            trimHandleAt(x, startX, endX, handleTouchPx)?.let { return Hit.Handle(selection, start = it) }
        }
    }
    val timeUs = layout.timeAt(x)
    return when {
        inMain -> {
            val found = if (timeUs >= 0L && timeUs < project.mainDurationUs) project.mainClipAt(timeUs) else null
            found?.let { (index, _) -> Hit.Clip(ClipRef.Main(project.mainTrack[index].id)) } ?: Hit.Empty
        }
        layerId != null -> project.layers.firstOrNull { it.id == layerId && timeUs >= it.startUs && timeUs < it.endUs }
            ?.let { Hit.Clip(ClipRef.Layer(it.id)) } ?: Hit.Empty
        inText -> project.texts.lastOrNull { inRow(lanes.texts, it.id) && timeUs >= it.startUs && timeUs < it.endUs }?.let { Hit.Clip(ClipRef.Text(it.id)) } ?: Hit.Empty
        inAudio -> project.audioTrack.lastOrNull { inRow(lanes.audio, it.id) && timeUs >= it.startUs && timeUs < it.endUs }?.let { Hit.Clip(ClipRef.Audio(it.id)) } ?: Hit.Empty
        else -> Hit.Empty
    }
}

private fun timeRangeOf(project: VideoProject, ref: ClipRef, startTrim: MainStartTrim? = null): Pair<Long, Long>? = when (ref) {
    is ClipRef.Main -> {
        val index = project.mainIndexOf(ref.id)
        if (index < 0) {
            null
        } else {
            val start = project.mainStartUs(index) + (startTrim?.offsetFor(index) ?: 0L)
            start to start + project.mainTrack[index].durationUs
        }
    }
    is ClipRef.Layer -> project.layers.firstOrNull { it.id == ref.id }?.let { it.startUs to it.endUs }
    is ClipRef.Text -> project.texts.firstOrNull { it.id == ref.id }?.let { it.startUs to it.endUs }
    is ClipRef.Audio -> project.audioTrack.firstOrNull { it.id == ref.id }?.let { it.startUs to it.endUs }
}

/** 吸着先: 再生位置、先頭、ほかのクリップの端。動かしているクリップ自身の端は含めない。 */
private fun snapTargets(project: VideoProject, playheadUs: Long, exclude: ClipRef): List<Long> {
    val targets = ArrayList<Long>()
    targets.add(playheadUs)
    targets.add(0L)
    var start = 0L
    project.mainTrack.forEach { clip ->
        if (clip.id != exclude.id) {
            targets.add(start)
            targets.add(start + clip.durationUs)
        }
        start += clip.durationUs
    }
    project.texts.forEach {
        if (it.id != exclude.id) {
            targets.add(it.startUs)
            targets.add(it.endUs)
        }
    }
    project.audioTrack.forEach {
        if (it.id != exclude.id) {
            targets.add(it.startUs)
            targets.add(it.endUs)
        }
    }
    project.layers.forEach {
        if (it.id != exclude.id) { targets.add(it.startUs); targets.add(it.endUs) }
    }
    return targets
}

// ---- ジェスチャ ----

private suspend fun AwaitPointerEventScope.timelineGesture(ctx: TimelineGestureContext) {
    val down = awaitFirstDown(requireUnconsumed = false)
    ctx.onInteraction()
    ctx.stopFling()
    val project = ctx.project()
    val layout = TimelineLayout(size.width.toFloat(), ctx.state.playheadUs, ctx.state.pxPerSecond)
    val hit = hitTest(down.position, project, layout, ctx.lanes, ctx.selection(), HANDLE_TOUCH.toPx())
    // 長押し・移動・指を離す・2 本目の指、のどれが先に起きたかで操作を決める。
    val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        var result: Early? = null
        while (result == null) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id }
            result = when {
                event.changes.count { it.pressed } >= 2 -> Early.MULTI
                change == null || !change.pressed -> Early.RELEASED
                (change.position - down.position).getDistance() > viewConfiguration.touchSlop -> Early.MOVED
                else -> null
            }
        }
        result
    }
    when (early) {
        Early.RELEASED -> tap(ctx, hit, down.position, layout)
        Early.MULTI -> pinch(ctx)
        Early.MOVED -> {
            val change = currentEvent.changes.firstOrNull { it.id == down.id } ?: return
            val delta = change.position - down.position
            // 縦方向は親の段スクロールへ渡す。クリップ選択中でも全レイヤーにたどり着ける。
            if (abs(delta.y) > abs(delta.x)) return
            if (hit is Hit.Handle) {
                trimClip(ctx, down, hit, project, layout)
                return
            }
            val ref = (hit as? Hit.Clip)?.ref
            when {
                ref == null || ref != ctx.selection() || !ctx.state.movingClips -> scroll(ctx, down)
                ref is ClipRef.Main -> reorderMain(ctx, down, ref, project)
                else -> moveClip(ctx, down, ref, project, layout)
            }
        }
        null -> {
            if (hit is Hit.Handle) {
                trimClip(ctx, down, hit, project, layout)
                return
            }
            val ref = (hit as? Hit.Clip)?.ref
            if (ref == null) {
                scroll(ctx, down)
            } else if (ref is ClipRef.Main) {
                reorderMain(ctx, down, ref, project)
            } else {
                moveClip(ctx, down, ref, project, layout)
            }
        }
    }
}

private fun tap(ctx: TimelineGestureContext, hit: Hit, position: Offset, layout: TimelineLayout) {
    when (hit) {
        is Hit.Clip -> ctx.onSelect(if (ctx.selection() == hit.ref) null else hit.ref)
        is Hit.Ruler -> ctx.state.playheadUs = layout.timeAt(position.x).coerceIn(0L, ctx.project().durationUs.coerceAtLeast(0L))
        is Hit.Empty -> ctx.onSelect(null)
        is Hit.Handle -> Unit
    }
}

private suspend fun AwaitPointerEventScope.scroll(ctx: TimelineGestureContext, down: PointerInputChange) {
    val tracker = VelocityTracker()
    tracker.addPosition(down.uptimeMillis, down.position)
    ctx.state.scrubbing = true
    var released = false
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.count { it.pressed } >= 2) {
            pinch(ctx)
            break
        }
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        tracker.addPosition(change.uptimeMillis, change.position)
        if (!change.pressed) {
            released = true
            break
        }
        val dx = change.position.x - change.previousPosition.x
        if (dx != 0f) {
            ctx.movePlayheadByPx(-dx)
            change.consume()
        }
    }
    val velocity = if (released) tracker.calculateVelocity().x else 0f
    if (abs(velocity) > MIN_FLING_VELOCITY) {
        ctx.startFling(-velocity)
    } else {
        ctx.state.scrubbing = false
    }
}

private suspend fun AwaitPointerEventScope.pinch(ctx: TimelineGestureContext) {
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.none { it.pressed }) break
        if (event.changes.count { it.pressed } >= 2) {
            val zoom = event.calculateZoom()
            if (zoom != 1f) {
                ctx.state.pxPerSecond = TimelineLayout.clampZoom(ctx.state.pxPerSecond * zoom, ctx.project().frameRate)
            }
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        }
    }
}

private suspend fun AwaitPointerEventScope.trimClip(
    ctx: TimelineGestureContext,
    down: PointerInputChange,
    hit: Hit.Handle,
    project: VideoProject,
    layout: TimelineLayout,
) {
    val ref = hit.ref
    val basePlayhead = ctx.state.playheadUs
    val thresholdUs = layout.usForPx(SNAP_DISTANCE.toPx())
    val targets = if (ctx.state.snapping) snapTargets(project, basePlayhead, ref) else emptyList()
    val mainIndex = if (ref is ClipRef.Main) project.mainIndexOf(ref.id) else -1
    val mainClip = project.mainTrack.getOrNull(mainIndex)
    val mainStart = if (mainIndex >= 0) project.mainStartUs(mainIndex) else 0L
    val text = if (ref is ClipRef.Text) project.texts.firstOrNull { it.id == ref.id } else null
    val layer = if (ref is ClipRef.Layer) project.layers.firstOrNull { it.id == ref.id } else null
    val audio = if (ref is ClipRef.Audio) project.audioTrack.firstOrNull { it.id == ref.id } else null
    var snappedTarget: Long? = null
    var released = false
    ctx.state.scrubbing = true
    try {
        var event = currentEvent
        while (true) {
            if (event.changes.count { it.pressed } >= 2) {
                ctx.onPreview { project }
                ctx.onStartTrim(null)
                ctx.onSnap(null)
                pinch(ctx)
                break
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                released = true
                break
            }
            val deltaUs = layout.usForPx(change.position.x - down.position.x)
            var target: Long? = null
            fun snappedEdge(wanted: Long, minUs: Long, maxUs: Long): Long {
                val bounded = wanted.coerceIn(minUs, maxOf(minUs, maxUs))
                target = snapTarget(bounded, targets.filter { it in minUs..maxUs }, thresholdUs)
                return target ?: bounded
            }
            if (mainClip != null && hit.start) {
                val minEdge = mainStart - (mainClip.sourceInUs / mainClip.speed).roundToLong()
                val maxEdge = mainStart + mainClip.durationUs - project.frameRate.frameDurationUs
                val edge = snappedEdge(mainStart + deltaUs, minEdge, maxEdge)
                val sourceIn = mainClip.sourceInUs + ((edge - mainStart) * mainClip.speed).roundToLong()
                val next = VideoEditOps.trimMainStart(project, mainClip.id, sourceIn)
                // 再生位置や目盛りを動かさず、左端だけを指に追従させる。
                ctx.onStartTrim(MainStartTrim(mainIndex, mainClip.durationUs - next.mainTrack[mainIndex].durationUs))
                ctx.onPreview { next }
            } else if (mainClip != null) {
                val maxEdge = mainStart + ((VideoEditOps.sourceLimitUs(project, mainClip.assetId) - mainClip.sourceInUs) / mainClip.speed).roundToLong()
                val endUs = snappedEdge(mainStart + mainClip.durationUs + deltaUs, mainStart + project.frameRate.frameDurationUs, maxEdge)
                val sourceOut = mainClip.sourceInUs + ((endUs - mainStart) * mainClip.speed).roundToLong()
                ctx.onPreview { VideoEditOps.trimMainEnd(project, mainClip.id, sourceOut) }
            } else if (text != null) {
                val finalEdge = if (hit.start) {
                    snappedEdge(text.startUs + deltaUs, 0L, text.endUs - project.frameRate.frameDurationUs)
                } else {
                    snappedEdge(text.endUs + deltaUs, text.startUs + project.frameRate.frameDurationUs, Long.MAX_VALUE)
                }
                ctx.onPreview {
                    if (hit.start) VideoEditOps.trimTextStart(project, text.id, finalEdge) else VideoEditOps.trimTextEnd(project, text.id, finalEdge)
                }
            } else if (layer != null) {
                val clip = layer.clip
                val finalEdge = if (hit.start) {
                    snappedEdge(layer.startUs + deltaUs,
                        maxOf(0L, layer.startUs - (clip.sourceInUs / clip.speed).roundToLong()),
                        layer.endUs - project.frameRate.frameDurationUs)
                } else {
                    snappedEdge(layer.endUs + deltaUs, layer.startUs + project.frameRate.frameDurationUs,
                        layer.startUs + ((VideoEditOps.sourceLimitUs(project, clip.assetId) - clip.sourceInUs) / clip.speed).roundToLong())
                }
                ctx.onPreview {
                    if (hit.start) LayerEditOps.trimStart(project, layer.id, finalEdge) else LayerEditOps.trimEnd(project, layer.id, finalEdge)
                }
            } else if (audio != null) {
                val finalEdge = if (hit.start) {
                    snappedEdge(audio.startUs + deltaUs, maxOf(0L, audio.startUs - audio.sourceInUs), audio.endUs - project.frameRate.frameDurationUs)
                } else {
                    snappedEdge(audio.endUs + deltaUs, audio.startUs + project.frameRate.frameDurationUs,
                        audio.startUs + VideoEditOps.sourceLimitUs(project, audio.assetId) - audio.sourceInUs)
                }
                ctx.onPreview {
                    if (hit.start) VideoEditOps.trimAudioStart(project, audio.id, finalEdge) else VideoEditOps.trimAudioEnd(project, audio.id, finalEdge)
                }
            }
            if (target != null && target != snappedTarget) ctx.haptic(HapticFeedbackType.TextHandleMove)
            snappedTarget = target
            ctx.onSnap(target)
            change.consume()
            event = awaitPointerEvent()
        }
    } finally {
        if (!released) ctx.onPreview { project }
        ctx.onStartTrim(null)
        ctx.onSnap(null)
        ctx.state.scrubbing = false
        ctx.onCommit()
    }
}

private suspend fun AwaitPointerEventScope.moveClip(
    ctx: TimelineGestureContext,
    down: PointerInputChange,
    ref: ClipRef,
    project: VideoProject,
    layout: TimelineLayout,
) {
    val text = if (ref is ClipRef.Text) project.texts.firstOrNull { it.id == ref.id } else null
    val layer = if (ref is ClipRef.Layer) project.layers.firstOrNull { it.id == ref.id } else null
    val audio = if (ref is ClipRef.Audio) project.audioTrack.firstOrNull { it.id == ref.id } else null
    val baseStart = text?.startUs ?: audio?.startUs ?: layer?.startUs ?: return
    val length = text?.durationUs ?: audio?.durationUs ?: layer?.durationUs ?: return
    ctx.haptic(HapticFeedbackType.LongPress)
    ctx.onSelect(ref)
    val targets = if (ctx.state.snapping) snapTargets(project, ctx.state.playheadUs, ref) else emptyList()
    val thresholdUs = layout.usForPx(SNAP_DISTANCE.toPx())
    var snappedTarget: Long? = null
    var released = false
    ctx.state.scrubbing = true
    try {
        var event = currentEvent
        while (true) {
            if (event.changes.count { it.pressed } >= 2) {
                ctx.onPreview { project }
                ctx.onSnap(null)
                pinch(ctx)
                break
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                released = true
                break
            }
            val startUs = baseStart + layout.usForPx(change.position.x - down.position.x)
            val snap = snapMovingRange(startUs, length, targets, thresholdUs)
            if (snap.targetUs != null && snap.targetUs != snappedTarget) ctx.haptic(HapticFeedbackType.TextHandleMove)
            snappedTarget = snap.targetUs
            ctx.onSnap(snap.targetUs)
            ctx.onPreview {
                when {
                    text != null -> VideoEditOps.moveText(project, text.id, snap.startUs)
                    layer != null -> LayerEditOps.move(project, layer.id, snap.startUs)
                    else -> VideoEditOps.moveAudio(project, ref.id, snap.startUs)
                }
            }
            change.consume()
            event = awaitPointerEvent()
        }
    } finally {
        if (!released) ctx.onPreview { project }
        ctx.onSnap(null)
        ctx.state.scrubbing = false
        ctx.onCommit()
    }
}

private suspend fun AwaitPointerEventScope.reorderMain(
    ctx: TimelineGestureContext,
    down: PointerInputChange,
    ref: ClipRef.Main,
    project: VideoProject,
) {
    val from = project.mainIndexOf(ref.id)
    if (from < 0) return
    ctx.haptic(HapticFeedbackType.LongPress)
    ctx.onSelect(ref)
    val durations = project.mainTrack.map { it.durationUs }
    val edge = EDGE_SCROLL.toPx()
    val step = EDGE_STEP.toPx()
    val initialLayout = TimelineLayout(size.width.toFloat(), ctx.state.playheadUs, ctx.state.pxPerSecond)
    val grabOffsetUs = initialLayout.timeAt(down.position.x) - project.mainStartUs(from)
    var drag = ReorderDrag(from, down.position.x, from, grabOffsetUs)
    var released = false
    ctx.state.scrubbing = true
    ctx.onReorder(drag)
    try {
        var event = currentEvent
        while (true) {
            if (event.changes.count { it.pressed } >= 2) {
                ctx.onReorder(null)
                pinch(ctx)
                break
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                released = true
                break
            }
            val x = change.position.x
            val width = size.width.toFloat()
            // 画面の端では、指を動かすたびに少しずつ送る。
            if (x < edge) {
                ctx.movePlayheadByPx(-step)
            } else if (x > width - edge) {
                ctx.movePlayheadByPx(step)
            }
            val layout = TimelineLayout(width, ctx.state.playheadUs, ctx.state.pxPerSecond)
            val target = reorderTargetIndex(durations, from, layout.timeAt(x) - grabOffsetUs)
            if (target != drag.target) ctx.haptic(HapticFeedbackType.TextHandleMove)
            drag = drag.copy(fingerX = x, target = target)
            ctx.onReorder(drag)
            change.consume()
            event = awaitPointerEvent()
        }
    } finally {
        ctx.onReorder(null)
        ctx.state.scrubbing = false
    }
    val target = drag.target
    if (released && target != from) ctx.onEdit { VideoEditOps.moveMain(it, from, target) }
}

/** 指を離したあとの慣性。端に着いたら止める。 */
private suspend fun flingPlayhead(state: TimelineState, initialVelocityPx: Float, durationUs: () -> Long) {
    var last = 0f
    try {
        AnimationState(initialValue = 0f, initialVelocity = initialVelocityPx).animateDecay(exponentialDecay()) {
            val delta = value - last
            last = value
            val next = state.playheadUs + (delta.toDouble() / state.pxPerSecond * US_PER_SECOND).roundToLong()
            val clamped = next.coerceIn(0L, durationUs().coerceAtLeast(0L))
            state.playheadUs = clamped
            if (clamped != next) cancelAnimation()
        }
    } finally {
        state.scrubbing = false
    }
}

private fun DrawScope.drawLayerClips(
    project: VideoProject, layout: TimelineLayout, lanes: Lanes, colors: TimelineColors,
    measurer: TextMeasurer, style: TextStyle,
) {
    project.layers.forEachIndexed { index, layer ->
        val (top, bottom) = lanes.layers.getValue(layer.id)
        drawRect(colors.lane, Offset(0f, top), Size(size.width, bottom - top))
        val left = layout.xOf(layer.startUs)
        val right = layout.xOf(layer.endUs)
        if (right < 0f || left > size.width) return@forEachIndexed
        val width = max(right - left, 1f)
        drawRect(colors.mainClip.copy(alpha = if (layer.visible) 1f else 0.4f), Offset(left, top), Size(width, bottom - top))
        val clip = layer.clip
        val label = "${index + 1} · ${project.assets[clip.assetId]?.name.orEmpty()} · ${formatTimecode(clip.sourceInUs, project.frameRate)}–${formatTimecode(clip.sourceOutUs, project.frameRate)}"
        drawClipLabel(label, left, top, width, bottom - top, colors.onMainClip, measurer, style)
        if (!layer.visible) drawLine(colors.onMainClip.copy(alpha = 0.5f), Offset(left, bottom), Offset(right, top), 1.dp.toPx())
    }
}
