// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameRateTest {

    @Test
    fun `30fps の時刻とコマ番号が往復で一致する`() {
        val rate = FrameRate(30)
        for (frame in 0L..300L) {
            assertEquals(frame, rate.frameAt(rate.timeOfFrame(frame)))
        }
        assertEquals(33_334L, rate.frameDurationUs)
    }

    @Test
    fun `29_97fps は長時間でもコマ番号がずれない`() {
        val rate = FrameRate(30000, 1001)
        listOf(0L, 1L, 29L, 30L, 1_799L, 107_892L, 2_589_411L).forEach { frame ->
            assertEquals(frame, rate.frameAt(rate.timeOfFrame(frame)))
        }
        // 1 時間は 107,892.1 コマ目にあたる。
        assertEquals(107_892L, rate.frameAt(3_600L * US_PER_SECOND))
        assertEquals(30, rate.nominalFps)
    }

    @Test
    fun `もっとも近いコマの先頭へ吸着する`() {
        val rate = FrameRate(30)
        assertEquals(0L, rate.snap(16_000L))
        assertEquals(33_334L, rate.snap(17_000L))
        assertEquals(0L, rate.snap(-5L))
    }

    @Test
    fun `時刻表示は分秒コマ、1 時間以上は時を付ける`() {
        val rate = FrameRate(30)
        assertEquals("01:05:15", formatTimecode(rate.timeOfFrame(65L * 30 + 15), rate))
        assertEquals("1:02:03:04", formatTimecode(rate.timeOfFrame(3_723L * 30 + 4), rate))
        assertEquals("00:00:00", formatTimecode(-1L, rate))
    }

    @Test
    fun `短い表示と秒の表示`() {
        assertEquals("1:05", formatClock(65L * US_PER_SECOND))
        assertEquals("1:00:00", formatClock(3_600L * US_PER_SECOND))
        assertEquals("12.3", formatSeconds(12_345_678L))
    }
}
