// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zerotoship.foldex.R
import kotlin.math.roundToInt

@Composable
internal fun MosaicPanel(
    blockSizePx: Float,
    maximumBlockSize: Float,
    previewing: Boolean,
    selectingArea: Boolean,
    editingExisting: Boolean,
    onToggleArea: () -> Unit,
    onDelete: () -> Unit,
    onSizeFinished: () -> Unit,
    onBlockSizeChange: (Float) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.image_mosaic_size, blockSizePx.roundToInt())
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.image_mosaic), style = MaterialTheme.typography.titleSmall)
            if (!selectingArea) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = blockSizePx.coerceIn(2f, maximumBlockSize),
                    onValueChange = { onBlockSizeChange(it.roundToInt().toFloat()) },
                    onValueChangeFinished = onSizeFinished,
                    valueRange = 2f..maximumBlockSize,
                    modifier = Modifier.semantics { contentDescription = label },
                )
                Text(stringResource(R.string.image_mosaic_hint), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onToggleArea) {
                Text(stringResource(if (selectingArea) R.string.image_mosaic_check_effect else R.string.image_mosaic_edit_area))
            }
            if (selectingArea) Text(stringResource(R.string.image_mosaic_area_hint), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                if (editingExisting) TextButton(onClick = onDelete) { Text(stringResource(R.string.image_mosaic_delete)) }
                Spacer(Modifier.weight(1f))
                if (!selectingArea) TextButton(onClick = onApply, enabled = !previewing) {
                    Text(stringResource(R.string.action_apply))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MosaicPanelPreview() {
    MaterialTheme {
        MosaicPanel(24f, 80f, false, false, true, {}, {}, {}, {}, {}, {})
    }
}
