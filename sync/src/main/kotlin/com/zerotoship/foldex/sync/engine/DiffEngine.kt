package com.zerotoship.foldex.sync.engine

import com.zerotoship.foldex.core.model.ConflictPolicy
import com.zerotoship.foldex.core.model.SyncDirection
import com.zerotoship.foldex.core.model.SyncState
import com.zerotoship.foldex.sync.model.ConflictResolution
import com.zerotoship.foldex.sync.model.SkipReason
import com.zerotoship.foldex.sync.model.SyncAction
import com.zerotoship.foldex.sync.model.SyncEntry

/**
 * 「現在のローカル」「現在のリモート」「前回同期状態」の 3 つから [SyncAction] のリストを組み立てる。
 * 列挙とフィルタは呼び出し側 (SyncEngine の Walker) が済ませて [Input] に渡す前提 — つまり
 * このクラス自体は副作用なしの純粋ロジックで、単体テストしやすい。仕様書 §8-C / §8-D。
 *
 * P6 は片方向のみ。方向に応じて生成されるのは Upload 系か Download 系の一方だけになる。
 * 競合 (両側で前回同期から変更) は [ConflictResolver] に委ね、その解決が「同期方向と逆向きの
 * 転送」を要求する場合 (例: TO_REMOTE で REMOTE_WINS) は [SkipReason.CONFLICT_SKIPPED] に落とす。
 *
 * @param now KEEP_BOTH のリネーム名に使う現在時刻 (epoch millis) を返す。テスト時に固定できる。
 */
