package com.zerotoship.foldex.sync.model

/**
 * DiffEngine が生成する実行計画の 1 単位。Executor がこれを並列に実行する。
 * 仕様書 §8-D。P6 は片方向のため、ジョブの方向に応じて Upload 系か Download 系の
 * どちらか一方しか現れない (DeleteRemote / DeleteLocal も同様)。
 */
sealed interface SyncAction {

    val path: String

    /** ローカル → リモート転送 (新規 or 更新)。 */
    data class Upload(
        override val path: String,
        val size: Long,
        val mtimeSeconds: Long,
    ) : SyncAction

    /** リモート → ローカル転送 (新規 or 更新)。 */
    data class Download(
        override val path: String,
        val size: Long,
        val mtimeSeconds: Long,
    ) : SyncAction

    /** ローカル側のファイルを削除 (片方向ダウンロード + deleteEnabled 時のみ)。 */
    data class DeleteLocal(override val path: String) : SyncAction

    /** リモート側のファイルを削除 (片方向アップロード + deleteEnabled 時のみ)。 */
    data class DeleteRemote(override val path: String) : SyncAction

    /** 両側で変更があり競合。[resolution] に従って Executor が処理する。 */
    data class Conflict(
        override val path: String,
        val local: SyncEntry,
        val remote: SyncEntry,
        val resolution: ConflictResolution,
    ) : SyncAction

    /** 何もしない (理由付き)。集計とログのために残す。 */
    data class Skip(
        override val path: String,
        val reason: SkipReason,
    ) : SyncAction
}

/** [SyncAction.Skip] の理由。 */
enum class SkipReason {
    /** 前回同期から両側とも変化なし。 */
    UNCHANGED,

    /** 削除対象だがジョブの deleteEnabled が false。 */
    DELETE_DISABLED,

    /** 片方向アップロードでリモートにのみ存在 (取り込まない)。 */
    REMOTE_ONLY,

    /** 片方向ダウンロードでローカルにのみ存在 (送らない)。 */
    LOCAL_ONLY,

    /** 競合だが ConflictPolicy が SKIP。 */
    CONFLICT_SKIPPED,
}
