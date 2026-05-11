package com.zerotoship.foldex.core.model

/**
 * 1 ファイルの前回同期状態。仕様書 §8-E に対応するドメインモデル。
 *
 * 「ローカルにあって、リモートにない」を「追加された」のか「削除された」のか
 * 判別するために必須。サイズと mtime の組で同一性を判定する (ハッシュは取らない)。
 */
data class SyncState(
    val jobId: String,
    val path: String,
    val localSize: Long? = null,
    val localMtime: Long? = null,
    val remoteSize: Long? = null,
    val remoteMtime: Long? = null,
    val lastSyncedAt: Long,
)
