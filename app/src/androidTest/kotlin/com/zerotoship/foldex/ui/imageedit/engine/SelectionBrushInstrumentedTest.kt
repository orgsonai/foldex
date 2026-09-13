// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zerotoship.foldex.ui.imageedit.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SelectionBrushInstrumentedTest {
    @Test fun brushPathMatchesLogicalSelectionAtPreviewAndOutputScales() {
        val selection = requireNotNull(SelectionSpec.BrushStroke(
            listOf(EditPoint(-3f, 8f), EditPoint(10f, 8f), EditPoint(10f, 20f), EditPoint(25f, 20f)),
            4f, EditRect(0f, 0f, 32f, 32f),
        ).applying(SelectionMode.SUBTRACT, SelectionSpec.BrushStroke(listOf(EditPoint(10f, 10f)), 2f))
            .applying(SelectionMode.ADD, SelectionSpec.BrushStroke(listOf(EditPoint(28f, 5f)), 2f)))
        for (scale in listOf(1, 2)) {
            val bitmap = Bitmap.createBitmap(32 * scale, 32 * scale, Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).drawPath(SelectionPaths.toPath(selection, scale.toFloat()), Paint().apply { color = Color.WHITE })
                var checked = 0
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    val logicalX = (x + .5f) / scale
                    val logicalY = (y + .5f) / scale
                    val expected = selection.contains(logicalX, logicalY)
                    // 境界画素の丸めではなく、内部・穴・範囲外の一致を確かめる。
                    val offsets = listOf(-.75f / scale, 0f, .75f / scale)
                    if (offsets.any { dx -> offsets.any { dy -> selection.contains(logicalX + dx, logicalY + dy) != expected } }) continue
                    assertEquals("scale=$scale at $x,$y", expected, Color.alpha(bitmap.getPixel(x, y)) == 255)
                    checked++
                }
                assertTrue(checked > bitmap.width * bitmap.height / 2)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun reeditingMosaicKeepsUpperLayersAndOtherMosaicsIntact() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "mosaic-edit-test-${UUID.randomUUID()}").apply { mkdirs() }
        val bitmaps = mutableListOf<Bitmap>()
        var source: ImageSource? = null
        try {
            val file = File(directory, "source.png")
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { bitmap ->
                try {
                    bitmap.setPixels(IntArray(64 * 64) { i -> Color.rgb(i * 37 % 256, i * 19 % 256, i * 7 % 256) }, 0, 64, 0, 0, 64, 64)
                    file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
            }
            source = ImageSource(file)
            val base = EditDocument.ofSingleImage("image", "image", "source", EditSize(64, 64), ImageFormat.PNG)
            val mosaic = Layer.Mosaic("first", "first", area = SelectionSpec.Rect(EditRect(0f, 0f, 40f, 32f)), blockSizePx = 4f)
            val stamp = Layer.Fill("stamp", "stamp", area = SelectionSpec.Rect(EditRect(12f, 12f, 24f, 24f)), color = Color.GREEN, mode = FillMode.COLOR)
            val other = mosaic.copy(id = "other", area = SelectionSpec.Rect(EditRect(48f, 40f, 64f, 64f)))
            val document = base.copy(layers = base.layers + listOf(mosaic, stamp, other))
            val session = requireNotNull(MosaicEditSession.edit(document, mosaic.id))
            val newArea = SelectionSpec.BrushStroke(listOf(EditPoint(3f, 16f), EditPoint(40f, 16f)), 10f, EditRect(0f, 0f, 64f, 64f))
            session.setArea(newArea)
            session.setBlockSize(12f)
            fun render(doc: EditDocument) = requireNotNull(DocumentRenderer.render(doc, { source }).getOrNull()).also { bitmaps += it }
            val actual = render(session.preview())
            val expected = render(document.copy(layers = base.layers + listOf(mosaic.copy(area = newArea, blockSizePx = 12f), stamp, other)))
            assertTrue(expected.sameAs(actual))
            assertEquals(Color.GREEN, actual.getPixel(13, 13))
            // 後ろに重ねて追加する誤実装なら、上のスタンプまで再度モザイクになる。
            assertFalse(actual.sameAs(render(document.copy(layers = document.layers + session.draft))))
            val hidden = render(session.preview(showEffect = false))
            assertTrue(hidden.sameAs(render(document.copy(layers = base.layers + listOf(stamp, other)))))
            assertEquals(render(document).getPixel(54, 46), actual.getPixel(54, 46))
        } finally {
            bitmaps.forEach { it.recycle() }
            source?.release()
            directory.deleteRecursively()
        }
    }
}
