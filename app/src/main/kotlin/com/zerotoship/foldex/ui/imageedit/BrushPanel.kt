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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zerotoship.foldex.ui.imageedit.model.StrokeMode
import kotlin.math.roundToInt

/**
 * ブラシ / 消しゴムの設定行。
 *
 * 太さは**画面上の見た目の太さ**で指定する。論理キャンバス座標へは描いた瞬間に換算するので、
 * 拡大して細かく描き込むこともできる (拡大するほど細い線が残る = 直感どおり)。
 */
@Composable
fun BrushPanel(
    settings: BrushSettings,
    onSettingsChange: (BrushSettings) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = settings.mode == StrokeMode.DRAW,
                    onClick = { onSettingsChange(settings.copy(mode = StrokeMode.DRAW)) },
                    label = { Text("ペン") },
                )
                Spacer(Modifier.width(6.dp))
                FilterChip(
                    selected = settings.mode == StrokeMode.ERASE,
                    onClick = { onSettingsChange(settings.copy(mode = StrokeMode.ERASE)) },
                    label = { Text("消しゴム") },
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "太さ ${settings.widthScreenPx.roundToInt()}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Slider(
                value = settings.widthScreenPx,
                onValueChange = { onSettingsChange(settings.copy(widthScreenPx = it)) },
                valueRange = 2f..80f,
                modifier = Modifier.fillMaxWidth(),
            )
            if (settings.mode == StrokeMode.DRAW) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PALETTE.forEach { color ->
                        ColorDot(
                            color = color,
                            selected = (settings.color and 0x00FFFFFF) == (color and 0x00FFFFFF),
                            onClick = { onSettingsChange(settings.copy(color = color)) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("濃さ", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = settings.alpha,
                        onValueChange = { onSettingsChange(settings.copy(alpha = it)) },
                        valueRange = 0.1f..1f,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text("ぼかし", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        // 内部の hardness は 1 = くっきり。UI では「ぼかし」として反転して見せる。
                        value = 1f - settings.hardness,
                        onValueChange = { onSettingsChange(settings.copy(hardness = 1f - it)) },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorDot(color: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(if (selected) 34.dp else 28.dp)
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

/** 写真の上でも見分けやすい 12 色。カラーピッカーは入れず、迷わない数に絞る。 */
internal val PALETTE: List<Int> = listOf(
    0xFFFFFFFF.toInt(), // 白
    0xFF000000.toInt(), // 黒
    0xFFE53935.toInt(), // 赤
    0xFFFB8C00.toInt(), // 橙
    0xFFFDD835.toInt(), // 黄
    0xFF43A047.toInt(), // 緑
    0xFF00ACC1.toInt(), // 水
    0xFF1E88E5.toInt(), // 青
    0xFF5E35B1.toInt(), // 紫
    0xFFD81B60.toInt(), // ピンク
    0xFF6D4C41.toInt(), // 茶
    0xFF9E9E9E.toInt(), // 灰
)
