// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.plan

import com.zerotoship.foldex.ui.videoedit.model.US_PER_SECOND
import com.zerotoship.foldex.ui.videoedit.model.VideoCodecChoice
import kotlin.math.abs
import kotlin.math.floor

/** 書き出したファイルを読み取って分かったこと。寸法は回転情報を反映した表示の向き。 */
data class OutputFacts(
    val width: Int,
    val height: Int,
    val videoMimeType: String?,
    val audioMimeType: String?,
    val durationUs: Long,
    val sizeBytes: Long,
)

/** 保存はしたが、指定どおりにならなかった点。成功と同じ扱いにせず、要求と実際を並べて見せる (設計書 §8-2)。 */
sealed interface UnmetCondition {
    data class Size(val requestedWidth: Int, val requestedHeight: Int, val actualWidth: Int, val actualHeight: Int) : UnmetCondition

    data class Codec(val requestedMimeType: String, val actualMimeType: String) : UnmetCondition

    data class Duration(val expectedUs: Long, val actualUs: Long) : UnmetCondition

    data class TargetSize(val targetBytes: Long, val actualBytes: Long) : UnmetCondition
}

object ExportCheck {
    const val MIME_H264 = "video/avc"
    const val MIME_HEVC = "video/hevc"

    /** 容量指定の書き出しの回数の上限 (初回 + 再試行 2 回)。 */
    const val MAX_TARGET_ATTEMPTS = 3

    private const val RETRY_MARGIN = 0.95

    fun videoMimeType(codec: VideoCodecChoice): String = when (codec) {
        VideoCodecChoice.H264 -> MIME_H264
        VideoCodecChoice.HEVC -> MIME_HEVC
    }

    /**
     * 出力の長さの許容差。3 コマか 0.1 秒の大きい方。
     * AAC の先頭の無音や容器の丸めを見込んだ暫定値で、実機の計測で詰める (受け入れ条件は 1 コマ以内)。
     */
    fun durationToleranceUs(frameRate: Int): Long = maxOf(3 * US_PER_SECOND / frameRate.coerceAtLeast(1), 100_000L)

    /** 保存してはいけない壊れた出力か。空、音声が無い、映像の書き出しで映像が無い。 */
    fun isBroken(output: OutputSpec, facts: OutputFacts): Boolean =
        facts.sizeBytes <= 0L ||
            facts.audioMimeType == null ||
            (!output.audioOnly && (facts.videoMimeType == null || facts.width <= 0 || facts.height <= 0))

    fun unmet(output: OutputSpec, expectedDurationUs: Long, facts: OutputFacts): List<UnmetCondition> = buildList {
        if (!output.audioOnly) {
            if (facts.width != output.width || facts.height != output.height) {
                add(UnmetCondition.Size(output.width, output.height, facts.width, facts.height))
            }
            val requested = videoMimeType(output.codec)
            val actual = facts.videoMimeType
            if (actual != null && actual != requested) add(UnmetCondition.Codec(requested, actual))
        }
        if (abs(facts.durationUs - expectedDurationUs) > durationToleranceUs(output.frameRate)) {
            add(UnmetCondition.Duration(expectedDurationUs, facts.durationUs))
        }
        val target = output.targetBytes
        if (target != null && facts.sizeBytes > target) add(UnmetCondition.TargetSize(target, facts.sizeBytes))
    }

    /** 書き出しの前に確保したい空き容量。見積もりの 1.1 倍 + 32MiB。例: 見積もり 100MiB → 142MiB。 */
    fun requiredFreeBytes(estimatedBytes: Long): Long =
        (estimatedBytes.coerceAtLeast(0L) * 1.1).toLong() + 32L * 1024L * 1024L

    /**
     * 目標の容量を超えたときの、次の映像ビットレート (設計書 §8-3)。
     * 例: 2,000kbps で 60MiB になり目標が 50MiB → 2,000 × 50 / 60 × 0.95 ≒ 1,583kbps。
     */
    fun retryVideoKbps(currentKbps: Int, targetBytes: Long, actualBytes: Long): Int {
        if (actualBytes <= 0L) return currentKbps
        return floor(currentKbps * (targetBytes.toDouble() / actualBytes) * RETRY_MARGIN).toInt()
    }
}
