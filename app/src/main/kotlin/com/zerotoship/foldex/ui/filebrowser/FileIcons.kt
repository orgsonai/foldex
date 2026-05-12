package com.zerotoship.foldex.ui.filebrowser

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.zerotoship.foldex.core.model.FileNode
import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.core.model.NodeType
import com.zerotoship.foldex.core.model.filetype.Category
import com.zerotoship.foldex.core.model.filetype.FileTypeRegistry
import java.io.File

/** ファイルノードのカテゴリ別アイコン。ディレクトリはフォルダ。 */
fun iconFor(node: FileNode): ImageVector =
    if (node.type == NodeType.DIRECTORY) Icons.Outlined.Folder else iconFor(FileTypeRegistry.categorize(node.name))

fun iconFor(category: Category): ImageVector = when (category) {
    Category.IMAGE -> Icons.Outlined.Image
    Category.VIDEO -> Icons.Outlined.Movie
    Category.AUDIO -> Icons.Outlined.Album
    Category.TEXT -> Icons.Outlined.Code
    Category.MARKDOWN -> Icons.Outlined.Article
    Category.HTML -> Icons.Outlined.Language
    Category.PDF -> Icons.Outlined.PictureAsPdf
    Category.ARCHIVE -> Icons.Outlined.Archive
    Category.OFFICE -> Icons.Outlined.Description
    Category.APK -> Icons.Outlined.Android
    Category.ISO -> Icons.Outlined.Archive
    Category.BINARY -> Icons.Outlined.Terminal
    Category.UNKNOWN -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

/** カテゴリ別のアイコン色 (`CLAUDE.md`/HANDOFF §11-D の方針: 種別ごとに控えめに色分け)。 */
@Composable
fun tintFor(node: FileNode, selected: Boolean): Color {
    val colors = MaterialTheme.colorScheme
    if (selected) return colors.primary
    if (node.type == NodeType.DIRECTORY) return colors.primary.copy(alpha = 0.85f)
    return when (FileTypeRegistry.categorize(node.name)) {
        Category.IMAGE -> Color(0xFF43A047)        // green
        Category.VIDEO -> Color(0xFFE53935)        // red
        Category.AUDIO -> Color(0xFF8E24AA)        // purple
        Category.TEXT -> Color(0xFF1E88E5)         // blue
        Category.MARKDOWN -> Color(0xFF1E88E5)
        Category.HTML -> Color(0xFFFB8C00)         // orange
        Category.PDF -> Color(0xFFD32F2F)
        Category.ARCHIVE, Category.ISO -> Color(0xFFF9A825) // amber
        Category.OFFICE -> Color(0xFF3949AB)       // indigo
        Category.APK -> Color(0xFF3DDC84)          // android green
        else -> colors.onSurfaceVariant
    }
}

/** ファイル名末尾の拡張子バッジ (HANDOFF §11-D)。拡張子がなければ何も出さない。 */
@Composable
fun ExtensionBadge(node: FileNode, modifier: Modifier = Modifier) {
    if (node.type != NodeType.FILE) return
    val ext = node.name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 } ?: return
    Text(
        text = ext.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** サムネ取得対象なら Coil に渡せるモデル (File / Uri) を返す。対象外・リモートは null。 */
private fun thumbnailModelFor(node: FileNode): Any? {
    if (node.type != NodeType.FILE) return null
    return when (FileTypeRegistry.categorize(node.name)) {
        Category.IMAGE, Category.VIDEO -> when (val u = node.uri) {
            is FileUri.Local -> File(u.absolutePath)
            is FileUri.Saf -> Uri.parse(u.documentUri)
            // リモートサムネは帯域消費が大きいため当面アイコンのみ (HANDOFF §10-C: 後で部分DL検討)
            is FileUri.Remote -> null
        }
        else -> null
    }
}

/**
 * 一覧の先頭に出すアイコン。
 * - 選択中: チェックボックス
 * - 画像/動画のローカルファイル: サムネ (読み込み中・失敗時はカテゴリアイコンにフォールバック)
 * - それ以外: カテゴリアイコン (種別ごとに控えめに色分け)
 */
@Composable
fun FileLeadingIcon(
    node: FileNode,
    selected: Boolean,
    size: Dp = 24.dp,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Icon(Icons.Default.CheckBox, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = modifier.size(size))
        return
    }
    val model = remember(node.uri, node.lastModified) { thumbnailModelFor(node) }
    if (model == null) {
        Icon(iconFor(node), contentDescription = null, tint = tintFor(node, false), modifier = modifier.size(size))
        return
    }
    val fallback = rememberVectorPainter(iconFor(node))
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(model).crossfade(true).build(),
        contentDescription = null,
        placeholder = fallback,
        error = fallback,
        fallback = fallback,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(RoundedCornerShape(4.dp)),
    )
}
