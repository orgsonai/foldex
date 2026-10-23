// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.viewer

import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SouthEast
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.zerotoship.foldex.R
import kotlinx.coroutines.delay

/**
 * Foldex の小窓の中身。映像を窓いっぱいに出し、タップで操作ボタンを出し入れする。
 *
 * 操作ボタンは中央に「巻き戻し・再生/一時停止・早送り」、左上に「閉じる」、右上に「全画面に戻す」、
 * 右下に大きさを変えるつまみ (つまみの操作そのものは [FloatingVideoFrame] が受ける)。
 * 再生中は最後の操作から [AUTO_HIDE_MS] で操作ボタンを隠す。
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun FloatingVideoContent(
    player: Player,
    isPlaying: Boolean,
    controlsVisible: Boolean,
    /** 最後に操作した回数。変わるたびに自動で隠すまでの時間を数え直す。 */
    interactionCount: Int,
    /** 巻き戻し・早送りボタンに添える量 (例: 「10秒」「5%」)。 */
    skipLabel: String,
    onToggleControls: () -> Unit,
    onHideControls: () -> Unit,
    onTogglePlay: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onClose: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(controlsVisible, isPlaying, interactionCount) {
        if (controlsVisible && isPlaying) {
            delay(AUTO_HIDE_MS)
            onHideControls()
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { onToggleControls() }) },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(AndroidColor.BLACK)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { view ->
                view.player = player
                // 再生中だけ画面を点けたままにする。
                view.keepScreenOn = isPlaying
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (controlsVisible) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkipButton(Icons.Default.FastRewind, stringResource(R.string.viewer_pip_rewind), "−$skipLabel", onRewind)
                IconButton(onClick = onTogglePlay, modifier = Modifier.size(56.dp)) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(if (isPlaying) R.string.viewer_pip_pause else R.string.viewer_pip_play),
                        tint = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                }
                SkipButton(Icons.Default.FastForward, stringResource(R.string.viewer_pip_forward), "+$skipLabel", onForward)
            }
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart)) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.popup_close), tint = Color.White)
            }
            IconButton(onClick = onExpand, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Default.OpenInFull, contentDescription = stringResource(R.string.popup_expand), tint = Color.White)
            }
            Icon(
                Icons.Default.SouthEast,
                contentDescription = stringResource(R.string.popup_resize),
                tint = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(24.dp),
            )
        }
    }
}

@Composable
private fun SkipButton(icon: ImageVector, description: String, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(52.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(28.dp))
            Text(label, color = Color.White, fontSize = 10.sp, maxLines = 1)
        }
    }
}

private const val AUTO_HIDE_MS = 3_000L
