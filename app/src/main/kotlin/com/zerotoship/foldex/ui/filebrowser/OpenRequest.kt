package com.zerotoship.foldex.ui.filebrowser

import android.net.Uri
import com.zerotoship.foldex.core.model.filetype.Category

/** ファイルブラウザがファイルを「開く」際に画面側へ依頼する操作。Activity 起動が必要なものを表す。 */
sealed interface OpenRequest {
    /** 内蔵ビューア (ViewerActivity) で開く。[localPath] はローカルの実体 (リモートはキャッシュ済み)。 */
    data class Builtin(val localPath: String, val name: String, val category: Category) : OpenRequest

    /** 外部アプリで ACTION_VIEW する。[chooser] が true ならアプリ選択ダイアログを毎回出す。 */
    data class External(val uri: Uri, val mime: String, val name: String, val chooser: Boolean) : OpenRequest

    /** APK をインストールする (ACTION_VIEW + package-archive MIME)。 */
    data class InstallApk(val uri: Uri) : OpenRequest
}
