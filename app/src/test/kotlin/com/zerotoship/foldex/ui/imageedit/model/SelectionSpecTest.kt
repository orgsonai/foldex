// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 選択範囲の判定。ここが狂うと「塗ったら選んでいない場所まで塗られた」になるので、
 * 画面を触らずに確かめられるうちに固めておく。
 */
class SelectionSpecTest {

    private val canvas = EditSize(1000, 600)

    private fun rect(l: Float, t: Float, r: Float, b: Float) =
        SelectionSpec.Rect(EditRect(l, t, r, b))

    // ---- 基本の形 ----

    @Test
    fun `矩形は左上を含み右下を含まない`() {
        val s = rect(10f, 20f, 110f, 120f)
        assertTrue(s.contains(10f, 20f))
        assertTrue(s.contains(109f, 119f))
        // 右端と下端は排他 (EditRect の約束と揃える)。隣り合う範囲が二重に当たらない。
        assertFalse(s.contains(110f, 60f))
        assertFalse(s.contains(60f, 120f))
        assertFalse(s.contains(9f, 20f))
    }

    @Test
    fun `楕円は角が外れて中心が入る`() {
        val s = SelectionSpec.Ellipse(EditRect(0f, 0f, 100f, 100f))
        assertTrue(s.contains(50f, 50f))
        assertTrue(s.contains(50f, 1f))
        // 外接矩形の四隅は楕円の外。
        assertFalse(s.contains(1f, 1f))
        assertFalse(s.contains(99f, 99f))
    }

    @Test
    fun `投げ縄は凹んだ形でも内外を判定できる`() {
        // コの字。真ん中のくぼみは外側になる。
        val s = SelectionSpec.Lasso(
            listOf(
                EditPoint(0f, 0f),
                EditPoint(100f, 0f),
                EditPoint(100f, 100f),
                EditPoint(70f, 100f),
                EditPoint(70f, 30f),
                EditPoint(30f, 30f),
                EditPoint(30f, 100f),
                EditPoint(0f, 100f),
            ),
        )
        assertTrue(s.contains(10f, 50f))
        assertTrue(s.contains(50f, 10f))
        assertFalse(s.contains(50f, 60f))
        assertFalse(s.contains(150f, 50f))
    }

    @Test
    fun `点が2つ以下の投げ縄は面積を持たない`() {
        val s = SelectionSpec.Lasso(listOf(EditPoint(0f, 0f), EditPoint(10f, 10f)))
        assertFalse(s.contains(5f, 5f))
    }

    // ---- 追加 / 除外 ----

    @Test
    fun `追加すると両方が選ばれる`() {
        val a = rect(0f, 0f, 100f, 100f)
        val b = rect(200f, 0f, 300f, 100f)
        val combined = a.applying(SelectionMode.ADD, b)!!
        assertTrue(combined.contains(50f, 50f))
        assertTrue(combined.contains(250f, 50f))
        assertFalse(combined.contains(150f, 50f))
    }

    @Test
    fun `除外すると重なった部分だけが抜ける`() {
        val a = rect(0f, 0f, 100f, 100f)
        val hole = rect(40f, 40f, 60f, 60f)
        val combined = a.applying(SelectionMode.SUBTRACT, hole)!!
        assertTrue(combined.contains(10f, 10f))
        assertFalse(combined.contains(50f, 50f))
    }

    @Test
    fun `新規は前の選択を捨てる`() {
        val a = rect(0f, 0f, 100f, 100f)
        val b = rect(200f, 0f, 300f, 100f)
        val replaced = a.applying(SelectionMode.REPLACE, b)!!
        assertFalse(replaced.contains(50f, 50f))
        assertTrue(replaced.contains(250f, 50f))
    }

