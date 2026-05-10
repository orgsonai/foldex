package com.zerotoship.foldex.ui.servers

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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zerotoship.foldex.core.model.ServerAuthMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: ServerEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ServerEditViewModel.ServerEditEvent.Saved -> {
                    snackbar.showSnackbar("保存しました")
                    onSaved()
                }
                is ServerEditViewModel.ServerEditEvent.Message ->
                    snackbar.showSnackbar(event.text)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "サーバーを追加" else "サーバーを編集") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::save) { Text("保存") }
                },
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
            OutlinedTextField(
                value = state.port.toString(),
                onValueChange = { v ->
                    val parsed = v.toIntOrNull()
                    if (parsed != null) viewModel.update { it.copy(port = parsed) }
                },
                label = { Text("ポート (SFTP 既定: 2022)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.rootPath,
                onValueChange = { v -> viewModel.update { it.copy(rootPath = v) } },
                label = { Text("ルートパス (例: /storage/emulated/0)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ToggleRow(
                title = "Wi-Fi 限定",
                description = "オン: Wi-Fi 接続中のみ起動可。オフ: 全インターフェースに bind",
                checked = state.wifiOnlyMode,
                onCheckedChange = { v -> viewModel.update { it.copy(wifiOnlyMode = v) } },
            )
            ToggleRow(
                title = "読み取り専用",
                description = "クライアントからの書き込みを禁止する",
                checked = state.readOnly,
                onCheckedChange = { v -> viewModel.update { it.copy(readOnly = v) } },
            )
            HorizontalDivider()
            AuthModeDropdown(
                selected = state.authMode,
                onSelected = viewModel::changeAuthMode,
            )
            if (state.authMode != ServerAuthMode.ANONYMOUS) {
                OutlinedTextField(
                    value = state.username,
                    onValueChange = { v -> viewModel.update { it.copy(username = v) } },
                    label = { Text("ユーザー名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.authMode == ServerAuthMode.PASSWORD ||
                state.authMode == ServerAuthMode.PASSWORD_OR_PUBLIC_KEY
            ) {
                OutlinedTextField(
                    value = state.password,
                    onValueChange = { v -> viewModel.update { it.copy(password = v) } },
                    label = {
                        Text(if (state.isNew) "パスワード (必須)" else "新しいパスワード (空のまま維持)")
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.authMode == ServerAuthMode.PUBLIC_KEY ||
                state.authMode == ServerAuthMode.PASSWORD_OR_PUBLIC_KEY
            ) {
                OutlinedTextField(
                    value = state.authorizedKeys,
                    onValueChange = { v -> viewModel.update { it.copy(authorizedKeys = v) } },
                    label = { Text("authorized_keys (1行1鍵)") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            HorizontalDivider()
            ToggleRow(
                title = "アプリ起動時に自動起動",
                description = "アプリを開いたタイミングでこのサーバーを起動する",
                checked = state.autoStartOnAppLaunch,
                onCheckedChange = { v -> viewModel.update { it.copy(autoStartOnAppLaunch = v) } },
            )
            ToggleRow(
                title = "端末再起動後に自動起動",
                description = "BOOT_COMPLETED 受信時に起動する (P6 後半で実装予定)",
                checked = state.autoStartOnBoot,
                onCheckedChange = { v -> viewModel.update { it.copy(autoStartOnBoot = v) } },
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
private fun AuthModeDropdown(
    selected: ServerAuthMode,
    onSelected: (ServerAuthMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = authModeLabel(selected),
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text("認証方式") },
            trailingIcon = {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "認証方式を選ぶ")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ServerAuthMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(authModeLabel(mode)) },
                    onClick = {
                        onSelected(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun authModeLabel(mode: ServerAuthMode): String = when (mode) {
    ServerAuthMode.ANONYMOUS -> "匿名 (anonymous)"
    ServerAuthMode.PASSWORD -> "パスワード"
    ServerAuthMode.PUBLIC_KEY -> "公開鍵"
    ServerAuthMode.PASSWORD_OR_PUBLIC_KEY -> "パスワード または 公開鍵"
}
