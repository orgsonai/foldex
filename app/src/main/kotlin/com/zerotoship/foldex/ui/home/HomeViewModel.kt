// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.home

import android.content.Context
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerotoship.foldex.core.data.repo.ConnectionRepository
import com.zerotoship.foldex.core.data.repo.HomeShortcutRepository
import com.zerotoship.foldex.core.model.Connection
import com.zerotoship.foldex.core.model.home.HomeFunction
import com.zerotoship.foldex.core.model.home.HomeShortcut
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
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
 * - 並び順は [HomeShortcutRepository.orderIds] (未登録 id は末尾)。
 * - 非表示は [HomeShortcutRepository.hiddenIds] で除外し、復元用に [hiddenShortcuts] で公開。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: HomeShortcutRepository,
    private val connections: ConnectionRepository,
) : ViewModel() {

    /** 接続タブ全部の Connection (ハンバーガードロワー表示と「追加」候補に使う)。 */
    val allConnections: StateFlow<List<Connection>> = connections.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 全タイル (組み込み + カスタム) を「表示順」適用後 / 非表示適用前で返す内部用 Flow。 */
    private val allShortcuts = combine(
        repo.customShortcuts,
        connections.observeAll(),
        repo.orderIds,
        repo.labelOverrides,
    ) { custom, conns, order, overrides ->
        val byId = conns.associateBy { it.id }
        // リモートタイルは接続名と自動同期する。それ以外は labelOverrides を優先 (ユーザーのリネーム)。
        val refreshed = custom.map { sc ->
            if (sc is HomeShortcut.RemoteConnection) {
                val name = byId[sc.connectionId]?.name
                if (name != null && name != sc.label) sc.copy(label = name) else sc
            } else sc
        }
        val merged = (BUILT_IN + refreshed).map { sc ->
            val override = overrides[sc.id]
            if (override != null && override != sc.label) sc.withLabel(override) else sc
        }
        applyOrder(merged, order)
    }

    /**
     * HOME のタイル一覧 (表示分)。組み込みも含めて非表示 id は除外する。
     * 起動直後の並び替えチラつきを消すため、初期値は前回表示のキャッシュ ([cachedShortcuts])
     * を使い、実値が届くたびにキャッシュを更新する ([cacheShortcuts])。キャッシュ無し (初回) は
     * 組み込みタイルを既定順で出す。
     */
    val shortcuts: StateFlow<List<HomeShortcut>> = combine(allShortcuts, repo.hiddenIds) { all, hidden ->
        all.filter { it.id !in hidden }
    }
        .onEach { repo.cacheShortcuts(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), repo.cachedShortcuts() ?: BUILT_IN)

    /** 復元 UI 用: 非表示にされている全タイル。 */
    val hiddenShortcuts: StateFlow<List<HomeShortcut>> = combine(allShortcuts, repo.hiddenIds) { all, hidden ->
        all.filter { it.id in hidden }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addLocalFolder(label: String, path: String) {
        viewModelScope.launch { repo.addLocalFolder(label, path) }
    }

    fun addSafFolder(label: String, documentUri: String) {
        viewModelScope.launch { repo.addSafFolder(label, documentUri) }
    }

    /**
     * これまで Foldex に SAF 経由でアクセス許可を与えた tree URI 一覧を返す。
     * HOME の「追加」ダイアログで提示し、ユーザーが選択すれば 1 タップで HOME に固定できる。
     */
    fun persistedSafTrees(): List<SafTree> = runCatching {
        context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && it.uri.toString().contains("/tree/") }
            .map { perm ->
                val raw = perm.uri.toString()
                // tree URI の最後のセグメント (例: primary:Pictures, com.termux:home) を表示名候補に
                val tail = raw.substringAfterLast("/tree/")
                    .let { java.net.URLDecoder.decode(it, "UTF-8") }
                SafTree(label = tail.substringAfterLast('/').ifEmpty { tail }, documentUri = raw)
            }
            .distinctBy { it.documentUri }
    }.getOrElse { emptyList() }

    data class SafTree(val label: String, val documentUri: String)

    private fun HomeShortcut.withLabel(newLabel: String): HomeShortcut = when (this) {
        is HomeShortcut.LocalFolder -> copy(label = newLabel)
        is HomeShortcut.SafFolder -> copy(label = newLabel)
        is HomeShortcut.RemoteConnection -> copy(label = newLabel)
        is HomeShortcut.Function -> copy(label = newLabel)
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

    fun rename(id: String, newLabel: String) {
        viewModelScope.launch { repo.rename(id, newLabel) }
    }

    fun setHidden(id: String, hidden: Boolean) {
        viewModelScope.launch { repo.setHidden(id, hidden) }
    }

    /** 表示中タイルのうち id を 1 つ上 / 下に動かして並び順を保存する (アクセシビリティ用)。 */
    fun moveUp(id: String) = move(id, delta = -1)
    fun moveDown(id: String) = move(id, delta = +1)

    private fun move(id: String, delta: Int) {
        viewModelScope.launch {
            val current = shortcuts.value.map { it.id }.toMutableList()
            val idx = current.indexOf(id)
            if (idx < 0) return@launch
            val newIdx = (idx + delta).coerceIn(0, current.lastIndex)
            if (newIdx == idx) return@launch
            current.removeAt(idx)
            current.add(newIdx, id)
            // 表示中だけだと非表示分の位置情報が落ちるので、非表示タイルを末尾に保持しつつ保存。
            val hidden = hiddenShortcuts.value.map { it.id }
            repo.setOrder(current + hidden.filter { it !in current })
        }
    }

    /**
     * ドラッグ並び替え用: 表示中タイルの from インデックスを to インデックスへ移動。
     * Compose 側で楽観更新したい場合に備えて、内部状態は次の orderIds 反映で同期する。
     */
    fun moveTo(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            val current = shortcuts.value.map { it.id }.toMutableList()
            if (fromIndex !in current.indices || toIndex !in current.indices) return@launch
            if (fromIndex == toIndex) return@launch
            val id = current.removeAt(fromIndex)
            current.add(toIndex, id)
            val hidden = hiddenShortcuts.value.map { it.id }
            repo.setOrder(current + hidden.filter { it !in current })
        }
    }

    /** ドラッグ完了時に呼ぶ: 表示中タイルの完全な並び順を保存する。 */
    fun applyOrder(visibleIds: List<String>) {
        viewModelScope.launch {
            val hidden = hiddenShortcuts.value.map { it.id }
            repo.setOrder(visibleIds + hidden.filter { it !in visibleIds })
        }
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
        // 取り外し可能ストレージ (SD / OTG): /storage/XXXX-XXXX 形式の直接アクセス。
        // SAF より大幅に高速。MANAGE_EXTERNAL_STORAGE 権限があれば File API で読み書き可能。
        val seen = mutableSetOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            runCatching {
                val sm = context.getSystemService(android.os.storage.StorageManager::class.java)
                sm?.storageVolumes?.forEach { vol ->
                    if (vol.isRemovable) {
                        val dir = vol.directory
                        if (dir != null && seen.add(dir.absolutePath)) {
                            val label = runCatching { vol.getDescription(context) }.getOrNull()
                                ?: "SDカード"
                            add(PresetPath(label, dir.absolutePath, accessible = dir.canRead()))
                        }
                    }
                }
            }
        }
        File("/storage").listFiles()?.forEach { f ->
            if (f.isDirectory && f.name != "self" && f.name != "emulated" && seen.add(f.absolutePath)) {
                add(PresetPath("SD: ${f.name}", f.absolutePath, accessible = f.canRead(),
                    note = if (!f.canRead()) "全ファイルへのアクセス権限が必要" else null))
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
            HomeShortcut.Function(id = "builtin_images", label = "画像", kind = HomeFunction.ALL_IMAGES),
            HomeShortcut.Function(id = "builtin_videos", label = "動画", kind = HomeFunction.ALL_VIDEOS),
            HomeShortcut.Function(id = "builtin_trash", label = "ゴミ箱", kind = HomeFunction.TRASH),
            HomeShortcut.Function(id = "builtin_perms", label = "権限/SAF", kind = HomeFunction.PERMISSIONS),
            HomeShortcut.Function(id = "builtin_saf", label = "SAFで開く", kind = HomeFunction.SAF_PICK),
        )

        /** 保存された [order] に従って並び替え、未登録 id は元の順序のまま末尾に回す。 */
        fun applyOrder(items: List<HomeShortcut>, order: List<String>): List<HomeShortcut> {
            if (order.isEmpty()) return items
            val byId = items.associateBy { it.id }
            val seen = mutableSetOf<String>()
            val out = ArrayList<HomeShortcut>(items.size)
            for (id in order) {
                val sc = byId[id] ?: continue
                if (seen.add(id)) out.add(sc)
            }
            for (sc in items) if (sc.id !in seen) out.add(sc)
            return out
        }
    }
}
