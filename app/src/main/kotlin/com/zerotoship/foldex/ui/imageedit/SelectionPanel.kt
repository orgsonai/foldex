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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
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
 * 選択範囲ツールの設定行 (v3)。
 *
 * 形 (矩形 / 楕円 / 投げ縄) と積み方 (新規 / 追加 / 除外) を選び、選んだ範囲に対して
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
    onShapeChange: (SelectionShape) -> Unit,
    onModeChange: (SelectionMode) -> Unit,
    onColorChange: (Int) -> Unit,
    onFill: () -> Unit,
    onErase: () -> Unit,
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
                ShapeChip(SelectionShape.RECT, shape, R.string.image_select_rect, onShapeChange)
                ShapeChip(SelectionShape.ELLIPSE, shape, R.string.image_select_ellipse, onShapeChange)
                ShapeChip(SelectionShape.LASSO, shape, R.string.image_select_lasso, onShapeChange)

                VerticalDivider(Modifier.height(24.dp).padding(horizontal = 2.dp))

                ModeChip(SelectionMode.REPLACE, mode, R.string.image_select_new, onModeChange)
                ModeChip(SelectionMode.ADD, mode, R.string.image_select_add, onModeChange)
                ModeChip(SelectionMode.SUBTRACT, mode, R.string.image_select_subtract, onModeChange)
            }

            Spacer(Modifier.size(6.dp))

            Row(
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
                TextButton(onClick = onFill, enabled = hasSelection) {
                    Text(stringResource(R.string.image_select_fill))
                }
                TextButton(onClick = onErase, enabled = hasSelection) {
                    Text(stringResource(R.string.image_select_erase))
                }
                TextButton(onClick = onInvert) {
                    Text(stringResource(R.string.image_select_invert))
                }
                TextButton(onClick = onDeselect, enabled = hasSelection) {
                    Text(stringResource(R.string.image_select_none))
                }
            }

            if (!hasSelection) {
                Text(
                    stringResource(R.string.image_select_hint),
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
