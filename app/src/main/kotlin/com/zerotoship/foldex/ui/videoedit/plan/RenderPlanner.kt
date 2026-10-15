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
import com.zerotoship.foldex.ui.videoedit.model.FrameFill
import com.zerotoship.foldex.ui.videoedit.model.MediaAsset
import com.zerotoship.foldex.ui.videoedit.model.VideoExportError
import com.zerotoship.foldex.ui.videoedit.model.VideoExportFailure
import com.zerotoship.foldex.ui.videoedit.model.VideoProject
import kotlin.math.roundToLong

/**
 * [VideoProject] と書き出し設定から [RenderPlan] を作る。純関数で、ファイルには触れない。
 *
 * 映像の書き出しの長さはメイントラックの長さ。音声のみはメインと音声トラックの長い方。
 */
object RenderPlanner {
    /** 容量指定で、これより低い映像ビットレートは符号化できないものとして扱う。 */
    const val MIN_VIDEO_KBPS = 100

    fun plan(project: VideoProject, settings: ExportSettings): Result<RenderPlan, VideoExportError> {
        if (settings.preset == ExportPreset.LOSSLESS_CUT) return fail(VideoExportFailure.UNSUPPORTED_PRESET)
        val audioOnly = settings.preset == ExportPreset.AUDIO_ONLY
        val missing = project.mainTrack.any { it.assetId !in project.assets } ||
            project.audioTrack.any { it.assetId !in project.assets } ||
            project.layers.any { it.visible && it.clip.assetId !in project.assets }
        if (missing) return fail(VideoExportFailure.SOURCE_MISSING)
        if (project.mainTrack.isEmpty() && project.audioTrack.isEmpty() && project.layers.none { it.visible }) return fail(VideoExportFailure.NOTHING_TO_EXPORT)
        if (!audioOnly && project.mainTrack.isEmpty()) return fail(VideoExportFailure.NO_VIDEO)
        if (audioOnly && !hasAudio(project)) return fail(VideoExportFailure.NO_AUDIO)

        val durationUs = if (audioOnly) {
            maxOf(project.mainDurationUs, project.audioTrack.maxOfOrNull { it.endUs } ?: 0L,
                project.layers.filter { it.visible }.maxOfOrNull { it.endUs } ?: 0L)
        } else {
            project.mainDurationUs
        }
        if (durationUs <= 0L) return fail(VideoExportFailure.NOTHING_TO_EXPORT)

        val estimate = ExportEstimate.estimate(settings, durationUs) ?: return fail(VideoExportFailure.UNSUPPORTED_PRESET)
        val videoKbps = if (audioOnly) 0 else estimate.videoKbps
        if (settings.preset == ExportPreset.TARGET_SIZE && videoKbps < MIN_VIDEO_KBPS) {
            return fail(VideoExportFailure.TARGET_TOO_SMALL)
        }
        val (width, height) = if (audioOnly) 0 to 0 else canvasSize(project.aspect, settings.resolution.shortEdge)
        val output = OutputSpec(
            width = width,
            height = height,
            frameRate = settings.fps,
            audioOnly = audioOnly,
            codec = settings.codec,
            videoKbps = videoKbps,
            audioKbps = estimate.audioKbps,
            targetBytes = if (settings.preset == ExportPreset.TARGET_SIZE) settings.targetMiB.toLong() * ExportEstimate.MIB else null,
        )

        val main = planMain(project, audioOnly)
        val lanes = planAudioLanes(project, durationUs)
        val texts = if (audioOnly) TrimmedTexts(emptyList(), trimmed = false) else planTexts(project, durationUs)
        val layers = project.layers.filter { it.visible && it.startUs < durationUs }.mapNotNull { layer ->
            val clip = layer.clip
            val asset = project.assets.getValue(clip.assetId)
            if (audioOnly && (asset.kind != AssetKind.VIDEO || !asset.hasAudio)) return@mapNotNull null
            val length = minOf(layer.endUs, durationUs) - layer.startUs
            val image = asset.kind == AssetKind.IMAGE
            PlannedLayer(
                media(asset, if (image) 0L else clip.sourceInUs,
                    if (image) 0L else minOf(clip.sourceOutUs, clip.sourceInUs + (length * clip.speed).roundToLong()),
                    length, if (image) 1.0 else clip.speed, clip.keepPitch,
                    GainCurve.of(clip.volume, clip.muted, clip.fadeInUs, clip.fadeOutUs, clip.durationUs),
                    clip.quarterTurns, clip.flipHorizontal, FrameFill.FIT),
                layer.startUs, layer.startUs + length, layer.centerX, layer.centerY,
                layer.widthFraction, layer.aspectRatio(asset), layer.opacity,
            )
        }
        val notices = buildSet {
            if (!audioOnly && (lanes.trimmed || texts.trimmed || project.layers.any { it.visible && it.endUs > durationUs })) add(PlanNotice.TRIMMED_AFTER_VIDEO)
            if (!audioOnly && (project.background and 0xFFFFFF) != 0) add(PlanNotice.BACKGROUND_BLACK)
        }
        return Result.Success(
            RenderPlan(
                output = output,
                // 音声のみで静止画だけのメインは、無音の隙間だけの段になるので作らない。
                main = if (main.any { it is PlannedMedia }) main else emptyList(),
                audioLanes = lanes.lanes,
                texts = texts.texts,
                durationUs = durationUs,
                notices = notices,
                layers = layers,
            ),
        )
    }

