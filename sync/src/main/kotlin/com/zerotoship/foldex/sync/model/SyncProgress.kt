// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.sync.model

/** 同期実行中の進捗スナップショット。UI / 通知へ伝えるための値型。 */
data class SyncProgress(
    val phase: Phase,
    val totalActions: Int = 0,
    val completedActions: Int = 0,
    val currentPath: String? = null,
    val transferredBytes: Long = 0,
    val totalBytes: Long = 0,
) {
    enum class Phase {
        /** ローカル/リモートを列挙して差分を計算中。 */
        SCANNING,

        /** SyncAction を実行中。 */
        EXECUTING,

        /** 後処理 (state / lastRun の更新) 中。 */
        FINALIZING,

        /** 完了。 */
        DONE,
    }
}
