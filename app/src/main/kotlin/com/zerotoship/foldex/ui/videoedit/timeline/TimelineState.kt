// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.timeline

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zerotoship.foldex.ui.videoedit.model.FrameRate

/**
 * 再生位置と表示倍率。指の動きに合わせて毎フレーム変わるので ViewModel には置かず、
 * 描画とプレビューが直接読む。ViewModel の操作には呼び出す時点の値を渡す。
 */
@Stable
class TimelineState {
    var playheadUs by mutableLongStateOf(0L)
    var pxPerSecond by mutableFloatStateOf(TimelineLayout.DEFAULT_PX_PER_SECOND)

    /** 指でスクロール・トリム中、または慣性で動いている間 true。プレビューを軽い表示に切り替える。 */
    var scrubbing by mutableStateOf(false)

    /** 全体表示の要求。値が変わるたびに、表示幅に合わせて倍率を決め直す。 */
    var fitRequest by mutableIntStateOf(0)
        private set

    fun requestFitAll() {
        fitRequest++
    }

    /** [frames] コマ進める (負なら戻す)。 */
    fun stepFrames(frames: Int, rate: FrameRate, durationUs: Long) {
        val next = rate.timeOfFrame(rate.frameAt(playheadUs) + frames)
        playheadUs = next.coerceIn(0L, durationUs.coerceAtLeast(0L))
    }
}
