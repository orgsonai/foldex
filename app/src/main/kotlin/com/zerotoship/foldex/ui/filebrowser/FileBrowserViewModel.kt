package com.zerotoship.foldex.ui.filebrowser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.core.model.FileNode
import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.core.model.Protocol
import com.zerotoship.foldex.storage.StorageProviderRouter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class FileBrowserViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: StorageProviderRouter,
) : ViewModel() {

    private val prefs = context.getSharedPreferences("foldex_browser", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(FileBrowserState())
    val state: StateFlow<FileBrowserState> = _state.asStateFlow()

    private val _snackbar = Channel<SnackbarEvent>(Channel.BUFFERED)
    val snackbarEvents = _snackbar.receiveAsFlow()

    private val _quickAccess = MutableStateFlow<List<QuickAccessEntry>>(emptyList())
    val quickAccess: StateFlow<List<QuickAccessEntry>> = _quickAccess.asStateFlow()

    init {
        val hasPerm = checkStoragePermission()
        val safRootUri = prefs.getString(KEY_SAF_ROOT, null)
        val favorites = prefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()
        _state.value = _state.value.copy(
            hasStoragePermission = hasPerm,
            hasSafRoot = safRootUri != null,
            favoriteUris = favorites,
        )
        when {
            hasPerm -> navigateTo(
                FileUri.Local(Environment.getExternalStorageDirectory().absolutePath),
                displayName = "内部ストレージ",
            )
            safRootUri != null -> navigateTo(FileUri.Saf(safRootUri), displayName = "ストレージ")
        }
        if (hasPerm) {
            viewModelScope.launch { _quickAccess.value = withContext(Dispatchers.IO) { computeQuickAccess() } }
        }
    }

    // --- Navigation ---

    fun checkStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val perm = android.Manifest.permission.READ_EXTERNAL_STORAGE
            context.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    fun onStoragePermissionGranted() {
        _state.value = _state.value.copy(hasStoragePermission = true, breadcrumbs = emptyList())
        navigateTo(
            FileUri.Local(Environment.getExternalStorageDirectory().absolutePath),
            displayName = "内部ストレージ",
        )
        viewModelScope.launch { _quickAccess.value = withContext(Dispatchers.IO) { computeQuickAccess() } }
    }

    fun onSafRootPicked(treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        prefs.edit().putString(KEY_SAF_ROOT, treeUri.toString()).apply()
        _state.value = _state.value.copy(hasSafRoot = true, breadcrumbs = emptyList())
        navigateTo(FileUri.Saf(treeUri.toString()), displayName = "ストレージ")
    }

    fun openSmbConnection(connectionId: String, displayName: String) {
        _state.value = _state.value.copy(breadcrumbs = emptyList(), selectedUris = emptySet())
        navigateTo(FileUri.Remote(Protocol.SMB, connectionId, "/"), displayName = displayName)
    }

    fun openLocalRoot() {
        _state.value = _state.value.copy(breadcrumbs = emptyList(), selectedUris = emptySet())
        navigateTo(
            FileUri.Local(Environment.getExternalStorageDirectory().absolutePath),
            displayName = "内部ストレージ",
        )
    }

    /** クイックアクセス/お気に入りからの遷移。階層をリセットして指定 URI を開く。 */
    fun open(uri: FileUri, displayName: String) {
        _state.value = _state.value.copy(breadcrumbs = emptyList(), selectedUris = emptySet())
        navigateTo(uri, displayName)
    }

    fun navigateTo(uri: FileUri, displayName: String) {
        val newCrumbs = _state.value.breadcrumbs + BreadcrumbItem(uri, displayName)
        _state.value = _state.value.copy(breadcrumbs = newCrumbs, selectedUris = emptySet())
        loadFiles(uri)
    }

    fun navigateToIndex(index: Int) {
        val crumbs = _state.value.breadcrumbs
        if (index !in crumbs.indices) return
        val newCrumbs = crumbs.take(index + 1)
        _state.value = _state.value.copy(breadcrumbs = newCrumbs, selectedUris = emptySet())
        loadFiles(newCrumbs.last().uri)
    }

    fun navigateUp(): Boolean {
        val size = _state.value.breadcrumbs.size
        if (size <= 1) return false
        navigateToIndex(size - 2)
        return true
    }

    fun setViewMode(mode: ViewMode) {
        _state.value = _state.value.copy(viewMode = mode)
    }

    fun refresh() {
        val uri = _state.value.currentUri ?: return
        loadFiles(uri)
    }

    // --- Selection ---

    fun toggleSelection(node: FileNode) {
        val key = node.uri.toStorageString()
        val current = _state.value.selectedUris
        _state.value = _state.value.copy(
            selectedUris = if (key in current) current - key else current + key
        )
    }

    fun selectAll() {
        val all = _state.value.files.map { it.uri.toStorageString() }.toSet()
        _state.value = _state.value.copy(selectedUris = all)
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selectedUris = emptySet())
    }

    // --- Clipboard (X-plore style) ---

    fun copySelected() {
        val nodes = _state.value.selectedNodes
        if (nodes.isEmpty()) return
        _state.value = _state.value.copy(clipboard = ClipboardOperation.Copy(nodes), selectedUris = emptySet())
    }

    fun cutSelected() {
        val nodes = _state.value.selectedNodes
        if (nodes.isEmpty()) return
        _state.value = _state.value.copy(clipboard = ClipboardOperation.Cut(nodes), selectedUris = emptySet())
    }

    fun clearClipboard() {
        _state.value = _state.value.copy(clipboard = null)
    }

    fun paste() {
        val clipboard = _state.value.clipboard ?: return
        val destDir = _state.value.currentUri ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            val nodes = clipboard.nodes
            val undoActions = mutableListOf<suspend () -> Unit>()
            var lastError: String? = null

            for (node in nodes) {
                val destUri = destUriFor(destDir, node.name) ?: continue
                val result = when (clipboard) {
                    is ClipboardOperation.Copy -> storage.copyWithin(node.uri, destUri)
                    is ClipboardOperation.Cut -> storage.moveWithin(node.uri, destUri)
                }
                when (result) {
                    is Result.Success -> {
                        when (clipboard) {
                            is ClipboardOperation.Copy ->
                                undoActions.add { storage.delete(destUri, recursive = true) }
                            is ClipboardOperation.Cut ->
                                undoActions.add { storage.moveWithin(destUri, node.uri) }
                        }
                    }
                    is Result.Failure -> lastError = result.error.message
                }
            }

            val newClipboard = if (clipboard is ClipboardOperation.Cut) null else clipboard
            _state.value = _state.value.copy(isLoading = false, clipboard = newClipboard)
            loadFilesSync(destDir)

            if (lastError != null) {
                emit(SnackbarEvent("エラー: $lastError"))
            } else {
                val msg = when (clipboard) {
                    is ClipboardOperation.Copy -> "${nodes.size}件コピーしました"
                    is ClipboardOperation.Cut -> "${nodes.size}件移動しました"
                }
                val undo: suspend () -> Unit = {
                    undoActions.forEach { it() }
                    loadFilesSync(destDir)
                }
                emit(SnackbarEvent(msg, actionLabel = "元に戻す", onAction = undo))
            }
        }
    }

    // --- Delete ---

    fun requestDelete() {
        val nodes = _state.value.selectedNodes
        if (nodes.isEmpty()) return
        _state.value = _state.value.copy(pendingDeleteNodes = nodes)
    }

    fun requestDeleteSingle(node: FileNode) {
        _state.value = _state.value.copy(pendingDeleteNodes = listOf(node))
    }

    fun dismissDeleteDialog() {
        _state.value = _state.value.copy(pendingDeleteNodes = emptyList())
    }

    fun confirmDelete() {
        val nodes = _state.value.pendingDeleteNodes
        if (nodes.isEmpty()) return
        _state.value = _state.value.copy(pendingDeleteNodes = emptyList(), isLoading = true)
        viewModelScope.launch {
            var lastError: String? = null
            for (node in nodes) {
                when (val r = storage.delete(node.uri, recursive = true)) {
                    is Result.Success -> Unit
                    is Result.Failure -> lastError = r.error.message
                }
            }
            val currentUri = _state.value.currentUri
            _state.value = _state.value.copy(isLoading = false, selectedUris = emptySet())
            if (currentUri != null) loadFilesSync(currentUri)
            if (lastError != null) {
                emit(SnackbarEvent("削除エラー: $lastError"))
            } else {
                emit(SnackbarEvent("${nodes.size}件削除しました"))
            }
        }
    }

    // --- Rename ---

    fun requestRename(node: FileNode) {
        _state.value = _state.value.copy(renameTarget = node, selectedUris = emptySet())
    }

    fun dismissRenameDialog() {
        _state.value = _state.value.copy(renameTarget = null)
    }

    fun confirmRename(newName: String) {
        val node = _state.value.renameTarget ?: return
        if (newName.isBlank() || newName == node.name) {
            _state.value = _state.value.copy(renameTarget = null)
            return
        }
        _state.value = _state.value.copy(renameTarget = null, isLoading = true)
        viewModelScope.launch {
            val to = newUriForRename(node.uri, newName)
            val oldName = node.name
            when (val r = storage.rename(node.uri, to)) {
                is Result.Success -> {
                    val currentUri = _state.value.currentUri
                    _state.value = _state.value.copy(isLoading = false)
                    if (currentUri != null) loadFilesSync(currentUri)
                    val undo: suspend () -> Unit = {
                        storage.rename(to, node.uri)
                        _state.value.currentUri?.let { loadFilesSync(it) }
                    }
                    emit(SnackbarEvent("「$oldName」→「$newName」に変更", "元に戻す", undo))
                }
                is Result.Failure -> {
                    _state.value = _state.value.copy(isLoading = false)
                    emit(SnackbarEvent("名前変更エラー: ${r.error.message}"))
                }
            }
        }
    }

    // --- Create folder ---

    fun showCreateFolderDialog() {
        _state.value = _state.value.copy(showCreateFolderDialog = true)
    }

    fun dismissCreateFolderDialog() {
        _state.value = _state.value.copy(showCreateFolderDialog = false)
    }

    fun createFolder(name: String) {
        if (name.isBlank()) return
        val currentUri = _state.value.currentUri ?: return
        val newUri = destUriFor(currentUri, name) ?: return
        _state.value = _state.value.copy(showCreateFolderDialog = false, isLoading = true)
        viewModelScope.launch {
            when (val r = storage.mkdir(newUri, false)) {
                is Result.Success -> {
                    loadFilesSync(currentUri)
                    _state.value = _state.value.copy(isLoading = false)
                    val undo: suspend () -> Unit = {
                        storage.delete(newUri, recursive = false)
                        loadFilesSync(currentUri)
                    }
                    emit(SnackbarEvent("「$name」を作成しました", "元に戻す", undo))
                }
                is Result.Failure -> {
                    _state.value = _state.value.copy(isLoading = false)
                    emit(SnackbarEvent("フォルダ作成エラー: ${r.error.message}"))
                }
            }
        }
    }

    // --- Search ---

    fun toggleSearch() {
        val active = !_state.value.isSearchActive
        _state.value = _state.value.copy(isSearchActive = active, searchQuery = if (!active) "" else _state.value.searchQuery)
    }

    fun setSearchQuery(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
    }

    fun closeSearch() {
        _state.value = _state.value.copy(isSearchActive = false, searchQuery = "")
    }

    // --- Favorites ---

    fun toggleFavorite(uri: FileUri) {
        val key = uri.toStorageString()
        val current = _state.value.favoriteUris
        val updated = if (key in current) current - key else current + key
        _state.value = _state.value.copy(favoriteUris = updated)
        prefs.edit().putStringSet(KEY_FAVORITES, updated).apply()
    }

    fun isFavorite(uri: FileUri): Boolean = uri.toStorageString() in _state.value.favoriteUris

    // --- Internal helpers ---

    private fun loadFiles(uri: FileUri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                _state.value = _state.value.copy(isLoading = false, files = collectFiles(uri))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message ?: "エラーが発生しました")
            }
        }
    }

    private suspend fun loadFilesSync(uri: FileUri) {
        try {
            _state.value = _state.value.copy(isLoading = false, files = collectFiles(uri))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(isLoading = false, error = e.message ?: "エラーが発生しました")
        }
    }

    // list() の terminal collect が Main で走ると大量ディレクトリで Main を塞ぐため、
    // 収集自体をバックグラウンドで行い、UI 更新だけ呼び出し元に戻す。
    private suspend fun collectFiles(uri: FileUri): List<FileNode> =
        withContext(Dispatchers.Default) {
            val files = ArrayList<FileNode>()
            storage.list(uri).collect { files.add(it) }
            files
        }

    private fun computeQuickAccess(): List<QuickAccessEntry> {
        val entries = mutableListOf<QuickAccessEntry>()
        val root = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
        if (root != null && root.isDirectory) {
            entries += QuickAccessEntry(FileUri.Local(root.absolutePath), "内部ストレージ", QuickAccessKind.INTERNAL_STORAGE)
            val standardDirs = listOf(
                Triple("Download", "ダウンロード", QuickAccessKind.DOWNLOAD),
                Triple("DCIM", "カメラ", QuickAccessKind.CAMERA),
                Triple("Pictures", "画像", QuickAccessKind.IMAGES),
                Triple("Movies", "動画", QuickAccessKind.VIDEO),
                Triple("Music", "音楽", QuickAccessKind.MUSIC),
                Triple("Documents", "ドキュメント", QuickAccessKind.DOCUMENTS),
            )
            for ((dirName, label, kind) in standardDirs) {
                val dir = File(root, dirName)
                if (dir.isDirectory) entries += QuickAccessEntry(FileUri.Local(dir.absolutePath), label, kind)
            }
        }
        // 取り外し可能ストレージ (SD カード / USB) — /storage 直下の emulated 以外のボリューム
        runCatching {
            File("/storage").listFiles()?.forEach { vol ->
                if (vol.name != "emulated" && vol.name != "self" && vol.isDirectory && vol.canRead()) {
                    entries += QuickAccessEntry(FileUri.Local(vol.absolutePath), "SDカード", QuickAccessKind.SD_CARD)
                }
            }
        }
        return entries
    }

    private fun destUriFor(dir: FileUri, name: String): FileUri? = when (dir) {
        is FileUri.Local -> FileUri.Local("${dir.absolutePath}/$name")
        is FileUri.Saf -> null // SAF destination not supported in P3
        is FileUri.Remote -> FileUri.Remote(
            protocol = dir.protocol,
            connectionId = dir.connectionId,
            path = if (dir.path.endsWith("/")) "${dir.path}$name" else "${dir.path}/$name",
        )
    }

    private fun newUriForRename(uri: FileUri, newName: String): FileUri = when (uri) {
        is FileUri.Local -> FileUri.Local("${File(uri.absolutePath).parent}/$newName")
        is FileUri.Saf -> FileUri.Saf(newName) // SAF: documentUri carries new display name by convention
        is FileUri.Remote -> {
            val parent = uri.path.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "")
            val newPath = if (parent.isEmpty()) "/$newName" else "$parent/$newName"
            FileUri.Remote(uri.protocol, uri.connectionId, newPath)
        }
    }

    private fun emit(event: SnackbarEvent) {
        _snackbar.trySend(event)
    }

    companion object {
        private const val KEY_SAF_ROOT = "saf_root_uri"
        private const val KEY_FAVORITES = "favorite_uris"
    }
}
