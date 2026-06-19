package com.zerotoship.foldex.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zerotoship.foldex.core.model.Connection
import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.ui.filebrowser.QuickAccessEntry
import com.zerotoship.foldex.ui.filebrowser.QuickAccessKind

/**
 * HOME / ファイル両画面で共有するナビゲーションドロワー本体。
 * クイックアクセス → 別フォルダを開く → お気に入り → リモート接続 の順で並べる。
 *
 * 選択ハイライトは [selectedKey] (FileUri.toStorageString() / Connection.id) で判定する。
 */
@Composable
fun AppDrawerContent(
    quickAccess: List<QuickAccessEntry>,
    favorites: List<Pair<FileUri, String>>,
    connections: List<Connection>,
    selectedKey: String?,
    onOpenUri: (FileUri, String) -> Unit,
    onPickFolder: () -> Unit,
    onOpenConnection: (Connection) -> Unit,
) {
    ModalDrawerSheet {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Foldex",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            HorizontalDivider()

            DrawerSectionLabel("クイックアクセス")
            quickAccess.forEach { entry ->
                NavigationDrawerItem(
                    icon = { Icon(quickAccessIcon(entry.kind), contentDescription = null) },
                    label = { Text(entry.label) },
                    selected = selectedKey == entry.uri.toStorageString(),
                    onClick = { onOpenUri(entry.uri, entry.label) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            NavigationDrawerItem(
                icon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                label = { Text("別のフォルダを開く…") },
                selected = false,
                onClick = onPickFolder,
                modifier = Modifier.padding(horizontal = 12.dp),
            )

            if (favorites.isNotEmpty()) {
                DrawerSectionLabel("お気に入り")
                favorites.forEach { (uri, key) ->
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Outlined.Star, contentDescription = null) },
                        label = {
                            Text(
                                favoriteLabel(uri),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        selected = selectedKey == key,
                        onClick = { onOpenUri(uri, favoriteLabel(uri)) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }

            if (connections.isNotEmpty()) {
                DrawerSectionLabel("リモート接続")
                connections.forEach { connection ->
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Outlined.Storage, contentDescription = null) },
                        label = { Text(connection.name) },
                        selected = selectedKey == "conn:${connection.id}",
                        onClick = { onOpenConnection(connection) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DrawerSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 28.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

private fun quickAccessIcon(kind: QuickAccessKind): ImageVector = when (kind) {
    QuickAccessKind.INTERNAL_STORAGE -> Icons.Outlined.PhoneAndroid
    QuickAccessKind.DOWNLOAD -> Icons.Outlined.Download
    QuickAccessKind.IMAGES -> Icons.Outlined.Image
    QuickAccessKind.CAMERA -> Icons.Outlined.PhotoCamera
    QuickAccessKind.VIDEO -> Icons.Outlined.Movie
    QuickAccessKind.MUSIC -> Icons.Outlined.MusicNote
    QuickAccessKind.DOCUMENTS -> Icons.Outlined.Description
    QuickAccessKind.SD_CARD -> Icons.Outlined.SdStorage
}

private fun favoriteLabel(uri: FileUri): String {
    val s = uri.toStorageString().trimEnd('/')
    return s.substringAfterLast('/').ifEmpty { s }
}
