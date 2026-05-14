package com.zerotoship.foldex.ui.filebrowser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * コピー/移動/共有保存などの長時間ファイル操作を一覧上部に出すバナー。
 * 1行目: ラベル + 件数 (5/12 など) + パーセンテージ
 * 2行目: 現在処理しているファイル名
 * 3行目: LinearProgressIndicator (totalBytes <= 0 は不定の indeterminate)
 */
@Composable
fun FileOpProgressBanner(
    progress: FileOpProgress,
    modifier: Modifier = Modifier,
) {
    val fraction = if (progress.totalBytes > 0) {
        (progress.bytesTransferred.toFloat() / progress.totalBytes).coerceIn(0f, 1f)
    } else {
        -1f
    }
    val percent = if (fraction >= 0f) "${(fraction * 100).toInt()}%" else "—"
    val transferredText = formatBytes(progress.bytesTransferred)
    val totalText = if (progress.totalBytes > 0) formatBytes(progress.totalBytes) else "?"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                progress.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            // weight(1f) で残り幅を Spacer に充てる (旧: Spacer.fillMaxWidth() は隣を押し出してた)。
            Spacer(Modifier.weight(1f))
            Text(
                "${progress.currentIndex} / ${progress.totalCount}   $percent",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            progress.currentName.ifEmpty { "—" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "$transferredText / $totalText",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(Modifier.height(4.dp))
        if (fraction >= 0f) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var b = bytes.toDouble()
    var i = 0
    while (b >= 1024 && i < units.lastIndex) { b /= 1024; i++ }
    return String.format(Locale.US, if (i == 0) "%.0f %s" else "%.1f %s", b, units[i])
}
