// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoSkipTest {

    @Test
    fun `秒数指定は長さに関係なくその秒数`() {
        assertEquals(10_000L, VideoSkip(10, percent = false).incrementMs(3_600_000L))
        assertEquals(10_000L, VideoSkip(10, percent = false).incrementMs(0L))
    }

    @Test
    fun `割合指定は長さの割合`() {
        // 60 分の 5% は 3 分
        assertEquals(180_000L, VideoSkip(5, percent = true).incrementMs(3_600_000L))
        // 2 分の 10% は 12 秒
        assertEquals(12_000L, VideoSkip(10, percent = true).incrementMs(120_000L))
    }

    @Test
    fun `割合指定でも短い動画は 1 秒は飛ばす`() {
        // 30 秒の 1% は 0.3 秒 → 1 秒
        assertEquals(1_000L, VideoSkip(1, percent = true).incrementMs(30_000L))
    }

    @Test
    fun `割合指定で長さが分からないときは 10 秒`() {
        assertEquals(10_000L, VideoSkip(5, percent = true).incrementMs(0L))
        assertEquals(10_000L, VideoSkip(5, percent = true).incrementMs(-1L))
    }
}
