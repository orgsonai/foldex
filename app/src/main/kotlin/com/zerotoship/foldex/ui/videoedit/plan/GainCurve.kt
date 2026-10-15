// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.plan

import kotlin.math.min

/**
 * クリップ 1 つの音量。時刻はクリップの先頭からの、速度変更の後 (タイムライン上) の時刻。
 *
 * フェードは直線。フェードインとフェードアウトが重なる短いクリップでは、小さい方の値を使う。
 * 例: 長さ 10 秒・音量 50%・フェードイン 2 秒 → 1 秒で 25%、5 秒で 50%。
 */
data class GainCurve(
    val volume: Float,
    val fadeInUs: Long,
    val fadeOutUs: Long,
    val durationUs: Long,
) {
    /** 音を加工しなくてよいか。加工の処理を付けずに済ませる判定に使う。 */
    val isUnity: Boolean get() = volume == 1f && fadeInUs <= 0L && fadeOutUs <= 0L

    fun gainAt(timeUs: Long): Float {
        if (volume <= 0f) return 0f
        var factor = 1.0
        if (fadeInUs > 0L && timeUs < fadeInUs) {
            factor = min(factor, timeUs.coerceAtLeast(0L).toDouble() / fadeInUs)
        }
        if (fadeOutUs > 0L && timeUs > durationUs - fadeOutUs) {
            factor = min(factor, (durationUs - timeUs).coerceAtLeast(0L).toDouble() / fadeOutUs)
        }
        return (volume * factor).toFloat()
    }

    companion object {
        /** クリップの値から作る。ミュートは音量 0、フェードはクリップの長さに収める。 */
        fun of(volume: Float, muted: Boolean, fadeInUs: Long, fadeOutUs: Long, durationUs: Long): GainCurve {
            val length = durationUs.coerceAtLeast(0L)
            return GainCurve(
                volume = if (muted) 0f else volume.coerceAtLeast(0f),
                fadeInUs = fadeInUs.coerceIn(0L, length),
                fadeOutUs = fadeOutUs.coerceIn(0L, length),
                durationUs = length,
            )
        }
    }
}
