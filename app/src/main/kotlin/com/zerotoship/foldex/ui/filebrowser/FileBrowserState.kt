package com.zerotoship.foldex.ui.filebrowser

import com.zerotoship.foldex.core.model.FileNode
import com.zerotoship.foldex.core.model.FileUri

data class BreadcrumbItem(val uri: FileUri, val displayName: String)

data class FileBrowserState(
    val breadcrumbs: List<BreadcrumbItem> = emptyList(),
    val files: List<FileNode> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val viewMode: ViewMode = ViewMode.LIST,
    val hasStoragePermission: Boolean = false,
    val hasSafRoot: Boolean = false,
) {
    val currentUri: FileUri? get() = breadcrumbs.lastOrNull()?.uri
    val canGoUp: Boolean get() = breadcrumbs.size > 1
}
