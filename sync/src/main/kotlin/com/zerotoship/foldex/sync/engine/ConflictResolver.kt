// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.sync.engine

import com.zerotoship.foldex.core.model.ConflictPolicy
import com.zerotoship.foldex.core.model.SyncDirection
import com.zerotoship.foldex.sync.model.ConflictResolution
import com.zerotoship.foldex.sync.model.ConflictSide
import com.zerotoship.foldex.sync.model.SyncEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 「両側で変更があった」状態を [ConflictPolicy] に従って具体的な処理に落とす。
 * DiffEngine が競合を検出するたびにこれを呼ぶ — 仕様書 §8-F。
 *
 * 片方向同期では「勝った側 → 負けた側」へ転送するだけなので、負けた側 (= 同期先) が
 * どちらかは [SyncDirection] で決まる:
 * - [SyncDirection.TO_REMOTE] … 同期先 = リモート
 * - [SyncDirection.TO_LOCAL]  … 同期先 = ローカル
 */
object ConflictResolver {

    fun resolve(
        path: String,
        local: SyncEntry,
        remote: SyncEntry,
        policy: ConflictPolicy,
        direction: SyncDirection,
        nowMillis: Long = System.currentTimeMillis(),
    ): ConflictResolution {
        // 競合の「敗者側」= リネームされる側 / 同 mtime 時に上書きされる側。
        // 双方向ではどちらが敗者か固有には決まらないので、ローカルを優先 (= リモート側を敗者扱い) とする。
        val destinationSide = when (direction) {
            SyncDirection.TO_REMOTE, SyncDirection.BIDIRECTIONAL -> ConflictSide.REMOTE
            SyncDirection.TO_LOCAL -> ConflictSide.LOCAL
        }
        return when (policy) {
            ConflictPolicy.LOCAL_WINS -> ConflictResolution.TakeLocal
            ConflictPolicy.REMOTE_WINS -> ConflictResolution.TakeRemote
            ConflictPolicy.SKIP -> ConflictResolution.Skip

            ConflictPolicy.NEWER_WINS -> when {
                local.mtimeSeconds > remote.mtimeSeconds -> ConflictResolution.TakeLocal
                remote.mtimeSeconds > local.mtimeSeconds -> ConflictResolution.TakeRemote
                // 同 mtime: 方向のソース側を優先 (= 同期先を上書き)
                else -> if (destinationSide == ConflictSide.REMOTE) {
                    ConflictResolution.TakeLocal
                } else {
                    ConflictResolution.TakeRemote
                }
            }

            ConflictPolicy.KEEP_BOTH -> ConflictResolution.KeepBoth(
                renamedPath = conflictRenamedPath(path, nowMillis),
                renameSide = destinationSide,
            )
        }
    }
}

private val CONFLICT_TS_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss")

/**
 * `dir/name.ext` → `dir/name (conflict 2026-05-11 14-30-00).ext` を生成する。
 * 拡張子が無ければ後ろにそのまま付ける。ディレクトリ部分は保持する。
 * 仕様書 §8-F のリネーム規則。
 */
internal fun conflictRenamedPath(path: String, timestampMillis: Long): String {
    val label = CONFLICT_TS_FORMATTER.format(
        Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()),
    )
    return conflictRenamedPath(path, "(conflict $label)")
}

internal fun conflictRenamedPath(path: String, suffix: String): String {
    val slash = path.lastIndexOf('/')
    val dir = if (slash >= 0) path.substring(0, slash + 1) else ""
    val name = if (slash >= 0) path.substring(slash + 1) else path
    val dot = name.lastIndexOf('.')
    return if (dot > 0) {
        val base = name.substring(0, dot)
        val ext = name.substring(dot) // ".ext" を含む
        "$dir$base $suffix$ext"
    } else {
        "$dir$name $suffix"
    }
}
