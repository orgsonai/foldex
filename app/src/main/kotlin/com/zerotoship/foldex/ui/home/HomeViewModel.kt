package com.zerotoship.foldex.ui.home

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerotoship.foldex.core.data.repo.ConnectionRepository
import com.zerotoship.foldex.core.data.repo.HomeShortcutRepository
import com.zerotoship.foldex.core.model.Connection
import com.zerotoship.foldex.core.model.home.HomeFunction
import com.zerotoship.foldex.core.model.home.HomeShortcut
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * HOME 画面の表示用 ViewModel。
 *
 * - 表示するタイルは「組み込み (固定)」+「ユーザー追加 (DataStore)」の合成。
 * - リモート接続ショートカットの label は最新の Connection.name に同期し直す
 *   (接続をリネームした際に HOME が古い名前を出さないようにするため)。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: HomeShortcutRepository,
    private val connections: ConnectionRepository,
) : ViewModel() {

    /** 接続タブ全部の Connection (ハンバーガードロワー表示と「追加」候補に使う)。 */
    val allConnections: StateFlow<List<Connection>> = connections.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** HOME のタイル一覧 (組み込み + カスタム)。リモートはコネクション名を最新化する。 */
    val shortcuts: StateFlow<List<HomeShortcut>> = combine(
        repo.customShortcuts,
        connections.observeAll(),
    ) { custom, conns ->
        val byId = conns.associateBy { it.id }
        val refreshed = custom.map { sc ->
            if (sc is HomeShortcut.RemoteConnection) {
                val name = byId[sc.connectionId]?.name
                if (name != null && name != sc.label) sc.copy(label = name) else sc
            } else sc
        }
        BUILT_IN + refreshed
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BUILT_IN)

    fun addLocalFolder(label: String, path: String) {
        viewModelScope.launch { repo.addLocalFolder(label, path) }
    }

    fun addRemoteConnection(connectionId: String) {
        viewModelScope.launch {
            val name = allConnections.value.firstOrNull { it.id == connectionId }?.name ?: connectionId
            repo.addRemoteConnection(name, connectionId)
        }
    }

    fun remove(id: String) {
        viewModelScope.launch { repo.remove(id) }
    }

    /**
     * Add ダイアログで提示する候補パス。実機で実在する/読めるものだけ返す。
     * Termux ホーム (`/data/data/com.termux/files/home`) は通常 Foldex から読めない (サンドボックス)
     * ため、提示しつつ「権限が必要」ラベルを付ける。
     */
    fun candidatePresets(): List<PresetPath> = buildList {
        val ext = Environment.getExternalStorageDirectory()
        if (ext.exists()) add(PresetPath("内部ストレージ", ext.absolutePath, accessible = ext.canRead()))
        File(ext, "Download").let { if (it.exists()) add(PresetPath("ダウンロード", it.absolutePath, it.canRead())) }
        File(ext, "DCIM").let { if (it.exists()) add(PresetPath("DCIM", it.absolutePath, it.canRead())) }
        File(ext, "Pictures").let { if (it.exists()) add(PresetPath("画像", it.absolutePath, it.canRead())) }
        File(ext, "Movies").let { if (it.exists()) add(PresetPath("動画", it.absolutePath, it.canRead())) }
        File(ext, "Music").let { if (it.exists()) add(PresetPath("音楽", it.absolutePath, it.canRead())) }
        val termux = File("/data/data/com.termux/files/home")
        add(PresetPath("Termux ホーム", termux.absolutePath, accessible = termux.canRead(), note = "別アプリの領域。root か Termux 側の共有設定が必要。"))
        File("/storage").listFiles()?.forEach { f ->
            if (f.isDirectory && f.name != "self" && f.name != "emulated") {
                add(PresetPath("SD: ${f.name}", f.absolutePath, accessible = f.canRead(), note = "SAF 経由のアクセスを推奨"))
            }
        }
    }

    data class PresetPath(
        val label: String,
        val path: String,
        val accessible: Boolean,
        val note: String? = null,
    )

    private companion object {
        // タブから到達できる機能 (サーバー/同期/設定) は HOME には置かない。
        // HOME はタイルのランチャー的役割で、Files / ゴミ箱 / 権限 / SAF を提供する。
        val BUILT_IN: List<HomeShortcut> = listOf(
            HomeShortcut.Function(id = "builtin_files", label = "ファイル", kind = HomeFunction.INTERNAL_STORAGE),
            HomeShortcut.Function(id = "builtin_trash", label = "ゴミ箱", kind = HomeFunction.TRASH),
            HomeShortcut.Function(id = "builtin_perms", label = "権限/SAF", kind = HomeFunction.PERMISSIONS),
            HomeShortcut.Function(id = "builtin_saf", label = "SAFで開く", kind = HomeFunction.SAF_PICK),
        )
    }
}
