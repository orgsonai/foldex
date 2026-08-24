// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.filebrowser

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.zerotoship.foldex.R
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
        title = { Text(stringResource(R.string.paste_conflict_title)) },
        text = {
            Column {
                Text(
                    text = pluralStringResource(R.plurals.paste_conflict_body, conflicts.size, conflicts.size),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val sample = conflicts.take(5).joinToString("\n") { "・${it.name}" }
                Text(
                    text = if (conflicts.size > 5) "$sample\n${pluralStringResource(R.plurals.paste_conflict_more, conflicts.size - 5, conflicts.size - 5)}" else sample,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onOverwrite) { Text(stringResource(R.string.action_overwrite)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}
