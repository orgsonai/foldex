package com.zerotoship.foldex.ui.connections

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zerotoship.foldex.core.model.Connection
import com.zerotoship.foldex.core.model.Protocol
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
                    DropdownMenuItem(
                        text = { Text("FTP を追加") },
                        onClick = {
                            protocolMenuOpen = false
                            viewModel.startCreate(Protocol.FTP)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("WebDAV を追加") },
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
                                // このハンドルを長押し = ドラッグ開始。行の長押しは従来どおり編集。
                                Icon(
                                    Icons.Default.DragHandle,
                                    contentDescription = "ドラッグして並び替え",
                                    modifier = dragHandle,
                                )
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
    is Connection.Ftp -> {
        val tls = if (useTls) "FTPS" else "平文"
        val mode = if (passiveMode) "PASV" else "ACTV"
        val auth = if (authMethod.wireName == "anonymous") "匿名" else (username ?: "user?")
        "ftp://$host:$port  ($tls / $mode / $auth)"
    }
    is Connection.WebDav -> {
        val scheme = if (useHttps) "https" else "http"
        val auth = if (authMethod.wireName == "anonymous") "匿名" else (username ?: "user?")
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
    val title = when (state.protocol) {
        Protocol.SMB -> if (state.isNew) "SMB 接続を追加" else "SMB 接続を編集"
        Protocol.SFTP -> if (state.isNew) "SFTP 接続を追加" else "SFTP 接続を編集"
        Protocol.FTP -> if (state.isNew) "FTP 接続を追加" else "FTP 接続を編集"
        Protocol.WEBDAV -> if (state.isNew) "WebDAV 接続を追加" else "WebDAV 接続を編集"
    }
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
                        label = { Text("URL から入力 (例: sftp://user@host:22/path)") },
                        singleLine = true,
                        isError = oneLinerError,
                        supportingText = if (oneLinerError) {
                            { Text("URL の書式が不正です") }
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
                            }) { Text("展開") }
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
                        value = state.portText,
                        onValueChange = { v ->
                            // 数字以外は無視。空欄も保持できるようにする (強制的に 22 等が入らないように)。
                            val sanitized = v.filter { it.isDigit() }.take(5)
                            onUpdate { st -> st.copy(portText = sanitized) }
                        },
                        label = { Text("ポート") },
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
    // 共有名と初期パスを 1 本の「パス」に統合。先頭セグメントが共有名、残りが初期パス。
    // 空欄も許可 (その場合 share は空 = ホスト直下相当)。分解は ViewModel.saveSmb 側で行う。
    OutlinedTextField(
        value = state.share,
        onValueChange = { v -> onUpdate { it.copy(share = v) } },
        label = { Text("パス (共有名/フォルダ)") },
        singleLine = true,
        placeholder = { Text("public/sub/folder") },
        supportingText = { Text("先頭が共有名。例: public または public/docs。空欄も可 (その場合は / )") },
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
    onGenerateKey: () -> Unit,
) {
    OutlinedTextField(
        value = state.username,
        onValueChange = { v -> onUpdate { it.copy(username = v) } },
        label = { Text("ユーザー名") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    // 認証方式: パスワード or 公開鍵 (authorized_keys)。
    Text("認証方式", style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.sftpAuthMode == ConnectionsViewModel.SftpAuthMode.PASSWORD,
            onClick = { onUpdate { it.copy(sftpAuthMode = ConnectionsViewModel.SftpAuthMode.PASSWORD) } },
            label = { Text("パスワード") },
        )
        FilterChip(
            selected = state.sftpAuthMode == ConnectionsViewModel.SftpAuthMode.PUBLIC_KEY,
            onClick = { onUpdate { it.copy(sftpAuthMode = ConnectionsViewModel.SftpAuthMode.PUBLIC_KEY) } },
            label = { Text("公開鍵 (authorized_keys)") },
        )
    }
    Spacer(Modifier.height(8.dp))
    if (state.sftpAuthMode == ConnectionsViewModel.SftpAuthMode.PASSWORD) {
        OutlinedTextField(
            value = state.password,
            onValueChange = { v -> onUpdate { it.copy(password = v) } },
            label = { Text(if (state.isNew) "パスワード" else "パスワード (変更時のみ)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        TextButton(onClick = onGenerateKey) {
            Text(if (state.sftpPublicKeyOpenSsh.isBlank()) "鍵を生成" else "鍵を再生成")
        }
        if (state.sftpPublicKeyOpenSsh.isNotBlank()) {
            Text(
                "リモートの ~/.ssh/authorized_keys にコピーしてください:",
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
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(state.sftpPublicKeyOpenSsh))
            }) { Text("公開鍵をコピー") }
        } else if (!state.isNew) {
            Text(
                "既に登録済みの鍵を使用します (再生成する場合は上の「鍵を生成」)。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = state.hostKeyFingerprint,
        onValueChange = { v -> onUpdate { it.copy(hostKeyFingerprint = v) } },
        label = { Text("ホスト鍵 SHA-256 (任意/初回は空でOK)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = state.initialPath,
        onValueChange = { v -> onUpdate { it.copy(initialPath = v) } },
        label = { Text("初期パス (任意)") },
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
        Text("匿名アクセス")
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
        Text("FTPS (TLS) を使用")
    }
    if (!state.useTls) {
        Text(
            "⚠ 平文通信です。パスワードとファイル内容が暗号化されません。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = state.passiveMode,
            onCheckedChange = { v -> onUpdate { it.copy(passiveMode = v) } },
        )
        Text("パッシブモード (PASV)")
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = state.charset,
        onValueChange = { v -> onUpdate { it.copy(charset = v) } },
        label = { Text("文字コード (UTF-8 / Shift_JIS / EUC-JP など)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = state.initialPath,
        onValueChange = { v -> onUpdate { it.copy(initialPath = v) } },
        label = { Text("初期パス (任意)") },
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
        Text("HTTPS を使用")
    }
    if (!state.useHttps) {
        Text(
            "⚠ 平文通信です。Basic 認証パスワードが暗号化されません。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = state.basePath,
        onValueChange = { v -> onUpdate { it.copy(basePath = v) } },
        label = { Text("ベースパス (例: /remote.php/dav/files/USER)") },
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
