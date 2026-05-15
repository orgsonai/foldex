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
    private val homeShortcutRepo: com.zerotoship.foldex.core.data.repo.HomeShortcutRepository,
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
        // ソート / 隠しファイルの既定を prefs から復元。
        val sortBy = runCatching {
            com.zerotoship.foldex.core.model.SortBy.valueOf(
                prefs.getString(KEY_SORT_BY, com.zerotoship.foldex.core.model.SortBy.NAME.name)!!,
            )
        }.getOrDefault(com.zerotoship.foldex.core.model.SortBy.NAME)
        val sortAsc = prefs.getBoolean(KEY_SORT_ASC, true)
        val showHidden = prefs.getBoolean(KEY_SHOW_HIDDEN, false)
        _state.value = _state.value.copy(
            hasStoragePermission = hasPerm,
            hasSafRoot = safRootUri != null,
            favoriteUris = favorites,
            sortBy = sortBy,
            sortAscending = sortAsc,
            showHidden = showHidden,
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
                editorEditableLimitKb = s.editorEditableLimitKb
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

    fun openSmbConnection(connectionId: String, displayName: String, initialPath: String = "/") {
        _state.value = _state.value.copy(breadcrumbs = emptyList(), selectedUris = emptySet())
        val path = if (initialPath.isBlank()) "/" else initialPath
        navigateTo(FileUri.Remote(Protocol.SMB, connectionId, path), displayName = displayName)
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

    /** プルダウン更新。上端スピナーだけ出し、完了後に下げる。 */
    fun pullRefresh() {
        val uri = _state.value.currentUri ?: return
        if (_state.value.isRefreshing) return
        _state.value = _state.value.copy(isRefreshing = true)
        viewModelScope.launch {
            try {
                loadFilesSync(uri)
            } finally {
                _state.value = _state.value.copy(isRefreshing = false)
            }
        }
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
            // ─── 動画 (Remote) はストリーミング: 全DLを待たずに ContentProvider 経由で再生 ───
            // 全DL→VideoViewer で File 経由、と比べて再生開始までが圧倒的に速い。
            // pipe ベースで seek 非対応なので、未対応形式は ExoPlayer のエラー→外部アプリ案内に転がる。
            val isStreamableRemoteVideo =
                initialCategory == Category.VIDEO &&
                    node.uri is FileUri.Remote &&
                    (mode == OpenWithMode.DEFAULT || mode == OpenWithMode.BUILTIN)
            if (isStreamableRemoteVideo) {
                val streamingUri = com.zerotoship.foldex.streaming.RemoteStreamProvider.buildUri(
                    context = context,
                    remote = node.uri as FileUri.Remote,
                    displayName = node.name,
                    size = node.size,
                )
                _openRequests.send(
                    OpenRequest.Builtin(
                        localPath = "",
                        name = node.name,
                        category = Category.VIDEO,
                        editable = false,
                        streamingMediaUri = streamingUri.toString(),
                    ),
                )
                return@launch
            }

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
                        // ローカル / リモート / SAF いずれも編集可能。
                        // リモート・SAF はキャッシュに編集 → FileBrowser に戻ったタイミングで
                        // [checkPendingUploads] が変更を検出して元の URI にアップロードバックする。
                        editable = true,
                        // 画像はスワイプで前後の画像へ遷移できるよう、同フォルダの兄弟画像を集める
                        // (HANDOFF §10-C: 「隣の画像へスワイプ」)。ローカルのみ対応。
                        siblings = if (category == Category.IMAGE && node.uri is FileUri.Local) {
                            collectImageSiblings(localFile)
                        } else emptyList(),
                        editableLimitKb = editorEditableLimitKb,
                    )
                else -> external(chooser = true)
            }
            _openRequests.send(request)
        }
    }

    /** ローカルの実体ファイルを返す。リモート/SAF はキャッシュへDLする。失敗時は snackbar を出して null。 */
    private suspend fun resolveLocalFile(node: FileNode): File? = when (val u = node.uri) {
        is FileUri.Local -> File(u.absolutePath)
        is FileUri.Saf -> downloadSafToCache(node)
        is FileUri.Remote -> downloadToCache(node)
    }

    /**
     * SAF ノードをキャッシュにコピー (内蔵ビューア用)。
     * 編集後の書き戻し用に [pendingEdits] に登録し、ON_RESUME で
     * [checkPendingUploads] が mtime 差分を見て元 URI に OVERWRITE する。
     */
    private suspend fun downloadSafToCache(node: FileNode): File? = withContext(Dispatchers.IO) {
        val u = node.uri as FileUri.Saf
        val dir = File(context.cacheDir, "opened").apply { mkdirs() }
        val safeName = node.name.replace(Regex("[^A-Za-z0-9._\\-]"), "_").ifEmpty { "file" }
        val out = File(dir, "saf_${u.documentUri.hashCode().toUInt()}_$safeName")
        val dlId = "saf_${out.absolutePath.hashCode()}_${System.nanoTime()}"
        val total = node.size.coerceAtLeast(0L)
        withContext(Dispatchers.Main) { addActiveDownload(ActiveDownload(id = dlId, name = node.name, totalBytes = total)) }
        try {
            runCatching {
                when (val r = storage.openInput(u)) {
                    is Result.Success -> r.value.use { ins ->
                        out.outputStream().use { os -> copyWithProgress(ins, os, total, dlId) }
                    }
                    is Result.Failure -> {
                        emit(SnackbarEvent("読み込み失敗: ${r.error.message}"))
                        return@withContext null
                    }
                }
                pendingEdits[out.absolutePath] = PendingEdit(u, out.lastModified())
                out
            }.getOrElse {
                emit(SnackbarEvent("読み込み失敗: ${it.message}"))
                null
            }
        } finally {
            withContext(Dispatchers.Main) { removeActiveDownload(dlId) }
        }
    }

    /**
     * 外部アプリ or 内蔵エディタに渡したキャッシュファイル → 元の (Remote/SAF) URI。
     * 編集が終わってユーザーが Foldex に戻ったとき [checkPendingUploads] で更新を検出して
     * アップロードバックする。
     */
    private data class PendingEdit(
        val sourceUri: FileUri,
        val mtimeAtOpen: Long,
    )
    private val pendingEdits = mutableMapOf<String, PendingEdit>()

    /** 設定の「テキストエディタ編集可能上限(KB)」を保持。settings.collect で更新する。 */
    private var editorEditableLimitKb: Int = 512

    private suspend fun downloadToCache(node: FileNode): File? {
        val dir = File(context.cacheDir, "opened").apply { mkdirs() }
        val safeName = node.name.replace(Regex("[^A-Za-z0-9._\\-]"), "_").ifEmpty { "file" }
        val out = File(dir, "${node.uri.toStorageString().hashCode().toUInt()}_$safeName")
        val dlId = "dl_${out.absolutePath.hashCode()}_${System.nanoTime()}"
        val total = node.size.coerceAtLeast(0L)
        addActiveDownload(ActiveDownload(id = dlId, name = node.name, totalBytes = total))
        return try {
            when (val r = storage.openInput(node.uri)) {
                is Result.Success -> withContext(Dispatchers.IO) {
                    runCatching {
                        r.value.use { input ->
                            out.outputStream().use { os ->
                                copyWithProgress(input, os, total, dlId)
                            }
                        }
                        val remote = node.uri as? FileUri.Remote
                        if (remote != null) {
                            pendingEdits[out.absolutePath] = PendingEdit(remote, out.lastModified())
                        }
                        out
                    }.getOrElse { emit(SnackbarEvent("読み込み失敗: ${it.message}")); null }
                }
                is Result.Failure -> { emit(SnackbarEvent("読み込み失敗: ${r.error.message}")); null }
            }
        } finally {
            removeActiveDownload(dlId)
        }
    }

    private fun addActiveDownload(d: ActiveDownload) {
        _state.value = _state.value.copy(activeDownloads = _state.value.activeDownloads + d)
    }

    private fun removeActiveDownload(id: String) {
        _state.value = _state.value.copy(
            activeDownloads = _state.value.activeDownloads.filterNot { it.id == id },
        )
    }

    /** 64KB バッファでコピーしつつ、80ms スロットルで activeDownloads の進捗を更新する。 */
    private fun copyWithProgress(input: java.io.InputStream, output: java.io.OutputStream, total: Long, dlId: String) {
        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
        var transferred = 0L
        var lastUpdate = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            transferred += n
            val now = System.currentTimeMillis()
            if (now - lastUpdate > 80) {
                lastUpdate = now
                val cur = _state.value.activeDownloads
                val updated = cur.map { if (it.id == dlId) it.copy(bytesTransferred = transferred, totalBytes = total) else it }
                _state.value = _state.value.copy(activeDownloads = updated)
            }
        }
    }

    /**
     * 外部アプリ/内蔵エディタから戻ってきたタイミングで、キャッシュファイルが
     * DL 時より新しければ元 (Remote / SAF) にアップロードバックする。
     */
    fun checkPendingUploads() {
        if (pendingEdits.isEmpty()) return
        viewModelScope.launch {
            val snapshot = pendingEdits.toMap()
            var uploaded = 0
            var failed = 0
            for ((path, info) in snapshot) {
                val f = File(path)
                if (!f.exists()) {
                    pendingEdits.remove(path)
                    continue
                }
                if (f.lastModified() <= info.mtimeAtOpen) continue // 編集されていない
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        when (val r = storage.openOutput(info.sourceUri, com.zerotoship.foldex.core.model.WriteMode.OVERWRITE)) {
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
                    pendingEdits[path] = info.copy(mtimeAtOpen = f.lastModified())
                } else {
                    failed++
                }
            }
            if (uploaded > 0 || failed > 0) {
                val msg = buildString {
                    if (uploaded > 0) {
                        val tag = if (snapshot.values.any { it.sourceUri is FileUri.Saf }) "" else "リモートに"
                        append("${uploaded} 件を${tag}保存しました")
                    }
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
                            editableLimitKb = editorEditableLimitKb,
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
        // SAF (Termux などの DocumentsProvider) も書き込み対応済み: openOutput(CREATE_NEW) が
        // 親 documentUri + pendingChildName を見て createDocument を呼ぶ。
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

    // --- Sort / Hidden / Properties / Share / Open externally / HOME add ---

    fun setSort(by: com.zerotoship.foldex.core.model.SortBy, ascending: Boolean) {
        if (_state.value.sortBy == by && _state.value.sortAscending == ascending) return
        _state.value = _state.value.copy(sortBy = by, sortAscending = ascending)
        prefs.edit().putString(KEY_SORT_BY, by.name).putBoolean(KEY_SORT_ASC, ascending).apply()
        val cur = _state.value.currentUri
        if (cur != null) viewModelScope.launch { loadFilesSync(cur) }
    }

    fun toggleShowHidden() {
        val newVal = !_state.value.showHidden
        _state.value = _state.value.copy(showHidden = newVal)
        prefs.edit().putBoolean(KEY_SHOW_HIDDEN, newVal).apply()
        val cur = _state.value.currentUri
        if (cur != null) viewModelScope.launch { loadFilesSync(cur) }
    }

    fun showProperties(node: FileNode) {
        _state.value = _state.value.copy(propertiesTarget = node)
    }

    fun dismissProperties() {
        _state.value = _state.value.copy(propertiesTarget = null)
    }

    /**
     * 現在開いている URI の「人が読める絶対パス」を返す。クリップボードへコピーする用。
     * Local: そのまま絶対パス、Remote: `<scheme>://<connectionId><path>`、SAF: documentUri 文字列。
     */
    fun currentAbsolutePath(): String? = when (val u = _state.value.currentUri) {
        is FileUri.Local -> u.absolutePath
        is FileUri.Remote -> "${u.protocol.scheme}://${u.connectionId}/${u.path.trimStart('/')}"
        is FileUri.Saf -> u.documentUri
        null -> null
    }

    /** タイトル長押し時にダイアログを開く: 現在パスを初期値にして編集できる。 */
    fun showPathInput() {
        _state.value = _state.value.copy(pendingPathInput = currentAbsolutePath() ?: "/")
    }

    fun dismissPathInput() {
        _state.value = _state.value.copy(pendingPathInput = null)
    }

    /**
     * パス手動入力ダイアログの確定。`/` 始まりはローカル絶対パス、それ以外は
     * [FileUri.fromStorageStringOrNull] で解釈を試みる (sftp://... 等)。
     * 解釈できた / そのフォルダが存在すれば navigate、駄目なら snackbar。
     */
    fun navigateToManualPath(input: String) {
        val raw = input.trim()
        if (raw.isEmpty()) {
            emit(SnackbarEvent("パスが空です"))
            return
        }
        val target: FileUri? = when {
            raw.startsWith("/") -> FileUri.Local(raw.trimEnd('/').ifEmpty { "/" })
            else -> FileUri.fromStorageStringOrNull(raw)
        }
        if (target == null) {
            emit(SnackbarEvent("パスを解釈できませんでした: $raw"))
            return
        }
        // ローカルなら存在チェック (リモート/SAF はネットワーク/解決後に判定する)。
        if (target is FileUri.Local) {
            val f = File(target.absolutePath)
            if (!f.isDirectory) {
                emit(SnackbarEvent("フォルダが見つかりません: ${target.absolutePath}"))
                return
            }
        }
        val name = when (target) {
            is FileUri.Local -> displayNameForLocal(File(target.absolutePath))
            is FileUri.Remote -> target.path.trimEnd('/').substringAfterLast('/').ifEmpty { target.connectionId }
            is FileUri.Saf -> "ストレージ"
        }
        _state.value = _state.value.copy(pendingPathInput = null)
        open(target, name)
    }

    /** 選択中ノードを「リモートはローカルにDLしてから」FileProvider 経由で他アプリに共有する。 */
    fun shareSelected() {
        val nodes = _state.value.selectedNodes.filter { it.type == NodeType.FILE }
        if (nodes.isEmpty()) return
        viewModelScope.launch {
            val uris = ArrayList<Uri>(nodes.size)
            val mimes = HashSet<String>()
            for (n in nodes) {
                val local = resolveLocalFile(n) ?: continue
                runCatching {
                    uris.add(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", local))
                    val mime = FileTypeRegistry.mimeTypeFor(n.name) ?: "*/*"
                    mimes.add(mime)
                }
            }
            if (uris.isEmpty()) return@launch
            val send = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                    type = mimes.firstOrNull() ?: "*/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    type = if (mimes.size == 1) mimes.first() else "*/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            runCatching { context.startActivity(Intent.createChooser(send, "共有").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }
                .onFailure { emit(SnackbarEvent("共有できませんでした: ${it.message}")) }
            clearSelection()
        }
    }

    /** 選択中のローカルフォルダを HOME のタイルとして追加する。 */
    fun addSelectedFoldersToHome() {
        val folders = _state.value.selectedNodes.filter {
            it.type == NodeType.DIRECTORY && it.uri is FileUri.Local
        }
        if (folders.isEmpty()) {
            emit(SnackbarEvent("HOME に追加できるのはローカルフォルダだけです"))
            return
        }
        viewModelScope.launch {
            for (n in folders) {
                val path = (n.uri as FileUri.Local).absolutePath
                homeShortcutRepo.addLocalFolder(n.name, path)
            }
            emit(SnackbarEvent("${folders.size} 件を HOME に追加しました"))
            clearSelection()
        }
    }

    /** 選択中の単一ノードを外部アプリで開く (ACTION_VIEW, chooser)。 */
    fun openSelectedExternally() {
        val node = _state.value.selectedNodes.firstOrNull { it.type == NodeType.FILE } ?: return
        viewModelScope.launch {
            val local = resolveLocalFile(node) ?: return@launch
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", local)
            val mime = FileTypeRegistry.mimeTypeFor(node.name) ?: "*/*"
            _openRequests.send(OpenRequest.External(uri = uri, mime = mime, name = node.name, chooser = true))
            clearSelection()
        }
    }

    // --- ZIP ---

    /** 選択中のノードを ZIP 化する確認ダイアログを開く。 */
    fun requestZipCompress() {
        val sel = _state.value.selectedNodes
        if (sel.isEmpty()) return
        _state.value = _state.value.copy(pendingZipCompress = sel)
    }

    fun dismissZipCompress() {
        _state.value = _state.value.copy(pendingZipCompress = emptyList())
    }

    /**
     * ZIP 圧縮を実行する。出力先は現在のフォルダ。
     * リモート/SAF はキャッシュに DL してから zip4j に渡し、できた zip を [openOutput] で書き込み。
     * [password] が空なら通常 ZIP、そうでなければ AES-256。
     */
    fun executeZipCompress(zipName: String, password: String?) {
        val targets = _state.value.pendingZipCompress
        val destDir = _state.value.currentUri ?: return
        if (targets.isEmpty()) return
        val finalName = if (zipName.endsWith(".zip", ignoreCase = true)) zipName else "$zipName.zip"
        _state.value = _state.value.copy(pendingZipCompress = emptyList(), isLoading = true)
        viewModelScope.launch {
            val cacheZip = withContext(Dispatchers.IO) {
                File(context.cacheDir, "compress_${System.currentTimeMillis()}_$finalName")
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val locals = ArrayList<File>(targets.size)
                    for (n in targets) {
                        val f = resolveLocalFile(n) ?: continue
                        locals += f
                    }
                    if (locals.isEmpty()) return@runCatching false
                    ZipOps.compress(locals, cacheZip, password.takeUnless { it.isNullOrEmpty() })
                    true
                }.getOrElse { e ->
                    emit(SnackbarEvent("圧縮失敗: ${e.message}"))
                    false
                }
            }
            if (!ok || !cacheZip.exists()) {
                _state.value = _state.value.copy(isLoading = false)
                return@launch
            }
            // できた zip を destDir に書き込む。
            val destUri = destUriFor(destDir, uniqueName(destDir, finalName))
            if (destUri == null) {
                emit(SnackbarEvent("保存先を解決できません"))
                _state.value = _state.value.copy(isLoading = false)
                return@launch
            }
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    cacheZip.inputStream().use { ins ->
                        when (val r = storage.openOutput(destUri, com.zerotoship.foldex.core.model.WriteMode.CREATE_NEW)) {
                            is Result.Success -> r.value.use { os -> ins.copyTo(os) }
                            is Result.Failure -> {
                                emit(SnackbarEvent("保存失敗: ${r.error.message}"))
                                return@runCatching false
                            }
                        }
                    }
                    true
                }.getOrElse {
                    emit(SnackbarEvent("保存失敗: ${it.message}"))
                    false
                }
            }
            runCatching { cacheZip.delete() }
            loadFilesSync(destDir)
            _state.value = _state.value.copy(isLoading = false)
            if (written) {
                emit(SnackbarEvent("「$finalName」を作成しました"))
                clearSelection()
            }
        }
    }

    /** 単一の zip ノードに対して解凍ダイアログを開く。最初は password なしで試す。 */
    fun requestZipExtract(node: FileNode? = null) {
        val target = node ?: _state.value.selectedNodes.singleOrNull { it.type == NodeType.FILE && ZipOps.isLikelyZip(it.name) }
        if (target == null) {
            emit(SnackbarEvent("解凍する ZIP を 1 件だけ選んでください"))
            return
        }
        _state.value = _state.value.copy(
            pendingZipExtract = ZipExtractRequest(target, needsPassword = false),
        )
    }

    fun dismissZipExtract() {
        _state.value = _state.value.copy(pendingZipExtract = null)
    }

    /**
     * 解凍を実行する。展開先は現在のフォルダ配下に「<zip基名>/」フォルダを作る。
     * [password] が空ならまずパスワードなしで試行し、要パスワード判定なら再度ダイアログを出す。
     */
    fun executeZipExtract(password: String?) {
        val req = _state.value.pendingZipExtract ?: return
        val node = req.node
        val destDir = _state.value.currentUri ?: return
        _state.value = _state.value.copy(pendingZipExtract = null, isLoading = true)
        viewModelScope.launch {
            // 1) zip 本体をローカルに揃える
            val zipFile = withContext(Dispatchers.IO) { resolveLocalFile(node) }
            if (zipFile == null) {
                _state.value = _state.value.copy(isLoading = false)
                return@launch
            }
            // 2) 一旦キャッシュに展開
            val cacheOut = withContext(Dispatchers.IO) {
                File(context.cacheDir, "extract_${System.currentTimeMillis()}_${node.name.removeSuffix(".zip")}")
                    .apply { mkdirs() }
            }
            val (ok, needsPwd, message) = withContext(Dispatchers.IO) {
                try {
                    ZipOps.extract(zipFile, cacheOut, password.takeUnless { it.isNullOrEmpty() })
                    Triple(true, false, null)
                } catch (e: ZipOps.WrongPassword) {
                    Triple(false, true, e.message)
                } catch (e: Exception) {
                    Triple(false, false, e.message)
                }
            }
            if (!ok) {
                _state.value = _state.value.copy(isLoading = false)
                if (needsPwd) {
                    _state.value = _state.value.copy(
                        pendingZipExtract = req.copy(needsPassword = true, initialError = message),
                    )
                } else {
                    emit(SnackbarEvent("解凍失敗: ${message ?: "原因不明"}"))
                }
                return@launch
            }
            // 3) cacheOut の中身を destDir 配下にコピー (storage 経由で SAF/Remote にも対応)
            val baseName = node.name.removeSuffix(".zip")
            val baseDirUri = destUriFor(destDir, uniqueName(destDir, baseName))
            if (baseDirUri == null) {
                emit(SnackbarEvent("展開先を解決できません"))
                cacheOut.deleteRecursively()
                _state.value = _state.value.copy(isLoading = false)
                return@launch
            }
            val mkRes = withContext(Dispatchers.IO) { storage.mkdir(baseDirUri, recursive = true) }
            if (mkRes is Result.Failure) {
                emit(SnackbarEvent("展開フォルダ作成失敗: ${mkRes.error.message}"))
                cacheOut.deleteRecursively()
                _state.value = _state.value.copy(isLoading = false)
                return@launch
            }
            // baseDirUri は擬似 URI (SAF) / 正規 URI (Local/Remote) のどちらか。実体ノードの
            // URI を解決し直すために、改めて parent dir の中の同名フォルダを stat する。
            val realBase = resolveDir(destDir, baseName)
            val target = realBase ?: baseDirUri
            withContext(Dispatchers.IO) {
                copyTreeIntoStorage(cacheOut, target)
            }
            cacheOut.deleteRecursively()
            loadFilesSync(destDir)
            _state.value = _state.value.copy(isLoading = false)
            emit(SnackbarEvent("「$baseName/」に展開しました"))
            clearSelection()
        }
    }

    /** 展開後 ZIP 直下のファイル/フォルダを storage 経由で再帰コピーする (SAF/Remote 対応)。 */
    private suspend fun copyTreeIntoStorage(srcDir: File, destDirUri: FileUri) {
        val children = srcDir.listFiles() ?: return
        for (child in children) {
            val childDest = destUriFor(destDirUri, child.name) ?: continue
            if (child.isDirectory) {
                storage.mkdir(childDest, recursive = true)
                val realChild = resolveDir(destDirUri, child.name) ?: childDest
                copyTreeIntoStorage(child, realChild)
            } else {
                runCatching {
                    child.inputStream().use { ins ->
                        when (val r = storage.openOutput(childDest, com.zerotoship.foldex.core.model.WriteMode.CREATE_NEW)) {
                            is Result.Success -> r.value.use { os -> ins.copyTo(os) }
                            is Result.Failure -> Unit
                        }
                    }
                }
            }
        }
    }

    /** 親 [parentDir] 配下に [name] という名前で実在するフォルダの URI を解決して返す。 */
    private suspend fun resolveDir(parentDir: FileUri, name: String): FileUri? {
        var found: FileUri? = null
        runCatching {
            storage.list(parentDir, com.zerotoship.foldex.core.model.ListOptions()).collect { node ->
                if (node.type == NodeType.DIRECTORY && node.name == name) found = node.uri
            }
        }
        return found
    }

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
            val options = com.zerotoship.foldex.core.model.ListOptions(
                showHidden = _state.value.showHidden,
                sortBy = _state.value.sortBy,
                sortAscending = _state.value.sortAscending,
            )
            storage.list(uri, options).collect { node ->
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
        // SAF: 親 dir.documentUri 配下に「name で createDocument する予定の」擬似 URI を返す。
        // openOutput(CREATE_NEW) / mkdir 側で pendingChildName を見て実体生成する。
        is FileUri.Saf -> FileUri.Saf(dir.documentUri, pendingChildName = name)
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
        private const val KEY_SORT_BY = "sort_by"
        private const val KEY_SORT_ASC = "sort_ascending"
        private const val KEY_SHOW_HIDDEN = "show_hidden"

        // 列挙中の部分結果を UI へ反映する最小間隔 (ミリ秒)。これより短い間隔の更新はスキップする。
        private const val FLUSH_INTERVAL_MS = 80L
    }
}
