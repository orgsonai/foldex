// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.model

import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class ExportPreset { STANDARD, HIGH_QUALITY, TARGET_SIZE, AUDIO_ONLY, LOSSLESS_CUT }

enum class ExportResolution(val shortEdge: Int) { P720(720), P1080(1080), P2160(2160) }

enum class VideoCodecChoice { H264, HEVC }

data class ExportSettings(
    val preset: ExportPreset = ExportPreset.STANDARD,
    val resolution: ExportResolution = ExportResolution.P1080,
    val fps: Int = 30,
    val codec: VideoCodecChoice = VideoCodecChoice.H264,
    val targetMiB: Int = 50,
    val audioKbps: Int = 192,
    val keepLocation: Boolean = true,
)

/** 書き出し前に見せる見積もり。実際の容量は書き出し後に測って確かめる (設計書 §8-3)。 */
data class ExportEstimateResult(
    /** 音声のみのときは 0。 */
    val videoKbps: Int,
    val audioKbps: Int,
    val estimatedBytes: Long,
    /** 映像のビットレートが、選んだ解像度で画質を保つ下限を下回るか。 */
    val belowMinimum: Boolean,
    /** 下限を満たせる一段低い解像度。どれも満たせなければ null。 */
    val suggestedResolution: ExportResolution?,
)

/**
 * 書き出し設定からビットレートと容量を見積もる。係数は暫定で、実機の計測で校正する。
 * 無劣化カットは元の動画のビットレートに依存するため見積もらない (null)。
 */
object ExportEstimate {
    const val CONTAINER_OVERHEAD = 0.02
    const val MIB = 1024L * 1024L

    fun estimate(settings: ExportSettings, durationUs: Long): ExportEstimateResult? {
        val audio = settings.audioKbps
        return when (settings.preset) {
            ExportPreset.LOSSLESS_CUT -> null
            ExportPreset.AUDIO_ONLY -> ExportEstimateResult(
                videoKbps = 0,
                audioKbps = audio,
                estimatedBytes = bytesFor(audio, durationUs),
                belowMinimum = false,
                suggestedResolution = null,
            )
            ExportPreset.STANDARD, ExportPreset.HIGH_QUALITY -> {
                val base = defaultVideoKbps(settings.resolution, settings.fps, settings.codec)
                val video = if (settings.preset == ExportPreset.HIGH_QUALITY) (base * HIGH_QUALITY_FACTOR).roundToInt() else base
                ExportEstimateResult(video, audio, bytesFor(video + audio, durationUs), belowMinimum = false, suggestedResolution = null)
            }
            ExportPreset.TARGET_SIZE -> {
                val video = videoKbpsForTarget(settings.targetMiB.toLong() * MIB, durationUs, audio)
                val minimum = minimumVideoKbps(settings.resolution, settings.fps, settings.codec)
                val below = video < minimum
                ExportEstimateResult(
                    videoKbps = video.coerceAtLeast(0),
                    audioKbps = audio,
                    estimatedBytes = settings.targetMiB.toLong() * MIB,
                    belowMinimum = below,
                    suggestedResolution = if (below) suggestResolution(video, settings) else null,
                )
            }
        }
    }

    /** 無劣化カットは、動画 1 本を切るだけで、ほかの加工をしていないときだけ使える。 */
    fun isLosslessCandidate(project: VideoProject): Boolean {
        val clip = project.mainTrack.singleOrNull() ?: return false
        val asset = project.assets[clip.assetId] ?: return false
        return asset.kind == AssetKind.VIDEO &&
            clip.speed == 1.0 &&
            clip.quarterTurns == 0 &&
            !clip.flipHorizontal &&
            clip.volume == 1f &&
            !clip.muted &&
            clip.fadeInUs == 0L &&
            clip.fadeOutUs == 0L &&
            project.texts.isEmpty() &&
            project.audioTrack.isEmpty() && project.layers.none { it.visible }
    }

    /**
     * 目標の容量に収まる映像のビットレート (kbps)。
     * 全体 = 容量 × 8 ÷ 秒、そこから容器の余裕 (2%) と音声を引く。
     * 例: 50MiB・180 秒・音声 128kbps → 全体 2,330kbps → 映像 2,155kbps。
     */
    fun videoKbpsForTarget(targetBytes: Long, durationUs: Long, audioKbps: Int): Int {
        if (durationUs <= 0L || targetBytes <= 0L) return 0
        val seconds = durationUs.toDouble() / US_PER_SECOND
        val totalKbps = targetBytes * 8.0 / seconds / 1000.0
        return floor(totalKbps * (1.0 - CONTAINER_OVERHEAD) - audioKbps).toInt()
    }

    /** H.264・30fps を基準にした既定の映像ビットレート。60fps は 1.5 倍、HEVC は 0.6 倍。 */
    fun defaultVideoKbps(resolution: ExportResolution, fps: Int, codec: VideoCodecChoice): Int {
        val base = when (resolution) {
            ExportResolution.P720 -> 5_000
            ExportResolution.P1080 -> 10_000
            ExportResolution.P2160 -> 40_000
        }
        return adjust(base, fps, codec, hevcFactor = 0.6)
    }

    /** 画質を保つ下限の目安。1080p30 の H.264 で 3,000kbps、720p30 で 1,500kbps。 */
    fun minimumVideoKbps(resolution: ExportResolution, fps: Int, codec: VideoCodecChoice): Int {
        val base = when (resolution) {
            ExportResolution.P720 -> 1_500
            ExportResolution.P1080 -> 3_000
            ExportResolution.P2160 -> 12_000
        }
        return adjust(base, fps, codec, hevcFactor = 0.7)
    }

    private fun suggestResolution(videoKbps: Int, settings: ExportSettings): ExportResolution? =
        ExportResolution.entries
            .filter { it.shortEdge < settings.resolution.shortEdge }
            .sortedByDescending { it.shortEdge }
            .firstOrNull { videoKbps >= minimumVideoKbps(it, settings.fps, settings.codec) }

    private fun adjust(base: Int, fps: Int, codec: VideoCodecChoice, hevcFactor: Double): Int {
        var value = base.toDouble()
        if (fps > 30) value *= 1.5
        if (codec == VideoCodecChoice.HEVC) value *= hevcFactor
        return value.roundToInt()
    }

    private fun bytesFor(kbps: Int, durationUs: Long): Long {
        if (durationUs <= 0L) return 0L
        val seconds = durationUs.toDouble() / US_PER_SECOND
        return (kbps * 1000.0 / 8.0 * seconds * (1.0 + CONTAINER_OVERHEAD)).roundToLong()
    }

    private const val HIGH_QUALITY_FACTOR = 1.6
}
