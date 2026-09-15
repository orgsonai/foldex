// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.timeline

import com.zerotoship.foldex.ui.videoedit.model.FrameRate
import com.zerotoship.foldex.ui.videoedit.model.US_PER_SECOND
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 時刻と画面の横位置の変換。再生位置は常に画面の中央に置き、タイムラインの方を動かす。
 * Android に依存しない純計算にして JVM テストで確かめる。
 */
data class TimelineLayout(val widthPx: Float, val playheadUs: Long, val pxPerSecond: Float) {
    val centerX: Float get() = widthPx / 2f
    val visibleStartUs: Long get() = timeAt(0f)
    val visibleEndUs: Long get() = timeAt(widthPx)

    fun xOf(timeUs: Long): Float = centerX + ((timeUs - playheadUs).toDouble() / US_PER_SECOND * pxPerSecond).toFloat()

    fun timeAt(x: Float): Long = playheadUs + usForPx(x - centerX)

    fun usForPx(px: Float): Long = (px.toDouble() / pxPerSecond * US_PER_SECOND).roundToLong()

    companion object {
        const val MIN_PX_PER_SECOND = 1f

        /** もっとも拡大したときに 1 コマが占める幅。 */
        const val MAX_PX_PER_FRAME = 48f
        const val DEFAULT_PX_PER_SECOND = 80f
        private const val FIT_ALL_SHARE = 0.45f

        fun maxPxPerSecond(rate: FrameRate): Float = MAX_PX_PER_FRAME * rate.numerator / rate.denominator

        fun clampZoom(pxPerSecond: Float, rate: FrameRate): Float = pxPerSecond.coerceIn(MIN_PX_PER_SECOND, maxPxPerSecond(rate))

        /** 再生位置がどこにあっても全体が見える倍率。中央から片側に全長が収まるようにする。 */
        fun fitAllPxPerSecond(durationUs: Long, widthPx: Float, rate: FrameRate): Float {
            if (durationUs <= 0L || widthPx <= 0f) return DEFAULT_PX_PER_SECOND
            val seconds = durationUs.toDouble() / US_PER_SECOND
            return clampZoom((widthPx * FIT_ALL_SHARE / seconds).toFloat(), rate)
        }
    }
}

/** 目盛りの間隔。画面上で [minSpacingPx] 以上離れる最小の間隔を選ぶ。拡大時はコマ単位、縮小時は秒・分単位。 */
fun rulerStepUs(pxPerSecond: Float, rate: FrameRate, minSpacingPx: Float): Long {
    val frame = rate.frameDurationUs
    val candidates = longArrayOf(
        frame, frame * 5, frame * 10,
        500_000L, 1_000_000L, 2_000_000L, 5_000_000L, 10_000_000L, 15_000_000L, 30_000_000L,
        60_000_000L, 120_000_000L, 300_000_000L, 600_000_000L, 1_800_000_000L, 3_600_000_000L,
    )
    return candidates.firstOrNull { it.toDouble() / US_PER_SECOND * pxPerSecond >= minSpacingPx } ?: candidates.last()
}

/** サムネイル 1 枚分。[localUs] はクリップ内の時刻、[timelineUs] はタイムライン上の時刻。 */
data class ThumbSlot(val clipIndex: Int, val localUs: Long, val timelineUs: Long, val widthUs: Long)

/** 左トリム中だけ、短くなった分を表示上の空白として保つ。指を離すと通常の前詰めに戻す。 */
data class MainStartTrim(val clipIndex: Int, val offsetUs: Long) {
    fun offsetFor(index: Int): Long = if (index >= clipIndex) offsetUs else 0L
}

private val THUMB_STEPS_US = longArrayOf(
    50_000L, 100_000L, 200_000L, 500_000L, 1_000_000L, 2_000_000L, 5_000_000L, 10_000_000L,
    30_000_000L, 60_000_000L, 120_000_000L, 300_000_000L, 600_000_000L, 1_800_000_000L,
)

/**
 * サムネイルの間隔。きりの良い値にそろえ、少し拡大縮小しても同じ時刻の画像を使い回せるようにする。
 * 1 枚の幅は [thumbWidthPx] 以上になる。
 */
fun thumbStepUs(pxPerSecond: Float, thumbWidthPx: Float): Long =
    THUMB_STEPS_US.firstOrNull { it.toDouble() / US_PER_SECOND * pxPerSecond >= thumbWidthPx } ?: THUMB_STEPS_US.last()

