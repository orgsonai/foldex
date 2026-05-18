package com.zerotoship.foldex.ui.viewer

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
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
 */
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
) {
    val context = LocalContext.current
    val ext = remember(displayName) { displayName.substringAfterLast('.', "").lowercase() }
    val unsupportedExtension = ext == "wmv" || ext == "asf"

    if (unsupportedExtension) {
        UnsupportedOverlay(
            modifier = modifier,
            message = "WMV / ASF は内蔵プレイヤーで再生できません。",
            onOpen = { onOpenExternally(file) },
        )
        return
    }

    val playUri = mediaUri ?: file.toURI().toString()
    var playbackError by remember(playUri) { mutableStateOf<String?>(null) }

    // PlayerView 側へ「再生中だけスリープさせない」状態を流すための flag。
    var isPlaying by remember(playUri) { mutableStateOf(false) }
    val player = remember(playUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(playUri))
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    playbackError = error.message ?: "再生エラー (${error.errorCodeName})"
                }
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }
            })
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
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
            },
            modifier = Modifier.fillMaxSize(),
        )
        // 再生エラー時はオーバーレイで案内 + 別アプリへ。
        playbackError?.let { msg ->
            UnsupportedOverlay(
                modifier = Modifier.fillMaxSize(),
                message = "再生できません: $msg",
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
                "VLC や MX Player など外部アプリで開いてください",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onOpen) {
                Icon(Icons.Default.OpenInNew, contentDescription = null)
                Spacer(Modifier.height(0.dp))
                Text("  別のアプリで開く")
            }
        }
    }
}
