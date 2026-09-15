// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zerotoship.foldex.R
import com.zerotoship.foldex.ui.imageedit.PALETTE
import com.zerotoship.foldex.ui.videoedit.EditPanel
import com.zerotoship.foldex.ui.videoedit.VideoEditActions
import com.zerotoship.foldex.ui.videoedit.model.AssetKind
import com.zerotoship.foldex.ui.videoedit.model.CanvasAspect
import com.zerotoship.foldex.ui.videoedit.model.ClipRef
import com.zerotoship.foldex.ui.videoedit.model.FrameFill
import com.zerotoship.foldex.ui.videoedit.model.LayerClip
import com.zerotoship.foldex.ui.videoedit.model.LayerEditOps
import com.zerotoship.foldex.ui.videoedit.model.MainClip
import com.zerotoship.foldex.ui.videoedit.model.TextClip
import com.zerotoship.foldex.ui.videoedit.model.US_PER_SECOND
import com.zerotoship.foldex.ui.videoedit.model.VideoEditOps
import com.zerotoship.foldex.ui.videoedit.model.VideoProject
import com.zerotoship.foldex.ui.videoedit.model.formatSeconds
import com.zerotoship.foldex.ui.videoedit.model.formatTimecode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 選んだクリップの設定パネル。スライダーは動かしている間はプレビューだけを更新し、
 * 指を離した時点で 1 手として履歴に積む (1 目盛りごとに Undo が増えないように)。
 */
@Composable
fun EditPanelHost(
    project: VideoProject,
    selection: ClipRef?,
    panel: EditPanel?,
    actions: VideoEditActions,
    modifier: Modifier = Modifier,
) {
    val close = { actions.openPanel(null) }
    val sourceActions = if (selection is ClipRef.Layer) object : VideoEditActions by actions {
        override fun edit(transform: (VideoProject) -> VideoProject) = actions.edit { LayerEditOps.editSource(it, selection.id, transform) }
        override fun preview(transform: (VideoProject) -> VideoProject) = actions.preview { LayerEditOps.editSource(it, selection.id, transform) }
    } else actions
    when (panel) {
        null -> Unit
        EditPanel.SOURCE_RANGE -> mainClipOf(project, selection)?.let { clip ->
            SourceRangePanel(project, clip, sourceActions, close, modifier)
        }
        EditPanel.CANVAS -> CanvasPanel(
            aspect = project.aspect,
            background = project.background,
            onAspect = { aspect -> actions.edit { it.copy(aspect = aspect) } },
            onBackground = { color -> actions.edit { it.copy(background = color) } },
            onDone = close,
            modifier = modifier,
        )
        EditPanel.SPEED -> mainClipOf(project, selection)?.let { clip ->
            SpeedPanel(clip = clip, actions = sourceActions, onDone = close, modifier = modifier)
        }
        EditPanel.TRANSFORM -> mainClipOf(project, selection)?.let { clip ->
            TransformPanel(
                clip = clip,
                onRotate = { turns ->
                    actions.edit { p -> VideoEditOps.updateMain(p, clip.id) { it.copy(quarterTurns = (it.quarterTurns + turns).mod(4)) } }
                },
                onFlip = { actions.edit { p -> VideoEditOps.updateMain(p, clip.id) { it.copy(flipHorizontal = !it.flipHorizontal) } } },
                onFill = { fill -> actions.edit { p -> VideoEditOps.updateMain(p, clip.id) { it.copy(fill = fill) } } },
                onDone = close,
                modifier = modifier,
            )
        }
        EditPanel.VOLUME -> when (selection) {
            is ClipRef.Main, is ClipRef.Layer -> mainClipOf(project, selection)?.let { clip ->
                VolumePanel(
                    volume = clip.volume,
                    muted = clip.muted,
                    fadeInUs = clip.fadeInUs,
                    fadeOutUs = clip.fadeOutUs,
                    durationUs = clip.durationUs,
                    onVolume = { value -> sourceActions.preview { p -> VideoEditOps.updateMain(p, clip.id) { it.copy(volume = value) } } },
                    onMuted = { value -> sourceActions.edit { p -> VideoEditOps.updateMain(p, clip.id) { it.copy(muted = value) } } },
                    onFades = { fadeIn, fadeOut -> sourceActions.preview { p -> VideoEditOps.setMainFades(p, clip.id, fadeIn, fadeOut) } },
                    onCommit = actions::commit,
                    onDone = close,
                    modifier = modifier,
                )
            }
            is ClipRef.Audio -> project.audioTrack.firstOrNull { it.id == selection.id }?.let { clip ->
                VolumePanel(
                    volume = clip.volume,
                    muted = clip.muted,
                    fadeInUs = clip.fadeInUs,
                    fadeOutUs = clip.fadeOutUs,
                    durationUs = clip.durationUs,
                    onVolume = { value -> actions.preview { p -> VideoEditOps.updateAudio(p, clip.id) { it.copy(volume = value) } } },
                    onMuted = { value -> actions.edit { p -> VideoEditOps.updateAudio(p, clip.id) { it.copy(muted = value) } } },
                    onFades = { fadeIn, fadeOut -> actions.preview { p -> VideoEditOps.setAudioFades(p, clip.id, fadeIn, fadeOut) } },
                    onCommit = actions::commit,
                    onDone = close,
                    modifier = modifier,
                )
            }
            else -> Unit
        }
        EditPanel.LAYER -> project.layers.firstOrNull { it.id == selection?.id }?.let { layer ->
            LayerPanel(project, layer, actions, close, modifier)
        }
        EditPanel.TEXT -> (selection as? ClipRef.Text)
            ?.let { ref -> project.texts.firstOrNull { it.id == ref.id } }
            ?.let { clip -> TextPanel(clip = clip, actions = actions, onDone = close, modifier = modifier) }
    }
}

