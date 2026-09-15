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
    fun `並べ替え先は移動方向の端が隣の中央を越えたところになる`() {
        val durations = listOf(2 * S, 2 * S, 2 * S)
        assertEquals(2, reorderTargetIndex(durations, fromIndex = 0, timeUs = 3_500_000L))
        assertEquals(0, reorderTargetIndex(durations, fromIndex = 0, timeUs = 500_000L))
        assertEquals(1, reorderTargetIndex(durations, fromIndex = 2, timeUs = 1_500_000L))
    }

    @Test
    fun `長いクリップもつかんだだけで並べ替わらず元の隣を越えてから移る`() {
        val durations = listOf(10 * S, S, S)
        assertEquals(0, reorderTargetIndex(durations, 0, 0L))
        assertEquals(0, reorderTargetIndex(durations, 0, 400_000L))
        assertEquals(1, reorderTargetIndex(durations, 0, 600_000L))
        assertEquals(2, reorderTargetIndex(durations, 0, 1_600_000L))
        assertEquals(1, reorderTargetIndex(listOf(S, S, 10 * S), 2, 1_400_000L))
    }

    @Test
    fun `短いクリップにも中央の移動領域を残し外側の取っ手はつかめる`() {
        assertEquals(true, trimHandleAt(99f, 100f, 112f, 28f))
        assertEquals(false, trimHandleAt(115f, 100f, 112f, 28f))
        assertNull(trimHandleAt(106f, 100f, 112f, 28f))
        assertNull(trimHandleAt(106f, 100f, 112f, 4f))
    }

    @Test
    fun `左トリム中は右端と後続の位置が固定され指を離してから前詰めになる`() {
        val layout = TimelineLayout(1000f, 0L, 100f)
        val during = thumbnailSlots(listOf(3 * S, 2 * S), layout, 100f, startTrim = MainStartTrim(0, S))
        assertEquals(S, during.first().timelineUs)
        assertEquals(600f, layout.xOf(during.first().timelineUs), 0.001f)
        assertEquals(4 * S, during.first { it.clipIndex == 1 }.timelineUs)
        val after = thumbnailSlots(listOf(3 * S, 2 * S), layout, 100f)
        assertEquals(0L, after.first().timelineUs)
        assertEquals(3 * S, after.first { it.clipIndex == 1 }.timelineUs)
    }

    @Test
    fun `左へ延ばすトリムでも前のクリップと後続の位置は動かさない`() {
        val layout = TimelineLayout(1000f, 3 * S, 100f)
        val slots = thumbnailSlots(listOf(2 * S, 4 * S, S), layout, 100f, startTrim = MainStartTrim(1, -S))
        assertEquals(0L, slots.first { it.clipIndex == 0 }.timelineUs)
        assertEquals(S, slots.first { it.clipIndex == 1 }.timelineUs)
        assertEquals(5 * S, slots.first { it.clipIndex == 2 }.timelineUs)
    }

    @Test
    fun `移動中は左右の端のうち近い方へ吸着して長さを保つ`() {
        val snap = snapMovingRange(S, 2 * S, listOf(1_080_000L, 3_020_000L), 100_000L)
        assertEquals(1_020_000L, snap.startUs)
        assertEquals(3_020_000L, snap.targetUs)
        val left = snapMovingRange(S, 2 * S, listOf(980_000L), 100_000L)
        assertEquals(980_000L, left.startUs)
        assertEquals(980_000L, left.targetUs)
    }

    @Test
    fun `右端への吸着で開始が負になる候補を使わず吸着したふりもしない`() {
        val snap = snapMovingRange(0L, 2 * S, listOf(1_950_000L), 100_000L)
        assertEquals(0L, snap.startUs)
        assertNull(snap.targetUs)
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
