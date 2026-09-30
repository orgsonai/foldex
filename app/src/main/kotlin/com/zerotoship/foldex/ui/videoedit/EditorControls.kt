// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.VerticalAlignCenter
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zerotoship.foldex.R
import com.zerotoship.foldex.ui.videoedit.model.ClipEditing
import com.zerotoship.foldex.ui.videoedit.model.ClipRef
import com.zerotoship.foldex.ui.videoedit.model.FrameRate
import com.zerotoship.foldex.ui.videoedit.model.VideoProject
import com.zerotoship.foldex.ui.videoedit.model.formatClock
import com.zerotoship.foldex.ui.videoedit.model.formatTimecode
import com.zerotoship.foldex.ui.videoedit.model.parseEditorTime
import com.zerotoship.foldex.ui.videoedit.timeline.TimelineState

@Composable
internal fun TimelineControls(
    timeline: TimelineState,
    rate: FrameRate,
    expanded: Boolean,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconToggleButton(checked = timeline.movingClips, onCheckedChange = { timeline.movingClips = it }, modifier = Modifier.weight(1f).height(56.dp)) {
            ControlLabel(Icons.Default.OpenWith, R.string.video_edit_move_label, R.string.video_edit_move_mode)
        }
        IconToggleButton(checked = timeline.snapping, onCheckedChange = { timeline.snapping = it }, modifier = Modifier.weight(1f).height(56.dp)) {
            ControlLabel(Icons.Default.VerticalAlignCenter, R.string.video_edit_snap_label, R.string.video_edit_snapping)
        }
        IconButton(onClick = { timeline.zoomBy(0.5f, rate) }, modifier = Modifier.weight(1f).height(56.dp)) {
            ControlLabel(Icons.Default.ZoomOut, R.string.video_edit_zoom_out_label, R.string.video_edit_zoom_out)
        }
        IconButton(onClick = { timeline.zoomBy(2f, rate) }, modifier = Modifier.weight(1f).height(56.dp)) {
            ControlLabel(Icons.Default.ZoomIn, R.string.video_edit_zoom_in_label, R.string.video_edit_zoom_in)
        }
        IconButton(onClick = timeline::requestFitAll, modifier = Modifier.weight(1f).height(56.dp)) {
            ControlLabel(Icons.Default.ZoomOutMap, R.string.video_edit_fit_label, R.string.video_edit_fit_all)
        }
        IconToggleButton(checked = expanded, onCheckedChange = { onExpand() }, modifier = Modifier.weight(1f).height(56.dp)) {
            ControlLabel(if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                if (expanded) R.string.video_edit_preview_label else R.string.video_edit_expand_label,
                if (expanded) R.string.video_edit_restore_preview else R.string.video_edit_expand_timeline)
        }
    }
}

@Composable
private fun ControlLabel(icon: ImageVector, label: Int, description: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, stringResource(description))
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun ClipSelectionInfo(project: VideoProject, selection: ClipRef?, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val range = ClipEditing.range(project, selection) ?: return
    val name = when (selection) {
        is ClipRef.Main -> project.mainTrack.firstOrNull { it.id == selection.id }?.assetId?.let { project.assets[it]?.name }
        is ClipRef.Layer -> project.layers.firstOrNull { it.id == selection.id }?.clip?.assetId?.let { project.assets[it]?.name }
        is ClipRef.Audio -> project.audioTrack.firstOrNull { it.id == selection.id }?.assetId?.let { project.assets[it]?.name }
        is ClipRef.Text -> project.texts.firstOrNull { it.id == selection.id }?.text
        null -> null
    }.orEmpty()
    Row(modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatTimecode(range.first, project.frameRate) + " — " + formatTimecode(range.last, project.frameRate),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { onSeek(range.first) }) {
            Icon(Icons.Default.FirstPage, stringResource(R.string.video_edit_clip_start))
        }
        IconButton(onClick = { onSeek((range.last - project.frameRate.frameDurationUs).coerceAtLeast(range.first)) }) {
            Icon(Icons.Default.LastPage, stringResource(R.string.video_edit_clip_end))
        }
    }
}

@Composable
internal fun SeekPositionDialog(timeUs: Long, durationUs: Long, rate: FrameRate, onSeek: (Long) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(formatTimecode(timeUs, rate)) }
    val parsed = parseEditorTime(value, rate)
    val valid = parsed != null && parsed in 0L..durationUs
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.video_edit_seek_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true,
                    label = { Text(stringResource(R.string.video_edit_seek_label)) }, isError = !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.video_edit_seek_hint, formatClock(durationUs)), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onSeek); onDismiss() }, enabled = valid) {
            Text(stringResource(R.string.video_edit_seek_go))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Preview(showBackground = true, locale = "ja", widthDp = 360)
@Composable
private fun TimelineControlsPreview() {
    MaterialTheme { TimelineControls(remember { TimelineState() }, FrameRate.FPS_30, false, {}) }
}
