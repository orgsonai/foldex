// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditDocumentTest {

    private fun photo(width: Int = 4000, height: Int = 3000): EditDocument =
        EditDocument.ofSingleImage(
            layerId = "l",
            name = "photo.jpg",
            sourceKey = "/photo.jpg",
            sourceSize = EditSize(width, height),
            format = ImageFormat.JPEG,
        )

    private fun EditDocument.image(): Layer.Image = activeLayer as Layer.Image

    // ---- 回転 ----

    @Test
    fun `90度を4回まわすと元に戻る`() {
        var layer = photo().image()
        repeat(4) { layer = layer.rotatedRight() }
        assertEquals(0, layer.quarterTurns)
        assertEquals(EditSize(4000, 3000), layer.logicalSize)
    }

    @Test
    fun `90度まわすと幅と高さが入れ替わる`() {
        val layer = photo().image().rotatedRight()
        assertEquals(EditSize(3000, 4000), layer.logicalSize)
    }

    @Test
    fun `左まわしは右まわし3回と同じ`() {
        val left = photo().image().rotatedLeft()
        var right = photo().image()
        repeat(3) { right = right.rotatedRight() }
        assertEquals(right.quarterTurns, left.quarterTurns)
    }

    // ---- 切り抜き ----

    @Test
    fun `切り抜くと論理サイズがその範囲になる`() {
        val layer = photo().image().withCrop(EditRect(1000f, 500f, 3000f, 2000f))
        assertEquals(EditSize(2000, 1500), layer.logicalSize)
    }

    @Test
    fun `元画像からはみ出す切り抜きは内側へ丸める`() {
        val layer = photo().image().withCrop(EditRect(-500f, -500f, 9000f, 9000f))
        assertEquals(EditRect(0f, 0f, 4000f, 3000f), layer.cropRect)
    }

    @Test
    fun `完全に外れた切り抜きは無視する`() {
        val original = photo().image()
        val layer = original.withCrop(EditRect(5000f, 5000f, 6000f, 6000f))
        assertNull(layer.crop)
    }

    @Test
    fun `切り抜きを解除すると元の範囲に戻る`() {
        val layer = photo().image()
            .withCrop(EditRect(100f, 100f, 200f, 200f))
            .withoutCrop()
        assertEquals(EditSize(4000, 3000), layer.logicalSize)
    }

    @Test
    fun `切り抜きは入れ子にせず常に元画像座標で解釈する`() {
        // 2 回続けて指定しても、2 回目が元画像座標としてそのまま採用される。
        val layer = photo().image()
            .withCrop(EditRect(1000f, 1000f, 3000f, 2000f))
            .withCrop(EditRect(0f, 0f, 500f, 500f))
        assertEquals(EditRect(0f, 0f, 500f, 500f), layer.cropRect)
    }

    // ---- 出力サイズ ----

    @Test
    fun `出力の長辺を指定すると比率を保って縮む`() {
        val doc = photo().withOutputLongEdge(1600)
        assertEquals(EditSize(1600, 1200), doc.outputSize)
    }

    @Test
    fun `切り抜いても出力の縮小率は維持される`() {
        // 先に 50% へ縮める指定をしてから切り抜く。
        val doc = photo().withOutputLongEdge(2000) // 4000 → 2000 (50%)
        val cropped = doc.image().withCrop(EditRect(0f, 0f, 2000f, 1500f))
        val next = doc.updateLayer("l") { cropped }.withCanvasFitting(cropped.logicalSize)
        // キャンバスは 2000x1500 になり、50% のままなので出力は 1000x750。
        assertEquals(EditSize(1000, 750), next.outputSize)
    }

    @Test
    fun `等倍なら出力サイズはキャンバスと同じ`() {
        assertEquals(EditSize(4000, 3000), photo().outputSize)
    }

    // ---- 幾何ヘルパー ----

    @Test
    fun `長辺フィットは拡大を禁止できる`() {
        val size = EditSize(800, 600)
        assertEquals(EditSize(800, 600), size.fitLongEdge(2000, allowUpscale = false))
        assertEquals(EditSize(2000, 1500), size.fitLongEdge(2000, allowUpscale = true))
    }

    @Test
    fun `矩形の交差`() {
        val a = EditRect(0f, 0f, 100f, 100f)
        assertEquals(EditRect(50f, 50f, 100f, 100f), a.intersect(EditRect(50f, 50f, 200f, 200f)))
        assertNull(a.intersect(EditRect(200f, 200f, 300f, 300f)))
    }
}
