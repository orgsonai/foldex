// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeTest {

    private fun points(vararg xy: Pair<Float, Float>) = xy.map { EditPoint(it.first, it.second) }

    @Test
    fun `2点以下はそのまま返す`() {
        val p = points(0f to 0f, 1f to 1f)
        assertEquals(p, Stroke.simplify(p, minDistance = 100f))
    }

    @Test
    fun `近すぎる点は捨てる`() {
        val p = points(0f to 0f, 1f to 0f, 2f to 0f, 50f to 0f)
        val simplified = Stroke.simplify(p, minDistance = 10f)
        // 始点と終点は必ず残り、途中の細かい点は落ちる。
        assertEquals(EditPoint(0f, 0f), simplified.first())
        assertEquals(EditPoint(50f, 0f), simplified.last())
        assertTrue("間引かれていること", simplified.size < p.size)
    }

    @Test
    fun `離れた点は残す`() {
        val p = points(0f to 0f, 100f to 0f, 200f to 0f, 300f to 0f)
        assertEquals(4, Stroke.simplify(p, minDistance = 10f).size)
    }

    @Test
    fun `始点と終点は必ず残る`() {
        val p = points(5f to 5f, 5f to 5f, 5f to 5f, 6f to 6f)
        val simplified = Stroke.simplify(p, minDistance = 1000f)
        assertEquals(EditPoint(5f, 5f), simplified.first())
        assertEquals(EditPoint(6f, 6f), simplified.last())
        assertEquals(2, simplified.size)
    }
}
