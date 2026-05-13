package com.zerotoship.foldex.ui.filebrowser

import com.zerotoship.foldex.core.model.DeleteBehavior
import com.zerotoship.foldex.core.model.FileNode
import com.zerotoship.foldex.core.model.FileUri

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

data class FileBrowserState(
    val breadcrumbs: List<BreadcrumbItem> = emptyList(),
    val files: List<FileNode> = emptyList(),
    val isLoading: Boolean = false,
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
