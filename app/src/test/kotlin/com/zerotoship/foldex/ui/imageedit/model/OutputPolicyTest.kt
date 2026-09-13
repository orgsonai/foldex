// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.model

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputPolicyTest {
    private fun photo(alpha: Transparency = Transparency.OPAQUE) = EditDocument.ofSingleImage(
        "image", "photo.jpg", "photo", EditSize(4000, 3000), ImageFormat.JPEG, sourceTransparency = alpha,
    )

    @Test fun `opaque photo covers the transparent canvas without a transparency warning`() {
        assertEquals(Transparency.OPAQUE, photo().transparency())
    }

    @Test fun `filename alone does not prove a photo is opaque`() {
        assertEquals(Transparency.UNKNOWN, photo(Transparency.UNKNOWN).transparency())
    }

    @Test fun `a clear operation and partially covering photo require pixel inspection`() {
        val doc = photo()
        val hole = Layer.Fill("hole", "hole", area = SelectionSpec.Rect(EditRect(1f, 1f, 2f, 2f)), color = 0, mode = FillMode.CLEAR)
        assertEquals(Transparency.UNKNOWN, doc.copy(layers = doc.layers + hole).transparency())
        assertEquals(Transparency.UNKNOWN, doc.updateActiveImage { it.copy(transform = LayerTransform(offsetX = 1f)) }.transparency())
        assertEquals(Transparency.UNKNOWN, doc.updateActiveImage { it.copy(alpha = 0.9f) }.transparency())
    }

    @Test fun `rotation and crop preserve a known fully covering photo`() {
        val doc = photo()
        val image = (doc.activeLayer as Layer.Image).rotatedRight()
        assertEquals(Transparency.OPAQUE, doc.copy(layers = listOf(image)).withCanvasFitting(image.logicalSize).transparency())
        val crop = image.withCrop(EditRect(100f, 100f, 400f, 300f))
        assertEquals(Transparency.OPAQUE, doc.copy(layers = listOf(crop)).withCanvasFitting(crop.logicalSize).transparency())
    }

    @Test fun `drawing erasers do not punch through the photo underneath`() {
        val doc = photo()
        val drawing = Layer.Drawing("drawing", "drawing", strokes = listOf(
            Stroke(listOf(EditPoint(1f, 1f)), 10f, 0, StrokeMode.ERASE),
        ))
        assertEquals(Transparency.OPAQUE, doc.copy(layers = doc.layers + drawing).transparency())
    }

    @Test fun `metadata background and target remain selected after resizing and format changes`() {
        val doc = photo().let { it.copy(output = it.output.copy(
            metadata = MetadataPolicy.REMOVE_LOCATION, jpegBackground = 0xff246810.toInt(), targetBytes = 102400,
        )) }
        val next = doc.withOutputLongEdge(600).let { it.copy(output = it.output.copy(format = ImageFormat.PNG)) }
        assertEquals(doc.output.copy(scale = 0.15f, format = ImageFormat.PNG), next.output)
        assertEquals(MetadataPolicy.KEEP_CAPTURE, photo().output.metadata)
        assertEquals(-1, photo().output.jpegBackground)
    }

    @Test fun `pixel undo and redo never reenable location or remove the selected size limit`() {
        val history = EditHistory()
        val first = photo()
        history.record(first)
        val resized = first.withOutputLongEdge(600)
        val current = resized.copy(output = resized.output.copy(
            metadata = MetadataPolicy.REMOVE_LOCATION, targetBytes = 1024, jpegBackground = 0xff000000.toInt(),
        ))
        val undone = requireNotNull(history.undo(current))
        assertEquals(first.outputSize, undone.outputSize)
        assertEquals(current.output.copy(scale = first.output.scale), undone.output)
        val redone = requireNotNull(history.redo(undone))
        assertEquals(current, redone)
    }
}
