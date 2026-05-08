package com.zerotoship.foldex.ui.filebrowser

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.zerotoship.foldex.core.model.FileNode

@Composable
fun DeleteConfirmDialog(
    nodes: List<FileNode>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("削除の確認") },
        text = {
            val desc = if (nodes.size == 1) {
                "「${nodes[0].name}」を削除しますか？\nこの操作は元に戻せません。"
            } else {
                "${nodes.size}件のアイテムを削除しますか？\nこの操作は元に戻せません。"
            }
            Text(desc)
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("削除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}
