// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.ui.imageedit.model.EditDocument
import com.zerotoship.foldex.ui.imageedit.model.EditSize
import com.zerotoship.foldex.ui.imageedit.model.ImageFormat
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** 失敗時は Android の Bitmap を確保する前に止まることも JVM 上で確認する。 */
class RenderFailureTest {
    private val document = EditDocument.ofSingleImage("image", "photo", "missing", EditSize(20, 20), ImageFormat.PNG)

    @Test fun `素材を解決できないとき不完全なBitmapを返さない`() {
        assertEquals(Result.Failure(RenderFailure.MissingSource("missing")), DocumentRenderer.render(document, { null }))
    }

    @Test fun `不正な出力倍率では素材を読み始めない`() {
        for (scale in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f)) {
            assertEquals(Result.Failure(RenderFailure.InvalidGeometry), DocumentRenderer.render(
                document.copy(output = document.output.copy(scale = scale)), { error("must not decode") },
            ))
        }
    }

    @Test fun `キャンセルを描画失敗へ変換しない`() {
        try {
            DocumentRenderer.render(document, { null }) { throw CancellationException() }
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
