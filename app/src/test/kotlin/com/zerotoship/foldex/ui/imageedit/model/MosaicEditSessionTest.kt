// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.model

import org.junit.Assert.*
import org.junit.Test

class MosaicEditSessionTest {
    private val area = SelectionSpec.Rect(EditRect(0f, 0f, 100f, 100f))
    private val mosaic = Layer.Mosaic("mosaic", "モザイク", area = area, blockSizePx = 16f)
    private fun base(): EditDocument {
        val base = EditDocument.ofSingleImage("image", "image", "source", EditSize(200, 100), ImageFormat.PNG)
        return base.copy(layers = base.layers + mosaic + Layer.Fill("fill", "fill", area = area, color = -1, mode = FillMode.COLOR),
            selection = SelectionSpec.Ellipse(EditRect(0f, 0f, 20f, 20f)))
    }

    @Test fun `再調整のプレビューは同じIDと描画順で置換し重ね描きしない`() {
        val base = base()
        val session = MosaicEditSession.edit(base, "mosaic")!!
        session.setBlockSize(24f)
        val preview = session.preview()
        assertEquals(listOf("image", "mosaic", "fill"), preview.layers.map { it.id })
        assertEquals(24f, (preview.layers[1] as Layer.Mosaic).blockSizePx, 0f)
        assertEquals(16f, (base.layers[1] as Layer.Mosaic).blockSizePx, 0f)
        assertEquals(base.layers.last(), preview.layers.last())
    }

    @Test fun `範囲修正中は対象のモザイクだけを隠す`() {
        val base = base().let { it.copy(layers = it.layers + mosaic.copy(id = "other")) }
        val session = MosaicEditSession.edit(base, "mosaic")!!
        assertEquals(listOf("image", "fill", "other"), session.preview(showEffect = false).layers.map { it.id })
        assertEquals(base, session.base)
        assertEquals(2, base.layers.filterIsInstance<Layer.Mosaic>().size)
    }

    @Test fun `粒度スライダーの一度の操作を一手で取り消せる`() {
        val session = MosaicEditSession.edit(base(), "mosaic")!!
        session.setBlockSize(20f); session.setBlockSize(30f); session.setBlockSize(40f)
        session.commitSize()
        assertTrue(session.undo())
        assertEquals(16f, session.draft.blockSizePx, 0f)
        assertFalse(session.canUndo)
        assertTrue(session.redo())
        assertEquals(40f, session.draft.blockSizePx, 0f)
    }

    @Test fun `粒度と範囲の変更をそれぞれ取り消してやり直せる`() {
        val session = MosaicEditSession.edit(base(), "mosaic")!!
        val stroke = SelectionSpec.BrushStroke(listOf(EditPoint(20f, 20f)), 4f)
        session.setBlockSize(40f)
        session.setArea(area.applying(SelectionMode.SUBTRACT, stroke))
        assertFalse(session.draft.area.contains(20f, 20f))
        session.undo()
        assertEquals(area, session.draft.area)
        assertEquals(40f, session.draft.blockSizePx, 0f)
        session.undo()
        assertEquals(16f, session.draft.blockSizePx, 0f)
        session.redo(); session.redo()
        assertFalse(session.draft.area.contains(20f, 20f))
    }

    @Test fun `取消では下書きへ渡す元の文書を変えない`() {
        val base = base()
        val session = MosaicEditSession.edit(base, "mosaic")!!
        session.setBlockSize(50f)
        session.setArea(null)
        assertSame(base, session.base)
        assertSame(base.layers[1], mosaic)
        assertEquals(area, mosaic.area)
    }

    @Test fun `適用した再編集全体をドキュメント履歴で一手で戻す`() {
        val base = base()
        val session = MosaicEditSession.edit(base, "mosaic")!!
        session.setArea(SelectionSpec.BrushStroke(listOf(EditPoint(50f, 50f)), 15f))
        session.setBlockSize(40f)
        val history = EditHistory()
        history.record(base)
        val committed = session.commit()
        assertEquals(session.draft.area, committed.selection)
        assertEquals(base, history.undo(committed))
        assertEquals(committed, history.redo(base))
    }

    @Test fun `削除は選んだモザイクだけに作用しUndoで戻せる`() {
        val base = base()
        val session = MosaicEditSession.edit(base, "mosaic")!!
        val history = EditHistory()
        history.record(base)
        val deleted = session.delete()
        assertEquals(listOf("image", "fill"), deleted.layers.map { it.id })
        assertEquals(base, history.undo(deleted))
    }

    @Test fun `新規のモザイクは適用まで元のレイヤーを変更しない`() {
        val base = base().let { it.copy(layers = it.layers.filterNot { l -> l is Layer.Mosaic }) }
        val session = MosaicEditSession.create(base, mosaic)
        assertFalse(session.existing)
        assertEquals(base.layers, session.preview(showEffect = false).layers)
        assertEquals(mosaic, session.commit().layers.last())
    }

    @Test fun `存在しないIDや不正な粒度では編集状態を壊さない`() {
        val base = base()
        assertNull(MosaicEditSession.edit(base, "gone"))
        assertNull(MosaicEditSession.edit(base, "fill"))
        val session = MosaicEditSession.edit(base, "mosaic")!!
        session.setBlockSize(Float.NaN)
        session.setBlockSize(1f)
        assertEquals(mosaic, session.draft)
        assertFalse(session.canUndo)
    }

    @Test fun `新しい範囲の操作は古いRedo分岐を残さない`() {
        val session = MosaicEditSession.edit(base(), "mosaic")!!
        session.setArea(SelectionSpec.BrushStroke(listOf(EditPoint(30f, 30f)), 10f))
        session.undo()
        session.setArea(SelectionSpec.BrushStroke(listOf(EditPoint(60f, 60f)), 10f))
        assertFalse(session.canRedo)
        assertFalse(session.redo())
    }

    @Test fun `切り抜き後も既存の粒度を範囲に含め調整中の上限は動かない`() {
        val cropped = base().copy(canvas = base().canvas.copy(width = 10, height = 10))
        val session = MosaicEditSession.edit(cropped, "mosaic")!!
        assertEquals(16f, session.maximumBlockSize, 0f)
        session.setBlockSize(2f)
        session.commitSize()
        assertEquals(16f, session.maximumBlockSize, 0f)
        session.undo()
        assertEquals(16f, session.draft.blockSizePx, 0f)
    }
}
