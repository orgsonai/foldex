// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zerotoship.foldex.R
import com.zerotoship.foldex.ui.videoedit.model.ClipRef

enum class ToolAction {
    ADD_VIDEO, ADD_IMAGE, ADD_AUDIO, ADD_TEXT, ADD_LAYER_VIDEO, ADD_LAYER_IMAGE, LAYER, CANVAS,
    SPLIT, TRIM_BEFORE, TRIM_AFTER, SOURCE_RANGE, SPEED, VOLUME, TRANSFORM, EDIT_TEXT, DUPLICATE, DELETE, DESELECT,
}

private data class ToolItem(
    val action: ToolAction,
    val icon: ImageVector,
    @StringRes val label: Int,
    /** 押すと開くパネル。開いている間は強調表示する。 */
    val panel: EditPanel? = null,
)

/**
 * 下部の操作列。何も選んでいなければ「追加」、クリップを選んでいればそのクリップの操作を出す。
 * その場で使えない操作を灰色で並べない (画像編集と同じ規則)。
 */
@Composable
fun VideoEditToolbar(
    selection: ClipRef?,
    activePanel: EditPanel?,
    enabled: Boolean,
    onAction: (ToolAction) -> Unit,
    sourceRangeAvailable: Boolean = true,
    audioAvailable: Boolean = true,
    canCut: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val items = when (selection) {
        null -> listOf(
            ToolItem(ToolAction.ADD_VIDEO, Icons.Default.VideoLibrary, R.string.video_edit_tool_video),
            ToolItem(ToolAction.ADD_AUDIO, Icons.Default.MusicNote, R.string.video_edit_tool_audio),
            ToolItem(ToolAction.ADD_TEXT, Icons.Default.Title, R.string.video_edit_tool_text),
            ToolItem(ToolAction.CANVAS, Icons.Default.AspectRatio, R.string.video_edit_tool_canvas, EditPanel.CANVAS),
        )
        is ClipRef.Main -> listOf(
            ToolItem(ToolAction.SPLIT, Icons.Default.ContentCut, R.string.video_edit_tool_split),
            ToolItem(ToolAction.TRIM_BEFORE, Icons.Default.FirstPage, R.string.video_edit_trim_before),
            ToolItem(ToolAction.TRIM_AFTER, Icons.Default.LastPage, R.string.video_edit_trim_after),
            ToolItem(ToolAction.SOURCE_RANGE, Icons.Default.Edit, R.string.video_edit_source_range, EditPanel.SOURCE_RANGE),
            ToolItem(ToolAction.SPEED, Icons.Default.Speed, R.string.video_edit_tool_speed, EditPanel.SPEED),
            ToolItem(ToolAction.VOLUME, Icons.AutoMirrored.Filled.VolumeUp, R.string.video_edit_tool_volume, EditPanel.VOLUME),
            ToolItem(ToolAction.TRANSFORM, Icons.Default.Crop, R.string.video_edit_tool_transform, EditPanel.TRANSFORM),
            ToolItem(ToolAction.DUPLICATE, Icons.Default.ContentCopy, R.string.video_edit_tool_duplicate),
            ToolItem(ToolAction.DELETE, Icons.Default.Delete, R.string.action_delete),
        )
        is ClipRef.Layer -> listOf(
            ToolItem(ToolAction.LAYER, Icons.Default.Layers, R.string.video_edit_layer_layout, EditPanel.LAYER),
            ToolItem(ToolAction.SPLIT, Icons.Default.ContentCut, R.string.video_edit_tool_split),
            ToolItem(ToolAction.TRIM_BEFORE, Icons.Default.FirstPage, R.string.video_edit_trim_before),
            ToolItem(ToolAction.TRIM_AFTER, Icons.Default.LastPage, R.string.video_edit_trim_after),
            ToolItem(ToolAction.SOURCE_RANGE, Icons.Default.Edit, R.string.video_edit_source_range, EditPanel.SOURCE_RANGE),
            ToolItem(ToolAction.SPEED, Icons.Default.Speed, R.string.video_edit_tool_speed, EditPanel.SPEED),
            ToolItem(ToolAction.VOLUME, Icons.AutoMirrored.Filled.VolumeUp, R.string.video_edit_tool_volume, EditPanel.VOLUME),
            ToolItem(ToolAction.TRANSFORM, Icons.Default.Crop, R.string.video_edit_tool_transform, EditPanel.TRANSFORM),
            ToolItem(ToolAction.DUPLICATE, Icons.Default.ContentCopy, R.string.video_edit_tool_duplicate),
            ToolItem(ToolAction.DELETE, Icons.Default.Delete, R.string.action_delete),
        )
        is ClipRef.Text -> listOf(
            ToolItem(ToolAction.EDIT_TEXT, Icons.Default.Edit, R.string.action_edit, EditPanel.TEXT),
            ToolItem(ToolAction.SPLIT, Icons.Default.ContentCut, R.string.video_edit_tool_split),
            ToolItem(ToolAction.TRIM_BEFORE, Icons.Default.FirstPage, R.string.video_edit_trim_before),
            ToolItem(ToolAction.TRIM_AFTER, Icons.Default.LastPage, R.string.video_edit_trim_after),
            ToolItem(ToolAction.DUPLICATE, Icons.Default.ContentCopy, R.string.video_edit_tool_duplicate),
            ToolItem(ToolAction.DELETE, Icons.Default.Delete, R.string.action_delete),
        )
        is ClipRef.Audio -> listOf(
            ToolItem(ToolAction.SPLIT, Icons.Default.ContentCut, R.string.video_edit_tool_split),
            ToolItem(ToolAction.TRIM_BEFORE, Icons.Default.FirstPage, R.string.video_edit_trim_before),
            ToolItem(ToolAction.TRIM_AFTER, Icons.Default.LastPage, R.string.video_edit_trim_after),
            ToolItem(ToolAction.VOLUME, Icons.AutoMirrored.Filled.VolumeUp, R.string.video_edit_tool_volume, EditPanel.VOLUME),
            ToolItem(ToolAction.DUPLICATE, Icons.Default.ContentCopy, R.string.video_edit_tool_duplicate),
            ToolItem(ToolAction.DELETE, Icons.Default.Delete, R.string.action_delete),
        )
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AddMediaMenu(enabled, onAction)
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    if (selection != null) {
                        IconButton(onClick = { onAction(ToolAction.DESELECT) }, enabled = enabled) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.video_edit_deselect))
                        }
                    }
                    items.filter {
                        when (it.action) {
                            ToolAction.SOURCE_RANGE, ToolAction.SPEED -> sourceRangeAvailable
                            ToolAction.VOLUME -> audioAvailable
                            else -> true
                        }
                    }.forEach { item ->
                        ToolButton(
                            icon = item.icon,
                            label = stringResource(item.label),
                            active = item.panel != null && item.panel == activePanel,
                            enabled = enabled && (item.action !in listOf(ToolAction.SPLIT, ToolAction.TRIM_BEFORE, ToolAction.TRIM_AFTER) || canCut),
                            onClick = { onAction(item.action) },
                            modifier = Modifier.widthIn(min = 56.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = when {
        !enabled -> LocalContentColor.current.copy(alpha = 0.38f)
        active -> MaterialTheme.colorScheme.primary
        else -> LocalContentColor.current
    }
    Surface(onClick = onClick, enabled = enabled, color = Color.Transparent,
        modifier = modifier.heightIn(min = 56.dp).semantics { selected = active }) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
        }
    }
}

