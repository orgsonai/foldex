// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.zerotoship.foldex.core.model.DeleteBehavior

/**
 * 削除の確認ダイアログ。
 *
 * ファイルブラウザと HOME の「画像」/「動画」の両方から使う。どちらから削除しても
 * 同じ文言・同じ選択肢になるように、ここ 1 か所にまとめている。
 *
 * @param count 削除対象の件数。
 * @param singleName 1 件のときのファイル名。`null` なら件数だけを出す。
 * @param defaultBehavior 設定「削除の行き先」。[askDestination] が false のときはこれで確定する。
 * @param askDestination true なら「ゴミ箱へ移動 / 完全に削除」をその場で選ばせる。
 * @param trashSupported ゴミ箱に入れられるか。リモート/SAF のように退避できない対象では
 *   false を渡す。false のときは選択肢を出さず、完全削除であることをはっきり書く。
 */
@Composable
fun DeleteConfirmDialog(
    count: Int,
    singleName: String?,
    defaultBehavior: DeleteBehavior,
    askDestination: Boolean,
    trashSupported: Boolean = true,
    onConfirm: (DeleteBehavior) -> Unit,
    onDismiss: () -> Unit,
) {
    // ゴミ箱に入れられない対象は、設定が何であれ完全削除しかない。
    val canChoose = askDestination && trashSupported
    var chosen by remember {
        mutableStateOf(
            when {
                !trashSupported -> DeleteBehavior.PERMANENT
                askDestination -> DeleteBehavior.TRASH
                else -> defaultBehavior
            },
        )
    }
    val countText = if (count == 1 && singleName != null) "「$singleName」" else "${count}件のアイテム"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("削除の確認") },
        text = {
            Column {
                when {
                    canChoose -> {
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
                    !trashSupported ->
                        Text("${countText}を削除しますか？\nこの場所はゴミ箱に対応していないため、完全に削除されます。")
                    chosen == DeleteBehavior.TRASH -> Text("${countText}をゴミ箱に移動しますか？")
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
