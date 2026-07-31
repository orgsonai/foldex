// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「文字や線を入れた後に切り抜き・回転しても位置がずれない」ことを確かめる。
 * ここがずれると、編集の途中で作業がやり直しになるので手を抜けない箇所。
 */
class CanvasTransformsTest {

    private val canvas = EditSize(1000, 600)

    private fun drawing(vararg points: Pair<Float, Float>) = Layer.Drawing(
        id = "d",
        name = "描画",
        strokes = listOf(
            Stroke(
                points = points.map { EditPoint(it.first, it.second) },
                widthPx = 10f,
                color = 0xFFFF0000.toInt(),
            ),
        ),
    )

    private fun text(x: Float, y: Float, rotation: Float = 0f) = Layer.Text(
        id = "t",
        name = "文字",
        text = "あ",
        style = TextStyleSpec(sizePx = 40f, color = 0xFF000000.toInt()),
        transform = LayerTransform(offsetX = x, offsetY = y, rotationDeg = rotation),
    )

    /** テスト用に固定サイズのテキストとして扱う (実測は描画側の責務)。 */
    private val fixedTextSize: (Layer.Text) -> EditSize = { EditSize(100, 40) }

    private fun Layer.Drawing.firstPoint(): EditPoint = strokes.first().points.first()

    // ---- 切り抜き (平行移動) ----

    @Test
    fun `切り抜くと線が原点のずれた分だけ動く`() {
        val moved = CanvasTransforms.translate(drawing(300f to 200f), -100f, -50f) as Layer.Drawing
        assertEquals(EditPoint(200f, 150f), moved.firstPoint())
    }

    @Test
    fun `切り抜きと解除を往復すると元の位置に戻る`() {
        val original = drawing(300f to 200f)
        val cropped = CanvasTransforms.translate(original, -100f, -50f)
        val restored = CanvasTransforms.translate(cropped, 100f, 50f) as Layer.Drawing
        assertEquals(EditPoint(300f, 200f), restored.firstPoint())
    }

    // ---- 回転 ----

    @Test
    fun `右に90度まわすと点が正しい位置へ移る`() {
        // 1000x600 の左上寄り (100, 50) は、回転後 (600x1000) では (550, 100)
        val rotated = CanvasTransforms.rotateRight(drawing(100f to 50f), canvas, fixedTextSize)
        assertEquals(EditPoint(550f, 100f), (rotated as Layer.Drawing).firstPoint())
    }

    @Test
    fun `右に4回まわすと線は元の位置に戻る`() {
        var layer: Layer = drawing(123f to 456f)
        var size = canvas
        repeat(4) {
            layer = CanvasTransforms.rotateRight(layer, size, fixedTextSize)
            size = EditSize(size.height, size.width)
        }
        assertEquals(EditPoint(123f, 456f), (layer as Layer.Drawing).firstPoint())
    }

    @Test
    fun `右にまわしてから左にまわすと元へ戻る`() {
        val right = CanvasTransforms.rotateRight(drawing(200f to 100f), canvas, fixedTextSize)
        val back = CanvasTransforms.rotateLeft(right, EditSize(canvas.height, canvas.width), fixedTextSize)
        assertEquals(EditPoint(200f, 100f), (back as Layer.Drawing).firstPoint())
    }

    @Test
    fun `文字は中心を基準に移り、角度が90度足される`() {
        // 中心 (150, 70) → 回転後の中心 (600 - 70, 150) = (530, 150)
        val rotated = CanvasTransforms.rotateRight(text(100f, 50f), canvas, fixedTextSize) as Layer.Text
        assertEquals(530f - 50f, rotated.transform.offsetX, 0.01f)
        assertEquals(150f - 20f, rotated.transform.offsetY, 0.01f)
        assertEquals(90f, rotated.transform.rotationDeg, 0.01f)
    }

    @Test
    fun `角度は-180から180に収まる`() {
        val rotated = CanvasTransforms.rotateRight(text(0f, 0f, rotation = 170f), canvas, fixedTextSize) as Layer.Text
        assertEquals(-100f, rotated.transform.rotationDeg, 0.01f)
    }

    // ---- 反転 ----

    @Test
    fun `左右反転で線が鏡像になる`() {
        val flipped = CanvasTransforms.flipHorizontal(drawing(200f to 100f), canvas, fixedTextSize)
        assertEquals(EditPoint(800f, 100f), (flipped as Layer.Drawing).firstPoint())
    }

    @Test
    fun `上下反転で線が鏡像になる`() {
        val flipped = CanvasTransforms.flipVertical(drawing(200f to 100f), canvas, fixedTextSize)
        assertEquals(EditPoint(200f, 500f), (flipped as Layer.Drawing).firstPoint())
    }

    @Test
    fun `左右反転を2回すると元に戻る`() {
        val once = CanvasTransforms.flipHorizontal(drawing(200f to 100f), canvas, fixedTextSize)
        val twice = CanvasTransforms.flipHorizontal(once, canvas, fixedTextSize) as Layer.Drawing
        assertEquals(EditPoint(200f, 100f), twice.firstPoint())
    }

    @Test
    fun `反転しても文字は鏡文字にならず位置だけ移る`() {
        // 中心 (150, 70) → 左右反転で (850, 70)
        val flipped = CanvasTransforms.flipHorizontal(text(100f, 50f), canvas, fixedTextSize) as Layer.Text
        assertEquals(850f - 50f, flipped.transform.offsetX, 0.01f)
        assertEquals(50f, flipped.transform.offsetY, 0.01f)
        assertEquals("文字自体は反転させない", "あ", flipped.text)
    }

    @Test
    fun `傾けた文字を反転すると傾きの向きが逆になる`() {
        val flipped = CanvasTransforms.flipHorizontal(text(100f, 50f, rotation = 15f), canvas, fixedTextSize) as Layer.Text
        assertEquals(-15f, flipped.transform.rotationDeg, 0.01f)
    }

    // ---- 画像レイヤーは触らない ----

    @Test
    fun `画像レイヤーは座標変換の対象外`() {
        val image = Layer.Image(id = "i", name = "写真", sourceKey = "/a.jpg", sourceSize = EditSize(1000, 600))
        assertEquals(image, CanvasTransforms.rotateRight(image, canvas, fixedTextSize))
        assertEquals(image, CanvasTransforms.translate(image, 10f, 10f))
    }
}
