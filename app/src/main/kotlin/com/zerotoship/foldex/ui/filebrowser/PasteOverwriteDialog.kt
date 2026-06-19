package com.zerotoship.foldex.ui.filebrowser

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.zerotoship.foldex.core.model.FileNode

/**
 * 貼り付け時、宛先に同名ファイルがある場合の確認ダイアログ。
 * 「すべて上書き」「キャンセル」の 2 択 (1 件ずつの選択肢が要るならフェーズ追加)。
 */
@Composable
fun PasteOverwriteDialog(
    conflicts: List<FileNode>,
    onOverwrite: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("同じ名前のファイルがあります") },
        text = {
            Column {
                Text(
                    text = "${conflicts.size} 件、貼り付け先に同じ名前のファイル/フォルダがあります。\n上書きして貼り付けますか?",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val sample = conflicts.take(5).joinToString("\n") { "・${it.name}" }
                Text(
                    text = if (conflicts.size > 5) "$sample\n…ほか ${conflicts.size - 5} 件" else sample,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onOverwrite) { Text("上書き") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("キャンセル") } },
    )
}
