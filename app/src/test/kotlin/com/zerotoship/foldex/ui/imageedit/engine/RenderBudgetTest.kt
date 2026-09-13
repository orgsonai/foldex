// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import com.zerotoship.foldex.ui.imageedit.model.EditDocument
import com.zerotoship.foldex.ui.imageedit.model.EditRect
import com.zerotoship.foldex.ui.imageedit.model.EditSize
import com.zerotoship.foldex.ui.imageedit.model.ImageFormat
import com.zerotoship.foldex.ui.imageedit.model.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderBudgetTest {
    private val mib = RenderBudget.MIB
    private fun photo(width: Int = 4000, height: Int = 3000) = EditDocument.ofSingleImage(
        "image", "photo", "/photo", EditSize(width, height), ImageFormat.JPEG,
    )

    @Test fun `12MP fits a sufficient budget without changing the requested output`() {
        val doc = photo()
        val original = doc.copy()
        assertEquals(RenderAdmission.Allowed(null), RenderBudget.plan(doc, ImageWork.EXPORT, 256 * mib))
        assertEquals(original, doc)
    }

    @Test fun `large photo square and transparent PNG suggest an actually affordable size`() {
        for (format in listOf(ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.WEBP)) {
            for (size in listOf(EditSize(8000, 6000), EditSize(8192, 8192), EditSize(4000, 3000))) {
                val base = photo(size.width, size.height)
                val doc = base.copy(output = base.output.copy(format = format))
                val rejected = RenderBudget.plan(doc, ImageWork.EXPORT, 128 * mib) as RenderAdmission.Rejected
                val edge = requireNotNull(rejected.suggestedLongEdge)
                assertTrue(edge in 16 until size.longEdge)
                val suggested = doc.withOutputLongEdge(edge)
                assertEquals(RenderAdmission.Allowed(null), RenderBudget.plan(suggested, ImageWork.EXPORT, 128 * mib))
                assertEquals(size, doc.outputSize)
                assertSame(doc.layers, suggested.layers)
            }
        }
    }

    @Test fun `8192 output cap is checked even with abundant memory`() {
        val doc = photo(12000, 4000)
        assertEquals(RenderAdmission.Rejected(8192), RenderBudget.plan(doc, ImageWork.EXPORT, Long.MAX_VALUE))
    }

    @Test fun `no work starts when even a tiny image cannot fit`() {
        for (work in ImageWork.entries) {
            assertEquals(RenderAdmission.Rejected(null), RenderBudget.plan(photo(), work, 0))
        }
    }

    @Test fun `preview may be smaller while export settings stay intact`() {
        val doc = photo(8192, 8192)
        val plan = RenderBudget.plan(doc, ImageWork.PREVIEW, 32 * mib) as RenderAdmission.Allowed
        assertTrue(requireNotNull(plan.targetLongEdge) < 2048)
        assertEquals(EditSize(8192, 8192), doc.outputSize)
        assertTrue(RenderBudget.requiredBytes(doc, doc.canvasSize.fitLongEdge(plan.targetLongEdge), ImageWork.PREVIEW) <= 32 * mib)
    }

    @Test fun `selection reserves inference memory even for small images`() {
        assertEquals(RenderAdmission.Rejected(null), RenderBudget.plan(photo(100, 100), ImageWork.SELECTION, 96 * mib))
        assertTrue(RenderBudget.plan(photo(100, 100), ImageWork.SELECTION, 192 * mib) is RenderAdmission.Allowed)
    }

    @Test fun `selection rejects work instead of silently using a very coarse image`() {
        assertEquals(
            RenderAdmission.Rejected(null),
            RenderBudget.plan(photo(1000, 1000), ImageWork.SELECTION, 136 * mib + mib / 2),
        )
    }

    @Test fun `sample boundaries include the larger decoder bitmap`() {
        val doc = photo()
        val atBoundary = RenderBudget.requiredBytes(doc, EditSize(2000, 1500), ImageWork.PREVIEW)
        val above = RenderBudget.requiredBytes(doc, EditSize(2004, 1503), ImageWork.PREVIEW)
        assertTrue(above > atBoundary * 2)
        val fitted = requireNotNull(RenderBudget.fittingEdge(doc, ImageWork.EXPORT, 4000, 96 * mib))
        assertEquals(2000, fitted)
    }

    @Test fun `cropped photo still budgets the whole decoded source`() {
        val original = photo(8000, 6000)
        val layer = (original.activeLayer as Layer.Image).withCrop(EditRect(0f, 0f, 100f, 100f))
        val cropped = original.copy(layers = listOf(layer)).withCanvasFitting(EditSize(100, 100))
        assertTrue(RenderBudget.requiredBytes(cropped, cropped.outputSize, ImageWork.EXPORT) > 500 * mib)
        assertTrue(RenderBudget.plan(cropped, ImageWork.EXPORT, 128 * mib) is RenderAdmission.Rejected)
    }

    @Test fun `sequential image layers do not retain all source pixels`() {
        val doc = photo()
        val image = doc.activeLayer as Layer.Image
        val multiple = doc.copy(layers = List(8) { image.copy(id = "$it", sourceKey = "/$it") })
        assertEquals(
            RenderBudget.requiredBytes(doc, doc.outputSize, ImageWork.EXPORT),
            RenderBudget.requiredBytes(multiple, doc.outputSize, ImageWork.EXPORT),
        )
    }

    @Test fun `hidden large source is not decoded or budgeted`() {
        val doc = photo(100, 100)
        val huge = (photo().activeLayer as Layer.Image).copy(visible = false, sourceSize = EditSize(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(
            RenderBudget.requiredBytes(doc, doc.outputSize, ImageWork.PREVIEW),
            RenderBudget.requiredBytes(doc.copy(layers = doc.layers + huge), doc.outputSize, ImageWork.PREVIEW),
        )
    }

    @Test fun `extreme dimensions saturate without wrapping the budget`() {
        val doc = photo(Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, RenderBudget.requiredBytes(doc, doc.outputSize, ImageWork.EXPORT))
        assertEquals(1 shl 30, RenderBudget.sampleSizeFor(Int.MAX_VALUE, 1))
    }

    @Test fun `very tall output can be reduced below one percent`() {
        val doc = photo(1000, 1_000_000)
        assertEquals(EditSize(1, 100), doc.withOutputLongEdge(100).outputSize)
        val advice = RenderBudget.plan(doc, ImageWork.EXPORT, 64 * mib) as RenderAdmission.Rejected
        val suggested = doc.withOutputLongEdge(requireNotNull(advice.suggestedLongEdge))
        assertTrue(suggested.outputSize.longEdge <= 8192)
        assertEquals(RenderAdmission.Allowed(null), RenderBudget.plan(suggested, ImageWork.EXPORT, 64 * mib))
    }

    private fun snapshot() = ImageMemorySnapshot(
        memoryClassBytes = 512 * mib, managedFreeBytes = 450 * mib, managedUsedBytes = 62 * mib,
        nativeUsedBytes = 64 * mib, systemAvailableBytes = 4_000 * mib, systemThresholdBytes = 500 * mib,
        lowRam = false, lowMemory = false,
    )

    @Test fun `retained previews from other editors reduce the next allocation`() {
        val memory = snapshot()
        assertEquals(384 * mib, memory.availableForWork(0))
        assertEquals(256 * mib, memory.availableForWork(64 * mib))
        assertEquals(0L, memory.availableForWork(200 * mib))
    }

    @Test fun `native usage managed headroom and system pressure each constrain work`() {
        val memory = snapshot()
        assertEquals(0L, memory.copy(lowMemory = true).availableForWork(0))
        assertEquals(0L, memory.copy(nativeUsedBytes = 1000 * mib).availableForWork(0))
        assertEquals(0L, memory.copy(managedFreeBytes = 20 * mib).availableForWork(0))
        assertEquals(0L, memory.copy(systemAvailableBytes = 400 * mib).availableForWork(0))
        assertEquals(96 * mib, memory.copy(lowRam = true).availableForWork(0))
    }
}
