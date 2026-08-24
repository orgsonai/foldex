// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.connections

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
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
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.zerotoship.foldex.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zerotoship.foldex.core.model.Connection
import com.zerotoship.foldex.core.model.Protocol
import com.zerotoship.foldex.ui.common.setPlainText
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

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
                title = { Text(stringResource(R.string.connections_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_back))
                    }
                },
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { protocolMenuOpen = true }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.connections_add))
                }
                DropdownMenu(
                    expanded = protocolMenuOpen,
                    onDismissRequest = { protocolMenuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.connections_add_protocol, "SMB")) },
                        onClick = {
                            protocolMenuOpen = false
                            viewModel.startCreate(Protocol.SMB)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.connections_add_protocol, "SFTP")) },
                        onClick = {
                            protocolMenuOpen = false
                            viewModel.startCreate(Protocol.SFTP)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.connections_add_protocol, "FTP")) },
                        onClick = {
                            protocolMenuOpen = false
                            viewModel.startCreate(Protocol.FTP)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.connections_add_protocol, "WebDAV")) },
                        onClick = {
                            protocolMenuOpen = false
                            viewModel.startCreate(Protocol.WEBDAV)
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
                        stringResource(R.string.connections_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.connections_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            // ドラッグ中はローカル liveOrder に楽観更新し、ドロップ時に ViewModel へ確定保存する
            // (StateFlow を毎フレーム更新するとドラッグ位置と競合してチラつくため)。
            var liveOrder by remember(connections) { mutableStateOf(connections) }
            val listState = rememberLazyListState()
            val reorderState = rememberReorderableLazyListState(listState) { from, to ->
                liveOrder = liveOrder.toMutableList().apply {
                    add(to.index, removeAt(from.index))
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) {
                items(liveOrder, key = { it.id }) { connection ->
                    ReorderableItem(reorderState, key = connection.id) { _ ->
                        // 行全体を長押し = ドラッグ開始。タップ = 開く。編集は右の鉛筆ボタンから。
                        // (以前は長押し=編集だったが、並び替えのつもりで編集画面に飛んでしまうため変更)
                        val dragHandle = Modifier.longPressDraggableHandle(
                            onDragStopped = {
                                val newIds = liveOrder.map { it.id }
                                if (newIds != connections.map { it.id }) viewModel.applyOrder(newIds)
                            },
                        )
                        ListItem(
                            headlineContent = { Text(connection.name) },
                            supportingContent = {
                                Text(connection.summary(), style = MaterialTheme.typography.bodySmall)
                            },
                            leadingContent = {
                                Icon(Icons.Default.DragHandle, contentDescription = stringResource(R.string.action_reorder))
                            },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { viewModel.startEdit(connection) }) {
                                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_edit))
                                    }
                                    IconButton(onClick = { pendingDelete = connection }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.action_delete),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            },
                            modifier = Modifier
                                .clickable { onOpen(connection) }
                                .then(dragHandle),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    editing?.let { state ->
        ConnectionEditDialog(
            state = state,
            onUpdate = viewModel::updateField,
            onProtocolChange = viewModel::changeProtocol,
            onApplyUri = viewModel::applyUri,
            onGenerateSftpKey = viewModel::generateSftpKeyPair,
            onSave = viewModel::save,
            onDismiss = viewModel::cancelEdit,
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.connections_delete_title)) },
            text = { Text(stringResource(R.string.connections_delete_body, target.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(target)
                        pendingDelete = null
                    },
                ) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun Connection.summary(): String = when (this) {
    is Connection.Smb -> {
        val auth = if (authMethod.wireName == "anonymous") stringResource(R.string.connection_anonymous) else (username ?: "user?")
        "smb://$host:$port/$share  ($auth)"
    }
    is Connection.Sftp -> {
        val fp = stringResource(if (hostKeyFingerprint.isNullOrBlank()) R.string.connection_host_key_unverified else R.string.connection_host_key_ok)
        "sftp://${username ?: "?"}@$host:$port  ($fp)"
    }
    is Connection.Ftp -> {
        val tls = if (useTls) "FTPS" else stringResource(R.string.connection_plaintext)
        val mode = if (passiveMode) "PASV" else "ACTV"
        val auth = if (authMethod.wireName == "anonymous") stringResource(R.string.connection_anonymous) else (username ?: "user?")
        "ftp://$host:$port  ($tls / $mode / $auth)"
    }
    is Connection.WebDav -> {
        val scheme = if (useHttps) "https" else "http"
        val auth = if (authMethod.wireName == "anonymous") stringResource(R.string.connection_anonymous) else (username ?: "user?")
        "$scheme://$host:$port$basePath  ($auth)"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionEditDialog(
    state: ConnectionsViewModel.EditingState,
    onUpdate: ((ConnectionsViewModel.EditingState) -> ConnectionsViewModel.EditingState) -> Unit,
    onProtocolChange: (Protocol) -> Unit,
    onApplyUri: (String) -> Boolean,
    onGenerateSftpKey: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = stringResource(
        if (state.isNew) R.string.connection_add_title else R.string.connection_edit_title,
        state.protocol.name,
    )
    AlertDialog(
        // 範囲外タップ / 戻るキーで誤って閉じないようにする (入力済み内容を失うのを防ぐ)。
        // 閉じるのは「キャンセル」ボタン経由のみ。
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                if (state.isNew) {
                    // ワンライナー入力: sftp://user@host:port/path 形式をペーストすると各欄に分解。
                    var oneLiner by remember { mutableStateOf("") }
                    var oneLinerError by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        value = oneLiner,
                        onValueChange = {
                            oneLiner = it
                            oneLinerError = false
                        },
                        label = { Text(stringResource(R.string.connection_uri_input)) },
                        singleLine = true,
                        isError = oneLinerError,
                        supportingText = if (oneLinerError) {
                            { Text(stringResource(R.string.connection_uri_invalid)) }
                        } else null,
                        trailingIcon = {
                            TextButton(onClick = {
                                if (oneLiner.isNotBlank()) {
                                    if (onApplyUri(oneLiner)) {
                                        oneLiner = ""
                                        oneLinerError = false
                                    } else {
                                        oneLinerError = true
                                    }
                                }
                            }) { Text(stringResource(R.string.connection_uri_expand)) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
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
                        FilterChip(
                            selected = state.protocol == Protocol.FTP,
                            onClick = { onProtocolChange(Protocol.FTP) },
                            label = { Text("FTP") },
                        )
                        FilterChip(
                            selected = state.protocol == Protocol.WEBDAV,
                            onClick = { onProtocolChange(Protocol.WEBDAV) },
                            label = { Text("WebDAV") },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = state.name,
                    onValueChange = { v -> onUpdate { it.copy(name = v) } },
                    label = { Text(stringResource(R.string.connection_display_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedTextField(
                        value = state.host,
                        onValueChange = { v -> onUpdate { it.copy(host = v) } },
                        label = { Text(stringResource(R.string.connection_host)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(
                        value = state.portText,
                        onValueChange = { v ->
                            // 数字以外は無視。空欄も保持できるようにする (強制的に 22 等が入らないように)。
                            val sanitized = v.filter { it.isDigit() }.take(5)
                            onUpdate { st -> st.copy(portText = sanitized) }
                        },
                        label = { Text(stringResource(R.string.connection_port)) },
                        singleLine = true,
                        placeholder = { Text(state.protocol.defaultPort.toString()) },
                        modifier = Modifier.size(width = 96.dp, height = 56.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                when (state.protocol) {
                    Protocol.SMB -> SmbExtraFields(state, onUpdate)
                    Protocol.SFTP -> SftpExtraFields(state, onUpdate, onGenerateSftpKey)
                    Protocol.FTP -> FtpExtraFields(state, onUpdate)
                    Protocol.WEBDAV -> WebDavExtraFields(state, onUpdate)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun SmbExtraFields(
    state: ConnectionsViewModel.EditingState,
    onUpdate: ((ConnectionsViewModel.EditingState) -> ConnectionsViewModel.EditingState) -> Unit,
) {
    // 共有名と初期パスを 1 本の「パス」に統合。先頭セグメントが共有名、残りが初期パス。
    // 空欄も許可 (その場合 share は空 = ホスト直下相当)。分解は ViewModel.saveSmb 側で行う。
    OutlinedTextField(
        value = state.share,
        onValueChange = { v -> onUpdate { it.copy(share = v) } },
        label = { Text(stringResource(R.string.connection_smb_path)) },
        singleLine = true,
        placeholder = { Text("public/sub/folder") },
        supportingText = { Text(stringResource(R.string.connection_smb_path_hint)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = state.domain,
        onValueChange = { v -> onUpdate { it.copy(domain = v) } },
        label = { Text(stringResource(R.string.connection_domain_optional)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = state.anonymous,
            onCheckedChange = { v -> onUpdate { it.copy(anonymous = v) } },
        )
        Text(stringResource(R.string.connection_anonymous_access))
    }
    if (!state.anonymous) {
        UserPasswordFields(state, onUpdate)
    }
}

@Composable
private fun SftpExtraFields(
    state: ConnectionsViewModel.EditingState,
    onUpdate: ((ConnectionsViewModel.EditingState) -> ConnectionsViewModel.EditingState) -> Unit,
    onGenerateKey: () -> Unit,
) {
    OutlinedTextField(
        value = state.username,
        onValueChange = { v -> onUpdate { it.copy(username = v) } },
        label = { Text(stringResource(R.string.connection_username)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    // 認証方式: パスワード or 公開鍵 (authorized_keys)。
    Text(stringResource(R.string.connection_auth_method), style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.sftpAuthMode == ConnectionsViewModel.SftpAuthMode.PASSWORD,
            onClick = { onUpdate { it.copy(sftpAuthMode = ConnectionsViewModel.SftpAuthMode.PASSWORD) } },
            label = { Text(stringResource(R.string.connection_password)) },
        )
        FilterChip(
            selected = state.sftpAuthMode == ConnectionsViewModel.SftpAuthMode.PUBLIC_KEY,
            onClick = { onUpdate { it.copy(sftpAuthMode = ConnectionsViewModel.SftpAuthMode.PUBLIC_KEY) } },
            label = { Text(stringResource(R.string.connection_public_key)) },
        )
    }
    Spacer(Modifier.height(8.dp))
    if (state.sftpAuthMode == ConnectionsViewModel.SftpAuthMode.PASSWORD) {
        OutlinedTextField(
            value = state.password,
            onValueChange = { v -> onUpdate { it.copy(password = v) } },
            label = { Text(stringResource(if (state.isNew) R.string.connection_password else R.string.connection_password_change)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        val clipboard = androidx.compose.ui.platform.LocalClipboard.current
        val clipboardScope = androidx.compose.runtime.rememberCoroutineScope()
        val publicKeyClipboardLabel = stringResource(R.string.connection_public_key_clipboard)
        TextButton(onClick = onGenerateKey) {
            Text(stringResource(if (state.sftpPublicKeyOpenSsh.isBlank()) R.string.connection_generate_key else R.string.connection_regenerate_key))
        }
        if (state.sftpPublicKeyOpenSsh.isNotBlank()) {
            Text(
                stringResource(R.string.connection_public_key_hint),
                style = MaterialTheme.typography.labelSmall,
            )
            OutlinedTextField(
                value = state.sftpPublicKeyOpenSsh,
                onValueChange = {},
                readOnly = true,
                singleLine = false,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = {
                clipboardScope.launch {
                    clipboard.setPlainText(publicKeyClipboardLabel, state.sftpPublicKeyOpenSsh)
                }
            }) { Text(stringResource(R.string.connection_copy_public_key)) }
        } else if (!state.isNew) {
            Text(
                stringResource(R.string.connection_existing_key_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = state.hostKeyFingerprint,
        onValueChange = { v -> onUpdate { it.copy(hostKeyFingerprint = v) } },
        label = { Text(stringResource(R.string.connection_host_key_optional)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = state.initialPath,
        onValueChange = { v -> onUpdate { it.copy(initialPath = v) } },
        label = { Text(stringResource(R.string.connection_initial_path_optional)) },
        singleLine = true,
        placeholder = { Text("/home/user") },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FtpExtraFields(
    state: ConnectionsViewModel.EditingState,
    onUpdate: ((ConnectionsViewModel.EditingState) -> ConnectionsViewModel.EditingState) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = state.anonymous,
            onCheckedChange = { v -> onUpdate { it.copy(anonymous = v) } },
        )
        Text(stringResource(R.string.connection_anonymous_access))
    }
    if (!state.anonymous) {
        UserPasswordFields(state, onUpdate)
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = state.useTls,
            onCheckedChange = { v -> onUpdate { it.copy(useTls = v) } },
        )
        Text(stringResource(R.string.connection_use_ftps))
    }
    if (!state.useTls) {
        Text(
            stringResource(R.string.connection_ftp_plain_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = state.passiveMode,
            onCheckedChange = { v -> onUpdate { it.copy(passiveMode = v) } },
        )
        Text(stringResource(R.string.connection_passive_mode))
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = state.charset,
        onValueChange = { v -> onUpdate { it.copy(charset = v) } },
        label = { Text(stringResource(R.string.connection_charset)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = state.initialPath,
        onValueChange = { v -> onUpdate { it.copy(initialPath = v) } },
        label = { Text(stringResource(R.string.connection_initial_path_optional)) },
        singleLine = true,
        placeholder = { Text("/pub") },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun WebDavExtraFields(
    state: ConnectionsViewModel.EditingState,
    onUpdate: ((ConnectionsViewModel.EditingState) -> ConnectionsViewModel.EditingState) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = state.useHttps,
            onCheckedChange = { v ->
                onUpdate {
                    val newPort = if (v) 443 else 80
                    it.copy(useHttps = v, portText = newPort.toString())
                }
            },
        )
        Text(stringResource(R.string.connection_use_https))
    }
    if (!state.useHttps) {
        Text(
            stringResource(R.string.connection_http_plain_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = state.basePath,
        onValueChange = { v -> onUpdate { it.copy(basePath = v) } },
        label = { Text(stringResource(R.string.connection_base_path)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = state.anonymous,
            onCheckedChange = { v -> onUpdate { it.copy(anonymous = v) } },
        )
        Text(stringResource(R.string.connection_anonymous_access))
    }
    if (!state.anonymous) {
        UserPasswordFields(state, onUpdate)
    }
}

@Composable
private fun UserPasswordFields(
    state: ConnectionsViewModel.EditingState,
    onUpdate: ((ConnectionsViewModel.EditingState) -> ConnectionsViewModel.EditingState) -> Unit,
) {
    OutlinedTextField(
        value = state.username,
        onValueChange = { v -> onUpdate { it.copy(username = v) } },
        label = { Text(stringResource(R.string.connection_username)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = state.password,
        onValueChange = { v -> onUpdate { it.copy(password = v) } },
        label = {
            Text(stringResource(if (state.isNew) R.string.connection_password else R.string.connection_password_change))
        },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}
