// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.model

import org.junit.Assert.*
import org.junit.Test

class SelectionBrushTest {
    private fun brush(vararg points: EditPoint, radius: Float = 5f) = SelectionSpec.BrushStroke(points.toList(), radius)

    @Test fun `一度のタップでも円形の選択になる`() {
        val stroke = brush(EditPoint(20f, 30f))
        assertTrue(stroke.contains(23f, 34f))
        assertFalse(stroke.contains(24f, 34f))
        assertEquals(EditRect(15f, 25f, 25f, 35f), stroke.bounds)
    }

    @Test fun `速い移動の端点間も途切れず丸い端を持つ`() {
        val stroke = brush(EditPoint(10f, 20f), EditPoint(90f, 20f))
        assertTrue(stroke.contains(50f, 24f))
        assertTrue(stroke.contains(6f, 20f))
        assertTrue(stroke.contains(94f, 20f))
        assertFalse(stroke.contains(50f, 26f))
    }

    @Test fun `同じ点が連続してもNaNにならない`() {
        val stroke = brush(EditPoint(20f, 20f), EditPoint(20f, 20f), EditPoint(20f, 50f))
        assertTrue(stroke.contains(20f, 20f))
        assertTrue(stroke.contains(24f, 35f))
        assertFalse(stroke.contains(26f, 35f))
    }

    @Test fun `折れ曲がるブラシの角に穴を作らない`() {
        val stroke = brush(EditPoint(10f, 10f), EditPoint(30f, 10f), EditPoint(30f, 30f))
        assertTrue(stroke.contains(32f, 8f))
        assertTrue(stroke.contains(30f, 20f))
        assertFalse(stroke.contains(15f, 20f))
    }

    @Test fun `輪郭の穴を残しながらブラシで追加と除外を行える`() {
        val outer = listOf(EditPoint(0f, 0f), EditPoint(100f, 0f), EditPoint(100f, 100f), EditPoint(0f, 100f))
        val hole = listOf(EditPoint(30f, 30f), EditPoint(70f, 30f), EditPoint(70f, 70f), EditPoint(30f, 70f))
        val selected = SelectionSpec.Contours(listOf(outer, hole))
            .applying(SelectionMode.ADD, brush(EditPoint(40f, 40f)))
            .applying(SelectionMode.SUBTRACT, brush(EditPoint(10f, 10f)))!!
        assertTrue(selected.contains(40f, 40f))
        assertFalse(selected.contains(60f, 60f))
        assertFalse(selected.contains(10f, 10f))
        assertTrue(selected.contains(90f, 90f))
    }

    @Test fun `表示を拡大しても同じ論理座標と太さになる`() {
        val canvas = EditSize(1000, 500)
        val first = SelectionBrush.fromViewport(listOf(EditPoint(50f, 35f), EditPoint(70f, 35f)),
            EditRect(10f, 10f, 210f, 110f), canvas, 24f)!!
        val zoomed = SelectionBrush.fromViewport(listOf(EditPoint(180f, 110f), EditPoint(220f, 110f)),
            EditRect(100f, 60f, 500f, 260f), canvas, 24f)!!
        assertEquals(first, zoomed)
        assertEquals(12f, first.radius, 0f)
    }

    @Test fun `画像の外を縁へ押し込まず実際に重なる部分だけを残す`() {
        val stroke = SelectionBrush.fromViewport(listOf(EditPoint(-2f, 20f)),
            EditRect(0f, 0f, 100f, 100f), EditSize(100, 100), 10f)!!
        assertEquals(EditRect(0f, 15f, 3f, 25f), stroke.bounds)
        assertTrue(stroke.contains(1f, 20f))
        assertFalse(stroke.contains(4f, 20f))
        assertFalse(stroke.contains(-1f, 20f))
        assertNull(SelectionBrush.fromViewport(listOf(EditPoint(-20f, 20f)),
            EditRect(0f, 0f, 100f, 100f), EditSize(100, 100), 10f))
    }

    @Test fun `回転と反転後もブラシの範囲と太さを保つ`() {
        val canvas = EditSize(100, 80)
        val stroke = SelectionBrush.fromViewport(listOf(EditPoint(20f, 20f), EditPoint(90f, 50f)),
            EditRect.ofSize(100, 80), canvas, 12f)!!
        for (transform in listOf(CanvasTransforms.rotateRightMap(canvas), CanvasTransforms.rotateLeftMap(canvas),
            CanvasTransforms.flipHorizontalMap(canvas), CanvasTransforms.flipVerticalMap(canvas))) {
            val mapped = stroke.mapPoints(transform) as SelectionSpec.BrushStroke
            assertEquals(stroke.radius, mapped.radius, .001f)
            for (x in 1..99 step 3) for (y in 1..79 step 3) {
                val point = transform(EditPoint(x.toFloat(), y.toFloat()))
                assertEquals(stroke.contains(x.toFloat(), y.toFloat()), mapped.contains(point.x, point.y))
            }
        }
    }

    @Test fun `切り抜きと解除でクリップ範囲ごと元に戻る`() {
        val stroke = SelectionBrush.fromViewport(listOf(EditPoint(0f, 20f)),
            EditRect.ofSize(100, 80), EditSize(100, 80), 12f)!!
        val crop = stroke.mapPoints(CanvasTransforms.translateMap(-10f, -5f))
        assertEquals(stroke, crop.mapPoints(CanvasTransforms.translateMap(10f, 5f)))
    }

    @Test fun `新規選択と一筆ずつのUndoRedoが画素の編集と共存する`() {
        val history = EditHistory()
        val base = EditDocument.ofSingleImage("image", "image", "key", EditSize(100, 80), ImageFormat.PNG)
        val added = base.copy(selection = brush(EditPoint(10f, 10f)))
        history.record(base)
        val subtracted = added.copy(selection = added.selection.applying(SelectionMode.SUBTRACT, brush(EditPoint(10f, 10f), radius = 2f)))
        history.record(added)
        assertEquals(added, history.undo(subtracted))
        assertEquals(base, history.undo(added))
        assertEquals(added, history.redo(base))
        assertEquals(subtracted, history.redo(added))
    }

    @Test fun `表示枠が不正なときや点数上限を超えると確定しない`() {
        val point = EditPoint(5f, 5f)
        val canvas = EditSize(100, 100)
        for (viewport in listOf(EditRect(0f, 0f, 0f, 0f), EditRect(0f, 0f, Float.NaN, 100f))) {
            assertNull(SelectionBrush.fromViewport(listOf(point), viewport, canvas, 10f))
        }
        assertNull(SelectionBrush.fromViewport(List(SelectionSpec.MAX_BRUSH_POINTS + 1) { point }, EditRect.ofSize(100, 100), canvas, 10f))
        assertNull(SelectionBrush.fromViewport(listOf(point), EditRect.ofSize(100, 100), canvas, Float.NaN))
    }
}
