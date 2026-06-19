// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.sync.model

/**
 * 同期対象 1 ファイルの軽量スナップショット。DiffEngine が
 * ローカル/リモートの列挙結果を相対パスをキーにまとめる際の値型。
 *
 * @param path 同期ルートからの相対パス。区切りは常に `/`、先頭スラッシュ無し。
 * @param size バイト数。
 * @param mtimeSeconds 最終更新時刻 (エポック秒・UTC・**秒精度に丸め済み**)。
 *   FTP の精度に合わせて秒単位で比較する — 仕様書 §8-C。
 */
data class SyncEntry(
    val path: String,
    val size: Long,
    val mtimeSeconds: Long,
)
