// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.engine

import android.content.Context
import android.content.ContextWrapper
import android.view.Surface
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.transformer.CompositionPlayer
import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.ui.videoedit.model.ExportPreset
import com.zerotoship.foldex.ui.videoedit.model.ExportResolution
import com.zerotoship.foldex.ui.videoedit.model.ExportSettings
import com.zerotoship.foldex.ui.videoedit.model.VideoProject
import com.zerotoship.foldex.ui.videoedit.plan.RenderPlanner

enum class PreviewStatus { EMPTY, PREPARING, READY, FAILED }

/** Media3 はメインスレッドだけで触る。UI の時計は常にデコーダの再生位置から取得する。 */
@Stable
@androidx.annotation.OptIn(UnstableApi::class, ExperimentalApi::class)
internal class PreviewController(context: Context) {
    private val context = PreviewContext(context.applicationContext)
    private var player: CompositionPlayer? = null
    private var surface: Surface? = null
    private var surfaceSize = Size(0, 0)
    private var project: VideoProject? = null
    private var needsPrepare = false
    private var scrubbing = false
    private var active = true
    private var pendingProject: VideoProject? = null
    private var pendingTimeUs = 0L
    private var appliedProject by mutableStateOf<VideoProject?>(null)
    var status by mutableStateOf(PreviewStatus.EMPTY)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionUs by mutableLongStateOf(0L)
        private set
    var durationUs by mutableLongStateOf(0L)
        private set
    var errorCode by mutableStateOf<String?>(null)
        private set
    var loop by mutableStateOf(false)
        private set
    var ended by mutableStateOf(false)
        private set

    fun update(next: VideoProject, atUs: Long) {
        if (!active) { pendingProject = next; pendingTimeUs = atUs; return }
        if (project == next && status != PreviewStatus.FAILED) {
            if (needsPrepare) { player?.prepare(); needsPrepare = false }
            return
        }
        pause()
        project = next
        ended = false
        errorCode = null
        if (next.mainTrack.isEmpty() && next.audioTrack.isEmpty()) {
            releasePlayer()
            durationUs = 0L
            status = PreviewStatus.EMPTY
            appliedProject = null
            return
        }
        val settings = ExportSettings(
            preset = if (next.mainTrack.isEmpty()) ExportPreset.AUDIO_ONLY else ExportPreset.STANDARD,
            resolution = ExportResolution.P720,
            fps = next.frameRate.nominalFps,
        )
        val plan = when (val result = RenderPlanner.plan(next, settings)) {
            is Result.Success -> result.value
            is Result.Failure -> { fail("PROJECT_UNAVAILABLE"); return }
        }
        durationUs = plan.durationUs
        positionUs = atUs.coerceIn(0L, durationUs)
        status = PreviewStatus.PREPARING
        try {
            val current = player ?: CompositionPlayer.Builder(context)
                .setVideoGraphFactory(MultipleInputVideoGraph.Factory())
                .experimentalSetEnableReplayableCache(true)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                .build().also { created ->
                    player = created
                    created.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_BUFFERING -> status = PreviewStatus.PREPARING
                                Player.STATE_READY -> status = PreviewStatus.READY
                                Player.STATE_ENDED -> {
                                    positionUs = durationUs
                                    pause()
                                    ended = true
                                }
                            }
                        }
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            playing = isPlaying
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            fail(error.errorCodeName)
                        }
                    })
                    surface?.takeIf { it.isValid }?.let { created.setVideoSurface(it, surfaceSize) }
                }
            current.setComposition(CompositionAdapter.build(plan), positionUs / 1_000L)
            current.repeatMode = if (loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
            current.prepare()
            needsPrepare = false
            appliedProject = next
        } catch (error: Exception) {
            fail(error.javaClass.simpleName)
        }
    }

    fun matches(project: VideoProject): Boolean = appliedProject == project

    fun attach(surface: Surface, width: Int, height: Int) {
        this.surface = surface
        surfaceSize = Size(width, height)
        if (surface.isValid) player?.let {
            it.setVideoSurface(surface, surfaceSize)
            it.experimentalRedrawLastFrame()
        }
    }

    fun detach(surface: Surface) {
        if (this.surface !== surface) return
        player?.clearVideoSurface()
        this.surface = null
    }

    fun seek(atUs: Long) {
        ended = false
        positionUs = atUs.coerceIn(0L, durationUs)
        if (status != PreviewStatus.FAILED) player?.seekTo(positionUs / 1_000L)
    }

    fun scrub(enabled: Boolean) {
        if (scrubbing == enabled) return
        // pause は scrubbing mode の前。終了時に以前の再生状態へ勝手に戻らないようにする。
        pause()
        scrubbing = enabled
        player?.setScrubbingModeEnabled(enabled)
    }

    fun togglePlay() {
        val current = player ?: return
        if (!active || status == PreviewStatus.FAILED) return
        if (current.playWhenReady) pause() else {
            if (scrubbing) scrub(false)
            if (positionUs >= durationUs || current.playbackState == Player.STATE_ENDED) seek(0L)
            if (needsPrepare) { current.prepare(); needsPrepare = false }
            current.play()
        }
    }

    fun pause() {
        player?.pause()
        playing = false
    }

    fun updateLoop(enabled: Boolean) {
        loop = enabled
        player?.repeatMode = if (enabled) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
    }

    fun poll(): Long {
        val current = player ?: return positionUs
        if (playing) positionUs = (current.currentPosition * 1_000L).coerceIn(0L, durationUs)
        return positionUs
    }

    fun setActive(active: Boolean) {
        this.active = active
        if (!active) {
            pause()
            // 画面が裏に回ったらデコーダを解放する。前面へ戻っても自動再生しない。
            player?.stop()
            needsPrepare = true
        } else if (pendingProject != null) {
            val pending = pendingProject!!
            pendingProject = null
            update(pending, pendingTimeUs)
        } else if (needsPrepare && status != PreviewStatus.FAILED) {
            player?.prepare()
            needsPrepare = false
        }
    }

    fun retry(atUs: Long) {
        val current = project ?: return
        releasePlayer()
        project = null
        update(current, atUs)
    }

    fun release() {
        releasePlayer()
        surface = null
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        appliedProject = null
        playing = false
        scrubbing = false
    }

    private fun fail(code: String) {
        pause()
        status = PreviewStatus.FAILED
        errorCode = code
        appliedProject = null
    }
}

/**
 * CompositionPlayer 1.11.1 の内部 ExoPlayer には診断を止める Builder 引数がない。
 * このプレイヤーの Context にだけ MediaMetrics を提供せず、内部の診断リスナー生成を止める。
 */
internal class PreviewContext(context: Context) : ContextWrapper(context) {
    override fun getApplicationContext(): Context = this
    override fun getSystemService(name: String): Any? = if (name == "media_metrics") null else super.getSystemService(name)
}
