// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.video.videoFrameMicros
import coil3.video.videoFrameOption
import com.zerotoship.foldex.R
import com.zerotoship.foldex.ui.videoedit.model.AssetKind
import com.zerotoship.foldex.ui.videoedit.model.FrameFill
import com.zerotoship.foldex.ui.videoedit.model.LayerClip
import com.zerotoship.foldex.ui.videoedit.model.MainClip
import com.zerotoship.foldex.ui.videoedit.model.TextClip
import com.zerotoship.foldex.ui.videoedit.model.VideoProject
import com.zerotoship.foldex.ui.videoedit.timeline.TimelineState
import java.io.File
import kotlin.math.roundToInt

/**
 * キャンバスの比率で、再生位置のコマと文字を表示する。
 *
 * 試作版は動画を再生せず、Coil の動画フレーム取得で静止画を出す。スクロール・再生中は
 * 近いキーフレームを 0.25 秒単位で、止まったらそのコマを正確に読み込む。
 */
@Composable
fun VideoPreview(
    project: VideoProject,
    timeline: TimelineState,
    coarse: Boolean,
    selectedTextId: String?,
    onSelectText: (String) -> Unit,
    onMoveText: (String, Float, Float) -> Unit,
    onMoveTextEnd: () -> Unit,
    selectedLayerId: String? = null,
    onSelectLayer: (String) -> Unit = {},
    onMoveLayer: (String, Float, Float) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    // 再生位置が変わっても、表示するコマや文字の組み合わせが変わったときだけ描き直す。
    val frame by remember(project, coarse) {
        derivedStateOf { previewFrameAt(project, timeline.playheadUs, coarse) }
    }
    val visibleTexts by remember(project) {
        derivedStateOf {
            val playhead = timeline.playheadUs
            project.texts.filter { playhead >= it.startUs && playhead < it.endUs }
        }
    }
    val layerFrames by remember(project, coarse) {
        derivedStateOf { project.layers.mapNotNull { layer ->
            previewLayerFrameAt(project, layer, timeline.playheadUs, coarse)?.let { layer to it }
        } }
    }
    val latestLayerMove by rememberUpdatedState(onMoveLayer)
    val latestMove by rememberUpdatedState(onMoveText)
    val latestMoveEnd by rememberUpdatedState(onMoveTextEnd)

    BoxWithConstraints(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        val ratio = project.aspect.ratio
        val boxWidth = if (maxHeight > 0.dp && maxWidth / maxHeight > ratio) maxHeight * ratio else maxWidth
        val boxHeight = boxWidth / ratio
        val density = LocalDensity.current
        val widthPx = with(density) { boxWidth.toPx() }
        val heightPx = with(density) { boxHeight.toPx() }
        val background = Color(project.background)
        Box(
            Modifier
                .size(boxWidth, boxHeight)
                .clipToBounds()
                .background(background)
                .pointerInput(selectedTextId, selectedLayerId, widthPx, heightPx) {
                    val textId = selectedTextId
                    val layerId = selectedLayerId
                    if (textId == null && layerId == null) return@pointerInput
                    if (widthPx <= 0f || heightPx <= 0f) return@pointerInput
                    // 選んだ文字は、プレビューのどこをなぞっても動かせる (指で文字を隠さずに済む)。
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var travelled = Offset.Zero
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val delta = change.position - change.previousPosition
                            travelled += delta
                            if (!dragging && travelled.getDistance() > viewConfiguration.touchSlop) dragging = true
                            if (dragging && delta != Offset.Zero) {
                                change.consume()
                                if (textId != null) latestMove(textId, delta.x / widthPx, delta.y / heightPx)
                                else if (layerId != null) latestLayerMove(layerId, delta.x / widthPx, delta.y / heightPx)
                            }
                        }
                        if (dragging) latestMoveEnd()
                    }
                },
        ) {
            val current = frame
            if (current != null) {
                PreviewFrameImage(frame = current, widthPx = widthPx, heightPx = heightPx)
            } else if (project.mainTrack.isEmpty()) {
                Text(
                    stringResource(R.string.video_edit_empty_hint),
                    color = if (background.luminance() > 0.5f) Color.Black else Color.White,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            }
            layerFrames.forEach { (layer, layerFrame) ->
                key(layer.id) {
                    val asset = project.assets.getValue(layer.clip.assetId)
                    val layerWidth = boxWidth * layer.widthFraction
                    val layerHeight = layerWidth / layer.aspectRatio(asset)
                    val odd = layer.clip.quarterTurns.mod(2) != 0
                    val sourceWidth = if (odd) layerHeight else layerWidth
                    val sourceHeight = if (odd) layerWidth else layerHeight
                    val selected = layer.id == selectedLayerId
                    val latestSelect by rememberUpdatedState(onSelectLayer)
                    Box(Modifier
                        .offset { IntOffset((layer.centerX * widthPx - with(density) { layerWidth.toPx() } / 2f).roundToInt(),
                            (layer.centerY * heightPx - with(density) { layerHeight.toPx() } / 2f).roundToInt()) }
                        .wrapContentSize(Alignment.TopStart, unbounded = true)
                        .requiredSize(layerWidth, layerHeight)
                        .then(if (selected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary) else Modifier)
                        .pointerInput(layer.id) { detectTapGestures { latestSelect(layer.id) } },
                    ) {
                        Box(Modifier.align(Alignment.Center).requiredSize(sourceWidth, sourceHeight)
                            .graphicsLayer { rotationZ = layer.clip.quarterTurns * 90f; alpha = layer.opacity }) {
                            PreviewFrameImage(layerFrame.copy(quarterTurns = 0),
                                with(density) { sourceWidth.toPx() }, with(density) { sourceHeight.toPx() })
                        }
                    }
                }
            }
            visibleTexts.forEach { clip ->
                key(clip.id) {
                    TextOverlay(
                        clip = clip,
                        widthPx = widthPx,
                        heightPx = heightPx,
                        selected = clip.id == selectedTextId,
                        onSelect = { onSelectText(clip.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewFrameImage(frame: PreviewFrame, widthPx: Float, heightPx: Float) {
    val context = LocalContext.current
    val requestSize = maxOf(widthPx, heightPx).roundToInt().coerceIn(64, 1920)
    // 次のコマを読み込む間は、直前のコマを仮に表示してちらつきを抑える。
    val lastKey = remember { arrayOfNulls<String>(1) }
    val request = remember(frame, requestSize) {
        val key = "videoedit-preview:${frame.source}#${frame.sourceTimeUs}#${frame.coarse}#$requestSize"
        val builder = ImageRequest.Builder(context)
            .data(sourceModel(frame.source))
            .size(requestSize)
            .memoryCacheKey(key)
            .crossfade(false)
        lastKey[0]?.let { builder.placeholderMemoryCacheKey(it) }
        if (frame.kind == AssetKind.VIDEO) {
            builder
                .videoFrameMicros(frame.sourceTimeUs)
                .videoFrameOption(if (frame.coarse) MediaMetadataRetriever.OPTION_CLOSEST_SYNC else MediaMetadataRetriever.OPTION_CLOSEST)
        }
        lastKey[0] = key
        builder.build()
    }
    val turns = frame.quarterTurns.mod(4)
    val ratio = if (heightPx > 0f) widthPx / heightPx else 1f
    // 90° 回すと縦横が入れ替わるので、はめ込みなら縮め、埋めるなら広げて枠に合わせる。
    val scale = when {
        turns % 2 == 0 -> 1f
        frame.fill == FrameFill.FIT -> minOf(ratio, 1f / ratio)
        else -> maxOf(ratio, 1f / ratio)
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = if (frame.fill == FrameFill.FILL) ContentScale.Crop else ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                rotationZ = turns * 90f
                scaleX = if (frame.flipHorizontal) -scale else scale
                scaleY = scale
            },
    )
}

@Composable
private fun TextOverlay(
    clip: TextClip,
    widthPx: Float,
    heightPx: Float,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val density = LocalDensity.current
    val fontPx = clip.sizeFraction * heightPx
    val fontSize = with(density) { fontPx.toSp() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val latestSelect by rememberUpdatedState(onSelect)
    val borderColor = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .offset {
                IntOffset(
                    (clip.centerX * widthPx - size.width / 2f).roundToInt(),
                    (clip.centerY * heightPx - size.height / 2f).roundToInt(),
                )
            }
            .onSizeChanged { size = it }
            .then(if (selected) Modifier.border(1.dp, borderColor) else Modifier)
            .pointerInput(clip.id) { detectTapGestures(onTap = { latestSelect() }) }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        val style = TextStyle(fontSize = fontSize, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (clip.outlined) {
            Text(clip.text, style = style.copy(color = Color.Black, drawStyle = Stroke(width = fontPx * 0.12f, join = StrokeJoin.Round)))
        }
        Text(clip.text, style = style.copy(color = Color(clip.color)))
    }
}

/** プレビューに出す素材とコマ。値が同じなら読み込み直さない。 */
@Immutable
internal data class PreviewFrame(
    val source: String,
    val kind: AssetKind,
    val sourceTimeUs: Long,
    val coarse: Boolean,
    val quarterTurns: Int,
    val flipHorizontal: Boolean,
    val fill: FrameFill,
)

/** 再生位置のメインクリップから、表示する素材とコマを決める。 */
internal fun previewFrameAt(project: VideoProject, playheadUs: Long, coarse: Boolean): PreviewFrame? {
    val (index, localUs) = project.mainClipAt(playheadUs) ?: return null
    val clip = project.mainTrack[index]
    return previewClipFrameAt(project, clip, localUs, coarse)
}

internal fun previewLayerFrameAt(project: VideoProject, layer: LayerClip, playheadUs: Long, coarse: Boolean): PreviewFrame? =
    if (layer.isActiveAt(playheadUs)) previewClipFrameAt(project, layer.clip, playheadUs - layer.startUs, coarse) else null

private fun previewClipFrameAt(project: VideoProject, clip: MainClip, localUs: Long, coarse: Boolean): PreviewFrame? {
    val asset = project.assets[clip.assetId] ?: return null
    val sourceUs = clip.sourceTimeAt(localUs)
    val isVideo = asset.kind == AssetKind.VIDEO
    // 切り出した区間の先頭を、丸めで直前の区間へ戻さない。
    val useCoarse = coarse && localUs >= COARSE_STEP_US && clip.durationUs - localUs > COARSE_STEP_US
    val timeUs = when {
        !isVideo -> 0L
        useCoarse -> sourceUs / COARSE_STEP_US * COARSE_STEP_US
        else -> project.frameRate.timeOfFrame(project.frameRate.frameAt(sourceUs))
    }.let { if (isVideo) it.coerceIn(clip.sourceInUs, maxOf(clip.sourceInUs, clip.sourceOutUs - 1L)) else it }
    return PreviewFrame(asset.source, asset.kind, timeUs, useCoarse && isVideo, clip.quarterTurns, clip.flipHorizontal, clip.fill)
}

/** Coil に渡す読み込み元。content:// は Uri、それ以外は端末内のファイル。 */
internal fun sourceModel(source: String): Any = if (source.startsWith("content://")) Uri.parse(source) else File(source)

private const val COARSE_STEP_US = 250_000L