private fun mainClipOf(project: VideoProject, selection: ClipRef?): MainClip? =
    when (selection) {
        is ClipRef.Main -> project.mainTrack.firstOrNull { it.id == selection.id }
        is ClipRef.Layer -> project.layers.firstOrNull { it.id == selection.id }?.clip
        else -> null
    }

@Composable
private fun PanelSurface(
    title: String,
    onDone: () -> Unit,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = onDone) { Text(stringResource(R.string.action_done)) }
            }
            content()
        }
    }
}

// ---- 使用区間 ----

/** 両端の間隔を保って使用区間をずらす。タイムライン上での並べ替えとは別の編集。 */
@Composable
private fun SourceRangePanel(
    project: VideoProject,
    clip: MainClip,
    actions: VideoEditActions,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val asset = project.assets[clip.assetId]
    val maxStart = ((asset?.durationUs ?: 0L) - clip.sourceDurationUs).coerceAtLeast(0L)
    val canShift = asset?.kind == AssetKind.VIDEO && maxStart > 0L
    val label = stringResource(R.string.video_edit_source_range)
    PanelSurface(label, onDone, modifier) {
        Text(asset?.name.orEmpty(), style = MaterialTheme.typography.labelMedium)
        Text(stringResource(R.string.video_edit_source_range_value,
            formatTimecode(clip.sourceInUs, project.frameRate), formatTimecode(clip.sourceOutUs, project.frameRate)))
        Text(stringResource(if (canShift) R.string.video_edit_source_range_hint else R.string.video_edit_source_range_full),
            style = MaterialTheme.typography.bodySmall)
        if (canShift) {
            Slider(
                value = (clip.sourceInUs.toDouble() / maxStart).toFloat().coerceIn(0f, 1f),
                onValueChange = { fraction ->
                    val start = (fraction.toDouble() * maxStart).roundToLong()
                    actions.preview { VideoEditOps.shiftMainSource(it, clip.id, start) }
                },
                onValueChangeFinished = actions::commit,
                modifier = Modifier.semantics { contentDescription = label },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { actions.edit { VideoEditOps.shiftMainSource(it, clip.id, clip.sourceInUs - project.frameRate.frameDurationUs) } },
                    enabled = clip.sourceInUs > 0L) { Text(stringResource(R.string.video_edit_source_previous)) }
                TextButton(onClick = { actions.edit { VideoEditOps.shiftMainSource(it, clip.id, clip.sourceInUs + project.frameRate.frameDurationUs) } },
                    enabled = clip.sourceInUs < maxStart) { Text(stringResource(R.string.video_edit_source_next)) }
            }
        }
    }
}

// ---- 速度 ----

