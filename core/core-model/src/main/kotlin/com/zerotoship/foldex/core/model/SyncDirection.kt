// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.model

/**
 * 同期方向。片方向 (TO_REMOTE / TO_LOCAL) と双方向 (BIDIRECTIONAL)。
 * 双方向は前回同期状態 (state DB) を使い「片側のみ変化 = 伝播 / 両側変化 = 競合 / 片側消失 = 削除伝播」で判定する。
 */
enum class SyncDirection(val wireName: String) {
    /** ローカル → リモート (アップロード/ミラー)。 */
    TO_REMOTE("to_remote"),

    /** リモート → ローカル (ダウンロード/ミラー)。 */
    TO_LOCAL("to_local"),

    /** ローカル ⇄ リモート (双方向)。 */
    BIDIRECTIONAL("bidirectional"),
    ;

    companion object {
        fun fromWireName(value: String): SyncDirection =
            entries.firstOrNull { it.wireName == value }
                ?: error("Unknown SyncDirection wire name: $value")
    }
}