    /**
     * キャンバスの比率と短辺から、出力の寸法を決める。符号化器が扱えるよう偶数に丸める。
     * 例: 16:9・1080 → 1920×1080、4:5・1080 → 1080×1350、4:3・720 → 960×720。
     */
    fun canvasSize(aspect: CanvasAspect, shortEdge: Int): Pair<Int, Int> =
        if (aspect.width >= aspect.height) {
            even(shortEdge.toDouble() * aspect.width / aspect.height) to shortEdge
        } else {
            shortEdge to even(shortEdge.toDouble() * aspect.height / aspect.width)
        }

    private fun even(value: Double): Int = ((value / 2).roundToLong() * 2).toInt()

    private fun hasAudio(project: VideoProject): Boolean =
        project.audioTrack.isNotEmpty() ||
            project.layers.any { it.visible && project.assets[it.clip.assetId]?.hasAudio == true } ||
            project.mainTrack.any { project.assets[it.assetId]?.let { asset -> asset.kind == AssetKind.VIDEO && asset.hasAudio } == true }

    private fun planMain(project: VideoProject, audioOnly: Boolean): List<PlannedItem> =
        project.mainTrack.map { clip ->
            val asset = project.assets.getValue(clip.assetId)
            if (asset.kind == AssetKind.IMAGE) {
                if (audioOnly) {
                    PlannedGap(clip.durationUs)
                } else {
                    media(asset, 0L, 0L, clip.durationUs, 1.0, true, GainCurve(1f, 0L, 0L, clip.durationUs), clip.quarterTurns, clip.flipHorizontal, clip.fill)
                }
            } else {
                media(
                    asset = asset,
                    sourceInUs = clip.sourceInUs,
                    sourceOutUs = clip.sourceOutUs,
                    durationUs = clip.durationUs,
                    speed = clip.speed,
                    keepPitch = clip.keepPitch,
                    gain = GainCurve.of(clip.volume, clip.muted, clip.fadeInUs, clip.fadeOutUs, clip.durationUs),
                    quarterTurns = clip.quarterTurns,
                    flipHorizontal = clip.flipHorizontal,
                    fill = clip.fill,
                )
            }
        }

    private class TrimmedLanes(val lanes: List<List<PlannedItem>>, val trimmed: Boolean)

    /**
     * 音声クリップを開始の早い順に、空いている最初の段へ入れる (区間の分割)。
     * [limitUs] より後ろは切る。フェードアウトは元の長さのまま計算し、切った位置で音が止まる (編集画面と同じ)。
     */
    private fun planAudioLanes(project: VideoProject, limitUs: Long): TrimmedLanes {
        var trimmed = false
        val clips = project.audioTrack
            .sortedWith(compareBy<AudioClip>({ it.startUs }, { it.id }))
            .mapNotNull { clip ->
                val end = minOf(clip.endUs, limitUs)
                if (end < clip.endUs) trimmed = true
                if (end <= clip.startUs) null else clip to end - clip.startUs
            }
        val lanes = mutableListOf<MutableList<Pair<AudioClip, Long>>>()
        val laneEnds = mutableListOf<Long>()
        for ((clip, length) in clips) {
            val index = laneEnds.indexOfFirst { it <= clip.startUs }
            if (index < 0) {
                lanes += mutableListOf(clip to length)
                laneEnds += clip.startUs + length
            } else {
                lanes[index] += clip to length
                laneEnds[index] = clip.startUs + length
            }
        }
        val planned = lanes.map { lane ->
            buildList {
                var cursor = 0L
                for ((clip, length) in lane) {
                    if (clip.startUs > cursor) add(PlannedGap(clip.startUs - cursor))
                    add(
                        media(
                            asset = project.assets.getValue(clip.assetId),
                            sourceInUs = clip.sourceInUs,
                            sourceOutUs = clip.sourceInUs + length,
                            durationUs = length,
                            speed = 1.0,
                            keepPitch = true,
                            gain = GainCurve.of(clip.volume, clip.muted, clip.fadeInUs, clip.fadeOutUs, clip.durationUs),
                            quarterTurns = 0,
                            flipHorizontal = false,
                            fill = FrameFill.FIT,
                        ),
                    )
                    cursor = clip.startUs + length
                }
            }
        }
        return TrimmedLanes(planned, trimmed)
    }

    private class TrimmedTexts(val texts: List<PlannedText>, val trimmed: Boolean)

    private fun planTexts(project: VideoProject, limitUs: Long): TrimmedTexts {
        var trimmed = false
        val texts = project.texts
            .filter { it.text.isNotBlank() }
            .mapNotNull { clip ->
                val end = minOf(clip.endUs, limitUs)
                if (end < clip.endUs) trimmed = true
                if (end <= clip.startUs) {
                    null
                } else {
                    PlannedText(clip.startUs, end, clip.text, clip.sizeFraction, clip.color, clip.outlined, clip.centerX, clip.centerY)
                }
            }
        return TrimmedTexts(texts, trimmed)
    }

    private fun media(
        asset: MediaAsset,
        sourceInUs: Long,
        sourceOutUs: Long,
        durationUs: Long,
        speed: Double,
        keepPitch: Boolean,
        gain: GainCurve,
        quarterTurns: Int,
        flipHorizontal: Boolean,
        fill: FrameFill,
    ) = PlannedMedia(
        assetId = asset.id,
        source = asset.source,
        name = asset.name,
        kind = asset.kind,
        sourceInUs = sourceInUs,
        sourceOutUs = sourceOutUs,
        durationUs = durationUs,
        speed = speed,
        keepPitch = keepPitch,
        gain = gain,
        quarterTurns = quarterTurns.mod(4),
        flipHorizontal = flipHorizontal,
        fill = fill,
    )

    private fun fail(reason: VideoExportFailure) = Result.Failure(VideoExportError(reason))
}
