// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.draft

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zerotoship.foldex.ui.imageedit.engine.DocumentRenderer
import com.zerotoship.foldex.ui.imageedit.engine.ImageExportCoordinator
import com.zerotoship.foldex.ui.imageedit.engine.ImageSource
import com.zerotoship.foldex.ui.imageedit.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Android 上の原子的ファイル公開と、復元後の実 Canvas 描画を通して確認する。 */
@RunWith(AndroidJUnit4::class)
class ImageDraftRenderInstrumentedTest {
    @Test fun restoredDraftRendersAndExportsAfterTheOriginalHasBeenDeleted() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "draft-render-test-${UUID.randomUUID()}").apply { mkdirs() }
        val store = ImageDraftStore(File(directory, "drafts"))
        val reopened = ImageDraftStore(File(directory, "drafts"))
        var before: Bitmap? = null
        var after: Bitmap? = null
        try {
            val original = File(directory, "original.png")
            Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).also { bitmap ->
                try {
                    bitmap.setPixels(IntArray(80 * 60) { index -> Color.rgb(index % 255, index / 80, 150) }, 0, 80, 0, 0, 80, 60)
                    original.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
            }
            val source = ImageSource(original)
            val base = EditDocument.ofSingleImage("base", "original.png", original.absolutePath, source.size, ImageFormat.PNG)
            val rotated = (base.layers.single() as Layer.Image).copy(crop = EditRect(10f, 10f, 70f, 50f), quarterTurns = 1, flipH = true)
            val area = SelectionSpec.Contours(listOf(listOf(EditPoint(2f, 2f), EditPoint(30f, 2f), EditPoint(30f, 30f))))
            val doc = base.copy(selection = area, layers = listOf(
                rotated,
                Layer.Drawing("drawing", "drawing", strokes = listOf(Stroke(listOf(EditPoint(1f, 1f), EditPoint(30f, 40f)), 4f, Color.RED))),
                Layer.Text("text", "text", text = "下書き", style = TextStyleSpec(8f, Color.WHITE)),
                Layer.Mosaic("mosaic", "mosaic", area = area, blockSizePx = 4f),
                Layer.Fill("hole", "hole", area = SelectionSpec.Rect(EditRect(4f, 4f, 8f, 8f)), color = 0, mode = FillMode.CLEAR),
            )).withCanvasFitting(rotated.logicalSize)
            before = requireNotNull(DocumentRenderer.render(doc, { source }).getOrNull())
            val initial = requireNotNull(store.create(UUID.randomUUID().toString(), original, original.name, null, base).getOrNull())
            val edited = initial.copy(revision = 1, dirty = true, document = doc.copy(layers = doc.layers.map {
                if (it is Layer.Image) it.copy(sourceKey = initial.assets.keys.single()) else it
            }))
            assertTrue(store.save(edited).isSuccess)
            store.close()
            source.release()
            assertTrue(original.delete())
            val restored = requireNotNull(reopened.open(initial.id).getOrNull()).draft
            val asset = reopened.assetFile(restored.id, restored.assets.keys.single())
            val restoredSource = ImageSource(asset)
            after = requireNotNull(DocumentRenderer.render(restored.document, { restoredSource }).getOrNull())
            assertTrue(before!!.sameAs(after))
            val prepared = ImageExportCoordinator(File(directory, "exports")).prepare(
                restored.document, { restoredSource }, asset, restored.assets.values.single(), {})
            requireNotNull(prepared.getOrNull()).use { assertTrue(it.file.length() > 0) }
            restoredSource.release()
        } finally {
            before?.recycle()
            after?.recycle()
            store.close()
            reopened.close()
            directory.deleteRecursively()
        }
    }
}
