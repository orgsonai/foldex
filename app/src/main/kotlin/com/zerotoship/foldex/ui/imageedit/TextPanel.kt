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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zerotoship.foldex.ui.imageedit.model.EditSize
import com.zerotoship.foldex.ui.imageedit.model.Layer
import com.zerotoship.foldex.ui.imageedit.model.TextFont
import com.zerotoship.foldex.ui.imageedit.model.TextOutline
import kotlin.math.roundToInt

/**
 * 文字の設定行。
 *
 * 文字は**文字列のまま**保持されるので、保存するまで何度でも打ち直せる。
 * 位置はキャンバスを 1 本指でドラッグして決める (2 本指は常にズーム)。
 */
@Composable
fun TextPanel(
    editing: Layer.Text?,
    others: List<Layer.Text>,
    canvasSize: EditSize?,
    onAdd: () -> Unit,
    onSelect: (String?) -> Unit,
    onChange: ((Layer.Text) -> Layer.Text) -> Unit,
    onDelete: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (editing == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onAdd) { Text("＋ 文字を追加") }
                    if (others.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Row(
                            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            others.forEach { layer ->
                                FilterChip(
                                    selected = false,
                                    onClick = { onSelect(layer.id) },
                                    label = {
                                        Text(layer.text.take(8).ifBlank { "(空)" }, maxLines = 1)
                                    },
                                )
                            }
                        }
                    }
                }
                return@Column
            }

            OutlinedTextField(
                value = editing.text,
                onValueChange = { input -> onChange { it.copy(text = input) } },
                label = { Text("文字") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )
            Spacer(Modifier.height(4.dp))

            // 大きさ。論理キャンバス基準なので、画像に対する比で見せた方が分かりやすい。
            val percent = canvasSize?.let { (editing.style.sizePx / it.height * 100f).roundToInt() } ?: 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("大きさ $percent%", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = editing.style.sizePx,
                    onValueChange = { size -> onChange { it.copy(style = it.style.copy(sizePx = size)) } },
                    valueRange = textSizeRange(canvasSize),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PALETTE.forEach { color ->
                    Box(
                        modifier = Modifier
                            .size(if (editing.style.color == color) 32.dp else 26.dp)
                            .clip(CircleShape)
                            .background(Color(color))
                            .border(
                                width = if (editing.style.color == color) 3.dp else 1.dp,
                                color = if (editing.style.color == color) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                                shape = CircleShape,
                            )
                            .clickable { onChange { it.copy(style = it.style.copy(color = color)) } },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextFont.entries.forEach { font ->
                    FilterChip(
                        selected = editing.style.font == font,
                        onClick = { onChange { it.copy(style = it.style.copy(font = font)) } },
                        label = { Text(font.label) },
                    )
                }
                FilterChip(
                    selected = editing.style.bold,
                    onClick = { onChange { it.copy(style = it.style.copy(bold = !it.style.bold)) } },
                    label = { Text("太字") },
                )
                TextOutline.entries.forEach { outline ->
                    FilterChip(
                        selected = editing.style.outline == outline,
                        onClick = { onChange { it.copy(style = it.style.copy(outline = outline)) } },
                        label = { Text(outline.label) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("傾き ${editing.transform.rotationDeg.roundToInt()}°", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = editing.transform.rotationDeg,
                    onValueChange = { deg ->
                        onChange { it.copy(transform = it.transform.copy(rotationDeg = deg)) }
                    },
                    valueRange = -180f..180f,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "位置は画像をドラッグして決めます",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDelete) { Text("削除") }
                TextButton(onClick = { onSelect(null) }) { Text("完了") }
            }
        }
    }
}

/** 文字サイズの範囲。画像の高さの 2%〜25% に収める (小さすぎ / 大きすぎを防ぐ)。 */
private fun textSizeRange(canvasSize: EditSize?): ClosedFloatingPointRange<Float> {
    val height = canvasSize?.height?.toFloat() ?: 1000f
    return (height * 0.02f)..(height * 0.25f)
}

/** 新しい文字の既定サイズ (画像の高さの 8%)。 */
internal fun defaultTextSize(canvasSize: EditSize?): Float =
    (canvasSize?.height?.toFloat() ?: 1000f) * 0.08f
