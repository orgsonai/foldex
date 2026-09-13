// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Slider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import com.zerotoship.foldex.ui.imageedit.model.Layer
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zerotoship.foldex.ui.imageedit.model.SelectionMode
import com.zerotoship.foldex.ui.imageedit.model.SelectionShape
import com.zerotoship.foldex.R

/**
 * 選択範囲と、適用済みモザイクの修正操作。
 *
 * 自動選択・ブラシ・図形と積み方 (新規 / 追加 / 除外) を選び、選んだ範囲に対して
 * 塗りつぶし・透明化・反転・解除を行う。
 *
 * 塗る色は**ペンと共有する**。エディタの中に「ペンの色」と「塗りの色」が別々にあると、
 * どちらを変えたのか分からなくなるため。
 */
@Composable
fun SelectionPanel(
    shape: SelectionShape,
    mode: SelectionMode,
    color: Int,
    hasSelection: Boolean,
    selectingObject: Boolean,
    brushDiameter: Float,
    brushLimit: Float,
    backdrop: SelectionBackdrop,
    onBrushDiameterChange: (Float) -> Unit,
    onBackdropChange: (SelectionBackdrop) -> Unit,
    mosaics: List<Layer.Mosaic>,
    onEditMosaic: (String) -> Unit,
    editingMosaicArea: Boolean = false,
    onCancelObjectSelection: () -> Unit,
    onShapeChange: (SelectionShape) -> Unit,
    onModeChange: (SelectionMode) -> Unit,
    onColorChange: (Int) -> Unit,
    onFill: () -> Unit,
    onErase: () -> Unit,
    onMosaic: () -> Unit,
    onInvert: () -> Unit,
    onDeselect: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {

            // 形と積み方。横に長くなるのでスクロールさせる (端末の幅に依存させない)。
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShapeChip(SelectionShape.BRUSH, shape, R.string.image_select_brush, onShapeChange)
                if (!editingMosaicArea) ShapeChip(SelectionShape.OBJECT, shape, R.string.image_select_object, onShapeChange)
                ShapeChip(SelectionShape.RECT, shape, R.string.image_select_rect, onShapeChange)
                ShapeChip(SelectionShape.ELLIPSE, shape, R.string.image_select_ellipse, onShapeChange)
                ShapeChip(SelectionShape.LASSO, shape, R.string.image_select_lasso, onShapeChange)

            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                ModeChip(SelectionMode.REPLACE, mode, R.string.image_select_new, onModeChange)
                ModeChip(SelectionMode.ADD, mode, R.string.image_select_add, onModeChange)
                ModeChip(SelectionMode.SUBTRACT, mode, R.string.image_select_subtract, onModeChange)
            }

            if (shape == SelectionShape.BRUSH) {
                val widthLabel = stringResource(R.string.image_selection_brush_size, brushDiameter.roundToInt())
                Text(widthLabel, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val decrease = stringResource(R.string.image_selection_brush_smaller)
                    val increase = stringResource(R.string.image_selection_brush_larger)
                    TextButton(onClick = { onBrushDiameterChange((brushDiameter - 1f).coerceAtLeast(1f)) },
                        modifier = Modifier.semantics { contentDescription = decrease }) { Text("−") }
                    Slider(value = brushDiameter.coerceIn(1f, brushLimit),
                        onValueChange = { onBrushDiameterChange(it.roundToInt().toFloat()) }, valueRange = 1f..brushLimit,
                        modifier = Modifier.weight(1f).semantics { contentDescription = widthLabel })
                    TextButton(onClick = { onBrushDiameterChange((brushDiameter + 1f).coerceAtMost(brushLimit)) },
                        modifier = Modifier.semantics { contentDescription = increase }) { Text("+") }
                }
                Text(stringResource(R.string.image_selection_brush_hint), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { onBackdropChange(SelectionBackdrop.entries[(backdrop.ordinal + 1) % SelectionBackdrop.entries.size]) }) {
                Text(stringResource(R.string.image_selection_background, stringResource(when (backdrop) {
                    SelectionBackdrop.CHECKERBOARD -> R.string.image_selection_background_checker
                    SelectionBackdrop.WHITE -> R.string.image_selection_background_white
                    SelectionBackdrop.BLACK -> R.string.image_selection_background_black
                })))
            }

            if (!editingMosaicArea) Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PALETTE.forEach { swatch ->
                    ColorSwatch(
                        color = swatch,
                        selected = (color and 0x00FFFFFF) == (swatch and 0x00FFFFFF),
                        onClick = { onColorChange(swatch) },
                    )
                }
            }

            // 操作。選んでいないときは「反転」だけ効く (全面が選ばれる)。
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!editingMosaicArea) TextButton(onClick = onMosaic, enabled = hasSelection && !selectingObject) {
                    Text(stringResource(R.string.image_mosaic))
                }
                if (!editingMosaicArea) TextButton(onClick = onFill, enabled = hasSelection && !selectingObject) {
                    Text(stringResource(R.string.image_select_fill))
                }
                if (!editingMosaicArea) TextButton(onClick = onErase, enabled = hasSelection && !selectingObject) {
                    Text(stringResource(R.string.image_select_erase))
                }
                TextButton(onClick = onInvert) {
                    Text(stringResource(R.string.image_select_invert))
                }
                TextButton(onClick = onDeselect, enabled = hasSelection) {
                    Text(stringResource(R.string.image_select_none))
                }
            }

            if (!editingMosaicArea && mosaics.isNotEmpty()) {
                var showMosaics by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { showMosaics = true }, enabled = !selectingObject) {
                        Text(stringResource(R.string.image_mosaic_edit_existing))
                    }
                    DropdownMenu(expanded = showMosaics, onDismissRequest = { showMosaics = false }) {
                        mosaics.forEachIndexed { index, mosaic ->
                            DropdownMenuItem(text = {
                                Text(stringResource(R.string.image_mosaic_entry, index + 1, mosaic.blockSizePx.roundToInt()))
                            }, onClick = { showMosaics = false; onEditMosaic(mosaic.id) })
                        }
                    }
                }
            }

            if (selectingObject) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.image_object_selecting), modifier = Modifier.weight(1f))
                    TextButton(onClick = onCancelObjectSelection) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            } else if (shape == SelectionShape.OBJECT || !hasSelection) {
                Text(
                    stringResource(if (shape == SelectionShape.OBJECT) R.string.image_object_hint else R.string.image_select_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ShapeChip(
    value: SelectionShape,
    current: SelectionShape,
    labelRes: Int,
    onChange: (SelectionShape) -> Unit,
) {
    FilterChip(
        selected = current == value,
        onClick = { onChange(value) },
        label = { Text(stringResource(labelRes)) },
    )
}

@Composable
private fun ModeChip(
    value: SelectionMode,
    current: SelectionMode,
    labelRes: Int,
    onChange: (SelectionMode) -> Unit,
) {
    FilterChip(
        selected = current == value,
        onClick = { onChange(value) },
        label = { Text(stringResource(labelRes)) },
    )
}

@Composable
private fun ColorSwatch(color: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(if (selected) 30.dp else 24.dp)
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

@Preview(showBackground = true)
@Composable
private fun SelectionBrushPanelPreview() {
    MaterialTheme {
        SelectionPanel(shape = SelectionShape.BRUSH, mode = SelectionMode.ADD, color = -1,
            hasSelection = true, selectingObject = false, brushDiameter = 32f, brushLimit = 512f,
            backdrop = SelectionBackdrop.CHECKERBOARD, onBrushDiameterChange = {}, onBackdropChange = {},
            mosaics = emptyList(), onEditMosaic = {}, onCancelObjectSelection = {}, onShapeChange = {},
            onModeChange = {}, onColorChange = {}, onFill = {}, onErase = {}, onMosaic = {}, onInvert = {}, onDeselect = {})
    }
}
