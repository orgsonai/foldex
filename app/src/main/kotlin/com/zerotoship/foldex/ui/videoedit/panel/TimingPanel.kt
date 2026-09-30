// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zerotoship.foldex.R
import com.zerotoship.foldex.ui.videoedit.VideoEditActions
import com.zerotoship.foldex.ui.videoedit.model.ClipEditing
import com.zerotoship.foldex.ui.videoedit.model.ClipRef
import com.zerotoship.foldex.ui.videoedit.model.ClipTiming
import com.zerotoship.foldex.ui.videoedit.model.FrameRate
import com.zerotoship.foldex.ui.videoedit.model.TextClip
import com.zerotoship.foldex.ui.videoedit.model.VideoEditOps
import com.zerotoship.foldex.ui.videoedit.model.VideoProject
import com.zerotoship.foldex.ui.videoedit.model.editorSeconds
import com.zerotoship.foldex.ui.videoedit.model.formatTimecode
import com.zerotoship.foldex.ui.videoedit.model.parseEditorTime

@Composable
internal fun TimingPanel(
    project: VideoProject,
    selection: ClipRef,
    playheadUs: Long,
    onSeek: (Long) -> Unit,
    actions: VideoEditActions,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember(selection) { mutableStateOf<ClipTiming.Field?>(null) }
    val range = ClipEditing.range(project, selection) ?: return
    PanelSurface(stringResource(R.string.video_edit_tool_timing), onDone, modifier) {
        if (selection is ClipRef.Main) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.video_edit_time_start), modifier = Modifier.weight(1f))
                Text(formatTimecode(range.first, project.frameRate), style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace))
            }
            Text(stringResource(R.string.video_edit_time_main_hint), style = MaterialTheme.typography.bodySmall)
            val index = project.mainIndexOf(selection.id)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = index > 0, onClick = {
                    actions.edit { p -> VideoEditOps.moveMain(p, p.mainIndexOf(selection.id), p.mainIndexOf(selection.id) - 1) }
                    onSeek(project.mainStartUs(index - 1))
                }) { Text(stringResource(R.string.video_edit_order_previous)) }
                TextButton(enabled = index in 0 until project.mainTrack.lastIndex, onClick = {
                    actions.edit { p -> VideoEditOps.moveMain(p, p.mainIndexOf(selection.id), p.mainIndexOf(selection.id) + 1) }
                    onSeek(range.first + project.mainTrack[index + 1].durationUs)
                }) { Text(stringResource(R.string.video_edit_order_next)) }
            }
        }
        ClipTiming.Field.entries.forEach { field ->
            val bounds = ClipTiming.bounds(project, selection, field) ?: return@forEach
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(timingLabel(field)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { editing = field }) {
                    Text(formatTimecode(bounds.valueUs, project.frameRate),
                        style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(end = 8.dp))
                    Icon(Icons.Default.Edit, stringResource(R.string.action_edit))
                }
            }
        }
        ClipTiming.bounds(project, selection, ClipTiming.Field.START)?.let { bounds ->
            TextButton(enabled = bounds.accepts(playheadUs) && bounds.valueUs != playheadUs, onClick = {
                actions.edit { ClipTiming.set(it, selection, ClipTiming.Field.START, playheadUs) }
            }) { Text(stringResource(R.string.video_edit_time_at_playhead)) }
        }
        Text(stringResource(if (selection is ClipRef.Text) R.string.video_edit_time_input_hint else R.string.video_edit_time_source_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    editing?.let { field ->
        ClipTiming.bounds(project, selection, field)?.let { bounds ->
            TimeValueDialog(
                title = stringResource(timingLabel(field)), bounds = bounds, rate = project.frameRate,
                onApply = { value ->
                    actions.edit { ClipTiming.set(it, selection, field, value) }
                    editing = null
                },
                onDismiss = { editing = null },
            )
        }
    }
}

private fun timingLabel(field: ClipTiming.Field): Int = when (field) {
    ClipTiming.Field.START -> R.string.video_edit_time_start
    ClipTiming.Field.DURATION -> R.string.video_edit_time_duration
    ClipTiming.Field.SOURCE_IN -> R.string.video_edit_time_source_in
    ClipTiming.Field.SOURCE_OUT -> R.string.video_edit_time_source_out
}

@Composable
private fun TimeValueDialog(
    title: String,
    bounds: ClipTiming.Bounds,
    rate: FrameRate,
    onApply: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(editorSeconds(bounds.valueUs).let { TextFieldValue(it, TextRange(0, it.length)) }) }
    val focus = remember { FocusRequester() }
    val parsed = parseEditorTime(input.text, rate)
    val valid = parsed != null && bounds.accepts(parsed)
    fun apply() { if (valid) parsed?.let(onApply) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, singleLine = true, isError = !valid,
                    label = { Text(stringResource(R.string.video_edit_seek_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { apply() }),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Text(stringResource(R.string.video_edit_time_allowed, editorSeconds(bounds.minUs), editorSeconds(bounds.maxUs)),
                    style = MaterialTheme.typography.bodySmall)
                // ダイアログの別ウィンドウに入力欄が付いてからフォーカスを要求する。
                LaunchedEffect(focus) { focus.requestFocus() }
            }
        },
        confirmButton = { TextButton(onClick = ::apply, enabled = valid) { Text(stringResource(R.string.action_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Preview(showBackground = true, widthDp = 360, locale = "ja")
@Composable
private fun TimingPanelPreview() {
    MaterialTheme {
        TimingPanel(VideoProject(texts = listOf(TextClip("t", 1_000_000L, 2_500_000L, "字幕"))),
            ClipRef.Text("t"), 0L, {}, VideoEditActions.None, {})
    }
}
