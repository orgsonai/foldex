package com.zerotoship.foldex.ui.filebrowser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.zerotoship.foldex.core.model.FileNode
import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.core.model.NodeType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FilePropertiesDialog(node: FileNode, onDismiss: () -> Unit) {
    val fmt = remember()
    val items = buildList {
        add("名前" to node.name)
        add("種類" to if (node.type == NodeType.DIRECTORY) "フォルダ" else "ファイル")
        add("場所" to when (val u = node.uri) {
            is FileUri.Local -> u.absolutePath
            is FileUri.Saf -> u.toStorageString()
            is FileUri.Remote -> "${u.protocol.name.lowercase()}://${u.connectionId}${u.path}"
        })
        if (node.type == NodeType.FILE) {
            add("サイズ" to "${formatBytes(node.size)}  (${node.size} B)")
        }
        node.lastModified?.toEpochMilliseconds()?.let { ms -> add("更新日時" to fmt.format(Date(ms))) }
        if (node.isHidden) add("属性" to "隠しファイル")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("プロパティ") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                items.forEach { (k, v) ->
                    Text(
                        text = k,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = v,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = if (k == "場所") FontFamily.Monospace else null,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

@Composable
private fun remember(): SimpleDateFormat = androidx.compose.runtime.remember {
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
}

private fun formatBytes(b: Long): String {
    if (b <= 0) return "0 B"
    val u = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = b.toDouble(); var i = 0
    while (v >= 1024 && i < u.lastIndex) { v /= 1024; i++ }
    return String.format(Locale.US, if (i == 0) "%.0f %s" else "%.1f %s", v, u[i])
}
