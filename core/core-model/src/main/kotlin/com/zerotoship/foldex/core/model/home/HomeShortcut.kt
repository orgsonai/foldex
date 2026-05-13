package com.zerotoship.foldex.core.model.home

/**
 * HOME 画面のショートカット。グリッドのタイル 1 枚に相当する。
 *
 * - [LocalFolder]: ローカル絶対パスをファイルブラウザで開く。
 * - [Function]: 機能ボタン (ファイル / ゴミ箱 / サーバ / 同期 / 設定 / 権限など)。固定の組み込みタイル。
 *
 * リモートサーバへの直リンクは P7 では未対応 (Connection を解決してから FileUri 化する経路が要るため)。
 */
sealed class HomeShortcut {
    abstract val id: String
    abstract val label: String

    data class LocalFolder(
        override val id: String,
        override val label: String,
        val path: String,
    ) : HomeShortcut()

    /** 接続タブに登録済みの個別のリモートサーバを HOME に固定するためのショートカット。 */
    data class RemoteConnection(
        override val id: String,
        override val label: String,
        val connectionId: String,
    ) : HomeShortcut()

    data class Function(
        override val id: String,
        override val label: String,
        val kind: HomeFunction,
    ) : HomeShortcut()
}

/** 組み込み機能タイルの種類。 */
enum class HomeFunction {
    INTERNAL_STORAGE, // /storage/emulated/0/
    TRASH,
    SERVERS,
    SYNC,
    SETTINGS,
    PERMISSIONS,
    SAF_PICK, // SAF ツリー選択を起動 (SD カード等)
}
