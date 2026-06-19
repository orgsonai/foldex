// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.settings

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLogScreen(
    onBack: () -> Unit,
    viewModel: AppLogViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val permanentLogUri by viewModel.permanentLogUri.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    var confirmClear by remember { mutableStateOf(false) }

    // 永久ログの保存先 (.log) をユーザーに手動作成させる。
    // MIME に "text/plain" を使うと SAF が拡張子 .txt を補って "foldex.log.txt" になってしまうため、
    // 標準拡張子を持たない "application/octet-stream" を使い、指定した "foldex.log" のまま作らせる。
    val createLogFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.setPermanentLog(uri.toString())
            Toast.makeText(context, "永久ログの保存先を設定しました", Toast.LENGTH_SHORT).show()
        }
    }

    // 新しい行が追加されたら自動で末尾へスクロール。
    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) {
            listState.animateScrollToItem(state.lines.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("実行ログ") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "再読み込み")
                    }
                    IconButton(onClick = {
                        val text = state.filteredLines.joinToString("\n")
                        if (text.isNotEmpty()) {
                            clipboard.setText(AnnotatedString(text))
                            Toast.makeText(context, "ログをコピーしました", Toast.LENGTH_SHORT).show()
                        }
                    }) { Icon(Icons.Default.ContentCopy, contentDescription = "ログをコピー") }
                    IconButton(onClick = {
                        val f = viewModel.logFile()
                        if (f.exists() && f.length() > 0) {
                            val uri = FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", f,
                            )
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { context.startActivity(Intent.createChooser(send, "ログを共有")) }
                        }
                    }) { Icon(Icons.Default.Share, contentDescription = "ログを共有") }
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "ログを消す")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 永久ログ: ユーザーが選んだ .log ファイルへ書き込みのたびに累計追記する。
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (permanentLogUri == null) "永久保存: オフ" else "永久保存: オン",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (permanentLogUri == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { createLogFile.launch("foldex.log") }) {
                    Text(if (permanentLogUri == null) "保存先を設定" else "保存先を変更")
                }
                if (permanentLogUri != null) {
                    TextButton(onClick = {
                        viewModel.setPermanentLog(null)
                        Toast.makeText(context, "永久保存を解除しました", Toast.LENGTH_SHORT).show()
                    }) { Text("解除") }
                }
            }
            HorizontalDivider()

            // フィルタ: 全部 / 情報 / 警告以上 / エラー
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    AppLogUiState.Filter.ALL to "全部",
                    AppLogUiState.Filter.INFO to "情報",
                    AppLogUiState.Filter.WARN to "警告以上",
                    AppLogUiState.Filter.ERROR to "エラー",
                ).forEach { (f, label) ->
                    FilterChip(
                        selected = state.filter == f,
                        onClick = { viewModel.setFilter(f) },
                        label = { Text(label) },
                    )
                }
            }
            HorizontalDivider()

            val shown = state.filteredLines
            when {
                state.loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                shown.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "ログはまだありません",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                ) {
                    items(shown) { line ->
                        val color = when {
                            line.contains("[ERROR]") -> MaterialTheme.colorScheme.error
                            line.contains("[WARN]") -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        Text(
                            text = line,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color,
                            overflow = TextOverflow.Visible,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("ログを消去") },
            text = { Text("保存されたログを全て削除します。") },
            confirmButton = {
                TextButton(onClick = { viewModel.clear(); confirmClear = false }) { Text("消去") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("キャンセル") }
            },
        )
    }
}
