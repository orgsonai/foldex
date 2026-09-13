// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MosaicLayerTest {
    private val mosaic = Layer.Mosaic(
        id = "m", name = "Mosaic",
        area = SelectionSpec.Rect(EditRect(100f, 50f, 300f, 200f)), blockSizePx = 25f,
    )
    private val textSize: (Layer.Text) -> EditSize = { EditSize(1, 1) }

    @Test
    fun `mosaic follows rotation crop and flip`() {
        val rotated = CanvasTransforms.rotateRight(mosaic, EditSize(800, 600), textSize) as Layer.Mosaic
        assertEquals(EditRect(400f, 100f, 550f, 300f), rotated.area.bounds)
        val cropped = CanvasTransforms.translate(rotated, -300f, -50f) as Layer.Mosaic
        assertEquals(EditRect(100f, 50f, 250f, 250f), cropped.area.bounds)
        val flipped = CanvasTransforms.flipHorizontal(cropped, EditSize(300, 400), textSize) as Layer.Mosaic
        assertEquals(EditRect(50f, 50f, 200f, 250f), flipped.area.bounds)
        assertEquals(25f, flipped.blockSizePx, 0f)
    }

    @Test
    fun `excluded area stays excluded when mosaic moves`() {
        val area = mosaic.area.applying(SelectionMode.SUBTRACT, SelectionSpec.Rect(EditRect(140f, 80f, 170f, 110f)))!!
        val moved = CanvasTransforms.translate(mosaic.copy(area = area), -100f, -50f) as Layer.Mosaic
        assertTrue(moved.area.contains(10f, 10f))
        assertFalse(moved.area.contains(50f, 40f))
    }

    @Test
    fun `undo redo restores exact mosaic selection and strength`() {
        val doc = EditDocument.ofSingleImage("i", "photo", "photo", EditSize(800, 600), ImageFormat.PNG)
        val history = EditHistory()
        history.record(doc)
        val edited = doc.copy(layers = doc.layers + mosaic)
        val undone = history.undo(edited)!!
        assertEquals(doc, undone)
        assertEquals(edited, history.redo(undone))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid block size is rejected`() {
        mosaic.copy(blockSizePx = Float.NaN)
    }
}