    @Test
    fun `積み重ねても入れ子にならない`() {
        // contains の再帰が深くならないよう、Combined は 1 段に平らへ積む約束。
        val s = rect(0f, 0f, 100f, 100f)
            .applying(SelectionMode.ADD, rect(200f, 0f, 300f, 100f))!!
            .applying(SelectionMode.ADD, rect(400f, 0f, 500f, 100f))!!
        val combined = s as SelectionSpec.Combined
        assertEquals(3, combined.parts.size)
        assertTrue(combined.parts.none { it.spec is SelectionSpec.Combined })
    }

    @Test
    fun `面積のない形は無視される`() {
        val a = rect(0f, 0f, 100f, 100f)
        val empty = rect(50f, 50f, 50f, 50f)
        assertEquals(a, a.applying(SelectionMode.ADD, empty))
    }

    @Test
    fun `選択がないときに除外しても何も選ばれない`() {
        assertNull(null.applying(SelectionMode.SUBTRACT, rect(0f, 0f, 10f, 10f)))
    }

    // ---- 反転 ----

    @Test
    fun `反転すると内と外が入れ替わる`() {
        val s = rect(0f, 0f, 100f, 100f).invertedIn(canvas)
        assertFalse(s.contains(50f, 50f))
        assertTrue(s.contains(500f, 300f))
    }

    @Test
    fun `選択なしから反転すると全面が選ばれる`() {
        val s = null.invertedIn(canvas)
        assertTrue(s.contains(0f, 0f))
        assertTrue(s.contains(999f, 599f))
        assertFalse(s.contains(1000f, 600f))
    }

    @Test
    fun `反転を2回かけると元に戻る`() {
        val original = rect(10f, 20f, 110f, 120f)
        val twice = original.invertedIn(canvas).invertedIn(canvas)
        // 式は伸びるが、判定は元と一致する。
        assertEquals(original.contains(50f, 50f), twice.contains(50f, 50f))
        assertEquals(original.contains(500f, 300f), twice.contains(500f, 300f))
        assertTrue(twice.contains(50f, 50f))
        assertFalse(twice.contains(500f, 300f))
    }

    // ---- 座標変換への追従 ----

    @Test
    fun `右90度で選択も一緒に回る`() {
        // 左上の 100x100 が、回転後は右上に来る。
        val s = rect(0f, 0f, 100f, 100f).mapPoints(CanvasTransforms.rotateRightMap(canvas))
        val b = s.bounds
        assertEquals(500f, b.left, 0.01f)
        assertEquals(0f, b.top, 0.01f)
        assertEquals(600f, b.right, 0.01f)
        assertEquals(100f, b.bottom, 0.01f)
    }

    @Test
    fun `左右反転で選択が鏡像になる`() {
        val s = rect(0f, 0f, 100f, 100f).mapPoints(CanvasTransforms.flipHorizontalMap(canvas))
        val b = s.bounds
        assertEquals(900f, b.left, 0.01f)
        assertEquals(1000f, b.right, 0.01f)
    }

    @Test
    fun `切り抜きの平行移動に追従する`() {
        val s = rect(100f, 100f, 200f, 200f).mapPoints(CanvasTransforms.translateMap(-100f, -100f))
        assertTrue(s.contains(0f, 0f))
        assertFalse(s.contains(150f, 150f))
    }

    @Test
    fun `追加した選択も丸ごと変換される`() {
        val s = rect(0f, 0f, 100f, 100f)
            .applying(SelectionMode.ADD, rect(200f, 0f, 300f, 100f))!!
            .mapPoints(CanvasTransforms.translateMap(10f, 20f))
        assertTrue(s.contains(60f, 70f))
        assertTrue(s.contains(260f, 70f))
        assertFalse(s.contains(50f, 10f))
    }

    // ---- bounds ----

    @Test
    fun `除外は範囲を広げない`() {
        val s = rect(0f, 0f, 100f, 100f)
            .applying(SelectionMode.SUBTRACT, rect(-500f, -500f, 50f, 50f))!!
        val b = s.bounds
        assertEquals(0f, b.left, 0.01f)
        assertEquals(100f, b.right, 0.01f)
    }
}
