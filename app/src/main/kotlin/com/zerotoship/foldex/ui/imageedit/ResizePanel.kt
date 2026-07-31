// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * 解像度を変える設定行。
 *
 * 指定方法は「長辺の px」と「%」の 2 つ。目標ファイルサイズでの指定は保存ダイアログ側に置く
 * (容量を気にするのは保存する瞬間なので、そこに集約した方が迷わない)。
 *
 * 予想サイズは式で見積もらず、**実際に原寸で書き出して測る**。入力が落ち着いてから
 * 計算するので数百 ms 待つが、表示が嘘にならない。
 */
@Composable
fun ResizePanel(
    state: ImageEditUiState,
    onSetLongEdge: (Int) -> Unit,
    onSetPercent: (Int) -> Unit,
) {
    val doc = state.document ?: return
    val canvasLongEdge = maxOf(doc.canvas.width, doc.canvas.height)
    var longEdgeText by remember(doc.outputSize) { mutableStateOf(doc.outputSize.longEdge.toString()) }

    // 入力が落ち着いてから反映する (1 文字打つたびに原寸レンダリングを走らせない)。
    LaunchedEffect(longEdgeText) {
        val value = longEdgeText.toIntOrNull() ?: return@LaunchedEffect
        if (value == doc.outputSize.longEdge) return@LaunchedEffect
        kotlinx.coroutines.delay(INPUT_DEBOUNCE_MS)
        onSetLongEdge(value)
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = longEdgeText,
                    onValueChange = { input -> longEdgeText = input.filter { it.isDigit() }.take(5) },
                    label = { Text("長辺 (px)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(140.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "${doc.outputSize.width} × ${doc.outputSize.height}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        buildString {
                            when {
                                state.estimating -> append("サイズを計算中…")
                                state.estimatedBytes != null -> {
                                    append("約 ${formatBytes(state.estimatedBytes.toLong())}")
                                    if (state.originalBytes > 0) {
                                        append(" (元 ${formatBytes(state.originalBytes)})")
                                    }
                                }
                                else -> append("元 ${formatBytes(state.originalBytes)}")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PERCENT_PRESETS.forEach { percent ->
                    AssistChip(
                        onClick = {
                            onSetPercent(percent)
                            longEdgeText = (canvasLongEdge * percent / 100).toString()
                        },
                        label = { Text("$percent%") },
                    )
                }
                LONG_EDGE_PRESETS.forEach { edge ->
                    if (edge <= canvasLongEdge) {
                        AssistChip(
                            onClick = {
                                onSetLongEdge(edge)
                                longEdgeText = edge.toString()
                            },
                            label = { Text("${edge}px") },
                        )
                    }
                }
                TextButton(onClick = {
                    onSetLongEdge(canvasLongEdge)
                    longEdgeText = canvasLongEdge.toString()
                }) { Text("元のサイズ") }
            }
        }
    }
}

private val PERCENT_PRESETS = listOf(75, 50, 25)
private val LONG_EDGE_PRESETS = listOf(2048, 1600, 1280, 800)
private const val INPUT_DEBOUNCE_MS = 500L
