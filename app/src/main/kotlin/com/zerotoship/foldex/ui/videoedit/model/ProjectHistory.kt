// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.model

/**
 * Undo / Redo。[VideoProject] のスナップショットを積むだけの実装。
 *
 * プロジェクトは映像を含まない値なので、1 手あたり数 KB。上限まで積んでも軽い。
 * スレッド安全ではない。ViewModel (メインスレッド) からのみ触ること。
 */
class ProjectHistory(private val limit: Int = DEFAULT_LIMIT) {

    private val past = ArrayDeque<VideoProject>()
    private val future = ArrayDeque<VideoProject>()

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /**
     * 変更を確定する直前に、**変更前**のプロジェクトを渡す。
     * 指を離した・スライダーを離したなどの操作の切れ目だけで呼ぶ。
     */
    fun record(previous: VideoProject) {
        past.addLast(previous)
        while (past.size > limit) past.removeFirst()
        // 新しい操作をしたら、それまでのやり直し履歴は無効になる。
        future.clear()
    }

    /** [current] を「やり直し」側へ送り、1 つ前の状態を返す。戻せなければ null。 */
    fun undo(current: VideoProject): VideoProject? {
        val previous = past.removeLastOrNull() ?: return null
        future.addLast(current)
        return previous
    }

    /** [current] を「元に戻す」側へ戻し、1 つ先の状態を返す。進めなければ null。 */
    fun redo(current: VideoProject): VideoProject? {
        val next = future.removeLastOrNull() ?: return null
        past.addLast(current)
        return next
    }

    private companion object {
        const val DEFAULT_LIMIT = 100
    }
}
