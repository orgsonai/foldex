// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import com.zerotoship.foldex.ui.imageedit.model.CanvasTransforms
import com.zerotoship.foldex.ui.imageedit.model.EditSize
import com.zerotoship.foldex.ui.imageedit.model.SelectionMode
import com.zerotoship.foldex.ui.imageedit.model.SelectionSpec
import com.zerotoship.foldex.ui.imageedit.model.applying
import com.zerotoship.foldex.ui.imageedit.model.invertedIn
import com.zerotoship.foldex.ui.imageedit.model.mapPoints
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class MaskContoursTest {
    @Test
    fun `every three by three mask keeps holes islands and diagonal corners`() {
        for (pattern in 0 until 512) {
            val mask = ByteArray(9) { if (pattern and (1 shl it) != 0) 1 else 0 }
            val selection = MaskContours.trace(mask, 3, 3, EditSize(300, 150))
            for (y in 0..2) for (x in 0..2) {
                assertEquals("pattern=$pattern at $x,$y", mask[y * 3 + x].toInt() != 0,
                    selection?.contains(x * 100f + 50, y * 50f + 25) == true)
            }
        }
    }

    @Test
    fun `full mask has just four corners and respects canvas boundaries`() {
        val result = MaskContours.trace(ByteArray(512 * 512) { 1 }, 512, 512, EditSize(4000, 3000))!!
        assertEquals(4, result.rings.single().size)
        assertTrue(result.contains(3999f, 2999f))
        assertFalse(result.contains(4001f, 2000f))
        assertFalse(result.contains(-1f, 2000f))
    }

    @Test
    fun `hole follows rotation and inverse selection`() {
        val mask = byteArrayOf(1, 1, 1, 1, 0, 1, 1, 1, 1)
        val canvas = EditSize(300, 150)
        val selection = MaskContours.trace(mask, 3, 3, canvas)!!
        assertEquals(2, selection.rings.size)
        val rotated = selection.mapPoints(CanvasTransforms.rotateRightMap(canvas))
        assertFalse(rotated.contains(75f, 150f))
        assertTrue(rotated.contains(125f, 150f))
        val inverted = rotated.invertedIn(EditSize(150, 300))
        assertTrue(inverted.contains(75f, 150f))
        assertFalse(inverted.contains(125f, 150f))
    }

    @Test
    fun `manual add can fill a hole and subtract an island`() {
        val area = MaskContours.trace(byteArrayOf(1, 0, 1), 3, 1, EditSize(300, 100))!!
        val middle = SelectionSpec.Rect(com.zerotoship.foldex.ui.imageedit.model.EditRect(100f, 0f, 200f, 100f))
        val added = area.applying(SelectionMode.ADD, middle)!!
        assertTrue(added.contains(150f, 50f))
        val removed = added.applying(SelectionMode.SUBTRACT, area)!!
        assertTrue(removed.contains(150f, 50f))
        assertFalse(removed.contains(50f, 50f))
    }

    @Test
    fun `empty result does not invent a selection`() {
        assertNull(MaskContours.trace(ByteArray(9), 3, 3, EditSize(3, 3)))
    }

    @Test(expected = CancellationException::class)
    fun `cancelled tracing stops without publishing a partial contour`() {
        MaskContours.trace(ByteArray(9) { 1 }, 3, 3, EditSize(3, 3)) { throw CancellationException() }
    }

    @Test
    fun `pathological noisy mask is rejected rather than exhausting path memory`() {
        val mask = ByteArray(512 * 512) { if ((it / 512 + it % 512) % 2 == 0) 1 else 0 }
        assertNull(MaskContours.trace(mask, 512, 512, EditSize(512, 512)))
    }
}
