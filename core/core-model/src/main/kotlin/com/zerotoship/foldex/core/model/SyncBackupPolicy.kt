package com.zerotoship.foldex.core.model

/** delete 同期で「しきい値より大きいファイル」を削除する前にどうするか (グローバル設定)。 */
enum class SyncBackupPolicy {
    /** 毎回確認する (バックグラウンド実行時は安全側に倒して BACKUP と同じ扱い)。 */
    ASK,

    /** 無確認でバックアップしてから削除。 */
    BACKUP,

    /** 無確認でバックアップせず削除 (しきい値より小さいファイルは常にバックアップされる)。 */
    SKIP,
}
