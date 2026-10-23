// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.model.home

/**
 * HOME 画面のショートカット。グリッドのタイル 1 枚に相当する。
 *
 * - [LocalFolder]: ローカル絶対パスをファイルブラウザで開く。
 * - [LocalFile]: よく使うファイルを、フォルダをたどらずに直接開く。開き方はタイルごとに選べる。
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

    data class LocalFile(
        override val id: String,
        override val label: String,
        val path: String,
        val openWith: FileOpenTarget = FileOpenTarget.Auto,
    ) : HomeShortcut()

    /**
     * SAF tree document URI を HOME に固定する (Termux, SD カードのような
     * Foldex の UID から直接読めないが SAF 経由ならアクセスできる領域用)。
     */
    data class SafFolder(
        override val id: String,
        override val label: String,
        val documentUri: String,
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

/** HOME に置いたファイルの開き方。 */
sealed class FileOpenTarget {
    /** ファイル一覧でタップしたときと同じ (設定「開き方」の拡張子ごとの指定に従う)。 */
    data object Auto : FileOpenTarget()

    /** 内蔵ビューアで開く。 */
    data object BuiltIn : FileOpenTarget()

    /** 毎回アプリを選ぶ。 */
    data object Ask : FileOpenTarget()

    /** 決めたアプリで開く。 */
    data class App(val packageName: String) : FileOpenTarget()

    /** 保存用の文字列。[parse] で戻す。 */
    fun encode(): String = when (this) {
        Auto -> "auto"
        BuiltIn -> "builtin"
        Ask -> "ask"
        is App -> "app:$packageName"
    }

    companion object {
        fun parse(raw: String?): FileOpenTarget = when {
            raw == "builtin" -> BuiltIn
            raw == "ask" -> Ask
            raw != null && raw.startsWith("app:") && raw.length > 4 -> App(raw.removePrefix("app:"))
            else -> Auto
        }
    }
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
    ALL_IMAGES, // MediaStore.Images で端末内の画像を横断表示
    ALL_VIDEOS, // MediaStore.Video で端末内の動画を横断表示
}