@Composable
private fun SpeedPanel(clip: MainClip, actions: VideoEditActions, onDone: () -> Unit, modifier: Modifier) {
    PanelSurface(stringResource(R.string.video_edit_tool_speed), onDone, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatSpeed(clip.speed), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(80.dp))
            Text(
                stringResource(R.string.video_edit_speed_duration, formatSeconds(clip.sourceDurationUs), formatSeconds(clip.durationUs)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 0.1〜16 倍を対数で並べ、1 倍付近を細かく合わせられるようにする。
        Slider(
            value = speedToSlider(clip.speed),
            onValueChange = { position -> actions.preview { p -> VideoEditOps.setMainSpeed(p, clip.id, sliderToSpeed(position)) } },
            onValueChangeFinished = actions::commit,
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SPEED_PRESETS.forEach { preset ->
                FilterChip(
                    selected = abs(clip.speed - preset) < 0.005,
                    onClick = { actions.edit { p -> VideoEditOps.setMainSpeed(p, clip.id, preset) } },
                    label = { Text(formatSpeed(preset)) },
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.video_edit_keep_pitch), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(
                checked = clip.keepPitch,
                onCheckedChange = { keep -> actions.edit { p -> VideoEditOps.updateMain(p, clip.id) { it.copy(keepPitch = keep) } } },
            )
        }
        if (clip.speed < 0.5) {
            Text(
                stringResource(R.string.video_edit_slow_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val SPEED_PRESETS = listOf(0.5, 1.0, 2.0, 4.0)

private fun formatSpeed(speed: Double): String = String.format(Locale.US, "%.2f×", speed)

private fun speedToSlider(speed: Double): Float =
    (ln(speed / VideoEditOps.MIN_SPEED) / ln(VideoEditOps.MAX_SPEED / VideoEditOps.MIN_SPEED)).toFloat().coerceIn(0f, 1f)

private fun sliderToSpeed(position: Float): Double {
    val raw = VideoEditOps.MIN_SPEED * (VideoEditOps.MAX_SPEED / VideoEditOps.MIN_SPEED).pow(position.toDouble())
    return (raw * 100).roundToLong() / 100.0
}

// ---- 音量 ----

@Composable
private fun VolumePanel(
    volume: Float,
    muted: Boolean,
    fadeInUs: Long,
    fadeOutUs: Long,
    durationUs: Long,
    onVolume: (Float) -> Unit,
    onMuted: (Boolean) -> Unit,
    onFades: (Long, Long) -> Unit,
    onCommit: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier,
) {
    val maxFadeUs = minOf(MAX_FADE_US, durationUs / 2).coerceAtLeast(0L)
    PanelSurface(stringResource(R.string.video_edit_tool_volume), onDone, modifier) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.video_edit_volume_value, (volume * 100).roundToInt()),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.video_edit_mute), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            Switch(checked = muted, onCheckedChange = onMuted)
        }
        Slider(
            value = volume.coerceIn(0f, 2f),
            onValueChange = onVolume,
            onValueChangeFinished = onCommit,
            valueRange = 0f..2f,
            enabled = !muted,
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
        )
        if (maxFadeUs > 0L) {
            Text(stringResource(R.string.video_edit_fade_in, formatSeconds(fadeInUs)), style = MaterialTheme.typography.labelMedium)
            Slider(
                value = fadeInUs.coerceAtMost(maxFadeUs).toFloat(),
                onValueChange = { onFades(it.roundToLong(), fadeOutUs) },
                onValueChangeFinished = onCommit,
                valueRange = 0f..maxFadeUs.toFloat(),
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
            )
            Text(stringResource(R.string.video_edit_fade_out, formatSeconds(fadeOutUs)), style = MaterialTheme.typography.labelMedium)
            Slider(
                value = fadeOutUs.coerceAtMost(maxFadeUs).toFloat(),
                onValueChange = { onFades(fadeInUs, it.roundToLong()) },
                onValueChangeFinished = onCommit,
                valueRange = 0f..maxFadeUs.toFloat(),
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
            )
        }
    }
}

private const val MAX_FADE_US = 3L * US_PER_SECOND

// ---- 変形 ----

@Composable
private fun TransformPanel(
    clip: MainClip,
    onRotate: (Int) -> Unit,
    onFlip: () -> Unit,
    onFill: (FrameFill) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier,
) {
    PanelSurface(stringResource(R.string.video_edit_tool_transform), onDone, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onRotate(-1) }) {
                Icon(Icons.Default.Rotate90DegreesCcw, contentDescription = stringResource(R.string.image_rotate_left))
            }
            IconButton(onClick = { onRotate(1) }) {
                Icon(Icons.Default.Rotate90DegreesCw, contentDescription = stringResource(R.string.image_rotate_right))
            }
            IconButton(onClick = onFlip) {
                Icon(Icons.Default.Flip, contentDescription = stringResource(R.string.image_flip_horizontal))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = clip.fill == FrameFill.FIT,
                onClick = { onFill(FrameFill.FIT) },
                label = { Text(stringResource(R.string.video_edit_fit)) },
            )
            FilterChip(
                selected = clip.fill == FrameFill.FILL,
                onClick = { onFill(FrameFill.FILL) },
                label = { Text(stringResource(R.string.video_edit_fill)) },
            )
        }
    }
}

