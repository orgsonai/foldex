// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zerotoship.foldex.ui.imageedit.engine.DocumentRenderer
import com.zerotoship.foldex.ui.imageedit.model.EditRect
import com.zerotoship.foldex.ui.imageedit.model.EditSize
import com.zerotoship.foldex.ui.imageedit.model.StrokeMode
import com.zerotoship.foldex.ui.imageedit.model.SelectionMode
import com.zerotoship.foldex.ui.imageedit.model.SelectionShape
import com.zerotoship.foldex.R

/**
 * 画像エディタの画面。
 *
 * 構成は上から: トップバー (戻る / 元に戻す・やり直す / 保存) → キャンバス →
 * 選択中ツールの設定行 → ツールバー。ツール未選択なら設定行は畳む。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageEditScreen(
    state: ImageEditUiState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSelectTool: (EditTool?) -> Unit,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onFlipHorizontal: () -> Unit,
    onFlipVertical: () -> Unit,
    onApplyCrop: (EditRect) -> Unit,
    onResetCrop: () -> Unit,
    onSetLongEdge: (Int) -> Unit,
    onSetPercent: (Int) -> Unit,
    onSetFormat: (com.zerotoship.foldex.ui.imageedit.model.ImageFormat) -> Unit,
    onSetQuality: (Int) -> Unit,
    onRequestEstimate: () -> Unit,
    onAddStroke: (com.zerotoship.foldex.ui.imageedit.model.Stroke) -> Unit,
    onAddText: (Float, Int) -> Unit,
    onSelectText: (String?) -> Unit,
    onChangeText: ((com.zerotoship.foldex.ui.imageedit.model.Layer.Text) -> com.zerotoship.foldex.ui.imageedit.model.Layer.Text) -> Unit,
    onMoveText: (Float, Float) -> Unit,
    onMoveTextEnd: () -> Unit,
    onDeleteText: () -> Unit,
    onApplySelection: (
        com.zerotoship.foldex.ui.imageedit.model.SelectionMode,
        com.zerotoship.foldex.ui.imageedit.model.SelectionSpec,
    ) -> Unit,
    onClearSelection: () -> Unit,
    onInvertSelection: () -> Unit,
    onFillSelection: (Int) -> Unit,
    onEraseSelection: () -> Unit,
    onBeginMosaic: () -> Unit,
    onMosaicPercent: (Float) -> Unit,
    onCommitMosaic: () -> Unit,
    onCancelMosaic: () -> Unit,
    onSelectObject: (com.zerotoship.foldex.ui.imageedit.model.EditPoint, SelectionMode) -> Unit,
    onCancelObjectSelection: () -> Unit,
    onSave: (SaveRequest) -> Unit,
) {
    val doc = state.document
    // 切り抜き枠は 0..1 の正規化座標で持つ (キャンバスの大きさが変わっても意味が変わらない)。
    var cropRect by remember { mutableStateOf(FULL_CROP) }
    var cropAspect by remember { mutableStateOf(CropAspect.FREE) }
    var brush by remember { mutableStateOf(DEFAULT_BRUSH) }
    var selectShape by remember { mutableStateOf(SelectionShape.OBJECT) }
    var selectMode by remember { mutableStateOf(SelectionMode.REPLACE) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    // ツールを切り替えたら枠を初期化する。
    LaunchedEffect(state.activeTool, doc?.canvas) {
        if (state.activeTool == EditTool.CROP) {
            cropRect = FULL_CROP
            cropAspect = CropAspect.FREE
        }
    }

    BackHandler(enabled = true) {
        when {
            state.saving -> Unit
            state.selectingObject -> onCancelObjectSelection()
            showSaveDialog -> showSaveDialog = false
            state.mosaicDraft != null -> onCancelMosaic()
            state.activeTool != null -> onSelectTool(null)
            state.dirty -> showDiscardDialog = true
            else -> onBack()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val size = state.outputSize
                        if (size != null) {
                            Text(
                                "${size.width}×${size.height}" +
                                    state.estimatedBytes?.let { "  ·  ${formatBytes(it.toLong())}" }.orEmpty() +
                                    if (state.estimating) "  ·  ${stringResource(R.string.image_calculating)}" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.selectingObject) onCancelObjectSelection()
                        else if (state.mosaicDraft != null) onCancelMosaic()
                        else if (state.dirty) showDiscardDialog = true else onBack()
                    }, enabled = !state.saving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_back))
                    }
                },
                actions = {
                    IconButton(onClick = onUndo, enabled = !state.saving && (state.canUndo || state.mosaicDraft != null)) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.action_undo))
                    }
                    IconButton(onClick = onRedo, enabled = state.canRedo && !state.saving && state.mosaicDraft == null) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.action_redo))
                    }
                    TextButton(
                        onClick = {
                            onRequestEstimate()
                            showSaveDialog = true
                        },
                        enabled = doc != null && !state.saving && state.mosaicDraft == null,
                    ) { Text(stringResource(R.string.action_save)) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        state.loading -> CenterProgress(stringResource(R.string.loading))
                        state.error != null -> CenterMessage(state.error)
                        else -> EditCanvas(
                            preview = state.preview,
                            canvasSize = doc?.let { EditSize(it.canvas.width, it.canvas.height) },
                            mode = when {
                                state.mosaicDraft != null || state.saving -> CanvasMode.View
                                state.activeTool == EditTool.CROP -> CanvasMode.Crop(
                                    rect = cropRect,
                                    onChange = { next ->
                                        val ratio = cropAspect.effectiveRatio(
                                            doc?.canvas?.width ?: 1,
                                            doc?.canvas?.height ?: 1,
                                        )
                                        cropRect = if (ratio == null) {
                                            next
                                        } else {
                                            fitCropToRatio(
                                                next,
                                                ratio,
                                                doc?.canvas?.width ?: 1,
                                                doc?.canvas?.height ?: 1,
                                            )
                                        }
                                    },
                                )
                                state.activeTool == EditTool.SELECT && selectShape == SelectionShape.OBJECT ->
                                    CanvasMode.ObjectSelect(
                                        current = state.selection,
                                        onTap = { onSelectObject(it, selectMode) },
                                    )
                                state.activeTool == EditTool.SELECT -> CanvasMode.Select(
                                    shape = selectShape,
                                    current = state.selection,
                                    onSelected = { drawn ->
                                        // なぞらずに離した (null) ときは解除。
                                        if (drawn == null) {
                                            onClearSelection()
                                        } else {
                                            onApplySelection(selectMode, drawn)
                                        }
                                    },
                                )
                                state.activeTool == EditTool.BRUSH ->
                                    CanvasMode.Brush(settings = brush, onStroke = onAddStroke)
                                state.activeTool == EditTool.TEXT && state.editingText != null ->
                                    CanvasMode.TextMove(
                                        bounds = state.editingText?.let { DocumentRenderer.measureText(it) },
                                        onDrag = onMoveText,
                                        onDragEnd = onMoveTextEnd,
                                    )
                                else -> CanvasMode.View
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (state.saving) {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) { CenterProgress(stringResource(R.string.text_saving)) }
                    }
                }

                // 選択中ツールの設定行。
                if (state.previewing) LinearProgressIndicator(Modifier.fillMaxWidth())
                val mosaic = state.mosaicDraft
                if (mosaic != null && doc != null) {
                    MosaicPanel(
                        blockSizePx = mosaic.blockSizePx,
                        shortEdge = minOf(doc.canvas.width, doc.canvas.height),
                        previewing = state.previewing || state.previewFailed,
                        onPercentChange = onMosaicPercent,
                        onApply = onCommitMosaic,
                        onCancel = onCancelMosaic,
                    )
                } else {
                    when (state.activeTool) {
                        EditTool.CROP -> {
                            HorizontalDivider()
                            CropPanel(
                                aspect = cropAspect,
                                onAspectChange = { picked ->
                                    cropAspect = picked
                                    val ratio = picked.effectiveRatio(
                                        doc?.canvas?.width ?: 1,
                                        doc?.canvas?.height ?: 1,
                                    )
                                    if (ratio != null) {
                                        cropRect = fitCropToRatio(
                                            cropRect,
                                            ratio,
                                            doc?.canvas?.width ?: 1,
                                            doc?.canvas?.height ?: 1,
                                        )
                                    }
                                },
                                onApply = {
                                    val canvas = doc?.canvas ?: return@CropPanel
                                    onApplyCrop(
                                        EditRect(
                                            left = cropRect.left * canvas.width,
                                            top = cropRect.top * canvas.height,
                                            right = cropRect.right * canvas.width,
                                            bottom = cropRect.bottom * canvas.height,
                                        ),
                                    )
                                    onSelectTool(null)
                                },
                                onReset = {
                                    cropRect = FULL_CROP
                                    onResetCrop()
                                },
                                onRotateLeft = onRotateLeft,
                                onRotateRight = onRotateRight,
                                onFlipHorizontal = onFlipHorizontal,
                                onFlipVertical = onFlipVertical,
                            )
                        }
                        EditTool.RESIZE -> {
                            HorizontalDivider()
                            ResizePanel(
                                state = state,
                                onSetLongEdge = onSetLongEdge,
                                onSetPercent = onSetPercent,
                            )
                        }
                        EditTool.SELECT -> {
                            HorizontalDivider()
                            SelectionPanel(
                                shape = selectShape,
                                mode = selectMode,
                                color = brush.color,
                                hasSelection = state.hasSelection,
                                onShapeChange = { onCancelObjectSelection(); selectShape = it },
                                onModeChange = { onCancelObjectSelection(); selectMode = it },
                                selectingObject = state.selectingObject,
                                onCancelObjectSelection = onCancelObjectSelection,
                                // 塗る色はペンと共有する (色の概念を 2 つ持たせない)。
                                onColorChange = { brush = brush.copy(color = it) },
                                onFill = { onFillSelection(brush.color) },
                                onErase = onEraseSelection,
                                onMosaic = onBeginMosaic,
                                onInvert = onInvertSelection,
                                onDeselect = onClearSelection,
                            )
                        }
                        EditTool.BRUSH -> {
                            HorizontalDivider()
                            BrushPanel(settings = brush, onSettingsChange = { brush = it })
                        }
                        EditTool.TEXT -> {
                            HorizontalDivider()
                            val canvasSize = doc?.let { EditSize(it.canvas.width, it.canvas.height) }
                            TextPanel(
                                editing = state.editingText,
                                others = state.textLayers,
                                canvasSize = canvasSize,
                                onAdd = { onAddText(defaultTextSize(canvasSize), brush.color) },
                                onSelect = onSelectText,
                                onChange = onChangeText,
                                onDelete = onDeleteText,
                            )
                        }
                        null -> Unit
                    }
                }

                HorizontalDivider()
                EditToolbar(
                    activeTool = state.activeTool,
                    enabled = doc != null && !state.saving && state.mosaicDraft == null,
                    onSelectTool = onSelectTool,
                )
            }
        }
    }

    if (showSaveDialog && doc != null) {
        SaveDialog(
            state = state,
            onFormatChange = onSetFormat,
            onQualityChange = onSetQuality,
            onDismiss = { showSaveDialog = false },
            onSave = { request ->
                showSaveDialog = false
                onSave(request)
            },
        )
    }

    if (showDiscardDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.image_unsaved_title)) },
            text = { Text(stringResource(R.string.image_unsaved_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    showSaveDialog = true
                }) { Text(stringResource(R.string.image_save_action)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onBack()
                }) { Text(stringResource(R.string.image_discard_close)) }
            },
        )
    }
}

// ---- ツールバー ----

@Composable
private fun EditToolbar(
    activeTool: EditTool?,
    enabled: Boolean,
    onSelectTool: (EditTool?) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton(
                icon = Icons.Default.Crop,
                label = stringResource(R.string.image_tool_crop),
                active = activeTool == EditTool.CROP,
                enabled = enabled,
                onClick = { onSelectTool(EditTool.CROP) },
                modifier = Modifier.weight(1f),
            )
            ToolButton(
                icon = Icons.Default.PhotoSizeSelectLarge,
                label = stringResource(R.string.image_tool_resize),
                active = activeTool == EditTool.RESIZE,
                enabled = enabled,
                onClick = { onSelectTool(EditTool.RESIZE) },
                modifier = Modifier.weight(1f),
            )
            ToolButton(
                icon = Icons.Default.HighlightAlt,
                label = stringResource(R.string.image_tool_select),
                active = activeTool == EditTool.SELECT,
                enabled = enabled,
                onClick = { onSelectTool(EditTool.SELECT) },
                modifier = Modifier.weight(1f),
            )
            ToolButton(
                icon = Icons.Default.Brush,
                label = stringResource(R.string.image_tool_pen),
                active = activeTool == EditTool.BRUSH,
                enabled = enabled,
                onClick = { onSelectTool(EditTool.BRUSH) },
                modifier = Modifier.weight(1f),
            )
            ToolButton(
                icon = Icons.Default.TextFields,
                label = stringResource(R.string.image_text),
                active = activeTool == EditTool.TEXT,
                enabled = enabled,
                onClick = { onSelectTool(EditTool.TEXT) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    rotateIcon: Boolean = false,
) {
    val tint = when {
        !enabled -> LocalContentColor.current.copy(alpha = 0.38f)
        active -> MaterialTheme.colorScheme.primary
        else -> LocalContentColor.current
    }
    Surface(onClick = onClick, enabled = enabled, color = Color.Transparent, modifier = modifier) {
        Column(
            modifier = Modifier.padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier
                    .size(22.dp)
                    // 上下反転には専用アイコンが無いので、左右反転アイコンを 90° 回して使う。
                    .then(if (rotateIcon) Modifier.rotate(90f) else Modifier),
            )
            Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
        }
    }
}

// ---- 切り抜きパネル ----

@Composable
private fun CropPanel(
    aspect: CropAspect,
    onAspectChange: (CropAspect) -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onFlipHorizontal: () -> Unit,
    onFlipVertical: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CropAspect.entries.forEach { item ->
                    FilterChip(
                        selected = aspect == item,
                        onClick = { onAspectChange(item) },
                        label = { Text(cropAspectLabel(item)) },
                    )
                }
            }
            // 回転・反転は切り抜きと一緒に使うことが多いのでここに置く
            // (ツールバーに 8 個並べるとラベルが読めなくなるため)。
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onRotateLeft) {
                    Icon(Icons.Default.Rotate90DegreesCcw, contentDescription = stringResource(R.string.image_rotate_left))
                }
                IconButton(onClick = onRotateRight) {
                    Icon(Icons.Default.Rotate90DegreesCw, contentDescription = stringResource(R.string.image_rotate_right))
                }
                IconButton(onClick = onFlipHorizontal) {
                    Icon(Icons.Default.Flip, contentDescription = stringResource(R.string.image_flip_horizontal))
                }
                IconButton(onClick = onFlipVertical) {
                    Icon(
                        Icons.Default.Flip,
                        contentDescription = stringResource(R.string.image_flip_vertical),
                        modifier = Modifier.rotate(90f),
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onReset) { Text(stringResource(R.string.action_reset)) }
                TextButton(onClick = onApply) { Text(stringResource(R.string.action_apply)) }
            }
        }
    }
}

@Composable
private fun cropAspectLabel(aspect: CropAspect): String = when (aspect) {
    CropAspect.FREE -> stringResource(R.string.image_crop_free)
    CropAspect.ORIGINAL -> stringResource(R.string.image_crop_original)
    CropAspect.SQUARE -> "1:1"
    CropAspect.R4_3 -> "4:3"
    CropAspect.R3_4 -> "3:4"
    CropAspect.R16_9 -> "16:9"
    CropAspect.R9_16 -> "9:16"
}

// ---- 共通の小物 ----

@Composable
private fun CenterProgress(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 1234567 → "1.2 MB" のような表示。 */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private val FULL_CROP = EditRect(0f, 0f, 1f, 1f)

/** ブラシの初期値: 中くらいの太さ・白・くっきり。 */
private val DEFAULT_BRUSH = BrushSettings(
    widthScreenPx = 12f,
    color = 0xFFE53935.toInt(),
    mode = StrokeMode.DRAW,
    hardness = 1f,
    alpha = 1f,
)
