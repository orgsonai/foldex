package com.zerotoship.foldex.sync.engine

import com.zerotoship.foldex.core.model.FileUri

/**
 * 同期ルート [root] からの相対パス [relativePath] (区切り `/`、先頭スラッシュ無し) を
 * 絶対 [FileUri] に解決する。SAF ルートはパス連結ができないため P6 では未対応。
 */
internal fun childUri(root: FileUri, relativePath: String): FileUri = when (root) {
    is FileUri.Local -> FileUri.Local(joinPath(root.absolutePath, relativePath))
    is FileUri.Remote -> FileUri.Remote(root.protocol, root.connectionId, joinPath(root.path, relativePath))
    is FileUri.Saf -> error("SAF ルートの同期は未対応 (P7 で対応予定): ${root.toStorageString()}")
}

/** 相対パスの親ディレクトリ (ルート直下なら空文字)。 */
internal fun parentRelativePath(relativePath: String): String =
    relativePath.substringBeforeLast('/', missingDelimiterValue = "")

private fun joinPath(base: String, relative: String): String {
    val b = base.trimEnd('/')
    val r = relative.trim('/')
    return when {
        r.isEmpty() -> b.ifEmpty { "/" }
        b.isEmpty() -> "/$r"
        else -> "$b/$r"
    }
}
