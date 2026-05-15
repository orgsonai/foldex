package com.zerotoship.foldex.ui.filebrowser

import android.net.Uri
import com.zerotoship.foldex.core.model.filetype.Category

/** ファイルブラウザがファイルを「開く」際に画面側へ依頼する操作。Activity 起動が必要なものを表す。 */
sealed interface OpenRequest {
    /**
     * 内蔵ビューア (ViewerActivity) で開く。[localPath] はローカルの実体 (リモートはキャッシュ済み)。
     * [editable] が true なら編集→保存が元ファイルに反映される (= ローカルファイルの場合のみ)。
     * 画像など「同フォルダ内の兄弟をスワイプで切り替えたい」カテゴリは [siblings] にローカル
     * パス配列を渡す ([localPath] もこの配列の要素である必要がある)。
     */
    data class Builtin(
        val localPath: String,
        val name: String,
        val category: Category,
        val editable: Boolean,
        val siblings: List<String> = emptyList(),
        /** テキストエディタで「編集可能」として開く上限 (KB)。ユーザー設定に従う。 */
        val editableLimitKb: Int = 512,
    ) : OpenRequest

    /** 外部アプリで ACTION_VIEW する。[chooser] が true ならアプリ選択ダイアログを毎回出す。 */
    data class External(val uri: Uri, val mime: String, val name: String, val chooser: Boolean) : OpenRequest

    /** APK をインストールする (ACTION_VIEW + package-archive MIME)。 */
    data class InstallApk(val uri: Uri) : OpenRequest
}
