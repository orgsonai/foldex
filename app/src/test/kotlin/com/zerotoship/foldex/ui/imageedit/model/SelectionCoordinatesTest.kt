// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.model

import org.junit.Assert.*
import org.junit.Test

class SelectionCoordinatesTest {
    @Test
    fun `letterboxed photo maps tap without including the margins`() {
        val rect = EditRect(20f, 150f, 420f, 450f)
        assertEquals(EditPoint(2000f, 1500f), SelectionCoordinates.fromViewport(EditPoint(220f, 300f), rect, EditSize(4000, 3000)))
        assertNull(SelectionCoordinates.fromViewport(EditPoint(220f, 100f), rect, EditSize(4000, 3000)))
    }

    @Test
    fun `zoom and pan may place the image origin outside the viewport`() {
        val point = SelectionCoordinates.fromViewport(EditPoint(100f, 200f), EditRect(-300f, -100f, 500f, 500f), EditSize(4000, 3000))
        assertEquals(EditPoint(2000f, 1500f), point)
    }

    @Test
    fun `portrait canvas uses separate horizontal and vertical ratios`() {
        val point = SelectionCoordinates.fromViewport(EditPoint(150f, 500f), EditRect(0f, 0f, 300f, 600f), EditSize(3000, 6000))!!
        assertEquals(1500f, point.x, 0.01f)
        assertEquals(5000f, point.y, 0.01f)
    }

    @Test
    fun `empty preview and outside right edge do not become edge selections`() {
        assertNull(SelectionCoordinates.fromViewport(EditPoint(0f, 0f), EditRect(0f, 0f, 0f, 0f), EditSize(1, 1)))
        assertNull(SelectionCoordinates.fromViewport(EditPoint(100f, 50f), EditRect(0f, 0f, 100f, 100f), EditSize(1, 1)))
    }
}