/** 選択中でも素材を追加できる入口をスクロール列の外に置く。 */
@Composable
private fun AddMediaMenu(enabled: Boolean, onAction: (ToolAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(Icons.Default.Add, stringResource(R.string.video_edit_add_media), tint = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(
                ToolAction.ADD_VIDEO to R.string.video_edit_tool_video,
                ToolAction.ADD_IMAGE to R.string.video_edit_tool_image,
                ToolAction.ADD_AUDIO to R.string.video_edit_tool_audio,
                ToolAction.ADD_TEXT to R.string.video_edit_tool_text,
                ToolAction.ADD_LAYER_VIDEO to R.string.video_edit_layer_video,
                ToolAction.ADD_LAYER_IMAGE to R.string.video_edit_layer_image,
                ToolAction.CANVAS to R.string.video_edit_tool_canvas,
            ).forEach { (action, label) ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { expanded = false; onAction(action) })
            }
        }
    }
}

@Preview(name = "Nothing selected", showBackground = true, widthDp = 360, locale = "ja")
@Composable
private fun VideoEditToolbarPreview() {
    MaterialTheme { VideoEditToolbar(selection = null, activePanel = null, enabled = true, onAction = {}) }
}

@Preview(name = "Clip selected", showBackground = true, widthDp = 360, locale = "ja")
@Composable
private fun VideoEditToolbarClipPreview() {
    MaterialTheme { VideoEditToolbar(selection = ClipRef.Main("c"), activePanel = EditPanel.SPEED, enabled = true, onAction = {}) }
}
