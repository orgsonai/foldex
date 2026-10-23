// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.home

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerotoship.foldex.R
import com.zerotoship.foldex.core.data.repo.ConnectionRepository
import com.zerotoship.foldex.core.data.repo.HomeShortcutRepository
import com.zerotoship.foldex.core.data.repo.OpenWithMode
import com.zerotoship.foldex.core.data.repo.OpenWithRepository
import com.zerotoship.foldex.core.data.repo.SettingsRepository
import com.zerotoship.foldex.core.model.Connection
import com.zerotoship.foldex.core.model.home.FileOpenTarget
import com.zerotoship.foldex.core.model.home.HomeFunction
import com.zerotoship.foldex.core.model.home.HomeShortcut
import com.zerotoship.foldex.core.model.filetype.Category
import com.zerotoship.foldex.core.model.filetype.FileTypeRegistry
import com.zerotoship.foldex.ui.archive.ArchiveExplorerActivity
import com.zerotoship.foldex.ui.filebrowser.ZipOps
import com.zerotoship.foldex.ui.viewer.ViewerActivity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    private val openWithRepo: OpenWithRepository,
    private val settingsRepo: SettingsRepository,
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
        val merged = (builtInShortcuts() + refreshed).map { sc ->
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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), repo.cachedShortcuts() ?: builtInShortcuts())

    /** 復元 UI 用: 非表示にされている全タイル。 */
    val hiddenShortcuts: StateFlow<List<HomeShortcut>> = combine(allShortcuts, repo.hiddenIds) { all, hidden ->
        all.filter { it.id in hidden }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** パスがファイルならファイルのタイル、それ以外はフォルダのタイルとして追加する。 */
    fun addLocalFolder(label: String, path: String) {
        viewModelScope.launch {
            if (File(path).isFile) repo.addLocalFile(label, path) else repo.addLocalFolder(label, path)
        }
    }

    fun setFileOpenTarget(id: String, target: FileOpenTarget) {
        viewModelScope.launch { repo.setFileOpenTarget(id, target) }
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

    // --- HOME に置いたファイル ---

    /**
     * タイルのファイルを開く Intent を作る。ファイルが無くなっていれば null。
     *
     * 「自動」はファイル一覧でタップしたときと同じ順で決める
     * (APK はインストール、ZIP は中身の閲覧、内蔵ビューアがあれば内蔵、無ければアプリを選ぶ)。
     */
    suspend fun openIntentFor(sc: HomeShortcut.LocalFile): Intent? {
        val file = File(sc.path)
        if (!file.isFile) return null
        val category = categoryOf(file)
        val external = { chooser: Boolean ->
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileProviderUri(file), mimeOf(file))
                // 外部エディタが保存できるよう WRITE も付ける (ファイル一覧と同じ)。
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            if (chooser) Intent.createChooser(view, file.name) else view
        }
        val builtIn = {
            when {
                category == Category.ARCHIVE && ZipOps.isLikelyZip(file.name) ->
                    ArchiveExplorerActivity.intent(context = context, localZipPath = file.absolutePath, name = file.name)
                category.hasBuiltInViewer -> ViewerActivity.intent(
                    context = context,
                    localPath = file.absolutePath,
                    name = file.name,
                    category = category,
                    editable = true,
                    editableLimitKb = settingsRepo.loaded.value?.editorEditableLimitKb ?: 512,
                )
                else -> external(true)
            }
        }
        return when (val target = sc.openWith) {
            FileOpenTarget.BuiltIn -> builtIn()
            FileOpenTarget.Ask -> external(true)
            is FileOpenTarget.App -> external(false).setPackage(target.packageName)
            FileOpenTarget.Auto -> {
                val ext = file.name.substringAfterLast('.', "").lowercase()
                when (openWithRepo.overrides.first()[ext] ?: OpenWithMode.DEFAULT) {
                    OpenWithMode.EXTERNAL -> external(false)
                    OpenWithMode.ASK -> external(true)
                    else -> if (category == Category.APK) {
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(fileProviderUri(file), "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    } else {
                        builtIn()
                    }
                }
            }
        }
    }

    /** 内蔵ビューアで開けるファイルか (開き方の選択肢に「内蔵ビューア」を出すかどうか)。 */
    fun hasBuiltInViewer(sc: HomeShortcut.LocalFile): Boolean {
        val file = File(sc.path)
        val category = categoryOf(file)
        return category.hasBuiltInViewer || (category == Category.ARCHIVE && ZipOps.isLikelyZip(file.name))
    }

    /** このファイルを開ける端末のアプリ。Foldex 自身は「内蔵ビューア」で選べるので除く。 */
    fun appsFor(sc: HomeShortcut.LocalFile): List<AppChoice> = runCatching {
        val file = File(sc.path)
        val pm = context.packageManager
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(fileProviderUri(file), mimeOf(file))
        pm.queryIntentActivities(view, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
            .map { AppChoice(it, appLabel(it)) }
            .sortedBy { it.label.lowercase() }
    }.getOrElse { emptyList() }

    /** アプリの表示名。アンインストール済みなどで引けなければパッケージ名のまま。 */
    fun appLabel(packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrElse { packageName }

    data class AppChoice(val packageName: String, val label: String)

    private fun categoryOf(file: File): Category {
        val category = FileTypeRegistry.categorize(file.name)
        // 拡張子の無い空ファイルは、ファイル一覧と同じく書きかけのテキストとして扱う。
        return if (category == Category.UNKNOWN && file.length() == 0L) Category.TEXT else category
    }

    private fun mimeOf(file: File): String = FileTypeRegistry.mimeTypeFor(file.name) ?: "*/*"

    private fun fileProviderUri(file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun HomeShortcut.withLabel(newLabel: String): HomeShortcut = when (this) {
        is HomeShortcut.LocalFolder -> copy(label = newLabel)
        is HomeShortcut.LocalFile -> copy(label = newLabel)
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
        if (ext.exists()) add(PresetPath(context.getString(R.string.internal_storage), ext.absolutePath, accessible = ext.canRead()))
        File(ext, "Download").let { if (it.exists()) add(PresetPath(context.getString(R.string.home_downloads), it.absolutePath, it.canRead())) }
        File(ext, "DCIM").let { if (it.exists()) add(PresetPath("DCIM", it.absolutePath, it.canRead())) }
        File(ext, "Pictures").let { if (it.exists()) add(PresetPath(context.getString(R.string.home_images), it.absolutePath, it.canRead())) }
        File(ext, "Movies").let { if (it.exists()) add(PresetPath(context.getString(R.string.home_videos), it.absolutePath, it.canRead())) }
        File(ext, "Music").let { if (it.exists()) add(PresetPath(context.getString(R.string.home_music), it.absolutePath, it.canRead())) }
        val termux = File("/data/data/com.termux/files/home")
        add(PresetPath(context.getString(R.string.home_termux_home), termux.absolutePath, accessible = termux.canRead(), note = context.getString(R.string.home_termux_note)))
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
                                ?: context.getString(R.string.home_sd_card)
                            add(PresetPath(label, dir.absolutePath, accessible = dir.canRead()))
                        }
                    }
                }
            }
        }
        File("/storage").listFiles()?.forEach { f ->
            if (f.isDirectory && f.name != "self" && f.name != "emulated" && seen.add(f.absolutePath)) {
                add(PresetPath("SD: ${f.name}", f.absolutePath, accessible = f.canRead(),
                    note = if (!f.canRead()) context.getString(R.string.home_all_files_required) else null))
            }
        }
    }

    data class PresetPath(
        val label: String,
        val path: String,
        val accessible: Boolean,
        val note: String? = null,
    )

    private fun builtInShortcuts(): List<HomeShortcut> = listOf(
        HomeShortcut.Function(id = "builtin_files", label = context.getString(R.string.home_files), kind = HomeFunction.INTERNAL_STORAGE),
        HomeShortcut.Function(id = "builtin_images", label = context.getString(R.string.home_images), kind = HomeFunction.ALL_IMAGES),
        HomeShortcut.Function(id = "builtin_videos", label = context.getString(R.string.home_videos), kind = HomeFunction.ALL_VIDEOS),
        HomeShortcut.Function(id = "builtin_trash", label = context.getString(R.string.home_trash), kind = HomeFunction.TRASH),
        HomeShortcut.Function(id = "builtin_perms", label = context.getString(R.string.home_permissions_saf), kind = HomeFunction.PERMISSIONS),
        HomeShortcut.Function(id = "builtin_saf", label = context.getString(R.string.home_open_with_saf), kind = HomeFunction.SAF_PICK),
    )

    private companion object {
        // タブから到達できる機能 (サーバー/同期/設定) は HOME には置かない。
        // HOME はタイルのランチャー的役割で、Files / ゴミ箱 / 権限 / SAF を提供する。
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
