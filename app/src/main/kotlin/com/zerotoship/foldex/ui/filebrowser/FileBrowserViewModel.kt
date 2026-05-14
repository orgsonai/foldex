package com.zerotoship.foldex.ui.filebrowser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.core.model.FileNode
import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.core.model.NodeType
import com.zerotoship.foldex.core.model.Protocol
import com.zerotoship.foldex.core.data.repo.OpenWithMode
import com.zerotoship.foldex.core.data.repo.OpenWithRepository
import com.zerotoship.foldex.core.data.repo.SettingsRepository
import com.zerotoship.foldex.core.data.repo.TrashRepository
import com.zerotoship.foldex.core.model.DeleteBehavior
import com.zerotoship.foldex.core.model.filetype.Category
import com.zerotoship.foldex.core.model.filetype.FileTypeRegistry
import com.zerotoship.foldex.storage.StorageProviderRouter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class FileBrowserViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: StorageProviderRouter,
    private val settingsRepo: SettingsRepository,
    private val openWithRepo: OpenWithRepository,
    private val trashRepo: TrashRepository,
) : ViewModel() {

    private val prefs = context.getSharedPreferences("foldex_browser", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(FileBrowserState())
    val state: StateFlow<FileBrowserState> = _state.asStateFlow()

    private val _snackbar = Channel<SnackbarEvent>(Channel.BUFFERED)
    val snackbarEvents = _snackbar.receiveAsFlow()

    private val _openRequests = Channel<OpenRequest>(Channel.BUFFERED)
    val openRequests = _openRequests.receiveAsFlow()

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
        // 起動時の navigateTo: 前回開いていたローカルフォルダがあればそれを優先 (まだ存在する場合のみ)。
        // リモートは復元しない (起動経路でネットワークに当たらせない)。
        val lastLocal = prefs.getString(KEY_LAST_LOCAL_PATH, null)
            ?.let { path -> File(path).takeIf { it.isDirectory } }
        when {
            hasPerm && lastLocal != null -> navigateTo(
                FileUri.Local(lastLocal.absolutePath),
                displayName = displayNameForLocal(lastLocal),
            )
            hasPerm -> navigateTo(
                FileUri.Local(Environment.getExternalStorageDirectory().absolutePath),
                displayName = "内部ストレージ",
            )
            safRootUri != null -> navigateTo(FileUri.Saf(safRootUri), displayName = "ストレージ")
        }
        if (hasPerm) {
            viewModelScope.launch { _quickAccess.value = withContext(Dispatchers.IO) { computeQuickAccess() } }
        }
        viewModelScope.launch {
            settingsRepo.settings.collect { s ->
                _state.value = _state.value.copy(
                    showExtensionBadge = s.showExtensionBadge,
                    deleteBehavior = s.deleteBehavior,
                    confirmBeforeDelete = s.confirmBeforeDelete,
                )
            }
        }
        // ゴミ箱の自動掃除は起動経路の I/O 競合を避けるため少し遅らせる。
        viewModelScope.launch {
            delay(2_000)
            val days = settingsRepo.settings.first().trashRetentionDays
            runCatching { trashRepo.purgeOlderThan(days) }
        }
    }

    private fun displayNameForLocal(file: File): String {
        val root = runCatching { Environment.getExternalStorageDirectory().absolutePath }.getOrNull()
        if (root != null && file.absolutePath == root) return "内部ストレージ"
        return file.name.ifEmpty { file.absolutePath }
    }

    private fun saveLastLocalPath(uri: FileUri) {
        val path = (uri as? FileUri.Local)?.absolutePath ?: return
        prefs.edit().putString(KEY_LAST_LOCAL_PATH, path).apply()
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
        _state.value = _state.value.copy(
            breadcrumbs = newCrumbs,
            selectedUris = emptySet(),
            viewMode = loadViewModeFor(uri),
        )
        saveLastLocalPath(uri)
        loadFiles(uri)
    }

    fun navigateToIndex(index: Int) {
        val crumbs = _state.value.breadcrumbs
        if (index !in crumbs.indices) return
        val newCrumbs = crumbs.take(index + 1)
        val target = newCrumbs.last().uri
        _state.value = _state.value.copy(
            breadcrumbs = newCrumbs,
            selectedUris = emptySet(),
            viewMode = loadViewModeFor(target),
        )
        saveLastLocalPath(target)
        loadFiles(target)
    }

    fun navigateUp(): Boolean {
        val size = _state.value.breadcrumbs.size
        if (size <= 1) return false
        navigateToIndex(size - 2)
        return true
    }

    fun setViewMode(mode: ViewMode) {
        val uri = _state.value.currentUri
        _state.value = _state.value.copy(viewMode = mode, pendingApplyViewModeToSubtree = mode)
        if (uri != null) saveViewMode(uri, mode, applyToSubtree = false)
    }

    /** ビューモード変更直後の「配下にも適用?」確認ダイアログの応答を反映する。 */
    fun applyViewModeToSubtree(apply: Boolean) {
        val pending = _state.value.pendingApplyViewModeToSubtree ?: return
        val uri = _state.value.currentUri
        if (uri != null && apply) saveViewMode(uri, pending, applyToSubtree = true)
        _state.value = _state.value.copy(pendingApplyViewModeToSubtree = null)
    }

    /** [navigateTo] / [open] からも呼ぶ、対象 URI に登録済みビューモードがあればロードする。 */
    private fun loadViewModeFor(uri: FileUri): ViewMode {
        prefs.getString(viewModeKey(uri), null)?.let { return parseModeEntry(it).first }
        var parent = parentUri(uri)
        while (parent != null) {
            prefs.getString(viewModeKey(parent), null)?.let {
                val (mode, subtree) = parseModeEntry(it)
                if (subtree) return mode
            }
            parent = parentUri(parent)
        }
        return ViewMode.LIST
    }

    private fun saveViewMode(uri: FileUri, mode: ViewMode, applyToSubtree: Boolean) {
        prefs.edit().putString(viewModeKey(uri), "${mode.name}|$applyToSubtree").apply()
    }

    private fun viewModeKey(uri: FileUri): String = "viewMode_" + when (uri) {
        is FileUri.Local -> "local:${uri.absolutePath}"
        is FileUri.Remote -> "remote:${uri.protocol.name}:${uri.connectionId}:${uri.path}"
        is FileUri.Saf -> "saf:${uri.documentUri}"
    }

    private fun parentUri(uri: FileUri): FileUri? = when (uri) {
        is FileUri.Local -> java.io.File(uri.absolutePath).parentFile?.absolutePath?.let { FileUri.Local(it) }
        is FileUri.Remote -> {
            if (uri.path == "/" || uri.path.isEmpty()) null
            else FileUri.Remote(uri.protocol, uri.connectionId, uri.path.substringBeforeLast('/').ifEmpty { "/" })
        }
        is FileUri.Saf -> null
    }

    private fun parseModeEntry(raw: String): Pair<ViewMode, Boolean> {
        val parts = raw.split('|')
        val mode = runCatching { ViewMode.valueOf(parts[0]) }.getOrDefault(ViewMode.LIST)
        val subtree = parts.getOrNull(1)?.toBoolean() ?: false
        return mode to subtree
    }

    fun refresh() {
        val uri = _state.value.currentUri ?: return
        loadFiles(uri)
    }

    // --- 一覧アイテムのタップ / 長押し ---
    // 画面側からは安定した関数参照として渡すため、選択モード分岐はここで行う。

    fun onItemClick(node: FileNode) {
        when {
            _state.value.isSelectionMode -> toggleSelection(node)
            node.type == NodeType.DIRECTORY -> navigateTo(node.uri, node.name)
            else -> openFile(node)
        }
    }

    fun onItemLongClick(node: FileNode) = toggleSelection(node)

    // --- Open file (tap) ---

    /**
     * ファイルをタップしたときの「開く」処理。
     * - APK: インストール起動
     * - 内蔵ビューア対応 (画像/テキスト/Markdown/HTML/音声): ViewerActivity
     * - それ以外: 外部アプリで ACTION_VIEW
     * リモートファイルは一旦キャッシュへダウンロードしてからローカル実体として扱う。
     */
    fun openFile(node: FileNode) {
        if (node.type != NodeType.FILE) return
        val initialCategory = FileTypeRegistry.categorize(node.name)
        val ext = node.name.substringAfterLast('.', "").lowercase()
        viewModelScope.launch {
            val mode = openWithRepo.overrides.first()[ext] ?: OpenWithMode.DEFAULT
            val localFile = resolveLocalFile(node) ?: return@launch
            // 拡張子なし / 不明 のときは先頭バイトを覗いて、テキストっぽければ TEXT 扱いにする
            // (例: `Dockerfile.bak`, `LICENSE`, `Makefile`, シェルスクリプトでも shebang 始まりだが
            //   拡張子なしのもの)。バイナリ誤判定を避けるため null バイト混入 / 非印字率で判定。
            val category = if (initialCategory == Category.UNKNOWN && localFile.isFile) {
                if (looksLikeText(localFile)) Category.TEXT else initialCategory
            } else initialCategory
            fun external(chooser: Boolean) = OpenRequest.External(
                uri = fileProviderUri(localFile),
                mime = FileTypeRegistry.mimeTypeFor(node.name) ?: "*/*",
                name = node.name,
                chooser = chooser,
            )
            val request = when {
                category == Category.APK -> OpenRequest.InstallApk(fileProviderUri(localFile))
                mode == OpenWithMode.EXTERNAL -> external(chooser = false)
                mode == OpenWithMode.ASK -> external(chooser = true)
                // DEFAULT / BUILTIN: 内蔵対応があれば内蔵、なければ外部アプリ選択
                category.hasBuiltInViewer ->
                    OpenRequest.Builtin(
                        localPath = localFile.absolutePath,
                        name = node.name,
                        category = category,
                        // ローカル + リモートともに編集可能にする (リモートはキャッシュに編集 →
                        // FileBrowser に戻ったタイミングで [checkPendingUploads] が変更を検出して
                        // 元のリモートにアップロードバックする)。SAF は未対応。
                        editable = node.uri !is FileUri.Saf,
                        // 画像はスワイプで前後の画像へ遷移できるよう、同フォルダの兄弟画像を集める
                        // (HANDOFF §10-C: 「隣の画像へスワイプ」)。ローカルのみ対応。
                        siblings = if (category == Category.IMAGE && node.uri is FileUri.Local) {
                            collectImageSiblings(localFile)
                        } else emptyList(),
                    )
                else -> external(chooser = true)
            }
            _openRequests.send(request)
        }
    }

    /** ローカルの実体ファイルを返す。リモートはキャッシュへDLする。失敗時は snackbar を出して null。 */
    private suspend fun resolveLocalFile(node: FileNode): File? = when (val u = node.uri) {
        is FileUri.Local -> File(u.absolutePath)
        is FileUri.Saf -> { emit(SnackbarEvent("この場所のファイルを開く処理は未対応です")); null }
        is FileUri.Remote -> downloadToCache(node)
    }

    /**
     * 外部アプリ or 内蔵エディタに渡したキャッシュファイル → 元のリモート URI。
     * 編集が終わってユーザーが Foldex に戻ったとき [checkPendingUploads] で更新を検出して
     * アップロードバックする。
     */
    private data class PendingRemoteEdit(
        val remoteUri: FileUri.Remote,
        val mtimeAtOpen: Long,
    )
    private val pendingRemoteEdits = mutableMapOf<String, PendingRemoteEdit>()

    private suspend fun downloadToCache(node: FileNode): File? {
        emit(SnackbarEvent("ダウンロード中…"))
        val dir = File(context.cacheDir, "opened").apply { mkdirs() }
        val safeName = node.name.replace(Regex("[^A-Za-z0-9._\\-]"), "_").ifEmpty { "file" }
        val out = File(dir, "${node.uri.toStorageString().hashCode().toUInt()}_$safeName")
        return when (val r = storage.openInput(node.uri)) {
            is Result.Success -> withContext(Dispatchers.IO) {
                runCatching {
                    r.value.use { input -> out.outputStream().use { input.copyTo(it) } }
                    // 編集後のアップロードバック用に DL 直後の mtime を控える。
                    val remote = node.uri as? FileUri.Remote
                    if (remote != null) {
                        pendingRemoteEdits[out.absolutePath] = PendingRemoteEdit(remote, out.lastModified())
                    }
                    out
                }.getOrElse { emit(SnackbarEvent("ダウンロード失敗: ${it.message}")); null }
            }
            is Result.Failure -> { emit(SnackbarEvent("ダウンロード失敗: ${r.error.message}")); null }
        }
    }

    /**
     * 外部アプリ/内蔵エディタから戻ってきたタイミングで、キャッシュファイルが
     * DL 時より新しければ元のリモートにアップロードバックする。
     */
    fun checkPendingUploads() {
        if (pendingRemoteEdits.isEmpty()) return
        viewModelScope.launch {
            val snapshot = pendingRemoteEdits.toMap()
            var uploaded = 0
            var failed = 0
            for ((path, info) in snapshot) {
                val f = File(path)
                if (!f.exists()) {
                    pendingRemoteEdits.remove(path)
                    continue
                }
                if (f.lastModified() <= info.mtimeAtOpen) continue // 編集されていない
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        when (val r = storage.openOutput(info.remoteUri, com.zerotoship.foldex.core.model.WriteMode.OVERWRITE)) {
                            is Result.Success -> r.value.use { out ->
                                f.inputStream().use { it.copyTo(out) }
                            }
                            is Result.Failure -> error(r.error.message ?: "openOutput failed")
                        }
                        true
                    }.getOrElse { false }
                }
                if (ok) {
                    uploaded++
                    pendingRemoteEdits[path] = info.copy(mtimeAtOpen = f.lastModified())
                } else {
                    failed++
                }
            }
            if (uploaded > 0 || failed > 0) {
                val msg = buildString {
                    if (uploaded > 0) append("${uploaded} 件をリモートに保存しました")
                    if (failed > 0) {
                        if (uploaded > 0) append(" / ")
                        append("${failed} 件は失敗")
                    }
                }
                emit(SnackbarEvent(msg))
                val cur = _state.value.currentUri
                if (cur != null) loadFilesSync(cur)
            }
        }
    }

    private fun fileProviderUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /**
     * 先頭バイトを覗いてテキストっぽいかを推定する。
     * - NUL バイト混入 → 多くがバイナリ → false
     * - 制御文字 (タブ・改行・CR 以外) の割合が 5% 未満なら true
     * - 4MB 超は閲覧/編集の上限を超えるので false
     */
    private fun looksLikeText(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        if (file.length() > 4L * 1024 * 1024) return false
        return runCatching {
            val sampleSize = minOf(8192L, file.length()).toInt()
            val bytes = ByteArray(sampleSize)
            file.inputStream().use { ins ->
                var read = 0
                while (read < sampleSize) {
                    val n = ins.read(bytes, read, sampleSize - read)
                    if (n < 0) break
                    read += n
                }
                if (read < bytes.size) return@use bytes.copyOf(read) else bytes
            }
            val actual = if (file.length() < sampleSize) bytes.copyOf(file.length().toInt()) else bytes
            if (actual.isEmpty()) return@runCatching false
            // NUL バイトはほぼ確実にバイナリ。
            if (actual.any { it == 0.toByte() }) return@runCatching false
            // 制御文字 (0x09 タブ / 0x0A LF / 0x0D CR を除く) の割合をチェック。
            var nonPrintable = 0
            for (b in actual) {
                val u = b.toInt() and 0xFF
                if (u in 0x00..0x1F && u != 0x09 && u != 0x0A && u != 0x0D) nonPrintable++
                else if (u == 0x7F) nonPrintable++
            }
            nonPrintable.toFloat() / actual.size < 0.05f
        }.getOrDefault(false)
    }

    /**
     * 同フォルダ内の画像ファイル絶対パス一覧を、現在の表示順 (state.files) に従って返す。
     * 表示中フォルダ以外を開いた場合は state.files に該当兄弟が居ないので、空リストを返す
     * (= スワイプ無効でも単独表示は可能)。
     */
    private fun collectImageSiblings(target: File): List<String> {
        val files = _state.value.files
        val parentPath = target.parentFile?.absolutePath ?: return emptyList()
        val curParentPath = (_state.value.currentUri as? FileUri.Local)?.absolutePath
        // 兄弟は現在表示中のフォルダにいる場合のみ (表示順を尊重したいため)。
        if (curParentPath != parentPath) return emptyList()
        return files.asSequence()
            .filter { it.type == NodeType.FILE }
            .filter { FileTypeRegistry.categorize(it.name) == Category.IMAGE }
            .mapNotNull { (it.uri as? FileUri.Local)?.absolutePath }
            .toList()
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

    /**
     * 貼り付け。宛先に同名がある場合は [pasteConflicts] にためてダイアログ表示用に公開する。
     * 競合に対する応答後は [confirmPasteOverwrite] / [dismissPasteConflict] を経由して
     * [executePaste] が呼ばれる。
     */
    fun paste() {
        val clipboard = _state.value.clipboard ?: return
        val destDir = _state.value.currentUri ?: return
        viewModelScope.launch {
            // 衝突 (宛先に同名 = stat が成功) を先に集める。
            val conflicts = mutableListOf<FileNode>()
            for (node in clipboard.nodes) {
                val destUri = destUriFor(destDir, node.name) ?: continue
                if (storage.stat(destUri) is Result.Success) conflicts += node
            }
            if (conflicts.isNotEmpty()) {
                _state.value = _state.value.copy(pasteConflicts = conflicts)
            } else {
                executePaste(overwrite = false)
            }
        }
    }

    fun confirmPasteOverwrite() {
        _state.value = _state.value.copy(pasteConflicts = emptyList())
        viewModelScope.launch { executePaste(overwrite = true) }
    }

    fun dismissPasteConflict() {
        _state.value = _state.value.copy(pasteConflicts = emptyList())
    }

    private suspend fun executePaste(overwrite: Boolean) {
        val clipboard = _state.value.clipboard ?: return
        val destDir = _state.value.currentUri ?: return
        val opLabel = when (clipboard) {
            is ClipboardOperation.Copy -> "コピー中…"
            is ClipboardOperation.Cut -> "移動中…"
        }
        val nodes = clipboard.nodes
        _state.value = _state.value.copy(
            isLoading = true,
            opProgress = FileOpProgress(
                label = opLabel,
                currentName = nodes.firstOrNull()?.name ?: "",
                currentIndex = 1,
                totalCount = nodes.size,
                bytesTransferred = 0,
                totalBytes = 0,
            ),
        )
        val undoActions = mutableListOf<suspend () -> Unit>()
        var lastError: String? = null

        for ((index, node) in nodes.withIndex()) {
            val destUri = destUriFor(destDir, node.name) ?: continue
            // ProgressObserver で逐次状態を更新。プロバイダによっては 64KB ごとに呼ばれて
            // 1ファイルで何千回も発火するので、80ms (約 12fps) に間引いて UI 負荷を抑える。
            var lastTick = 0L
            val observer = com.zerotoship.foldex.core.model.ProgressObserver { transferred, total ->
                val now = System.currentTimeMillis()
                if (now - lastTick < 80 && transferred < total) return@ProgressObserver
                lastTick = now
                val prev = _state.value.opProgress
                if (prev != null) {
                    _state.value = _state.value.copy(
                        opProgress = prev.copy(
                            currentName = node.name,
                            currentIndex = index + 1,
                            bytesTransferred = transferred,
                            totalBytes = total,
                        ),
                    )
                }
            }
            // 進捗バーは新しいファイルに切り替える前にリセット (0/total)。
            _state.value = _state.value.copy(
                opProgress = _state.value.opProgress?.copy(
                    currentName = node.name,
                    currentIndex = index + 1,
                    bytesTransferred = 0,
                    totalBytes = 0,
                ),
            )
            // overwrite=true なら、衝突しているもののみ既存削除してから書く。
            if (overwrite) {
                val existing = storage.stat(destUri)
                if (existing is Result.Success) {
                    storage.delete(destUri, recursive = true)
                }
            }
            val result = when (clipboard) {
                is ClipboardOperation.Copy -> storage.copyWithin(node.uri, destUri, observer)
                is ClipboardOperation.Cut -> storage.moveWithin(node.uri, destUri, observer)
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
        _state.value = _state.value.copy(
            isLoading = false,
            clipboard = newClipboard,
            opProgress = null,
        )
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

    // --- Delete ---

    fun requestDelete() = requestDeleteOf(_state.value.selectedNodes)

    fun requestDeleteSingle(node: FileNode) = requestDeleteOf(listOf(node))

    private fun requestDeleteOf(nodes: List<FileNode>) {
        if (nodes.isEmpty()) return
        val behavior = _state.value.deleteBehavior
        // 確認オフ かつ 行き先が固定なら、ダイアログを出さず即実行する。
        if (!_state.value.confirmBeforeDelete && behavior != DeleteBehavior.ASK) {
            performDelete(nodes, behavior)
        } else {
            _state.value = _state.value.copy(pendingDeleteNodes = nodes)
        }
    }

    fun dismissDeleteDialog() {
        _state.value = _state.value.copy(pendingDeleteNodes = emptyList())
    }

    fun confirmDelete(behavior: DeleteBehavior) {
        val nodes = _state.value.pendingDeleteNodes
        _state.value = _state.value.copy(pendingDeleteNodes = emptyList())
        performDelete(nodes, behavior)
    }

    private fun performDelete(nodes: List<FileNode>, behavior: DeleteBehavior) {
        if (nodes.isEmpty()) return
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            var lastError: String? = null
            var trashedCount = 0
            var deletedCount = 0
            for (node in nodes) {
                val localFile = (node.uri as? FileUri.Local)?.let { File(it.absolutePath) }
                if (behavior == DeleteBehavior.TRASH && localFile != null) {
                    if (trashRepo.moveToTrash(localFile)) trashedCount++
                    else lastError = "「${node.name}」をゴミ箱へ移動できませんでした"
                } else {
                    when (val r = storage.delete(node.uri, recursive = true)) {
                        is Result.Success -> deletedCount++
                        is Result.Failure -> lastError = r.error.message
                    }
                }
            }
            val currentUri = _state.value.currentUri
            _state.value = _state.value.copy(isLoading = false, selectedUris = emptySet())
            if (currentUri != null) loadFilesSync(currentUri)
            when {
                lastError != null -> emit(SnackbarEvent("削除エラー: $lastError"))
                trashedCount > 0 && deletedCount == 0 -> emit(SnackbarEvent("${trashedCount}件をゴミ箱に移動しました"))
                trashedCount > 0 -> emit(SnackbarEvent("${trashedCount + deletedCount}件削除しました (うち${trashedCount}件はゴミ箱)"))
                else -> emit(SnackbarEvent("${deletedCount}件削除しました"))
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

    // --- Create folder / file ---

    fun showCreateDialog(kind: CreateKind = CreateKind.FOLDER) {
        _state.value = _state.value.copy(pendingCreate = kind)
    }

    fun dismissCreateDialog() {
        _state.value = _state.value.copy(pendingCreate = null)
    }

    fun confirmCreate(name: String, kind: CreateKind) {
        when (kind) {
            CreateKind.FOLDER -> createFolder(name)
            CreateKind.FILE -> createFile(name)
        }
    }

    private fun createFolder(name: String) {
        if (name.isBlank()) return
        val currentUri = _state.value.currentUri ?: return
        val newUri = destUriFor(currentUri, name) ?: return
        _state.value = _state.value.copy(pendingCreate = null, isLoading = true)
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

    /** 空ファイルを作成し、内蔵ビューアで開けるカテゴリならそのまま開く。 */
    private fun createFile(name: String) {
        if (name.isBlank()) return
        val currentUri = _state.value.currentUri ?: return
        val newUri = destUriFor(currentUri, name) ?: return
        _state.value = _state.value.copy(pendingCreate = null, isLoading = true)
        viewModelScope.launch {
            // 既存があれば上書きはせず、エラーで返す。
            val existing = storage.stat(newUri)
            if (existing is Result.Success) {
                _state.value = _state.value.copy(isLoading = false)
                emit(SnackbarEvent("「$name」は既に存在します"))
                return@launch
            }
            val created = when (val r = storage.openOutput(newUri, com.zerotoship.foldex.core.model.WriteMode.CREATE_NEW)) {
                is Result.Success -> {
                    withContext(Dispatchers.IO) { runCatching { r.value.close() } }
                    true
                }
                is Result.Failure -> {
                    emit(SnackbarEvent("ファイル作成エラー: ${r.error.message}"))
                    false
                }
            }
            loadFilesSync(currentUri)
            _state.value = _state.value.copy(isLoading = false)
            if (created) {
                emit(SnackbarEvent("「$name」を作成しました"))
                // ローカル新規ファイルは内蔵ビューアで開ける場合があるので、すぐ開く。
                val category = FileTypeRegistry.categorize(name)
                val localPath = (newUri as? FileUri.Local)?.absolutePath
                if (localPath != null && category.hasBuiltInViewer) {
                    _openRequests.send(
                        OpenRequest.Builtin(
                            localPath = localPath,
                            name = name,
                            category = category,
                            editable = true,
                        ),
                    )
                }
            }
        }
    }

    // --- Share receive (ACTION_SEND / ACTION_SEND_MULTIPLE) ---

    /**
     * 外部アプリから受け取った Android URI 群を受領し、表示名を引いて [pendingShares] に積む。
     * 実際の書き込みは [saveSharedFilesHere] でユーザーが「ここに保存」を押したとき。
     */
    fun receiveSharedFiles(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val collected = uris.mapNotNull { uri ->
                val name = queryDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/')
                    ?: "shared_${System.currentTimeMillis()}"
                SharedIncomingFile(uri.toString(), name)
            }
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(pendingShares = collected)
                if (collected.isNotEmpty()) {
                    emit(SnackbarEvent("${collected.size} 件の共有を受信しました。保存先で「ここに保存」を押してください"))
                }
            }
        }
    }

    fun dismissPendingShares() {
        _state.value = _state.value.copy(pendingShares = emptyList())
    }

    /** 現在の表示フォルダに、受領した共有ファイルを逐次コピーする。 */
    fun saveSharedFilesHere() {
        val shares = _state.value.pendingShares
        if (shares.isEmpty()) return
        val destDir = _state.value.currentUri ?: run {
            emit(SnackbarEvent("保存先フォルダを開いてからもう一度押してください"))
            return
        }
        if (destDir is FileUri.Saf) {
            emit(SnackbarEvent("SAF フォルダへの保存はまだ未対応です。内部ストレージかリモート接続を開いてください"))
            return
        }
        _state.value = _state.value.copy(
            isLoading = true,
            opProgress = FileOpProgress(
                label = "保存中…",
                currentName = shares.firstOrNull()?.name ?: "",
                currentIndex = 1,
                totalCount = shares.size,
                bytesTransferred = 0,
                totalBytes = 0,
            ),
        )
        viewModelScope.launch {
            var saved = 0
            var lastError: String? = null
            for ((index, share) in shares.withIndex()) {
                val destUri = destUriFor(destDir, uniqueName(destDir, share.name)) ?: continue
                // 既知のサイズが取れるなら進捗 UI に渡す (Content URI: OpenableColumns.SIZE)。
                val knownSize = runCatching {
                    context.contentResolver.query(
                        android.net.Uri.parse(share.sourceUri),
                        arrayOf(android.provider.OpenableColumns.SIZE),
                        null, null, null,
                    )?.use { cur -> if (cur.moveToFirst()) cur.getLong(0) else 0L } ?: 0L
                }.getOrDefault(0L)
                _state.value = _state.value.copy(
                    opProgress = _state.value.opProgress?.copy(
                        currentName = share.name,
                        currentIndex = index + 1,
                        bytesTransferred = 0,
                        totalBytes = knownSize,
                    ),
                )
                val ok: Boolean = withContext(Dispatchers.IO) {
                    runCatching {
                        val input = context.contentResolver.openInputStream(android.net.Uri.parse(share.sourceUri))
                            ?: return@runCatching false
                        input.use { inStream ->
                            when (val r = storage.openOutput(destUri, com.zerotoship.foldex.core.model.WriteMode.CREATE_NEW)) {
                                is Result.Success -> r.value.use { outStream ->
                                    val buf = ByteArray(64 * 1024)
                                    var transferred = 0L
                                    var lastTick = 0L
                                    while (true) {
                                        val n = inStream.read(buf)
                                        if (n <= 0) break
                                        outStream.write(buf, 0, n)
                                        transferred += n
                                        // バー更新は 64KB ごとは多すぎるので 80ms スロットル。
                                        val now = System.currentTimeMillis()
                                        if (now - lastTick >= 80) {
                                            lastTick = now
                                            val captured = transferred
                                            withContext(Dispatchers.Main) {
                                                _state.value = _state.value.copy(
                                                    opProgress = _state.value.opProgress?.copy(
                                                        bytesTransferred = captured,
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                }
                                is Result.Failure -> {
                                    lastError = r.error.message
                                    return@runCatching false
                                }
                            }
                            true
                        }
                    }.getOrElse {
                        lastError = it.message
                        false
                    }
                }
                if (ok) saved++
            }
            _state.value = _state.value.copy(
                isLoading = false,
                pendingShares = emptyList(),
                opProgress = null,
            )
            loadFilesSync(destDir)
            if (saved > 0) {
                emit(SnackbarEvent("${saved} 件を保存しました${if (lastError != null) " (${lastError})" else ""}"))
            } else {
                emit(SnackbarEvent("保存に失敗しました: ${lastError ?: "原因不明"}"))
            }
        }
    }

    private fun queryDisplayName(uri: android.net.Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cur ->
                if (cur.moveToFirst()) cur.getString(0) else null
            }
    }.getOrNull()

    private suspend fun uniqueName(destDir: FileUri, name: String): String {
        var candidate = name
        var counter = 1
        while (true) {
            val test = destUriFor(destDir, candidate) ?: return candidate
            if (storage.stat(test) !is Result.Success) return candidate
            val base = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "")
            candidate = if (ext.isNotEmpty() && ext != name) "$base ($counter).$ext" else "$name ($counter)"
            counter++
            if (counter > 999) return "shared_${System.currentTimeMillis()}_$name"
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

    private var loadJob: Job? = null

    private fun loadFiles(uri: FileUri) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // 既存の一覧をすぐにクリアして「読み込み中」を出し、届いた分から順次表示する。
            _state.value = _state.value.copy(isLoading = true, error = null, files = emptyList())
            try {
                streamFiles(uri)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message ?: "エラーが発生しました")
            }
        }
    }

    private suspend fun loadFilesSync(uri: FileUri) {
        try {
            streamFiles(uri)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(isLoading = false, error = e.message ?: "エラーが発生しました")
        }
    }

    // 大量ファイルのフォルダで「全件そろうまで何も出ない」を避けるため、
    // 列挙はバックグラウンドで行いつつ、一定間隔で部分結果を UI に反映する (届いた分から即描画)。
    private suspend fun streamFiles(uri: FileUri) {
        val acc = ArrayList<FileNode>()
        var lastFlush = 0L
        var posted = false
        suspend fun flush(force: Boolean) {
            val now = System.currentTimeMillis()
            if (!force && posted && now - lastFlush < FLUSH_INTERVAL_MS) return
            val snapshot = ArrayList(acc)
            withContext(Dispatchers.Main.immediate) {
                _state.value = _state.value.copy(isLoading = false, files = snapshot)
            }
            posted = true
            lastFlush = now
        }
        withContext(Dispatchers.Default) {
            storage.list(uri).collect { node ->
                acc.add(node)
                flush(force = false) // 内部で時間しきい値によりスロットルされる
            }
        }
        flush(force = true)
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
        // 前回開いていたローカルパス (起動時の復元用)。リモートは保存しない (起動経路でネットワークを避けるため)。
        private const val KEY_LAST_LOCAL_PATH = "last_local_path"

        // 列挙中の部分結果を UI へ反映する最小間隔 (ミリ秒)。これより短い間隔の更新はスキップする。
        private const val FLUSH_INTERVAL_MS = 80L
    }
}
