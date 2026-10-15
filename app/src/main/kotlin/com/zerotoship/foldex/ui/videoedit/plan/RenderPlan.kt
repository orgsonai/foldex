// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.plan

import com.zerotoship.foldex.ui.videoedit.model.AssetKind
import com.zerotoship.foldex.ui.videoedit.model.FrameFill
import com.zerotoship.foldex.ui.videoedit.model.VideoCodecChoice

/**
 * 「どの素材のどの区間を、どの段の何秒目に、どの効果で置くか」を値で表したもの (設計書 §5)。
 *
 * Media3 の型を含めないので、長さ・隙間・段の割り当てを JVM テストで確かめられる。
 * Media3 の `Composition` への変換は engine/CompositionAdapter だけが行う。
 */
data class RenderPlan(
    val output: OutputSpec,
    /** メインの段。映像の書き出しでは映像と音声を、音声のみでは音声だけを使う。空なら段を作らない。 */
    val main: List<PlannedItem>,
    /** 音声トラックのクリップを、時間が重ならない組に分けた段。段ごとに 1 つのシーケンスになる。 */
    val audioLanes: List<List<PlannedItem>>,
    /** 後ろほど上に重ねる。 */
    val texts: List<PlannedText>,
    val durationUs: Long,
    val notices: Set<PlanNotice>,
    /** 後ろほど手前。表示区間の外は透明にする。 */
    val layers: List<PlannedLayer> = emptyList(),
) {
    /** 書き出しで読む素材 (素材ごとに 1 つ)。開始前の存在確認に使う。 */
    val media: List<PlannedMedia>
        get() = (main + audioLanes.flatten() + layers.map { it.media }).filterIsInstance<PlannedMedia>().distinctBy { it.assetId }
}

data class OutputSpec(
    /** 表示される向きの寸法。音声のみは 0。 */
    val width: Int,
    val height: Int,
    /** 静止画のコマの fps。動画はこれより高い fps のときだけコマを間引く。 */
    val frameRate: Int,
    val audioOnly: Boolean,
    val codec: VideoCodecChoice,
    /** 音声のみは 0。 */
    val videoKbps: Int,
    val audioKbps: Int,
    /** 容量指定のときの上限。 */
    val targetBytes: Long?,
)

sealed interface PlannedItem {
    val durationUs: Long
}

/** 何も置かない時間。映像の段では画が無く、音声は無音になる。 */
data class PlannedGap(override val durationUs: Long) : PlannedItem

data class PlannedMedia(
    val assetId: String,
    /** 端末内の絶対パス、または content:// URI。 */
    val source: String,
    val name: String,
    val kind: AssetKind,
    /** 映像・音声の素材の区間。静止画は使わない (0)。 */
    val sourceInUs: Long,
    val sourceOutUs: Long,
    /** タイムライン上の長さ。速度変更の後。 */
    override val durationUs: Long,
    val speed: Double,
    val keepPitch: Boolean,
    val gain: GainCurve,
    val quarterTurns: Int,
    val flipHorizontal: Boolean,
    val fill: FrameFill,
) : PlannedItem

/** 表示区間 [startUs] 以上 [endUs] 未満の文字。位置と大きさはキャンバスに対する割合。 */
data class PlannedText(
    val startUs: Long,
    val endUs: Long,
    val text: String,
    val sizeFraction: Float,
    val color: Int,
    val outlined: Boolean,
    val centerX: Float,
    val centerY: Float,
)

/** 書き出す前に知らせる、編集画面と結果が変わる点。黙って欠かさないために計画と一緒に返す。 */
enum class PlanNotice {
    /** 背景色は書き出しに未対応。はめ込みの余白は黒になる。 */
    BACKGROUND_BLACK,

    /** メイン映像の終わりより後ろのレイヤー・音声・文字は書き出さない。 */
    TRIMMED_AFTER_VIDEO,
}

/** 1 つの独立した映像レイヤー。前後の隙間を含めて出力全体と同じ長さの段へ変換する。 */
data class PlannedLayer(
    val media: PlannedMedia,
    val startUs: Long,
    val endUs: Long,
    val centerX: Float,
    val centerY: Float,
    val widthFraction: Float,
    val aspectRatio: Float,
    val opacity: Float,
) {
    fun items(durationUs: Long): List<PlannedItem> = buildList {
        if (startUs > 0L) add(PlannedGap(startUs))
        add(media)
        if (endUs < durationUs) add(PlannedGap(durationUs - endUs))
    }
}