/** メイントラックのうち、見えている範囲だけのサムネイル。クリップ数ではなく画面の幅に比例した数になる。 */
fun thumbnailSlots(
    durationsUs: List<Long>,
    layout: TimelineLayout,
    thumbWidthPx: Float,
    maxSlots: Int = 300,
    startTrim: MainStartTrim? = null,
): List<ThumbSlot> {
    if (layout.widthPx <= 0f || layout.pxPerSecond <= 0f) return emptyList()
    val step = thumbStepUs(layout.pxPerSecond, thumbWidthPx)
    val visibleStart = layout.visibleStartUs
    val visibleEnd = layout.visibleEndUs
    val result = ArrayList<ThumbSlot>()
    var clipStart = 0L
    durationsUs.forEachIndexed { index, duration ->
        val displayStart = clipStart + (startTrim?.offsetFor(index) ?: 0L)
        val clipEnd = displayStart + duration
        if (duration > 0L && clipEnd > visibleStart && displayStart < visibleEnd) {
            val first = (visibleStart - displayStart).coerceAtLeast(0L) / step
            val last = (minOf(visibleEnd, clipEnd) - displayStart - 1).coerceAtLeast(0L) / step
            var k = first
            while (k <= last && result.size < maxSlots) {
                val local = k * step
                result.add(ThumbSlot(index, local, displayStart + local, minOf(step, duration - local)))
                k++
            }
        }
        clipStart += duration
    }
    return result
}

/**
 * 並べ替えで差し込む先。[timeUs] はドラッグ中のクリップの左端。
 * 元の並びを基準に、移動方向の端が隣のクリップの中央を越えたら差し込む。
 * 自分の長さを先に詰めると、長いクリップをつかんだだけで末尾へ飛ぶため、判定中は詰めない。
 * `VideoEditOps.moveMain(from, to)` の to にそのまま渡せる。
 */
fun reorderTargetIndex(durationsUs: List<Long>, fromIndex: Int, timeUs: Long): Int {
    if (fromIndex !in durationsUs.indices) return fromIndex
    val originalStart = durationsUs.take(fromIndex).sum()
    var target = fromIndex
    var start = 0L
    durationsUs.forEachIndexed { index, duration ->
        val middle = start + duration / 2
        if (timeUs < originalStart && index < fromIndex && timeUs < middle) target = minOf(target, index)
        if (timeUs > originalStart && index > fromIndex && timeUs + durationsUs[fromIndex] > middle) target = index
        start += duration
    }
    return target
}

/** 取っ手の内側への当たり判定を制限し、短いクリップでも中央をつかんで移動できるようにする。 */
fun trimHandleAt(x: Float, startX: Float, endX: Float, touchPx: Float): Boolean? {
    val inset = minOf(touchPx, (endX - startX).coerceAtLeast(0f) / 3f)
    return when {
        x >= startX - touchPx && x < startX + inset -> true
        x > endX - inset && x <= endX + touchPx -> false
        else -> null
    }
}

data class RangeSnap(val startUs: Long, val targetUs: Long?)

/** 両端のうち近い方を吸着させ、長さと非負の開始位置を保つ。 */
fun snapMovingRange(startUs: Long, durationUs: Long, targetsUs: Iterable<Long>, thresholdUs: Long): RangeSnap {
    val start = startUs.coerceAtLeast(0L)
    val left = snapTarget(start, targetsUs.filter { it >= 0L }, thresholdUs)
    val right = snapTarget(start + durationUs, targetsUs.filter { it >= durationUs }, thresholdUs)
    return when {
        left != null && (right == null || abs(left - start) <= abs(right - (start + durationUs))) -> RangeSnap(left, left)
        right != null -> RangeSnap(right - durationUs, right)
        else -> RangeSnap(start, null)
    }
}

/** [valueUs] にもっとも近い目標が [thresholdUs] 以内にあれば、その目標を返す。 */
fun snapTarget(valueUs: Long, targetsUs: Iterable<Long>, thresholdUs: Long): Long? {
    var best: Long? = null
    var bestDistance = Long.MAX_VALUE
    for (target in targetsUs) {
        val distance = abs(target - valueUs)
        if (distance <= thresholdUs && distance < bestDistance) {
            best = target
            bestDistance = distance
        }
    }
    return best
}
