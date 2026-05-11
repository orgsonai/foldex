package com.zerotoship.foldex.sync.model

/** 競合 (両側で変更) を実際にどう処理するか。ConflictResolver が ConflictPolicy から決定する。 */
sealed interface ConflictResolution {

    /** ローカルの内容を採用する (片方向ダウンロードならローカルを保持、アップロードならリモートを上書き)。 */
    data object TakeLocal : ConflictResolution

    /** リモートの内容を採用する。 */
    data object TakeRemote : ConflictResolution

    /**
     * 両方残す。負けた側 ([renameSide]) の既存ファイルを [renamedPath] にリネームしてから
     * 勝った側の内容を元のパスに転送する。
     */
    data class KeepBoth(
        val renamedPath: String,
        val renameSide: ConflictSide,
    ) : ConflictResolution

    /** 何もしない (ユーザー通知のみ)。 */
    data object Skip : ConflictResolution
}

/** 競合に関わる 2 つの側。 */
enum class ConflictSide { LOCAL, REMOTE }
