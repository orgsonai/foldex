// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import com.zerotoship.foldex.ui.imageedit.model.EditSize
import com.zerotoship.foldex.ui.imageedit.model.MetadataPolicy
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputContractTest {
    @Test fun `one transparent or translucent pixel between old sample points is found`() {
        for (alpha in listOf(0, 1, 127, 254)) {
            assertTrue(TransparencyScan.hasAlpha(257, 129) { y, row ->
                row.fill(-1)
                if (y == 127) row[255] = (alpha shl 24) or 0xffffff
            })
        }
    }

    @Test fun `opaque image is checked through its last row`() {
        var rows = 0
        assertFalse(TransparencyScan.hasAlpha(19, 23) { _, row -> row.fill(-1); rows++ })
        assertEquals(23, rows)
    }

    @Test fun `pixel scan propagates cancellation`() {
        assertThrows(CancellationException::class.java) {
            TransparencyScan.hasAlpha(10, 10) { _, _ -> throw CancellationException() }
        }
    }

    private val attributes = mapOf(
        "DateTimeOriginal" to "2026:09:13 10:00:00", "Make" to "Camera", "Artist" to "Artist",
        "GPSLatitude" to "35/1,0/1,0/1", "GPSImgDirection" to "180/1", "GPSAreaInformation" to "place",
        "Orientation" to "6", "PixelXDimension" to "4000", "Xmp" to "private xmp", "MakerNote" to "private maker note",
    )

    @Test fun `default metadata keeps capture and GPS but never copies stale orientation or opaque blobs`() {
        val selected = ExifTransfer.selectAttributes(MetadataPolicy.KEEP_CAPTURE, attributes::get)
        assertEquals(attributes.filterKeys { it in setOf("DateTimeOriginal", "Make", "Artist", "GPSLatitude", "GPSImgDirection", "GPSAreaInformation") }, selected)
    }

    @Test fun `location removal excludes direction and place information as well as coordinates`() {
        val selected = ExifTransfer.selectAttributes(MetadataPolicy.REMOVE_LOCATION, attributes::get)
        assertEquals(setOf("DateTimeOriginal", "Make", "Artist"), selected.keys)
        assertTrue(ExifTransfer.LOCATION_TAGS.none { selected.containsKey(it) })
    }

    @Test fun `sharing does not even read source capture information`() {
        assertTrue(ExifTransfer.selectAttributes(MetadataPolicy.SHARE) { error("must not read") }.isEmpty())
    }

    @Test fun `size advice retains ratio and never recommends the failed size`() {
        val size = EditSize(4000, 3000)
        val edge = requireNotNull(OutputSizeAdvice.suggest(size, 1_000_000, 250_000))
        assertTrue(edge in 16 until 2000)
        val fitted = size.fitLongEdge(edge)
        assertTrue(kotlin.math.abs(fitted.width.toDouble() / fitted.height - 4.0 / 3) < 0.01)
        assertEquals(16, OutputSizeAdvice.suggest(size, Long.MAX_VALUE, 1))
    }

    @Test fun `impossible tiny output offers no further shrink and invalid goals are not accepted`() {
        assertNull(OutputSizeAdvice.suggest(EditSize(16, 9), 2000, 1024))
        assertNull(OutputSizeAdvice.suggest(EditSize(200, 100), 2000, 0))
        assertNull(OutputSizeAdvice.suggest(EditSize(200, 100), 1000, 1024))
    }

    @Test fun `quality selection includes metadata overhead in every measured candidate`() {
        val found = ImageEncoder.findQualityForTarget(7000) { quality -> 6000 + quality * 20 }
        assertTrue(found.withinTarget)
        assertTrue(found.quality <= 50)
        assertTrue(found.bytes <= 7000)
        val impossible = ImageEncoder.findQualityForTarget(5000) { quality -> 6000 + quality * 20 }
        assertFalse(impossible.withinTarget)
    }
}
