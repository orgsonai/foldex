// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.filebrowser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import com.zerotoship.foldex.core.model.DeleteBehavior
import com.zerotoship.foldex.core.model.FileNode

@Composable
fun DeleteConfirmDialog(
    nodes: List<FileNode>,
    defaultBehavior: DeleteBehavior,
    /** true なら「ゴミ箱へ移動 / 完全に削除」を選ばせる ([defaultBehavior] が ASK のとき)。 */
    askDestination: Boolean,
    onConfirm: (DeleteBehavior) -> Unit,
    onDismiss: () -> Unit,
) {
    var chosen by remember { mutableStateOf(if (askDestination) DeleteBehavior.TRASH else defaultBehavior) }
    val countText = if (nodes.size == 1) "「${nodes[0].name}」" else "${nodes.size}件のアイテム"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("削除の確認") },
        text = {
            Column {
                when {
                    askDestination -> {
                        Text("${countText}をどうしますか？")
                        Spacer(Modifier.height(8.dp))
                        Column(Modifier.selectableGroup()) {
                            DestinationRow("ゴミ箱へ移動 (後で復元できます)", chosen == DeleteBehavior.TRASH) {
                                chosen = DeleteBehavior.TRASH
                            }
                            DestinationRow("完全に削除 (元に戻せません)", chosen == DeleteBehavior.PERMANENT) {
                                chosen = DeleteBehavior.PERMANENT
                            }
                        }
                    }
                    defaultBehavior == DeleteBehavior.TRASH -> Text("${countText}をゴミ箱に移動しますか？")
                    else -> Text("${countText}を削除しますか？\nこの操作は元に戻せません。")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(chosen) }) {
                Text(if (chosen == DeleteBehavior.TRASH) "ゴミ箱へ移動" else "削除")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
private fun DestinationRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.size(8.dp))
        Text(label)
    }
}
