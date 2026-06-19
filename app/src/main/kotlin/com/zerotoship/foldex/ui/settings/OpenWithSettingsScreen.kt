// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zerotoship.foldex.core.data.repo.OpenWithMode

// 設定画面で一覧表示する代表的な拡張子。これ以外でユーザーが上書きしたものも追加で表示する。
private val COMMON_EXTENSIONS = listOf(
    "txt", "log", "json", "xml", "csv", "md", "html", "htm",
    "jpg", "png", "gif", "webp", "svg",
    "mp3", "m4a", "flac", "wav",
    "mp4", "mkv", "mov",
    "pdf", "zip", "7z", "rar", "apk",
    "doc", "docx", "xls", "xlsx", "ppt", "pptx",
)

private val OpenWithMode.label: String
    get() = when (this) {
        OpenWithMode.DEFAULT -> "自動 (内蔵対応なら内蔵)"
        OpenWithMode.BUILTIN -> "内蔵ビューア"
        OpenWithMode.ASK -> "毎回選択"
        OpenWithMode.EXTERNAL -> "外部アプリ"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenWithSettingsScreen(
    onBack: () -> Unit,
    viewModel: OpenWithSettingsViewModel = hiltViewModel(),
) {
    val overrides by viewModel.overrides.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }

    val rows = remember(overrides) {
        (COMMON_EXTENSIONS + overrides.keys).distinct().sorted()
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("ファイルの開き方") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = "拡張子を追加")
            }
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxWidth()) {
            item {
                Text(
                    "拡張子ごとにファイルの開き方を指定できます。指定しないものは「自動」です。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                HorizontalDivider()
            }
            items(rows, key = { it }) { ext ->
                ExtensionRow(
                    ext = ext,
                    mode = overrides[ext] ?: OpenWithMode.DEFAULT,
                    onModeChange = { viewModel.set(ext, it) },
                )
                HorizontalDivider()
            }
        }
    }

    if (showAdd) {
        AddExtensionDialog(
            onConfirm = { ext, mode -> viewModel.set(ext, mode); showAdd = false },
            onDismiss = { showAdd = false },
        )
    }
}

@Composable
private fun ExtensionRow(ext: String, mode: OpenWithMode, onModeChange: (OpenWithMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(".$ext", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { expanded = true }) { Text(mode.label) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                OpenWithMode.entries.forEach { m ->
                    DropdownMenuItem(text = { Text(m.label) }, onClick = { onModeChange(m); expanded = false })
                }
            }
        }
    }
}

@Composable
private fun AddExtensionDialog(onConfirm: (String, OpenWithMode) -> Unit, onDismiss: () -> Unit) {
    var ext by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(OpenWithMode.BUILTIN) }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("拡張子を追加") },
        text = {
            Column {
                OutlinedTextField(
                    value = ext,
                    onValueChange = { ext = it.removePrefix(".").lowercase().filter { c -> c.isLetterOrDigit() } },
                    label = { Text("拡張子 (例: epub)") },
                    singleLine = true,
                )
                Box {
                    TextButton(onClick = { expanded = true }) { Text("開き方: ${mode.label}") }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        OpenWithMode.entries.forEach { m ->
                            DropdownMenuItem(text = { Text(m.label) }, onClick = { mode = m; expanded = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (ext.isNotEmpty()) onConfirm(ext, mode) }, enabled = ext.isNotEmpty()) {
                Text("追加")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}
