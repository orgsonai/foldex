// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zerotoship.foldex.R
import com.zerotoship.foldex.ui.common.FileDetails

/** 全画面の動画の上に一瞬出す知らせの、共通の下地 (映像の色に負けないよう黒の半透明)。 */
private val FeedbackShape = RoundedCornerShape(8.dp)
private val FeedbackBackground = Color.Black.copy(alpha = 0.6f)

/** 左右のダブルタップで飛んだ向きと量 (「−10秒」など)。 */
@Composable
internal fun VideoSkipFeedback(forward: Boolean, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.background(FeedbackBackground, FeedbackShape).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (forward) Icons.Default.FastForward else Icons.Default.FastRewind,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(32.dp),
        )
        Text((if (forward) "+" else "−") + label, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

/** 縦のスワイプで変えた明るさ・音量の今の値。 */
@Composable
internal fun VideoLevelFeedback(volume: Boolean, percent: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.background(FeedbackBackground, FeedbackShape).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (volume) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.Brightness6,
            contentDescription = stringResource(if (volume) R.string.viewer_volume else R.string.viewer_brightness),
            tint = Color.White,
        )
        Text("$percent%", color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

/** 前回の続きから再生し始めたことの知らせと、「最初から」ボタン。 */
@Composable
internal fun VideoResumeBar(positionMs: Long, onRestart: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.background(FeedbackBackground, FeedbackShape).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.viewer_resumed_at, FileDetails.formatDuration(positionMs)),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onRestart) {
            Text(stringResource(R.string.viewer_play_from_start))
        }
    }
}
