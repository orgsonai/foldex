// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.model

import java.util.Locale

/** 1 秒 = 1,000,000 µs。時刻は浮動小数の秒ではなく整数マイクロ秒で持ち、長時間でも誤差を積もらせない。 */
const val US_PER_SECOND: Long = 1_000_000L

/**
 * 有理数のフレームレート。29.97fps は 30000/1001 として持つ。
 *
 * フレーム i は `timeOfFrame(i)` 以上 `timeOfFrame(i + 1)` 未満の区間。
 * 時刻 → フレームは切り捨て、フレーム → 時刻は切り上げにして、往復で同じフレームに戻す。
 */
data class FrameRate(val numerator: Int, val denominator: Int = 1) {
    init {
        require(numerator > 0 && denominator > 0) { "frame rate must be positive" }
    }

    /** 時刻表示のコマ桁に使う呼び名上の fps (29.97 → 30)。 */
    val nominalFps: Int get() = (numerator + denominator - 1) / denominator

    /** 1 コマの長さ (切り上げ)。最小のクリップ長に使う。 */
    val frameDurationUs: Long get() = ceilDiv(US_PER_SECOND * denominator, numerator.toLong())

    /** [timeUs] を含むフレームの番号。 */
    fun frameAt(timeUs: Long): Long {
        if (timeUs <= 0L) return 0L
        return timeUs * numerator / (US_PER_SECOND * denominator)
    }

    /** フレーム [frame] の先頭の時刻。 */
    fun timeOfFrame(frame: Long): Long {
        if (frame <= 0L) return 0L
        return ceilDiv(frame * US_PER_SECOND * denominator, numerator.toLong())
    }

    /** もっとも近いフレームの先頭へ吸着させる。 */
    fun snap(timeUs: Long): Long {
        if (timeUs <= 0L) return 0L
        val frame = frameAt(timeUs)
        val start = timeOfFrame(frame)
        val next = timeOfFrame(frame + 1)
        return if (timeUs - start <= next - timeUs) start else next
    }

    companion object {
        val FPS_30 = FrameRate(30)

        private fun ceilDiv(value: Long, divisor: Long): Long = (value + divisor - 1) / divisor
    }
}

/**
 * 時刻の表示。1 時間未満は「分:秒:コマ」、以上は「時:分:秒:コマ」。
 * コマ桁は秒の先頭からのコマ数で、呼び名上の fps 未満に収める。
 */
fun formatTimecode(timeUs: Long, rate: FrameRate): String {
    val time = timeUs.coerceAtLeast(0L)
    val totalSeconds = time / US_PER_SECOND
    val frames = (rate.frameAt(time) - rate.frameAt(totalSeconds * US_PER_SECOND))
        .coerceIn(0L, (rate.nominalFps - 1).toLong())
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d:%02d", hours, minutes, seconds, frames)
    } else {
        String.format(Locale.US, "%02d:%02d:%02d", minutes, seconds, frames)
    }
}

/** 「12.3」のような秒の表示 (小数 1 桁)。単位は呼び出し側の文言に含める。 */
fun formatSeconds(timeUs: Long): String = String.format(Locale.US, "%.1f", timeUs.toDouble() / US_PER_SECOND)

/** 「1:05」のような分:秒の短い表示。 */
fun formatClock(timeUs: Long): String {
    val totalSeconds = timeUs.coerceAtLeast(0L) / US_PER_SECOND
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
