// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.timeline

import com.zerotoship.foldex.ui.videoedit.model.FrameRate
import com.zerotoship.foldex.ui.videoedit.model.US_PER_SECOND
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val S = US_PER_SECOND

class TimelineLayoutTest {

    @Test
    fun `再生位置は画面の中央に来る`() {
        val layout = TimelineLayout(widthPx = 1000f, playheadUs = 5 * S, pxPerSecond = 100f)
        assertEquals(500f, layout.xOf(5 * S), 0.001f)
        assertEquals(600f, layout.xOf(6 * S), 0.001f)
        assertEquals(4 * S, layout.timeAt(400f))
        assertEquals(0L, layout.visibleStartUs)
        assertEquals(10 * S, layout.visibleEndUs)
    }

    @Test
    fun `全体表示は中央から片側に全長が収まり、拡大の上限は 1 コマ 48px`() {
        assertEquals(45f, TimelineLayout.fitAllPxPerSecond(10 * S, 1000f, FrameRate.FPS_30), 0.001f)
        assertEquals(1440f, TimelineLayout.clampZoom(99_999f, FrameRate.FPS_30), 0.001f)
    }

    @Test
    fun `並べ替え先は動かすクリップを除いた並びで数える`() {
        val durations = listOf(2 * S, 2 * S, 2 * S)
        assertEquals(2, reorderTargetIndex(durations, fromIndex = 0, timeUs = 3_500_000L))
        assertEquals(0, reorderTargetIndex(durations, fromIndex = 0, timeUs = 500_000L))
        assertEquals(1, reorderTargetIndex(durations, fromIndex = 2, timeUs = 1_500_000L))
    }

    @Test
    fun `サムネイルは見えている範囲だけ作る`() {
        // 幅 1000px・毎秒 100px・再生位置 0 → 見える範囲は -5〜5 秒。サムネ幅 100px なので 1 秒間隔。
        val layout = TimelineLayout(1000f, 0L, 100f)
        val slots = thumbnailSlots(listOf(3 * S, 10 * S), layout, thumbWidthPx = 100f)
        assertEquals(listOf(0, 0, 0, 1, 1), slots.map { it.clipIndex })
        assertEquals(4 * S, slots[4].timelineUs)
        assertEquals(1 * S, slots[4].localUs)
        assertEquals(1 * S, slots[2].widthUs)
    }

    @Test
    fun `吸着は閾値以内でもっとも近い目標を選ぶ`() {
        val targets = listOf(900_000L, 1_050_000L, 2_000_000L)
        assertEquals(1_050_000L, snapTarget(1_000_000L, targets, thresholdUs = 100_000L))
        assertNull(snapTarget(1_500_000L, targets, thresholdUs = 100_000L))
    }

    @Test
    fun `目盛りは最小間隔以上になる`() {
        assertEquals(1 * S, rulerStepUs(pxPerSecond = 100f, rate = FrameRate.FPS_30, minSpacingPx = 60f))
        assertEquals(FrameRate.FPS_30.frameDurationUs, rulerStepUs(pxPerSecond = 1440f, rate = FrameRate.FPS_30, minSpacingPx = 40f))
    }
}
