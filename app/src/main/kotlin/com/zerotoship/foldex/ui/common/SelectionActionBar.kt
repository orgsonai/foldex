// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 長押しで選択モードに入ったときに出る操作バー。
 *
 * ファイル一覧と HOME の「画像」/「動画」で**同じ位置・同じ並び・同じアイコン**にするため、
 * 実体をここ 1 つにしている。画面ごとに違うのは ⋮ の中身だけで、それは [overflow] で渡す。
 *
 * 並びは 全選択 / コピー / 切り取り / 名前変更 / 削除 / ⋮ の順に固定する。
 * 下に置いているのは、選択中に何度も押す操作なので片手で届く必要があるため。
 *
 * @param selectedCount 選択件数。名前変更のように 1 件のときしか使えないものの判定に使う。
 * @param overflow ⋮ を開いたときの中身。引数の関数を呼ぶとメニューが閉じる。
 */
@Composable
fun SelectionActionBar(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    overflow: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    BottomAppBar {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Default.SelectAll, contentDescription = "全選択")
            }
            IconButton(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, contentDescription = "コピー")
            }
            IconButton(onClick = onCut) {
                Icon(Icons.Default.ContentCut, contentDescription = "切り取り")
            }
            IconButton(onClick = onRename, enabled = selectedCount == 1) {
                Icon(Icons.Default.DriveFileRenameOutline, contentDescription = "名前変更")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "削除",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "その他")
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    overflow { menuOpen = false }
                }
            }
        }
    }
}
