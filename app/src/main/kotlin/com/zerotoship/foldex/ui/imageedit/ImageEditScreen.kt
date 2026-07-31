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
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zerotoship.foldex.ui.imageedit.model.EditRect

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
    onSave: (SaveRequest) -> Unit,
) {
    val doc = state.document
    // 切り抜き枠は 0..1 の正規化座標で持つ (キャンバスの大きさが変わっても意味が変わらない)。
    var cropRect by remember { mutableStateOf(FULL_CROP) }
    var cropAspect by remember { mutableStateOf(CropAspect.FREE) }
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
            showSaveDialog -> showSaveDialog = false
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
                                buildString {
                                    append("${size.width}×${size.height}")
                                    state.estimatedBytes?.let { append("  ・  ${formatBytes(it.toLong())}") }
                                    if (state.estimating) append("  ・  計算中…")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (state.dirty) showDiscardDialog = true else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = onUndo, enabled = state.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "元に戻す")
                    }
                    IconButton(onClick = onRedo, enabled = state.canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "やり直す")
                    }
                    TextButton(
                        onClick = {
                            onRequestEstimate()
                            showSaveDialog = true
                        },
                        enabled = doc != null && !state.saving,
                    ) { Text("保存") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        state.loading -> CenterProgress("読み込み中…")
                        state.error != null -> CenterMessage(state.error)
                        else -> EditCanvas(
                            preview = state.preview,
                            cropRect = cropRect.takeIf { state.activeTool == EditTool.CROP },
                            onCropRectChange = { next ->
                                val ratio = cropAspect.effectiveRatio(
                                    doc?.canvas?.width ?: 1,
                                    doc?.canvas?.height ?: 1,
                                )
                                cropRect = if (ratio == null) {
                                    next
                                } else {
                                    fitCropToRatio(next, ratio, doc?.canvas?.width ?: 1, doc?.canvas?.height ?: 1)
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (state.saving) {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) { CenterProgress("保存中…") }
                    }
                }

                // 選択中ツールの設定行。
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
                    null -> Unit
                }

                HorizontalDivider()
                EditToolbar(
                    activeTool = state.activeTool,
                    enabled = doc != null && !state.saving,
                    onSelectTool = onSelectTool,
                    onRotateLeft = onRotateLeft,
                    onRotateRight = onRotateRight,
                    onFlipHorizontal = onFlipHorizontal,
                    onFlipVertical = onFlipVertical,
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
            title = { Text("保存していない編集があります") },
            text = { Text("保存せずに閉じると、編集した内容は失われます。") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    showSaveDialog = true
                }) { Text("保存する") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onBack()
                }) { Text("破棄して閉じる") }
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
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onFlipHorizontal: () -> Unit,
    onFlipVertical: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton(
                icon = Icons.Default.Crop,
                label = "切り抜き",
                active = activeTool == EditTool.CROP,
                enabled = enabled,
                onClick = { onSelectTool(EditTool.CROP) },
                modifier = Modifier.weight(1f),
            )
            ToolButton(
                icon = Icons.Default.PhotoSizeSelectLarge,
                label = "サイズ",
                active = activeTool == EditTool.RESIZE,
                enabled = enabled,
                onClick = { onSelectTool(EditTool.RESIZE) },
                modifier = Modifier.weight(1f),
            )
            ToolButton(
                icon = Icons.Default.Rotate90DegreesCcw,
                label = "左回転",
                enabled = enabled,
                onClick = onRotateLeft,
                modifier = Modifier.weight(1f),
            )
            ToolButton(
                icon = Icons.Default.Rotate90DegreesCw,
                label = "右回転",
                enabled = enabled,
                onClick = onRotateRight,
                modifier = Modifier.weight(1f),
            )
            ToolButton(
                icon = Icons.Default.Flip,
                label = "左右反転",
                enabled = enabled,
                onClick = onFlipHorizontal,
                modifier = Modifier.weight(1f),
            )
            ToolButton(
                icon = Icons.Default.Flip,
                label = "上下反転",
                enabled = enabled,
                onClick = onFlipVertical,
                rotateIcon = true,
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
                        label = { Text(item.label) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onReset) { Text("切り抜きを解除") }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onApply) { Text("適用") }
            }
        }
    }
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
