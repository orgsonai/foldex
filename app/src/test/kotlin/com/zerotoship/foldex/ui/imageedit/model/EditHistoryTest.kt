// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {

    private fun doc(longEdge: Int): EditDocument =
        EditDocument.ofSingleImage(
            layerId = "l",
            name = "a.jpg",
            sourceKey = "/a.jpg",
            sourceSize = EditSize(longEdge, longEdge),
            format = ImageFormat.JPEG,
        )

    @Test
    fun `初期状態では戻すことも進むこともできない`() {
        val history = EditHistory()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertNull(history.undo(doc(100)))
        assertNull(history.redo(doc(100)))
    }

    @Test
    fun `記録した状態へ戻り、やり直しで元に戻る`() {
        val history = EditHistory()
        val first = doc(100)
        val second = doc(200)

        history.record(first)
        assertTrue(history.canUndo)

        val undone = history.undo(second)
        assertEquals(first, undone)
        assertTrue(history.canRedo)

        val redone = history.redo(first)
        assertEquals(second, redone)
        assertFalse(history.canRedo)
    }

    @Test
    fun `戻した後に新しい操作をするとやり直し履歴は消える`() {
        val history = EditHistory()
        val a = doc(100)
        val b = doc(200)
        val c = doc(300)

        history.record(a)
        history.undo(b)
        assertTrue(history.canRedo)

        history.record(c)
        assertFalse("新しい操作をしたら redo は無効になる", history.canRedo)
    }

    @Test
    fun `上限を超えたら古いものから捨てる`() {
        val history = EditHistory(limit = 3)
        repeat(5) { history.record(doc(100 + it)) }
        assertEquals(3, history.depth)

        // 残っているのは最後の 3 つ (102, 103, 104)。順に戻れる。
        assertEquals(doc(104), history.undo(doc(999)))
        assertEquals(doc(103), history.undo(doc(104)))
        assertEquals(doc(102), history.undo(doc(103)))
        assertNull(history.undo(doc(102)))
    }

    @Test
    fun `clear で両方の履歴が消える`() {
        val history = EditHistory()
        history.record(doc(100))
        history.undo(doc(200))
        history.clear()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }
}
