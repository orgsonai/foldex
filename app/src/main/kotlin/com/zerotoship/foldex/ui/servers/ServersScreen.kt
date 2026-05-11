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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zerotoship.foldex.core.model.ServerConfig
import com.zerotoship.foldex.core.model.ServerType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (ServerConfig) -> Unit,
    onOpenLogs: (ServerConfig) -> Unit,
    viewModel: ServersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<ServerConfig?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ServersViewModel.ServerEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("自機サーバー") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    if (state.servers.any { it.isRunning }) {
                        TextButton(onClick = viewModel::stopAll) { Text("すべて停止") }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "サーバーを追加")
            }
        },
    ) { padding ->
        if (state.servers.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "サーバー設定がありません。FAB から追加してください",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(state.servers, key = { it.config.id }) { row ->
                    ServerRow(
                        row = row,
                        onStart = { viewModel.start(row.config) },
                        onStop = { viewModel.stop(row.config) },
                        onEdit = { onEdit(row.config) },
                        onDelete = { pendingDelete = row.config },
                        onOpenLogs = { onOpenLogs(row.config) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("削除しますか?") },
            text = { Text("「${target.name}」を削除します。元には戻せません。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target)
                    pendingDelete = null
                }) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("キャンセル") }
            },
        )
    }
}

@Composable
private fun ServerRow(
    row: ServersViewModel.ServerRowState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val config = row.config
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(config.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${config.type.label} ・ port ${config.port} ・ ${config.bindAddressLabel()}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(if (row.isRunning) "起動中" else "停止中") },
                colors = AssistChipDefaults.assistChipColors(
                    disabledLabelColor = if (row.isRunning) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (row.isRunning) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, contentDescription = "停止")
                }
            } else {
                IconButton(onClick = onStart) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "起動")
                }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "編集") }
            IconButton(onClick = onOpenLogs) {
                Icon(Icons.Outlined.Description, contentDescription = "ログ")
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "削除") }
        }
    }
}

private val ServerType.label: String
    get() = when (this) {
        ServerType.SFTP -> "SFTP"
        ServerType.FTP -> "FTP"
    }

private fun ServerConfig.bindAddressLabel(): String = when (bindAddress) {
    ServerConfig.BIND_WIFI_ONLY -> "Wi-Fi のみ"
    ServerConfig.BIND_ALL_INTERFACES -> "全インターフェース"
    else -> bindAddress
}
