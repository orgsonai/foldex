// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class MosaicColorsTest {
    @Test
    fun `uses every pixel in the block instead of a single sampled point`() {
        val pixels = IntArray(100) { 0xFF000000.toInt() }
        pixels[50] = 0xFFFFFFFF.toInt()
        assertEquals(0xFF020202.toInt(), MosaicColors.average(pixels))
    }

    @Test
    fun `hidden transparent colors cannot tint the block`() {
        assertEquals(0x7FFF0000, MosaicColors.average(intArrayOf(0xFFFF0000.toInt(), 0x000000FF)))
    }

    @Test
    fun `transparent block clears hidden RGB`() {
        assertEquals(0, MosaicColors.average(intArrayOf(0x00FFFFFF, 0x00123456)))
    }

    @Test
    fun `partial edge block excludes unused buffer contents`() {
        assertEquals(0xFF000000.toInt(), MosaicColors.average(intArrayOf(0xFF000000.toInt(), -1), 1))
    }

    @Test
    fun `large bright block does not overflow sums`() {
        assertEquals(-1, MosaicColors.average(IntArray(1_000_000) { -1 }))
    }
}
