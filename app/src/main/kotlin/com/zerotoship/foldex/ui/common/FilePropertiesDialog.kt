// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.common

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zerotoship.foldex.R
import com.zerotoship.foldex.core.model.FileNode
import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.core.model.NodeType
import kotlinx.coroutines.launch

/**
 * ファイル/フォルダのプロパティ。
 *
 * 基本情報はすぐ出し、実体を読まないと分からないもの (解像度・EXIF・フォルダの合計サイズ) は
 * 後から足す。ハッシュは大きいファイルだと時間がかかるので、押されたときだけ計算する。
 *
 * どの行も**長押しで値をコピー**できる。パスやハッシュを他所に貼りたいことが多いため。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilePropertiesDialog(node: FileNode, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    val basic = remember(node, context) { FileDetails.basicSections(context, node) }
    var extra by remember(node) { mutableStateOf<List<DetailSection>>(emptyList()) }
    var loadingExtra by remember(node) { mutableStateOf(false) }
    var hashRows by remember(node) { mutableStateOf<List<DetailRow>>(emptyList()) }
    var hashing by remember(node) { mutableStateOf(false) }

    val isLocal = node.uri is FileUri.Local
    val isFile = node.type == NodeType.FILE

    LaunchedEffect(node) {
        if (!isLocal) return@LaunchedEffect
        loadingExtra = true
        extra = FileDetails.loadExtraSections(context, node)
        loadingExtra = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.prop_title)) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                (basic + extra).forEachIndexed { index, section ->
                    if (section.title != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            section.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                    } else if (index > 0) {
                        Spacer(Modifier.height(12.dp))
                    }
                    section.rows.forEach { row -> PropertyRow(row) { copy(scope, clipboard, context, it) } }
                }

                if (loadingExtra) {
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }

                if (hashRows.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.prop_hash_section),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    hashRows.forEach { row -> PropertyRow(row) { copy(scope, clipboard, context, it) } }
                }

                // ローカルのファイルだけ。フォルダやリモートでは意味がない。
                if (isLocal && isFile && hashRows.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    TextButton(
                        onClick = {
                            hashing = true
                            scope.launch {
                                hashRows = FileDetails.computeHashes(context, node)
                                hashing = false
                            }
                        },
                        enabled = !hashing,
                    ) {
                        Text(stringResource(if (hashing) R.string.prop_computing else R.string.prop_compute_hashes))
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.prop_long_press_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PropertyRow(row: DetailRow, onCopy: (DetailRow) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = { onCopy(row) })
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = row.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = row.value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (row.mono) FontFamily.Monospace else null,
        )
    }
}

private fun copy(
    scope: kotlinx.coroutines.CoroutineScope,
    clipboard: androidx.compose.ui.platform.Clipboard,
    context: android.content.Context,
    row: DetailRow,
) {
    scope.launch {
        clipboard.setPlainText(row.label, row.value)
        Toast.makeText(context, context.getString(R.string.prop_copied, row.label), Toast.LENGTH_SHORT).show()
    }
}