class DiffEngine(
    private val now: () -> Long = System::currentTimeMillis,
) {

    data class Input(
        val direction: SyncDirection,
        val conflictPolicy: ConflictPolicy,
        val deleteEnabled: Boolean,
        /** 同期ルートからの相対パス -> 現在のローカルファイル (フィルタ済み・ファイルのみ)。 */
        val local: Map<String, SyncEntry>,
        /** 同期ルートからの相対パス -> 現在のリモートファイル (フィルタ済み・ファイルのみ)。 */
        val remote: Map<String, SyncEntry>,
        /** 相対パス -> 前回同期時の状態。空なら「初回同期」。 */
        val previous: Map<String, SyncState>,
    )

    fun computeActions(input: Input): List<SyncAction> {
        val paths = (input.local.keys + input.remote.keys).toSortedSet()
        if (input.direction == SyncDirection.BIDIRECTIONAL) {
            return paths.map { path ->
                bidirectionalActionFor(path, input.local[path], input.remote[path], input.previous[path],
                    input.conflictPolicy, input.deleteEnabled)
            }
        }
        val toRemote = input.direction == SyncDirection.TO_REMOTE
        return paths.map { path ->
            actionFor(
                path = path,
                l = input.local[path],
                r = input.remote[path],
                p = input.previous[path],
                toRemote = toRemote,
                policy = input.conflictPolicy,
                direction = input.direction,
                deleteEnabled = input.deleteEnabled,
            )
        }
    }

    // --- 双方向 ---

    private enum class SideState { SAME, NEW, MODIFIED, DELETED, NEVER }

    private fun localSideState(l: SyncEntry?, p: SyncState?): SideState = when {
        l != null && p.matchesLocal(l) -> SideState.SAME
        l != null && p.hadLocal() -> SideState.MODIFIED
        l != null -> SideState.NEW
        p.hadLocal() -> SideState.DELETED
        else -> SideState.NEVER
    }

    private fun remoteSideState(r: SyncEntry?, p: SyncState?): SideState = when {
        r != null && p.matchesRemote(r) -> SideState.SAME
        r != null && p.hadRemote() -> SideState.MODIFIED
        r != null -> SideState.NEW
        p.hadRemote() -> SideState.DELETED
        else -> SideState.NEVER
    }

    private fun bidirectionalActionFor(
        path: String,
        l: SyncEntry?,
        r: SyncEntry?,
        p: SyncState?,
        policy: ConflictPolicy,
        deleteEnabled: Boolean,
    ): SyncAction {
        val ls = localSideState(l, p)
        val rs = remoteSideState(r, p)

        // 両側に存在し内容が完全一致 → 既に同期済み
        if (l != null && r != null && sameContent(l, r)) return SyncAction.Skip(path, SkipReason.UNCHANGED)

        // 片側が削除され、もう片側は前回から無変化 → 削除を伝播
        if (rs == SideState.DELETED && ls == SideState.SAME) {
            return if (deleteEnabled) SyncAction.DeleteLocal(path) else SyncAction.Skip(path, SkipReason.DELETE_DISABLED)
        }
        if (ls == SideState.DELETED && rs == SideState.SAME) {
            return if (deleteEnabled) SyncAction.DeleteRemote(path) else SyncAction.Skip(path, SkipReason.DELETE_DISABLED)
        }
        // 両側とも存在しない (削除済み / もともと無い) → 何もしない
        if (l == null && r == null) return SyncAction.Skip(path, SkipReason.UNCHANGED)

        // 片側にのみ存在 → 存在する側を反対側へ伝播 (削除との競合は上で処理済み)
        if (l != null && r == null) return SyncAction.Upload(path, l.size, l.mtimeSeconds)
        if (r != null && l == null) return SyncAction.Download(path, r.size, r.mtimeSeconds)

        // 両側に存在し内容が違う: 片側が無変化なら変化した側を伝播
        if (ls == SideState.SAME && (rs == SideState.MODIFIED || rs == SideState.NEW)) {
            return SyncAction.Download(path, r!!.size, r.mtimeSeconds)
        }
        if (rs == SideState.SAME && (ls == SideState.MODIFIED || ls == SideState.NEW)) {
            return SyncAction.Upload(path, l!!.size, l.mtimeSeconds)
        }
        // それ以外 (両側変化 / 前回状態が無く内容が違う) → 競合
        val resolution = ConflictResolver.resolve(path, l!!, r!!, policy, SyncDirection.BIDIRECTIONAL, now())
        return if (resolution == ConflictResolution.Skip) {
            SyncAction.Skip(path, SkipReason.CONFLICT_SKIPPED)
        } else {
            SyncAction.Conflict(path, l, r, resolution)
        }
    }

    // --- 片方向 ---

    private fun actionFor(
        path: String,
        l: SyncEntry?,
        r: SyncEntry?,
        p: SyncState?,
        toRemote: Boolean,
        policy: ConflictPolicy,
        direction: SyncDirection,
        deleteEnabled: Boolean,
    ): SyncAction {
        // source / destination をジョブ方向に合わせて読み替える
        val src = if (toRemote) l else r
        val dst = if (toRemote) r else l
        val srcMatchesPrev = if (toRemote) p.matchesLocal(l) else p.matchesRemote(r)
        val dstMatchesPrev = if (toRemote) p.matchesRemote(r) else p.matchesLocal(l)
        val prevHadSrc = if (toRemote) p.hadLocal() else p.hadRemote()

        return when {
            // どちらにも無い: 起こり得ない (パスは和集合から来る)
            src == null && dst == null -> SyncAction.Skip(path, SkipReason.UNCHANGED)

            // 同期先にのみ存在 (= source から消えた or 元々 source 外)
            src == null -> when {
                prevHadSrc && deleteEnabled -> deleteDest(toRemote, path)
                prevHadSrc -> SyncAction.Skip(path, SkipReason.DELETE_DISABLED)
                else -> SyncAction.Skip(path, if (toRemote) SkipReason.REMOTE_ONLY else SkipReason.LOCAL_ONLY)
            }

            // source にのみ存在: 新規 (または同期先から消えていれば作り直す) → 転送
            dst == null -> transfer(toRemote, path, src)

            // 両側に存在
            !srcMatchesPrev && dstMatchesPrev -> transfer(toRemote, path, src) // 通常の更新
            srcMatchesPrev && dstMatchesPrev -> SyncAction.Skip(path, SkipReason.UNCHANGED)
            // 初回同期で両側が完全一致なら「既に同期済み」とみなす
            p == null && sameContent(l!!, r!!) -> SyncAction.Skip(path, SkipReason.UNCHANGED)
            // それ以外 (同期先が前回からドリフトしている、または初回で内容が違う) は競合
            else -> resolveConflict(path, l!!, r!!, policy, direction, toRemote)
        }
    }

    private fun resolveConflict(
        path: String,
        l: SyncEntry,
        r: SyncEntry,
        policy: ConflictPolicy,
        direction: SyncDirection,
        toRemote: Boolean,
    ): SyncAction {
        val resolution = ConflictResolver.resolve(path, l, r, policy, direction, now())
        val takeSource = when (resolution) {
            ConflictResolution.TakeLocal -> toRemote        // ローカル採用が「ソース採用」になるのは TO_REMOTE
            ConflictResolution.TakeRemote -> !toRemote
            is ConflictResolution.KeepBoth -> true          // 同期先をリネームしてソースを転送 → 前進可能
            ConflictResolution.Skip -> false
        }
        return if (takeSource) {
            SyncAction.Conflict(path, l, r, resolution)
        } else {
            SyncAction.Skip(path, SkipReason.CONFLICT_SKIPPED)
        }
    }

    private fun transfer(toRemote: Boolean, path: String, src: SyncEntry): SyncAction =
        if (toRemote) {
            SyncAction.Upload(path, src.size, src.mtimeSeconds)
        } else {
            SyncAction.Download(path, src.size, src.mtimeSeconds)
        }

    private fun deleteDest(toRemote: Boolean, path: String): SyncAction =
        if (toRemote) SyncAction.DeleteRemote(path) else SyncAction.DeleteLocal(path)
}

private fun sameContent(a: SyncEntry, b: SyncEntry): Boolean =
    a.size == b.size && a.mtimeSeconds == b.mtimeSeconds

private fun SyncState?.hadLocal(): Boolean = this != null && localSize != null && localMtime != null
private fun SyncState?.hadRemote(): Boolean = this != null && remoteSize != null && remoteMtime != null

/** entry が前回同期時のローカル状態と一致するか (null entry や null state は不一致扱い)。 */
private fun SyncState?.matchesLocal(entry: SyncEntry?): Boolean =
    this != null && entry != null && localSize == entry.size && localMtime == entry.mtimeSeconds

private fun SyncState?.matchesRemote(entry: SyncEntry?): Boolean =
    this != null && entry != null && remoteSize == entry.size && remoteMtime == entry.mtimeSeconds
