// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.plan

import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.ui.videoedit.model.AssetKind
import com.zerotoship.foldex.ui.videoedit.model.AudioClip
import com.zerotoship.foldex.ui.videoedit.model.CanvasAspect
import com.zerotoship.foldex.ui.videoedit.model.ExportEstimate
import com.zerotoship.foldex.ui.videoedit.model.ExportPreset
import com.zerotoship.foldex.ui.videoedit.model.ExportSettings
import com.zerotoship.foldex.ui.videoedit.model.MainClip
import com.zerotoship.foldex.ui.videoedit.model.MediaAsset
import com.zerotoship.foldex.ui.videoedit.model.TextClip
import com.zerotoship.foldex.ui.videoedit.model.US_PER_SECOND
import com.zerotoship.foldex.ui.videoedit.model.VideoExportFailure
import com.zerotoship.foldex.ui.videoedit.model.VideoProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val S = US_PER_SECOND

class RenderPlannerTest {

    private val video = MediaAsset("v", AssetKind.VIDEO, "/v.mp4", "v.mp4", durationUs = 10 * S, hasAudio = true)
    private val silent = MediaAsset("s", AssetKind.VIDEO, "/s.mp4", "s.mp4", durationUs = 10 * S, hasAudio = false)
    private val image = MediaAsset("i", AssetKind.IMAGE, "/i.jpg", "i.jpg", durationUs = 0)
    private val music = MediaAsset("m", AssetKind.AUDIO, "/m.mp3", "m.mp3", durationUs = 60 * S)
    private val assets = listOf(video, silent, image, music).associateBy { it.id }

    private fun project(
        main: List<MainClip> = emptyList(),
        audio: List<AudioClip> = emptyList(),
        texts: List<TextClip> = emptyList(),
        background: Int = 0xFF000000.toInt(),
    ) = VideoProject(assets = assets, mainTrack = main, audioTrack = audio, texts = texts, background = background)

    private fun plan(project: VideoProject, settings: ExportSettings = ExportSettings()): RenderPlan =
        (RenderPlanner.plan(project, settings) as Result.Success).value

    private fun failure(project: VideoProject, settings: ExportSettings = ExportSettings()): VideoExportFailure =
        (RenderPlanner.plan(project, settings) as Result.Failure).error.reason

    private val tenSeconds = MainClip("c1", "v", 0, 10 * S)

    @Test
    fun `メインのクリップを順に並べ、全体の長さは速度変更の後の合計`() {
        val result = plan(project(main = listOf(tenSeconds, MainClip("c2", "v", 2 * S, 6 * S, speed = 2.0))))
        assertEquals(12 * S, result.durationUs)
        assertEquals(listOf(10 * S, 2 * S), result.main.map { it.durationUs })
        val second = result.main[1] as PlannedMedia
        assertEquals(2 * S, second.sourceInUs)
        assertEquals(6 * S, second.sourceOutUs)
        assertEquals(2.0, second.speed, 0.0)
    }

    @Test
    fun `出力の寸法は比率と短辺から決め、偶数にする`() {
        assertEquals(1920 to 1080, RenderPlanner.canvasSize(CanvasAspect.R16_9, 1080))
        assertEquals(1080 to 1920, RenderPlanner.canvasSize(CanvasAspect.R9_16, 1080))
        assertEquals(1080 to 1350, RenderPlanner.canvasSize(CanvasAspect.R4_5, 1080))
        assertEquals(960 to 720, RenderPlanner.canvasSize(CanvasAspect.R4_3, 720))
        assertEquals(2160 to 2160, RenderPlanner.canvasSize(CanvasAspect.R1_1, 2160))
    }

    @Test
    fun `音声トラックは重なるクリップを別の段に分け、前に隙間を入れる`() {
        val a1 = AudioClip("a1", "m", startUs = 1 * S, sourceInUs = 0, sourceOutUs = 4 * S)
        val a2 = AudioClip("a2", "m", startUs = 3 * S, sourceInUs = 10 * S, sourceOutUs = 12 * S)
        val a3 = AudioClip("a3", "m", startUs = 6 * S, sourceInUs = 0, sourceOutUs = 3 * S)
        val result = plan(project(main = listOf(tenSeconds), audio = listOf(a3, a2, a1)))
        assertEquals(2, result.audioLanes.size)
        // 段 1: 隙間 1 秒 → a1 (1〜5 秒) → 隙間 1 秒 → a3 (6〜9 秒)。段 2: 隙間 3 秒 → a2 (3〜5 秒)。
        assertEquals(listOf(1 * S, 4 * S, 1 * S, 3 * S), result.audioLanes[0].map { it.durationUs })
        assertEquals(listOf(true, false, true, false), result.audioLanes[0].map { it is PlannedGap })
        assertEquals(listOf(3 * S, 2 * S), result.audioLanes[1].map { it.durationUs })
        assertEquals(10 * S, (result.audioLanes[1][1] as PlannedMedia).sourceInUs)
    }

