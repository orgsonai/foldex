package com.zerotoship.foldex.ui.connections

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.zerotoship.foldex.core.model.Connection
import com.zerotoship.foldex.core.model.Protocol

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConnectionsScreen(
    onBack: () -> Unit,
    onOpen: (Connection) -> Unit,
    viewModel: ConnectionsViewModel = hiltViewModel(),
) {
    val connections by viewModel.connections.collectAsStateWithLifecycle()
    val editing by viewModel.editing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<Connection?>(null) }
    var protocolMenuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ConnectionsViewModel.ConnectionEvent.Message ->
                    snackbar.showSnackbar(event.text)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("接続") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { protocolMenuOpen = true }) {
                    Icon(Icons.Default.Add, contentDescription = "接続を追加")
                }
                DropdownMenu(
                    expanded = protocolMenuOpen,
                    onDismissRequest = { protocolMenuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("SMB を追加") },
                        onClick = {
                            protocolMenuOpen = false
                            viewModel.startCreate(Protocol.SMB)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("SFTP を追加") },
                        onClick = {
                            protocolMenuOpen = false
                            viewModel.startCreate(Protocol.SFTP)
                        },
                    )
                }
            }
        },
    ) { padding ->
        if (connections.isEmpty()) {
            Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.Storage,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "保存された接続はありません",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "右下の + ボタンから追加",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                items(connections, key = { it.id }) { connection ->
                    ListItem(
                        headlineContent = { Text(connection.name) },
                        supportingContent = {
                            Text(connection.summary(), style = MaterialTheme.typography.bodySmall)
                        },
                        leadingContent = {
                            Icon(Icons.Outlined.Storage, contentDescription = null)
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { viewModel.startEdit(connection) }) {
                                    Icon(Icons.Default.Edit, contentDescription = "編集")
                                }
                                IconButton(onClick = { pendingDelete = connection }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "削除",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        },
                        modifier = Modifier.combinedClickable(
                            onClick = { onOpen(connection) },
                            onLongClick = { viewModel.startEdit(connection) },
                        ),
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    editing?.let { state ->
        ConnectionEditDialog(
            state = state,
            onUpdate = viewModel::updateField,
            onProtocolChange = viewModel::changeProtocol,
            onSave = viewModel::save,
            onDismiss = viewModel::cancelEdit,
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("接続を削除") },
            text = { Text("「${target.name}」を削除します。よろしいですか?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(target)
                        pendingDelete = null
                    },
                ) { Text("削除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("キャンセル") }
            },
        )
    }
}

private fun Connection.summary(): String = when (this) {
    is Connection.Smb -> {
        val auth = if (authMethod.wireName == "anonymous") "匿名" else (username ?: "user?")
        "smb://$host:$port/$share  ($auth)"
    }
    is Connection.Sftp -> {
        val fp = if (hostKeyFingerprint.isNullOrBlank()) "未検証" else "鍵OK"
        "sftp://${username ?: "?"}@$host:$port  ($fp)"
    }
    else -> "${protocol.scheme}://$host:$port"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionEditDialog(
    state: ConnectionsViewModel.EditingState,
    onUpdate: ((ConnectionsViewModel.EditingState) -> ConnectionsViewModel.EditingState) -> Unit,
    onProtocolChange: (Protocol) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = when (state.protocol) {
        Protocol.SMB -> if (state.isNew) "SMB 接続を追加" else "SMB 接続を編集"
        Protocol.SFTP -> if (state.isNew) "SFTP 接続を追加" else "SFTP 接続を編集"
        else -> "接続"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (state.isNew) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.protocol == Protocol.SMB,
                            onClick = { onProtocolChange(Protocol.SMB) },
                            label = { Text("SMB") },
                        )
                        FilterChip(
                            selected = state.protocol == Protocol.SFTP,
                            onClick = { onProtocolChange(Protocol.SFTP) },
                            label = { Text("SFTP") },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = state.name,
                    onValueChange = { v -> onUpdate { it.copy(name = v) } },
                    label = { Text("表示名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedTextField(
                        value = state.host,
                        onValueChange = { v -> onUpdate { it.copy(host = v) } },
                        label = { Text("ホスト") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(
                        value = state.port.toString(),
                        onValueChange = { v ->
                            v.toIntOrNull()?.let { p -> onUpdate { st -> st.copy(port = p) } }
                                ?: if (v.isEmpty()) onUpdate { st -> st.copy(port = state.protocol.defaultPort) } else Unit
                        },
                        label = { Text("ポート") },
                        singleLine = true,
                        modifier = Modifier.size(width = 96.dp, height = 56.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                when (state.protocol) {
                    Protocol.SMB -> SmbExtraFields(state, onUpdate)
                    Protocol.SFTP -> SftpExtraFields(state, onUpdate)
                    else -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}

@Composable
private fun SmbExtraFields(
    state: ConnectionsViewModel.EditingState,
    onUpdate: ((ConnectionsViewModel.EditingState) -> ConnectionsViewModel.EditingState) -> Unit,
) {
    OutlinedTextField(
        value = state.share,
        onValueChange = { v -> onUpdate { it.copy(share = v) } },
        label = { Text("共有名") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = state.domain,
        onValueChange = { v -> onUpdate { it.copy(domain = v) } },
        label = { Text("ドメイン (任意)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = state.anonymous,
            onCheckedChange = { v -> onUpdate { it.copy(anonymous = v) } },
        )
        Text("匿名アクセス")
    }
    if (!state.anonymous) {
        UserPasswordFields(state, onUpdate)
    }
}

@Composable
private fun SftpExtraFields(
    state: ConnectionsViewModel.EditingState,
    onUpdate: ((ConnectionsViewModel.EditingState) -> ConnectionsViewModel.EditingState) -> Unit,
) {
    UserPasswordFields(state, onUpdate)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = state.hostKeyFingerprint,
        onValueChange = { v -> onUpdate { it.copy(hostKeyFingerprint = v) } },
        label = { Text("ホスト鍵 SHA-256 (任意/初回は空でOK)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun UserPasswordFields(
    state: ConnectionsViewModel.EditingState,
    onUpdate: ((ConnectionsViewModel.EditingState) -> ConnectionsViewModel.EditingState) -> Unit,
) {
    OutlinedTextField(
        value = state.username,
        onValueChange = { v -> onUpdate { it.copy(username = v) } },
        label = { Text("ユーザー名") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = state.password,
        onValueChange = { v -> onUpdate { it.copy(password = v) } },
        label = {
            Text(if (state.isNew) "パスワード" else "パスワード (変更時のみ)")
        },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}
