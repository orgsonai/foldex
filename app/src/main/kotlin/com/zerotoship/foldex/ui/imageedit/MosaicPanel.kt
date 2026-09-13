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
    shortEdge: Int,
    previewing: Boolean,
    onPercentChange: (Float) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.image_mosaic_size, blockSizePx.roundToInt())
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.image_mosaic), style = MaterialTheme.typography.titleSmall)
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = (blockSizePx / shortEdge.coerceAtLeast(1) * 100f).coerceIn(0.5f, 8f),
                onValueChange = onPercentChange,
                valueRange = 0.5f..8f,
                modifier = Modifier.semantics { contentDescription = label },
            )
            Text(stringResource(R.string.image_mosaic_hint), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onApply, enabled = !previewing) {
                    Text(stringResource(R.string.action_apply))
                }
            }
        }
    }
}

@Preview
@Composable
private fun MosaicPanelPreview() {
    MaterialTheme {
        MosaicPanel(24f, 1000, false, {}, {}, {})
    }
}
