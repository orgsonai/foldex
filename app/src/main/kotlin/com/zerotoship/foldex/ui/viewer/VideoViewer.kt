// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.viewer

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.ViewGroup
import android.view.Window
import androidx.activity.compose.LocalActivity
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
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.zerotoship.foldex.R
import com.zerotoship.foldex.core.model.VideoSkip
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

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
 *   画面の左右 1/3 をすばやく続けて叩いても同じだけ飛ぶ。
 * - 縦にスワイプすると、左半分でこの画面の明るさ、右半分で音量が変わる。
 * - 止めた位置を動画ごとに覚え ([VideoPositionStore])、次に開くと続きから再生する。
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
    /** 再生速度 (1 が等倍)。 */
    speed: Float = 1f,
    /** 操作バーの歯車など、プレーヤー側で速度が変わったとき。 */
    onSpeedChange: (Float) -> Unit = {},
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

    // 前回止めた位置。小窓から戻したとき (位置の指定あり) は使わない。
    val resumePositionMs = remember(playUri) {
        if (startPositionMs > 0) 0L else VideoPositionStore.get(context, playUri)
    }
    var showResumeBar by remember(playUri) { mutableStateOf(resumePositionMs > 0) }
    val currentOnSpeedChange = rememberUpdatedState(onSpeedChange)

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
                    // 止めるたびに位置を覚える (アプリごと終了させられても続きから見られるように)。
                    if (!playing) VideoPositionStore.save(context, playUri, currentPosition, duration)
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) VideoPositionStore.remove(context, playUri)
                }
                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                    // 操作バーの歯車から変えた速度も、上の速度ボタンの表示に反映する。
                    currentOnSpeedChange.value(playbackParameters.speed)
                }
            })
            val startAt = if (startPositionMs > 0) startPositionMs else resumePositionMs
            if (startAt > 0) seekTo(startAt)
            prepare()
            playWhenReady = startPlaying
        }
    }
    LaunchedEffect(player, speed) {
        if (player.playbackParameters.speed != speed) player.setPlaybackSpeed(speed)
    }
    LaunchedEffect(showResumeBar) {
        if (showResumeBar) {
            delay(RESUME_BAR_MS)
            showResumeBar = false
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
            if (player.playbackState != Player.STATE_ENDED) {
                VideoPositionStore.save(context, playUri, player.currentPosition, player.duration)
            }
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

    // 左右のダブルタップで飛んだ知らせ (向き。null なら出さない) と、縦スワイプで変えた明るさ・音量の知らせ。
    var skipFeedback by remember { mutableStateOf<SkipFeedback?>(null) }
    var levelFeedback by remember { mutableStateOf<LevelFeedback?>(null) }
    LaunchedEffect(skipFeedback) {
        if (skipFeedback != null) {
            delay(FEEDBACK_MS)
            skipFeedback = null
        }
    }
    LaunchedEffect(levelFeedback) {
        if (levelFeedback != null) {
            delay(FEEDBACK_MS)
            levelFeedback = null
        }
    }
    val skipLabel = if (skip.percent) "${skip.amount}%" else stringResource(R.string.popup_skip_seconds, skip.amount)
    val window = LocalActivity.current?.window
    val audioManager = remember { context.getSystemService(AudioManager::class.java) }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(playUri, inPip) {
                if (inPip) return@pointerInput
                var lastTapUpMs = 0L
                var lastTapSide = 0
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    // 左 1/3 は巻き戻し、右 1/3 は早送りの場所。真ん中はふつうのタップ。
                    val side = when {
                        down.position.x < size.width / 3f -> -1
                        down.position.x > size.width * 2f / 3f -> 1
                        else -> 0
                    }
                    // 同じ側をすばやく続けて叩いたら 2 回目から飛ぶ (叩くたびに飛ぶ)。
                    // 1 回目は操作バーの出し入れとして PlayerView に任せる。
                    val repeatTap = side != 0 && zoom == 1f && side == lastTapSide &&
                        down.uptimeMillis - lastTapUpMs < viewConfiguration.doubleTapTimeoutMillis
                    if (repeatTap) down.consume()
                    var swipe = Swipe.NONE
                    var level = 0f
                    var multiTouch = false
                    var moved = false
                    var upMs = down.uptimeMillis
                    do {
                        // PlayerView より先に受け取る (Initial)。消費すると PlayerView にはキャンセルが届き、
                        // つまんだりスワイプしただけで操作バーが出たり消えたりしない。
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2) multiTouch = true
                        if (swipe == Swipe.NONE && (fingers >= 2 || zoom > 1f)) {
                            // 2 本指はつまんで拡大、拡大中の 1 本指はずらす。
                            val newZoom = (zoom * event.calculateZoom()).coerceIn(1f, 5f)
                            val maxX = size.width * (newZoom - 1f) / 2f
                            val maxY = size.height * (newZoom - 1f) / 2f
                            val movedPan = pan + event.calculatePan()
                            val changed = newZoom != zoom || movedPan != pan
                            zoom = newZoom
                            pan = Offset(movedPan.x.coerceIn(-maxX, maxX), movedPan.y.coerceIn(-maxY, maxY))
                            if (fingers >= 2 || changed) {
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } else if (fingers == 1 && !multiTouch) {
                            val change = event.changes.first { it.pressed }
                            val total = change.position - down.position
                            if (total.getDistance() > viewConfiguration.touchSlop) moved = true
                            // 縦にはっきり動かしたら、左半分は明るさ、右半分は音量。
                            // 上端は通知を引き出す操作と重なるので除く。
                            if (swipe == Swipe.NONE && moved && !repeatTap &&
                                abs(total.y) > abs(total.x) * 1.5f && down.position.y > size.height * TOP_EXCLUSION
                            ) {
                                swipe = if (down.position.x < size.width / 2f) Swipe.BRIGHTNESS else Swipe.VOLUME
                                level = if (swipe == Swipe.BRIGHTNESS) currentBrightness(context, window) else currentVolume(audioManager)
                            }
                            if (swipe != Swipe.NONE) {
                                level = (level + (change.previousPosition.y - change.position.y) / (size.height * SWIPE_FULL_RATIO))
                                    .coerceIn(0f, 1f)
                                if (swipe == Swipe.BRIGHTNESS) setBrightness(window, level) else setVolume(audioManager, level)
                                levelFeedback = LevelFeedback(swipe == Swipe.VOLUME, (level * 100).roundToInt())
                                change.consume()
                            }
                        }
                        if (repeatTap) event.changes.forEach { it.consume() }
                        upMs = event.changes.first().uptimeMillis
                    } while (event.changes.any { it.pressed })
                    if (!moved && !multiTouch && swipe == Swipe.NONE && zoom == 1f && side != 0) {
                        if (repeatTap) {
                            if (side < 0) controllerPlayer.seekBack() else controllerPlayer.seekForward()
                            skipFeedback = SkipFeedback(forward = side > 0)
                        }
                        lastTapUpMs = upMs
                        lastTapSide = side
                    } else {
                        lastTapUpMs = 0L
                        lastTapSide = 0
                    }
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
        if (!inPip) {
            skipFeedback?.let { feedback ->
                VideoSkipFeedback(
                    forward = feedback.forward,
                    label = skipLabel,
                    modifier = Modifier
                        .align(if (feedback.forward) Alignment.CenterEnd else Alignment.CenterStart)
                        .padding(horizontal = 48.dp),
                )
            }
            levelFeedback?.let { feedback ->
                VideoLevelFeedback(feedback.volume, feedback.percent, Modifier.align(Alignment.Center))
            }
            if (showResumeBar) {
                VideoResumeBar(
                    positionMs = resumePositionMs,
                    onRestart = {
                        player.seekTo(0)
                        VideoPositionStore.remove(context, playUri)
                        showResumeBar = false
                    },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
                )
            }
        }
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

private enum class Swipe { NONE, BRIGHTNESS, VOLUME }

/** 新しく作るたびに別物として扱われる (同じ向きで続けて飛んでも、知らせを出し直す)。 */
private class SkipFeedback(val forward: Boolean)

private data class LevelFeedback(val volume: Boolean, val percent: Int)

/** 上端からこの割合の高さまでは、明るさ・音量のスワイプを始めない。 */
private const val TOP_EXCLUSION = 0.08f

/** 画面の高さのこの割合だけ動かすと、0% から 100% まで変わる。 */
private const val SWIPE_FULL_RATIO = 0.8f

private const val FEEDBACK_MS = 700L
private const val RESUME_BAR_MS = 6_000L

/**
 * 今の明るさ (0〜1)。この画面だけの明るさを変えていなければ端末の設定値から始める。
 * 端末の設定値は 0〜255 の目安で、機種によっては見た目と少しずれる。
 */
private fun currentBrightness(context: Context, window: Window?): Float {
    val own = window?.attributes?.screenBrightness ?: -1f
    if (own >= 0f) return own
    val system = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    }.getOrDefault(128)
    return (system / 255f).coerceIn(0f, 1f)
}

/** この画面だけの明るさを変える (端末の設定は変えない。ビューアを閉じれば元に戻る)。 */
private fun setBrightness(window: Window?, level: Float) {
    window ?: return
    window.attributes = window.attributes.apply { screenBrightness = level.coerceAtLeast(MIN_BRIGHTNESS) }
}

/** 真っ暗で何も見えなくならないよう、少しだけ明るさを残す。 */
private const val MIN_BRIGHTNESS = 0.01f

private fun currentVolume(audioManager: AudioManager?): Float {
    audioManager ?: return 0f
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
}

private fun setVolume(audioManager: AudioManager?, level: Float) {
    audioManager ?: return
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).roundToInt(), 0) }
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
