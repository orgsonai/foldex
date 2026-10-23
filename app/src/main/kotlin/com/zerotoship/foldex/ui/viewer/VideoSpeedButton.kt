// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.zerotoship.foldex.R

/** 選べる再生速度。 */
internal val VIDEO_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** `1x` `1.25x` `0.5x` の形。記号は言語を問わず同じなのでリソース化しない。 */
internal fun videoSpeedLabel(speed: Float): String =
    (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()) + "x"

/** ビューア上部の再生速度ボタン。今の速度を表示し、押すと速度の一覧を出す。 */
@Composable
internal fun VideoSpeedButton(speed: Float, onSpeedChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val description = stringResource(R.string.viewer_speed)
    Box(modifier) {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = description },
        ) {
            Text(videoSpeedLabel(speed))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            VIDEO_SPEEDS.forEach { s ->
                DropdownMenuItem(
                    text = { Text(videoSpeedLabel(s)) },
                    trailingIcon = { if (s == speed) Icon(Icons.Default.Check, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onSpeedChange(s)
                    },
                )
            }
        }
    }
}
