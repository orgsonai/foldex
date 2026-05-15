package com.zerotoship.foldex.ui.filebrowser

import com.zerotoship.foldex.core.model.DeleteBehavior
import com.zerotoship.foldex.core.model.FileNode
import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.core.model.SortBy

data class BreadcrumbItem(val uri: FileUri, val displayName: String)

sealed class ClipboardOperation {
    abstract val nodes: List<FileNode>
    data class Copy(override val nodes: List<FileNode>) : ClipboardOperation()
    data class Cut(override val nodes: List<FileNode>) : ClipboardOperation()
}

data class SnackbarEvent(
    val message: String,
    val actionLabel: String? = null,
    val onAction: (suspend () -> Unit)? = null,
)

/** ACTION_SEND / ACTION_SEND_MULTIPLE で外部アプリから受け取ったファイル。 */
data class SharedIncomingFile(val sourceUri: String, val name: String)

/**
 * バックグラウンドで実行中のリモート/SAF ダウンロード 1 件分の進捗。
 * UI 上部に「DL中 N件」のバナーを出すために使う。
 */
data class ActiveDownload(
    val id: String,
    val name: String,
    val bytesTransferred: Long = 0,
    val totalBytes: Long = 0,
)

/** ZIP 解凍ダイアログのリクエスト。`needsPassword` が true ならパスワード入力を求める。 */
data class ZipExtractRequest(val node: FileNode, val needsPassword: Boolean, val initialError: String? = null)

/**
 * コピー / 移動 / 共有保存などの長時間ファイル操作の進捗。
 * 全体のうち [currentIndex]/[totalCount] 件目を処理中、現在ファイル名は [currentName]。
 * 進捗バー用のバイト数は [bytesTransferred]/[totalBytes] (totalBytes <= 0 のときは不確定)。
 */
data class FileOpProgress(
    val label: String,          // 「コピー中…」「移動中…」「保存中…」
    val currentName: String,
    val currentIndex: Int,
    val totalCount: Int,
    val bytesTransferred: Long,
    val totalBytes: Long,
)

data class FileBrowserState(
    val breadcrumbs: List<BreadcrumbItem> = emptyList(),
    val files: List<FileNode> = emptyList(),
    val isLoading: Boolean = false,
    /** プルダウン更新中。`isLoading` と違って全画面 CircularProgressIndicator は出さず、上端のスピナーのみ。 */
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val viewMode: ViewMode = ViewMode.LIST,
    val hasStoragePermission: Boolean = false,
    val hasSafRoot: Boolean = false,
    val selectedUris: Set<String> = emptySet(),
    val clipboard: ClipboardOperation? = null,
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val pendingDeleteNodes: List<FileNode> = emptyList(),
    val renameTarget: FileNode? = null,
    val pendingCreate: CreateKind? = null, // null = 非表示, FOLDER/FILE = ダイアログ表示中
    val pasteConflicts: List<FileNode> = emptyList(), // paste 時の上書き確認待ち
    val favoriteUris: Set<String> = emptySet(),
    val showExtensionBadge: Boolean = true,
    val deleteBehavior: DeleteBehavior = DeleteBehavior.TRASH,
    val confirmBeforeDelete: Boolean = true,
    // ビューモードを変更したあと「配下のフォルダにも適用するか」を確認する保留状態。null = ダイアログ非表示。
    val pendingApplyViewModeToSubtree: ViewMode? = null,
    // ACTION_SEND で受け取ったファイル群。空でない間、ファイル一覧上部にバナーで案内する。
    val pendingShares: List<SharedIncomingFile> = emptyList(),
    // 進行中のコピー/移動/保存などの進捗。null = 表示しない。
    val opProgress: FileOpProgress? = null,
    // ソート: 既定は 名前 昇順。
    val sortBy: SortBy = SortBy.NAME,
    val sortAscending: Boolean = true,
    // 隠しファイル ("." で始まる) の表示。
    val showHidden: Boolean = false,
    // 単体プロパティ表示の対象 (null = ダイアログ非表示)。
    val propertiesTarget: FileNode? = null,
    // パス手動入力ダイアログの初期文字列 (null = 非表示)。
    val pendingPathInput: String? = null,
    /** 現在ダウンロード中のリモート/SAFファイル一覧 (バックグラウンドDLバナー用)。 */
    val activeDownloads: List<ActiveDownload> = emptyList(),
    // ZIP 圧縮ダイアログ: 圧縮対象ノード一覧 (空 = 非表示)。
    val pendingZipCompress: List<FileNode> = emptyList(),
    // ZIP 解凍ダイアログ: 対象 zip と「パスワード要否」 (null = 非表示)。
    val pendingZipExtract: ZipExtractRequest? = null,
) {
    val currentUri: FileUri? get() = breadcrumbs.lastOrNull()?.uri
    val canGoUp: Boolean get() = breadcrumbs.size > 1
    val isSelectionMode: Boolean get() = selectedUris.isNotEmpty()
    val canPaste: Boolean get() = clipboard != null
    val selectedNodes: List<FileNode> get() = files.filter { it.uri.toStorageString() in selectedUris }
    val filteredFiles: List<FileNode>
        get() = if (searchQuery.isEmpty()) files
                else files.filter { matchesSearch(it, searchQuery) }

    private fun matchesSearch(node: FileNode, query: String): Boolean =
        if (query.contains('*') || query.contains('?')) {
            buildGlobRegex(query).containsMatchIn(node.name)
        } else {
            node.name.contains(query, ignoreCase = true)
        }

    companion object {
        fun buildGlobRegex(glob: String): Regex {
            val pattern = buildString {
                for (c in glob) when (c) {
                    '*' -> append(".*")
                    '?' -> append(".")
                    else -> append(Regex.escape(c.toString()))
                }
            }
            return Regex(pattern, RegexOption.IGNORE_CASE)
        }
    }
}
