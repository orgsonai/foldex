// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.model

/** 適用前の調整を文書から分け、既存モザイクの位置と描画順を維持する。 */
internal class MosaicEditSession private constructor(
    val base: EditDocument,
    initial: Layer.Mosaic,
    val existing: Boolean,
) {
    var draft: Layer.Mosaic = initial
        private set
    private val past = ArrayDeque<Layer.Mosaic>()
    private val future = ArrayDeque<Layer.Mosaic>()
    private var sizeBeforeGesture: Layer.Mosaic? = null
    // 切り抜き後に開き直しても、既存の粒度をスライダーの範囲外にしない。
    val maximumBlockSize: Float = maxOf(3f, initial.blockSizePx, minOf(base.canvas.width, base.canvas.height) * .08f)
    val canUndo: Boolean get() = past.isNotEmpty() || sizeBeforeGesture?.let { it != draft } == true
    val canRedo: Boolean get() = future.isNotEmpty()

    fun setArea(area: SelectionSpec?) {
        commitSize()
        change(draft.copy(area = area ?: SelectionSpec.Lasso(emptyList())))
    }

    fun setBlockSize(size: Float) {
        if (!size.isFinite() || size < 2f || size == draft.blockSizePx) return
        if (sizeBeforeGesture == null) sizeBeforeGesture = draft
        draft = draft.copy(blockSizePx = size)
        future.clear()
    }

    fun commitSize() {
        val before = sizeBeforeGesture ?: return
        sizeBeforeGesture = null
        if (before != draft) record(before)
    }

    fun undo(): Boolean {
        commitSize()
        val before = past.removeLastOrNull() ?: return false
        future.addLast(draft)
        draft = before
        return true
    }

    fun redo(): Boolean {
        commitSize()
        val after = future.removeLastOrNull() ?: return false
        past.addLast(draft)
        draft = after
        return true
    }

    fun preview(showEffect: Boolean = true): EditDocument = if (!showEffect) {
        base.copy(layers = base.layers.filterNot { it.id == draft.id })
    } else base.copy(layers = if (existing) {
        base.layers.map { if (it.id == draft.id) draft else it }
    } else base.layers + draft)

    /** 適用は呼び出し元で履歴 1 手とする。取消は base をそのまま使う。 */
    fun commit(): EditDocument = preview().copy(selection = draft.area.takeUnless { it.isEmpty })
    fun delete(): EditDocument = base.copy(layers = base.layers.filterNot { it.id == draft.id })

    private fun change(next: Layer.Mosaic) {
        if (draft == next) return
        record(draft)
        draft = next
        future.clear()
    }
    private fun record(value: Layer.Mosaic) {
        past.addLast(value)
        while (past.size > 50) past.removeFirst()
    }

    companion object {
        fun create(base: EditDocument, draft: Layer.Mosaic): MosaicEditSession {
            require(base.layers.none { it.id == draft.id })
            return MosaicEditSession(base, draft, false)
        }
        fun edit(base: EditDocument, id: String): MosaicEditSession? {
            val layer = base.layers.firstOrNull { it.id == id } as? Layer.Mosaic ?: return null
            return MosaicEditSession(base, layer, true)
        }
    }
}
