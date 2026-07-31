// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.model

/**
 * Undo / Redo。[EditDocument] のスナップショットを積むだけの素直な実装。
 *
 * これが成立するのは、ドキュメントが画素データを持たないから。
 * Bitmap をコピーする方式だと 4000x3000 の画像で 1 手あたり 48MB かかり、
 * 10 手で 480MB — 落ちる。値オブジェクトなら 1 手あたり数 KB で、
 * 上限 [limit] 手まで積んでも 1MB に満たない。
 *
 * スレッド安全ではない。UI スレッド (ViewModel) からのみ触ること。
 */
class EditHistory(private val limit: Int = DEFAULT_LIMIT) {

    private val past = ArrayDeque<EditDocument>()
    private val future = ArrayDeque<EditDocument>()

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** デバッグ・テスト用。 */
    val depth: Int get() = past.size

    /**
     * 変更を確定する直前に、**変更前**のドキュメントを渡す。
     * 呼ぶのは「指を離した」「ダイアログで OK を押した」など操作の切れ目だけ。
     * ドラッグ中の中間状態を積むと履歴が使い物にならなくなる。
     */
    fun record(previous: EditDocument) {
        past.addLast(previous)
        while (past.size > limit) past.removeFirst()
        // 新しい操作をしたら、それまでのやり直し履歴は無効になる。
        future.clear()
    }

    /** [current] を「やり直し」側へ送り、1 つ前の状態を返す。戻せなければ null。 */
    fun undo(current: EditDocument): EditDocument? {
        val previous = past.removeLastOrNull() ?: return null
        future.addLast(current)
        return previous
    }

    /** [current] を「元に戻す」側へ戻し、1 つ先の状態を返す。進めなければ null。 */
    fun redo(current: EditDocument): EditDocument? {
        val next = future.removeLastOrNull() ?: return null
        past.addLast(current)
        return next
    }

    fun clear() {
        past.clear()
        future.clear()
    }

    private companion object {
        const val DEFAULT_LIMIT = 50
    }
}
