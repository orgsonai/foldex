// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.model

import kotlin.math.roundToLong

enum class AssetKind { VIDEO, IMAGE, AUDIO }

/**
 * 素材 1 つ。映像や音のデータは持たず、場所と調べた情報だけを持つ。
 * [widthPx] / [heightPx] は回転情報を反映した「表示される向き」の寸法。
 */
data class MediaAsset(
    val id: String,
    val kind: AssetKind,
    /** 端末内の絶対パス、または content:// URI。 */
    val source: String,
    val name: String,
    /** 映像・音声の長さ。静止画は 0。 */
    val durationUs: Long,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val hasAudio: Boolean = false,
)

/** キャンバスと素材の縦横比が違うときの収め方。 */
enum class FrameFill { FIT, FILL }

enum class CanvasAspect(val width: Int, val height: Int) {
    R16_9(16, 9),
    R9_16(9, 16),
    R1_1(1, 1),
    R4_5(4, 5),
    R4_3(4, 3),
    ;

    val ratio: Float get() = width.toFloat() / height
}

/**
 * メイントラックのクリップ。タイムライン上の位置は持たず、前にあるクリップの長さの合計で決まる (前詰め)。
 * 位置を持たないので、途中のクリップを削除・トリムしても後ろが自動で詰まる。
 */
data class MainClip(
    val id: String,
    val assetId: String,
    val sourceInUs: Long,
    val sourceOutUs: Long,
    val speed: Double = 1.0,
    val keepPitch: Boolean = true,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val fadeInUs: Long = 0L,
    val fadeOutUs: Long = 0L,
    val quarterTurns: Int = 0,
    val flipHorizontal: Boolean = false,
    val fill: FrameFill = FrameFill.FIT,
) {
    val sourceDurationUs: Long get() = sourceOutUs - sourceInUs

    /** タイムライン上の長さ = 素材の区間 ÷ 速度。 */
    val durationUs: Long get() = (sourceDurationUs / speed).roundToLong()

    /** クリップ内の時刻 → 素材の時刻。 */
    fun sourceTimeAt(localUs: Long): Long = sourceInUs + (localUs.coerceIn(0L, durationUs) * speed).roundToLong()
}

data class TextClip(
    val id: String,
    val startUs: Long,
    val durationUs: Long,
    val text: String,
    /** キャンバスの高さに対する文字の大きさ。 */
    val sizeFraction: Float = 0.08f,
    val color: Int = 0xFFFFFFFF.toInt(),
    val outlined: Boolean = true,
    /** キャンバスに対する文字の中心 (0〜1)。 */
    val centerX: Float = 0.5f,
    val centerY: Float = 0.8f,
) {
    val endUs: Long get() = startUs + durationUs
}

/** 音声トラックのクリップ。メインと違い、タイムライン上の開始時刻を自分で持つ。 */
data class AudioClip(
    val id: String,
    val assetId: String,
    val startUs: Long,
    val sourceInUs: Long,
    val sourceOutUs: Long,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val fadeInUs: Long = 0L,
    val fadeOutUs: Long = 0L,
) {
    val durationUs: Long get() = sourceOutUs - sourceInUs
    val endUs: Long get() = startUs + durationUs
}

/** 選択中のクリップ。種類ごとに探す場所が違うので型で区別する。 */
sealed interface ClipRef {
    val id: String

    data class Main(override val id: String) : ClipRef

    data class Text(override val id: String) : ClipRef

    data class Audio(override val id: String) : ClipRef
}

/** 編集中のプロジェクト全体。画素や音のデータを含まないので、丸ごと Undo 履歴に積める。 */
data class VideoProject(
    val assets: Map<String, MediaAsset> = emptyMap(),
    val mainTrack: List<MainClip> = emptyList(),
    val texts: List<TextClip> = emptyList(),
    val audioTrack: List<AudioClip> = emptyList(),
    val aspect: CanvasAspect = CanvasAspect.R16_9,
    val background: Int = 0xFF000000.toInt(),
    val frameRate: FrameRate = FrameRate.FPS_30,
) {
    val mainDurationUs: Long get() = mainTrack.sumOf { it.durationUs }

    val durationUs: Long
        get() = maxOf(
            mainDurationUs,
            texts.maxOfOrNull { it.endUs } ?: 0L,
            audioTrack.maxOfOrNull { it.endUs } ?: 0L,
        )

    val clipCount: Int get() = mainTrack.size + texts.size + audioTrack.size

    /** [index] 番目のメインクリップの開始時刻。 */
    fun mainStartUs(index: Int): Long = mainTrack.take(index).sumOf { it.durationUs }

    fun mainIndexOf(id: String): Int = mainTrack.indexOfFirst { it.id == id }

    /**
     * [timeUs] にあるメインクリップの番号と、クリップ内の時刻。
     * 全体の末尾ちょうどは、最後のクリップの終わりとして扱う。
     */
    fun mainClipAt(timeUs: Long): Pair<Int, Long>? {
        var start = 0L
        mainTrack.forEachIndexed { index, clip ->
            val end = start + clip.durationUs
            if (timeUs < end || (index == mainTrack.lastIndex && timeUs == end)) {
                return index to (timeUs - start).coerceAtLeast(0L)
            }
            start = end
        }
        return null
    }

    fun contains(ref: ClipRef): Boolean = when (ref) {
        is ClipRef.Main -> mainTrack.any { it.id == ref.id }
        is ClipRef.Text -> texts.any { it.id == ref.id }
        is ClipRef.Audio -> audioTrack.any { it.id == ref.id }
    }
}
