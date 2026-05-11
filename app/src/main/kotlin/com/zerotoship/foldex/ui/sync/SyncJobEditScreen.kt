package com.zerotoship.foldex.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zerotoship.foldex.core.model.Connection
import com.zerotoship.foldex.core.model.ConflictPolicy
import com.zerotoship.foldex.core.model.SyncDirection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncJobEditScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: SyncJobEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val connections by viewModel.connections.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SyncJobEditEvent.Saved -> {
                    snackbar.showSnackbar("保存しました")
                    onSaved()
                }
                is SyncJobEditEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "同期ジョブを追加" else "同期ジョブを編集") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = { TextButton(onClick = viewModel::save) { Text("保存") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = state.name,
                onValueChange = { v -> viewModel.update { it.copy(name = v) } },
                label = { Text("名前") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            DirectionDropdown(state.direction) { d -> viewModel.update { it.copy(direction = d) } }
            OutlinedTextField(
                value = state.localPath,
                onValueChange = { v -> viewModel.update { it.copy(localPath = v) } },
                label = { Text("ローカルのフォルダ (例: /storage/emulated/0/DCIM)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ConnectionDropdown(
                connections = connections,
                selectedId = state.connectionId,
                onSelected = { id -> viewModel.update { it.copy(connectionId = id) } },
            )
            if (connections.isEmpty()) {
                Text(
                    "リモートの接続が登録されていません。先に「接続を管理」で追加してください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedTextField(
                value = state.remotePath,
                onValueChange = { v -> viewModel.update { it.copy(remotePath = v) } },
                label = { Text("リモートのフォルダ (接続のルートからの相対パス、空欄でルート)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            HorizontalDivider()
            ConflictPolicyDropdown(state.conflictPolicy) { p -> viewModel.update { it.copy(conflictPolicy = p) } }
            OutlinedTextField(
                value = if (state.intervalMinutes == 0) "" else state.intervalMinutes.toString(),
                onValueChange = { v ->
                    val parsed = v.trim().toIntOrNull() ?: 0
                    viewModel.update { it.copy(intervalMinutes = parsed.coerceAtLeast(0)) }
                },
                label = { Text("実行間隔 (分) — 空欄/0 で手動のみ、15 以上で定期実行") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            ToggleRow(
                title = "Wi-Fi のみ",
                description = "従量制でない接続 (Wi-Fi 等) のときだけ実行する",
                checked = state.requiresWifi,
                onCheckedChange = { v -> viewModel.update { it.copy(requiresWifi = v) } },
            )
            ToggleRow(
                title = "充電中のみ",
                description = "端末が充電中のときだけ実行する",
                checked = state.requiresCharging,
                onCheckedChange = { v -> viewModel.update { it.copy(requiresCharging = v) } },
            )
            ToggleRow(
                title = "バッテリー低下時は実行しない",
                description = "バッテリー残量が少ないときは実行を延期する",
                checked = state.requiresBatteryNotLow,
                onCheckedChange = { v -> viewModel.update { it.copy(requiresBatteryNotLow = v) } },
            )
            HorizontalDivider()
            ToggleRow(
                title = "削除も同期する",
                description = "同期元から消えたファイルを同期先からも削除する",
                checked = state.deleteEnabled,
                onCheckedChange = { v -> viewModel.update { it.copy(deleteEnabled = v) } },
            )
            if (state.deleteEnabled) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "同期元のファイルを消すと、次回同期で同期先のファイルも消えます。誤削除に注意してください。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            HorizontalDivider()
            OutlinedTextField(
                value = state.includePatternsText,
                onValueChange = { v -> viewModel.update { it.copy(includePatternsText = v) } },
                label = { Text("含めるパターン (glob、1行1パターン、空欄で全て)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.excludePatternsText,
                onValueChange = { v -> viewModel.update { it.copy(excludePatternsText = v) } },
                label = { Text("除外するパターン (glob、例: **/.git/**, *.tmp)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.maxFileSizeMb,
                onValueChange = { v -> viewModel.update { it.copy(maxFileSizeMb = v.filter(Char::isDigit)) } },
                label = { Text("最大ファイルサイズ (MB、空欄で無制限)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            HorizontalDivider()
            ToggleRow(
                title = "有効",
                description = "オフにすると定期実行も手動実行もしない",
                checked = state.enabled,
                onCheckedChange = { v -> viewModel.update { it.copy(enabled = v) } },
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.isNew) "追加" else "更新")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DropdownField(
    label: String,
    valueText: String,
    contentDescription: String,
    menuContent: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = valueText,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(label) },
            trailingIcon = {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = contentDescription)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            menuContent { expanded = false }
        }
    }
}

@Composable
private fun DirectionDropdown(selected: SyncDirection, onSelected: (SyncDirection) -> Unit) {
    DropdownField(label = "方向", valueText = directionLabel(selected), contentDescription = "方向を選ぶ") { dismiss ->
        SyncDirection.entries.forEach { d ->
            DropdownMenuItem(text = { Text(directionLabel(d)) }, onClick = { onSelected(d); dismiss() })
        }
    }
}

@Composable
private fun ConflictPolicyDropdown(selected: ConflictPolicy, onSelected: (ConflictPolicy) -> Unit) {
    DropdownField(label = "競合解決", valueText = conflictPolicyLabel(selected), contentDescription = "競合解決を選ぶ") { dismiss ->
        ConflictPolicy.entries.forEach { p ->
            DropdownMenuItem(text = { Text(conflictPolicyLabel(p)) }, onClick = { onSelected(p); dismiss() })
        }
    }
}

@Composable
private fun ConnectionDropdown(
    connections: List<Connection>,
    selectedId: String?,
    onSelected: (String) -> Unit,
) {
    val selected = connections.firstOrNull { it.id == selectedId }
    val valueText = when {
        selected != null -> "${selected.name} (${selected.protocol.scheme})"
        selectedId != null -> "(不明な接続)"
        else -> "未選択"
    }
    DropdownField(label = "リモートの接続", valueText = valueText, contentDescription = "接続を選ぶ") { dismiss ->
        if (connections.isEmpty()) {
            DropdownMenuItem(text = { Text("接続がありません") }, onClick = { dismiss() }, enabled = false)
        } else {
            connections.forEach { c ->
                DropdownMenuItem(
                    text = { Text("${c.name} (${c.protocol.scheme})") },
                    onClick = { onSelected(c.id); dismiss() },
                )
            }
        }
    }
}
