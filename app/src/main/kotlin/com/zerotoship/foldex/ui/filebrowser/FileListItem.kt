package com.zerotoship.foldex.ui.filebrowser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zerotoship.foldex.core.model.FileNode
import com.zerotoship.foldex.core.model.NodeType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 行アイテムはスクロール中に大量に compose/measure されるため、
// 無駄な描画 (選択時以外の background 塗り = overdraw) や余計なアロケーションを避ける。

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListItem(
    node: FileNode,
    selected: Boolean = false,
    showBadge: Boolean = true,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (selected) Modifier.background(colors.secondaryContainer) else Modifier)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileLeadingIcon(node = node, selected = selected, size = 24.dp)
        Spacer(Modifier.width(16.dp))
        Text(
            text = node.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (showBadge) {
            Spacer(Modifier.width(8.dp))
            ExtensionBadge(node)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileDetailedItem(
    node: FileNode,
    selected: Boolean = false,
    showBadge: Boolean = true,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // 詳細表示では日付だけでなく時刻 (分まで) も出す。端末のタイムゾーン/ロケールに従う。
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val subtitle = remember(node.size, node.lastModified) {
        buildString {
            if (node.size >= 0) append(formatSize(node.size))
            node.lastModified?.toEpochMilliseconds()?.let { ms ->
                if (isNotEmpty()) append("  ")
                append(dateFmt.format(Date(ms)))
            }
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (selected) Modifier.background(colors.secondaryContainer) else Modifier)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileLeadingIcon(node = node, selected = selected, size = 24.dp)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        if (showBadge) {
            Spacer(Modifier.width(8.dp))
            ExtensionBadge(node)
        }
    }
}

fun formatSize(bytes: Long): String = when {
    bytes < 1_024L -> "${bytes}B"
    bytes < 1_048_576L -> "${"%.1f".format(bytes / 1_024f)}KB"
    bytes < 1_073_741_824L -> "${"%.1f".format(bytes / 1_048_576f)}MB"
    else -> "${"%.1f".format(bytes / 1_073_741_824f)}GB"
}
