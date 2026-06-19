package com.zerotoship.foldex.ui.filebrowser

import com.zerotoship.foldex.core.model.FileUri

/** ドロワーのクイックアクセス項目。アイコンは UI 側で [kind] から決める。 */
data class QuickAccessEntry(
    val uri: FileUri,
    val label: String,
    val kind: QuickAccessKind,
)

enum class QuickAccessKind {
    INTERNAL_STORAGE,
    DOWNLOAD,
    IMAGES,
    CAMERA,
    VIDEO,
    MUSIC,
    DOCUMENTS,
    SD_CARD,
}
