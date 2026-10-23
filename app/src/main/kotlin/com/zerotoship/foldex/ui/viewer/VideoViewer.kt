// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.viewer

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.zerotoship.foldex.R
import com.zerotoship.foldex.core.model.VideoSkip
import java.io.File

/**
 * Media3 ExoPlayer + PlayerView による簡易動画ビューア。
 *
 * - WMV (`.wmv` / `.asf` の WMV9/VC-1) は Android 標準の MediaCodec / ExoPlayer
 *   が対応していないため、拡張子で予め判定して **外部アプリへ即フォールバック**。
 * - それ以外の形式でも prepare/再生でエラーが出たらオーバーレイで「別のアプリで開く」を案内。
 * - リモートファイルは [mediaUri] (content://com.zerotoship.foldex.streaming/...) を直接渡し、
 *   ContentProvider 経由で pipe ストリーミング再生する (全DLを待たない)。
 *   ローカルファイルは [file] から file URI を組み立てる。
 * - 2 本指でつまむと拡大・縮小 (1〜5 倍)、拡大中は 1 本指でずらせる。
 *   操作バーは拡大せず、映像の面 ([PlayerView.getVideoSurfaceView]) だけを拡大する。
 * - ポップアップ再生 (PiP) 中は小窓に操作ボタンを重ねない。一時停止は小窓のボタンで行う
 *   ([ViewerActivity] が [onPlayerChanged] で受け取ったプレーヤーを操作する)。
 * - 操作バーの巻き戻し・早送りは設定の [skip] だけ飛ぶ (割合指定は動画の長さから毎回計算する)。
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoViewer(
    file: File,
    modifier: Modifier = Modifier,
    /** 表示名 (WMV 等の拡張子判定用)。streaming URI には拡張子情報が無いので別途渡す。 */
    displayName: String = file.name,
    /**
     * 非 null のときはこれを ExoPlayer に渡す (リモートストリーミング)。null のときは
     * [file] からローカル URI を組み立てる。
     */
    mediaUri: String? = null,
    onOpenExternally: (File) -> Unit = {},
    /** ポップアップ再生 (PiP) の小窓に出ているか。 */
    inPip: Boolean = false,
    /** 再生に使うプレーヤーを Activity へ渡す (ポップアップ再生の操作用)。破棄時は null。 */
    onPlayerChanged: (Player?) -> Unit = {},
    /** 巻き戻し・早送りで飛ばす量。 */
    skip: VideoSkip = VideoSkip.DEFAULT,
    /** 再生を始める位置 (Foldex の小窓から全画面に戻したとき)。 */
    startPositionMs: Long = 0L,
    /** 開いたらすぐ再生するか (小窓で一時停止していたら、全画面でも止めたまま開く)。 */
    startPlaying: Boolean = true,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val ext = remember(displayName) { displayName.substringAfterLast('.', "").lowercase() }
    val unsupportedExtension = ext == "wmv" || ext == "asf"

    if (unsupportedExtension) {
        UnsupportedOverlay(
            modifier = modifier,
            message = stringResource(R.string.video_unsupported_wmv),
            onOpen = { onOpenExternally(file) },
        )
        return
    }

    val playUri = mediaUri ?: file.toURI().toString()
    var playbackError by remember(playUri) { mutableStateOf<String?>(null) }

    // PlayerView 側へ「再生中だけスリープさせない」状態を流すための flag。
    var isPlaying by remember(playUri) { mutableStateOf(false) }
    val player = remember(playUri) {
        // 端末の「使用状況と診断情報」経由で再生の統計を渡さない (PRIVACY.md §5)。
        ExoPlayer.Builder(context).setUsePlatformDiagnostics(false).build().apply {
            setMediaItem(MediaItem.fromUri(playUri))
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    playbackError = error.message
                        ?: resources.getString(R.string.video_playback_error, error.errorCodeName)
                }
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }
            })
            if (startPositionMs > 0) seekTo(startPositionMs)
            prepare()
            playWhenReady = startPlaying
        }
    }
    // 操作バーには、飛ばす量を設定に合わせたプレーヤーを渡す (ExoPlayer の量は作るときにしか決められず、割合にもできないため)。
    val currentSkip = rememberUpdatedState(skip)
    val controllerPlayer = remember(player) {
        object : ForwardingPlayer(player) {
            override fun getSeekBackIncrement(): Long = currentSkip.value.incrementMs(player.duration)
            override fun getSeekForwardIncrement(): Long = currentSkip.value.incrementMs(player.duration)
            override fun seekBack() {
                player.seekTo((player.currentPosition - seekBackIncrement).coerceAtLeast(0))
            }
            override fun seekForward() {
                val duration = player.duration
                val target = player.currentPosition + seekForwardIncrement
                player.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
            }
        }
    }
    DisposableEffect(player) {
        onPlayerChanged(player)
        onDispose {
            onPlayerChanged(null)
            player.release()
        }
    }

    // つまんで拡大した倍率と、拡大中にずらした量 (px)。動画を替えたり小窓にしたら元に戻す。
    var zoom by remember(playUri) { mutableFloatStateOf(1f) }
    var pan by remember(playUri) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(inPip) {
        if (inPip) {
            zoom = 1f
            pan = Offset.Zero
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(playUri, inPip) {
                if (inPip) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        // PlayerView より先に受け取る (Initial)。消費すると PlayerView にはキャンセルが届き、
                        // つまんだだけで操作バーが出たり消えたりしない。
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val fingers = event.changes.count { it.pressed }
                        // 1 本指は拡大中だけずらす。等倍のときはタップを PlayerView に任せる。
                        if (fingers >= 2 || zoom > 1f) {
                            val newZoom = (zoom * event.calculateZoom()).coerceIn(1f, 5f)
                            val maxX = size.width * (newZoom - 1f) / 2f
                            val maxY = size.height * (newZoom - 1f) / 2f
                            val moved = pan + event.calculatePan()
                            val changed = newZoom != zoom || moved != pan
                            zoom = newZoom
                            pan = Offset(moved.x.coerceIn(-maxX, maxX), moved.y.coerceIn(-maxY, maxY))
                            if (fingers >= 2 || changed) {
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = controllerPlayer
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { view ->
                // 再生中だけ keepScreenOn を立てる。一時停止/終了でディスプレイは通常通り消える。
                view.keepScreenOn = isPlaying
                // 小窓では操作バーが映像を覆ってしまうので出さない。
                view.useController = !inPip
                if (inPip) view.hideController()
                // SurfaceView は Compose の graphicsLayer では拡大できないので、映像の面の View を直接拡大する。
                view.videoSurfaceView?.apply {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = pan.x
                    translationY = pan.y
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // 再生エラー時はオーバーレイで案内 + 別アプリへ。
        playbackError?.let { msg ->
            UnsupportedOverlay(
                modifier = Modifier.fillMaxSize(),
                message = stringResource(R.string.video_cannot_play, msg),
                onOpen = { onOpenExternally(file) },
            )
        }
    }
}

@Composable
private fun UnsupportedOverlay(modifier: Modifier, message: String, onOpen: () -> Unit) {
    Box(
        modifier = modifier.background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.video_open_external_help),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onOpen) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                Spacer(Modifier.height(0.dp))
                Text(stringResource(R.string.open_in_another_app))
            }
        }
    }
}