// ---- 文字 ----

@Composable
private fun TextPanel(clip: TextClip, actions: VideoEditActions, onDone: () -> Unit, modifier: Modifier) {
    // 1 文字ごとに履歴を積まない。パネルを閉じる・別の文字に切り替えるときに 1 手へまとめる。
    val latestActions by rememberUpdatedState(actions)
    DisposableEffect(clip.id) { onDispose { latestActions.commit() } }
    // 入力欄は手元の値で持ち、ViewModel の反映を待たずに文字が消えたりカーソルが飛んだりしないようにする。
    var text by remember(clip.id) { mutableStateOf(clip.text) }
    LaunchedEffect(clip.text) { if (clip.text != text) text = clip.text }
    PanelSurface(stringResource(R.string.video_edit_tool_text), onDone, modifier) {
        OutlinedTextField(
            value = text,
            onValueChange = { value ->
                text = value
                actions.preview { p -> VideoEditOps.updateText(p, clip.id) { it.copy(text = value) } }
            },
            maxLines = 3,
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
        )
        Text(
            stringResource(R.string.video_edit_text_size, (clip.sizeFraction * 100).roundToInt()),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Slider(
            value = clip.sizeFraction.coerceIn(0.02f, 0.25f),
            onValueChange = { value -> actions.preview { p -> VideoEditOps.updateText(p, clip.id) { it.copy(sizeFraction = value) } } },
            onValueChangeFinished = actions::commit,
            valueRange = 0.02f..0.25f,
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PALETTE.forEach { color ->
                ColorSwatch(
                    color = color,
                    selected = (clip.color and 0xFFFFFF) == (color and 0xFFFFFF),
                    onClick = { actions.edit { p -> VideoEditOps.updateText(p, clip.id) { it.copy(color = color) } } },
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = clip.outlined,
                onClick = { actions.edit { p -> VideoEditOps.updateText(p, clip.id) { it.copy(outlined = !it.outlined) } } },
                label = { Text(stringResource(R.string.video_edit_text_outline)) },
            )
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.video_edit_text_duration, formatSeconds(clip.durationUs)), style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = { actions.edit { p -> VideoEditOps.trimTextEnd(p, clip.id, clip.endUs - HALF_SECOND_US) } }) {
                Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.video_edit_shorter))
            }
            IconButton(onClick = { actions.edit { p -> VideoEditOps.trimTextEnd(p, clip.id, clip.endUs + HALF_SECOND_US) } }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.video_edit_longer))
            }
        }
    }
}

private const val HALF_SECOND_US = US_PER_SECOND / 2

// ---- 比率 ----

@Composable
private fun CanvasPanel(
    aspect: CanvasAspect,
    background: Int,
    onAspect: (CanvasAspect) -> Unit,
    onBackground: (Int) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier,
) {
    PanelSurface(stringResource(R.string.video_edit_tool_canvas), onDone, modifier) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CanvasAspect.entries.forEach { item ->
                FilterChip(selected = aspect == item, onClick = { onAspect(item) }, label = { Text(aspectLabel(item)) })
            }
        }
        Text(
            stringResource(R.string.video_edit_background),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PALETTE.forEach { color ->
                ColorSwatch(color = color, selected = (background and 0xFFFFFF) == (color and 0xFFFFFF), onClick = { onBackground(color) })
            }
        }
    }
}

