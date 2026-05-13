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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.zerotoship.foldex.core.model.ServerLog
import com.zerotoship.foldex.core.model.ServerLogEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerLogScreen(
    onBack: () -> Unit,
    viewModel: ServerLogViewModel = hiltViewModel(),
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("接続ログ - ${config?.name ?: "…"}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    if (logs.isNotEmpty()) {
                        TextButton(onClick = { confirmClear = true }) { Text("クリア") }
                    }
                },
            )
        },
    ) { padding ->
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "ログはまだありません",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(logs, key = { it.id }) { log ->
                    LogRow(log)
                    HorizontalDivider()
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("ログを削除しますか?") },
            text = { Text("このサーバーの接続ログをすべて削除します。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clear()
                    confirmClear = false
                }) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("キャンセル") }
            },
        )
    }
}

@Composable
private fun LogRow(log: ServerLog) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                eventLabel(log.event),
                style = MaterialTheme.typography.titleSmall,
                color = eventColor(log.event),
            )
            Spacer(Modifier.weight(1f))
            Text(
                FORMATTER.format(Date(log.timestamp)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            buildString {
                append(log.clientAddress)
                log.username?.let { append("  user=").append(it) }
            },
            style = MaterialTheme.typography.bodySmall,
        )
        log.details?.takeIf { it.isNotBlank() }?.let { details ->
            Spacer(Modifier.height(2.dp))
            Text(details, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun eventLabel(event: ServerLogEvent): String = when (event) {
    ServerLogEvent.SERVER_STARTED -> "起動"
    ServerLogEvent.SERVER_STOPPED -> "停止"
    ServerLogEvent.SERVER_START_FAILED -> "起動失敗"
    ServerLogEvent.CLIENT_CONNECTED -> "接続"
    ServerLogEvent.CLIENT_DISCONNECTED -> "切断"
    ServerLogEvent.AUTH_SUCCESS -> "認証成功"
    ServerLogEvent.AUTH_FAILED -> "認証失敗"
    ServerLogEvent.FILE_OP_FAILED -> "操作失敗"
}

@Composable
private fun eventColor(event: ServerLogEvent): androidx.compose.ui.graphics.Color = when (event) {
    ServerLogEvent.AUTH_FAILED,
    ServerLogEvent.SERVER_START_FAILED,
    ServerLogEvent.FILE_OP_FAILED -> MaterialTheme.colorScheme.error
    ServerLogEvent.AUTH_SUCCESS, ServerLogEvent.SERVER_STARTED -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurface
}

private val FORMATTER = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
