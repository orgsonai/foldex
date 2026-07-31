// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zerotoship.foldex.ui.imageedit.model.ImageFormat
import kotlin.math.roundToInt

/**
 * 保存の設定。
 *
 * 既定は**別名保存** (`photo_edited.jpg`)。原本の喪失だけは取り返しがつかないので、
 * 上書きは同じ並びに置きつつ明示的に選ばせる。
 *
 * 「◯KB 以下にする」を指定すると、その容量に収まる最大の品質を自動で探して保存する
 * (`ImageEncoder.findQualityForTarget`)。
 */
@Composable
fun SaveDialog(
    state: ImageEditUiState,
    onFormatChange: (ImageFormat) -> Unit,
    onQualityChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    onSave: (SaveRequest) -> Unit,
) {
    val doc = state.document ?: return
    var format by remember { mutableStateOf(doc.output.format) }
    var quality by remember { mutableFloatStateOf(doc.output.quality.toFloat()) }
    var keepLocation by remember { mutableStateOf(true) }
    var useTargetSize by remember { mutableStateOf(false) }
    var targetKbText by remember { mutableStateOf("500") }

    val transparentWarning = format == ImageFormat.JPEG && doc.canvas.background is
        com.zerotoship.foldex.ui.imageedit.model.Background.Transparent

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "${doc.outputSize.width} × ${doc.outputSize.height}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.estimatedBytes != null && !useTargetSize) {
                    Text(
                        "現在の設定で約 ${formatBytes(state.estimatedBytes.toLong())}" +
                            if (state.originalBytes > 0) " (元 ${formatBytes(state.originalBytes)})" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))

                Text("形式", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ImageFormat.entries.forEach { item ->
                        FilterChip(
                            selected = format == item,
                            onClick = {
                                format = item
                                onFormatChange(item)
                            },
                            label = { Text(item.displayName) },
                        )
                    }
                }
                if (transparentWarning) {
                    Text(
                        "透明な部分は JPEG では黒く潰れます。PNG か WebP を選んでください。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (format.hasQuality) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = useTargetSize, onCheckedChange = { useTargetSize = it })
                        Text("容量を指定して収める", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (useTargetSize) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = targetKbText,
                                onValueChange = { input ->
                                    targetKbText = input.filter { it.isDigit() }.take(6)
                                },
                                label = { Text("目標") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(140.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("KB 以下", style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            "この容量に収まる範囲でいちばん高い画質を自動で選びます。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text("画質 ${quality.roundToInt()}", style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = quality,
                            onValueChange = { quality = it },
                            // 指を離してから反映する (動かすたびに原寸で書き出して測るのは重い)。
                            onValueChangeFinished = { onQualityChange(quality.roundToInt()) },
                            valueRange = 30f..100f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                if (state.hasLocation) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = !keepLocation, onCheckedChange = { keepLocation = !it })
                        Text("位置情報を削除して保存", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = state.canSaveAs,
                onClick = {
                    onSave(
                        buildRequest(
                            overwrite = false,
                            keepLocation = keepLocation,
                            useTargetSize = useTargetSize,
                            targetKbText = targetKbText,
                        ),
                    )
                },
            ) { Text("別名で保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("キャンセル") }
                TextButton(onClick = {
                    onSave(
                        buildRequest(
                            overwrite = true,
                            keepLocation = keepLocation,
                            useTargetSize = useTargetSize,
                            targetKbText = targetKbText,
                        ),
                    )
                }) { Text("上書き保存") }
            }
        },
    )
}

private fun buildRequest(
    overwrite: Boolean,
    keepLocation: Boolean,
    useTargetSize: Boolean,
    targetKbText: String,
): SaveRequest = SaveRequest(
    overwrite = overwrite,
    keepLocation = keepLocation,
    targetBytes = if (useTargetSize) {
        (targetKbText.toIntOrNull() ?: 0).takeIf { it > 0 }?.times(1024)
    } else {
        null
    },
)