@Composable
private fun aspectLabel(aspect: CanvasAspect): String = when (aspect) {
    CanvasAspect.R16_9 -> stringResource(R.string.video_edit_aspect_landscape)
    CanvasAspect.R9_16 -> stringResource(R.string.video_edit_aspect_portrait)
    CanvasAspect.R1_1 -> stringResource(R.string.video_edit_aspect_square)
    CanvasAspect.R4_5 -> "4:5"
    CanvasAspect.R4_3 -> "4:3"
}

@Composable
private fun ColorSwatch(color: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(if (selected) 34.dp else 28.dp)
            .clip(CircleShape)
            .background(Color(color))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

@Preview(name = "Speed", showBackground = true, widthDp = 360, locale = "ja")
@Composable
private fun SpeedPanelPreview() {
    MaterialTheme {
        SpeedPanel(
            clip = MainClip("c", "a", 0L, 12L * US_PER_SECOND, speed = 2.0),
            actions = VideoEditActions.None,
            onDone = {},
            modifier = Modifier,
        )
    }
}

@Preview(name = "Canvas", showBackground = true, widthDp = 360, locale = "ja")
@Composable
private fun CanvasPanelPreview() {
    MaterialTheme {
        CanvasPanel(aspect = CanvasAspect.R9_16, background = 0xFF000000.toInt(), onAspect = {}, onBackground = {}, onDone = {}, modifier = Modifier)
    }
}

@Composable
private fun LayerPanel(project: VideoProject, layer: LayerClip, actions: VideoEditActions, onDone: () -> Unit, modifier: Modifier) {
    fun edit(transform: (LayerClip) -> LayerClip) = actions.edit { LayerEditOps.update(it, layer.id, transform) }
    fun preview(transform: (LayerClip) -> LayerClip) = actions.preview { LayerEditOps.update(it, layer.id, transform) }
    PanelSurface(stringResource(R.string.video_edit_layer_layout), onDone, modifier) {
        Text(project.assets[layer.clip.assetId]?.name.orEmpty(), style = MaterialTheme.typography.labelMedium)
        Text(stringResource(R.string.video_edit_layer_hint), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.video_edit_layer_visible), modifier = Modifier.weight(1f))
            Switch(checked = layer.visible, onCheckedChange = { value -> edit { it.copy(visible = value) } })
        }
        LayerSlider(stringResource(R.string.video_edit_layer_width), layer.widthFraction, 0.05f..2f, actions::commit) {
            value -> preview { it.copy(widthFraction = value) }
        }
        LayerSlider(stringResource(R.string.video_edit_layer_opacity), layer.opacity, 0f..1f, actions::commit) {
            value -> preview { it.copy(opacity = value) }
        }
        LayerSlider(stringResource(R.string.video_edit_layer_x), layer.centerX, 0f..1f, actions::commit) {
            value -> preview { it.copy(centerX = value) }
        }
        LayerSlider(stringResource(R.string.video_edit_layer_y), layer.centerY, 0f..1f, actions::commit) {
            value -> preview { it.copy(centerY = value) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { edit { it.copy(clip = it.clip.copy(quarterTurns = (it.clip.quarterTurns + 1).mod(4))) } }) {
                Icon(Icons.Default.Rotate90DegreesCw, stringResource(R.string.image_rotate_right))
            }
            IconButton(onClick = { edit { it.copy(clip = it.clip.copy(flipHorizontal = !it.clip.flipHorizontal)) } }) {
                Icon(Icons.Default.Flip, stringResource(R.string.image_flip_horizontal))
            }
            TextButton(onClick = { edit { it.copy(centerX = 0.5f, centerY = 0.5f) } }) {
                Text(stringResource(R.string.video_edit_layer_center))
            }
            val index = project.layers.indexOfFirst { it.id == layer.id }
            TextButton(enabled = index < project.layers.lastIndex, onClick = { actions.edit { LayerEditOps.reorder(it, layer.id, 1) } }) {
                Text(stringResource(R.string.video_edit_layer_forward))
            }
            TextButton(enabled = index > 0, onClick = { actions.edit { LayerEditOps.reorder(it, layer.id, -1) } }) {
                Text(stringResource(R.string.video_edit_layer_backward))
            }
        }
    }
}

@Composable
private fun LayerSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onCommit: () -> Unit, onChange: (Float) -> Unit) {
    Text("$label · ${(value * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
    Slider(value = value.coerceIn(range), onValueChange = onChange, onValueChangeFinished = onCommit,
        valueRange = range, modifier = Modifier.semantics { contentDescription = label })
}