    @Test
    fun `映像の終わりより後ろの音声と文字は切り、知らせる`() {
        val crossing = AudioClip("a1", "m", startUs = 8 * S, sourceInUs = 0, sourceOutUs = 5 * S, fadeOutUs = 1 * S)
        val after = AudioClip("a2", "m", startUs = 12 * S, sourceInUs = 0, sourceOutUs = 2 * S)
        val text = TextClip("t1", startUs = 9 * S, durationUs = 3 * S, text = "end")
        val result = plan(project(main = listOf(tenSeconds), audio = listOf(crossing, after), texts = listOf(text)))
        assertEquals(10 * S, result.durationUs)
        assertEquals(listOf(8 * S, 2 * S), result.audioLanes.single().map { it.durationUs })
        val trimmed = result.audioLanes.single()[1] as PlannedMedia
        assertEquals(2 * S, trimmed.sourceOutUs)
        // フェードアウトは元の長さ (5 秒) で計算し、切った位置で音が止まる。
        assertEquals(5 * S, trimmed.gain.durationUs)
        assertEquals(10 * S, result.texts.single().endUs)
        assertTrue(PlanNotice.TRIMMED_AFTER_VIDEO in result.notices)
    }

    @Test
    fun `音声のみはメインと音声の長い方の長さで、静止画は無音の隙間になる`() {
        val main = listOf(MainClip("c1", "i", 0, 3 * S), tenSeconds)
        val audio = listOf(AudioClip("a1", "m", startUs = 0, sourceInUs = 0, sourceOutUs = 20 * S))
        val result = plan(project(main = main, audio = audio, texts = listOf(TextClip("t", 0, S, "x"))), ExportSettings(preset = ExportPreset.AUDIO_ONLY))
        assertEquals(20 * S, result.durationUs)
        assertEquals(PlannedGap(3 * S), result.main[0])
        assertTrue(result.main[1] is PlannedMedia)
        assertTrue(result.output.audioOnly)
        assertEquals(0, result.output.width)
        assertTrue(result.texts.isEmpty())
        assertTrue(result.notices.isEmpty())
    }

    @Test
    fun `音声のみで静止画だけのメインは段を作らない`() {
        val audio = listOf(AudioClip("a1", "m", startUs = 0, sourceInUs = 0, sourceOutUs = 5 * S))
        val result = plan(project(main = listOf(MainClip("c1", "i", 0, 3 * S)), audio = audio), ExportSettings(preset = ExportPreset.AUDIO_ONLY))
        assertTrue(result.main.isEmpty())
        assertEquals(1, result.audioLanes.size)
    }

    @Test
    fun `書き出せない構成は理由を返す`() {
        assertEquals(VideoExportFailure.NOTHING_TO_EXPORT, failure(project()))
        val onlyMusic = project(audio = listOf(AudioClip("a1", "m", 0, 0, 5 * S)))
        assertEquals(VideoExportFailure.NO_VIDEO, failure(onlyMusic))
        val silentOnly = project(main = listOf(MainClip("c1", "s", 0, 10 * S)))
        assertEquals(VideoExportFailure.NO_AUDIO, failure(silentOnly, ExportSettings(preset = ExportPreset.AUDIO_ONLY)))
        assertEquals(VideoExportFailure.SOURCE_MISSING, failure(project(main = listOf(MainClip("c1", "x", 0, S)))))
        assertEquals(VideoExportFailure.UNSUPPORTED_PRESET, failure(project(main = listOf(tenSeconds)), ExportSettings(preset = ExportPreset.LOSSLESS_CUT)))
    }

    @Test
    fun `容量指定は目標を上限として持ち、小さすぎる目標は止める`() {
        val result = plan(project(main = listOf(tenSeconds)), ExportSettings(preset = ExportPreset.TARGET_SIZE, targetMiB = 50))
        assertEquals(50 * ExportEstimate.MIB, result.output.targetBytes)
        // 50MiB ÷ 10 秒 = 41,943kbps。容器の余裕 2% と音声 192kbps を引いて 40,912kbps。
        assertEquals(40_912, result.output.videoKbps)
        val hour = List(360) { MainClip("c$it", "v", 0, 10 * S) }
        assertEquals(VideoExportFailure.TARGET_TOO_SMALL, failure(project(main = hour), ExportSettings(preset = ExportPreset.TARGET_SIZE, targetMiB = 1)))
    }

    @Test
    fun `黒以外の背景色は書き出せないことを知らせる`() {
        assertTrue(PlanNotice.BACKGROUND_BLACK in plan(project(main = listOf(tenSeconds), background = 0xFFFFFFFF.toInt())).notices)
        assertFalse(PlanNotice.BACKGROUND_BLACK in plan(project(main = listOf(tenSeconds))).notices)
    }

    @Test
    fun `回転は 4 で割った余りに揃える`() {
        val result = plan(project(main = listOf(tenSeconds.copy(quarterTurns = -1), MainClip("c2", "v", 0, S, quarterTurns = 5))))
        assertEquals(listOf(3, 1), result.main.map { (it as PlannedMedia).quarterTurns })
    }
}
