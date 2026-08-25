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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zerotoship.foldex.R
import com.zerotoship.foldex.ui.common.setPlainText
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLogScreen(
    onBack: () -> Unit,
    viewModel: AppLogViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val permanentLogUri by viewModel.permanentLogUri.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
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
            Toast.makeText(context, resources.getString(R.string.log_permanent_set), Toast.LENGTH_SHORT).show()
        }
    }

    // 新しい行が追加されたら自動で末尾へスクロール。
    // maxValue はレイアウトが終わるまで 0 なので、1 フレーム待ってから動かす。
    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) {
            withFrameNanos { }
            listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_logs)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_reload))
                    }
                    IconButton(onClick = {
                        val text = state.filteredLines.joinToString("\n")
                        if (text.isNotEmpty()) {
                            scope.launch {
                                clipboard.setPlainText(resources.getString(R.string.log_clipboard_label), text)
                                Toast.makeText(context, resources.getString(R.string.log_copied), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) { Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.log_copy)) }
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
                            runCatching { context.startActivity(Intent.createChooser(send, resources.getString(R.string.log_share))) }
                        }
                    }) { Icon(Icons.Default.Share, contentDescription = stringResource(R.string.log_share)) }
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.log_delete))
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
                    text = stringResource(if (permanentLogUri == null) R.string.log_permanent_off else R.string.log_permanent_on),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (permanentLogUri == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { createLogFile.launch("foldex.log") }) {
                    Text(stringResource(if (permanentLogUri == null) R.string.log_destination_set else R.string.log_destination_change))
                }
                if (permanentLogUri != null) {
                    TextButton(onClick = {
                        viewModel.setPermanentLog(null)
                        Toast.makeText(context, resources.getString(R.string.log_permanent_disabled), Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.action_disable)) }
                }
            }
            HorizontalDivider()

            // フィルタ: 全部 / 情報 / 警告以上 / エラー
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    AppLogUiState.Filter.ALL to R.string.log_filter_all,
                    AppLogUiState.Filter.INFO to R.string.log_filter_info,
                    AppLogUiState.Filter.WARN to R.string.log_filter_warn,
                    AppLogUiState.Filter.ERROR to R.string.log_filter_error,
                ).forEach { (f, label) ->
                    FilterChip(
                        selected = state.filter == f,
                        onClick = { viewModel.setFilter(f) },
                        label = { Text(stringResource(label)) },
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
                            stringResource(R.string.log_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                else -> {
                    // 背景の明るさからライト/ダークを判定する (テーマ設定で system を上書きできるため、
                    // isSystemInDarkTheme() ではなく実際に適用中の配色から決める)。
                    val palette = logPalette(
                        dark = MaterialTheme.colorScheme.background.luminance() < 0.5f,
                        primary = MaterialTheme.colorScheme.primary,
                        error = MaterialTheme.colorScheme.error,
                    )
                    // 行ごとの Text を LazyColumn に並べる。内蔵ビューアの ReadOnlyText と同じ形。
                    //
                    // 全行を 1 つの Text に載せる形 (1.5.6〜1.6.2) は、1 行あたり最大 9 個の
                    // スパンが数千個になり、なぞり選択のヒットテストが追いつかなかった。
                    // 500 行に絞っても足りなかったので、器ごと変える。
                    // 行をまたいだ選択は SelectionContainer が LazyColumn 越しでも扱える。
                    val body = remember(state.lines, state.filter, palette) {
                        buildLogLines(shown, palette)
                    }
                    SelectionContainer {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            items(count = body.size, key = { it }) { i ->
                                Text(
                                    text = body[i],
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.log_clear_title)) },
            text = { Text(stringResource(R.string.log_clear_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.clear(); confirmClear = false }) { Text(stringResource(R.string.action_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
