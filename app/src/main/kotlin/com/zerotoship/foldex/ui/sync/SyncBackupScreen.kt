// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zerotoship.foldex.R
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncBackupScreen(
    onBack: () -> Unit,
    viewModel: SyncBackupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (state.jobName.isBlank()) stringResource(R.string.sync_backup_title) else stringResource(R.string.sync_backup_title_job, state.jobName)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.generations.isEmpty() -> Text(
                    stringResource(R.string.sync_backup_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Text(
                            stringResource(R.string.sync_backup_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                        HorizontalDivider()
                    }
                    items(state.generations, key = { it.gen.id }) { gv ->
                        GenerationRow(
                            view = gv,
                            onRestore = { viewModel.requestBatchRestore(gv.gen.id) },
                            onDelete = { viewModel.delete(gv.gen.id) },
                            onClickLocal = { viewModel.showSideDetail(gv, "local") },
                            onClickRemote = { viewModel.showSideDetail(gv, "remote") },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    state.pendingBatchRestore?.let { pending ->
        RestoreConfirmDialog(
            pending = pending,
            onCancel = { viewModel.confirmBatchRestore(null) },
            onRestoreSkippingExisting = { viewModel.confirmBatchRestore(false) },
            onOverwrite = { viewModel.confirmBatchRestore(true) },
        )
    }
    state.pendingDetail?.let { detail ->
        SideDetailDialog(detail = detail, onDismiss = { viewModel.dismissDetail() })
    }
}

@Composable
private fun SideDetailDialog(detail: PendingDetail, onDismiss: () -> Unit) {
    val sideLabel = stringResource(if (detail.side == "local") R.string.sync_side_local else R.string.sync_side_remote)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_backup_detail, sideLabel)) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(
                    stringResource(R.string.sync_backup_date_count, formatDate(detail.createdAt), detail.files.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 4.dp))
                if (detail.files.isEmpty()) {
                    Text(stringResource(R.string.sync_backup_no_files), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    ) {
                        items(detail.files) { f ->
                            androidx.compose.foundation.layout.Column(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            ) {
                                Text(
                                    f.relativePath,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    formatBytes(f.sizeBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun GenerationRow(
    view: GenerationView,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onClickLocal: () -> Unit,
    onClickRemote: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(formatDate(view.gen.createdAt), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.sync_backup_count_size, view.gen.fileCount, formatBytes(view.gen.sizeBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // ローカル/リモートの内訳をチップで表示。バッチ復元で書き戻す範囲がひと目で分かるように。
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                if (view.localCount > 0) {
                    AssistChip(
                        onClick = onClickLocal,
                        label = { Text(stringResource(R.string.sync_backup_local_count, view.localCount)) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    )
                }
                if (view.remoteCount > 0) {
                    AssistChip(
                        onClick = onClickRemote,
                        label = { Text(stringResource(R.string.sync_backup_remote_count, view.remoteCount)) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                    )
                }
            }
        }
        IconButton(onClick = onRestore) { Icon(Icons.Default.Restore, contentDescription = stringResource(R.string.sync_backup_restore_all)) }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.DeleteForever, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * 一括復元の事前確認ダイアログ。
 * 復元を実行する前に「どのファイルが、ローカル/リモートのどちらへ書き戻されるか」を一覧表示し、
 * そのうえで復元するか尋ねる。既存ファイルとの衝突があるときは上書き/スキップを選べる。
 */
@Composable
private fun RestoreConfirmDialog(
    pending: PendingBatchRestore,
    onCancel: () -> Unit,
    onRestoreSkippingExisting: () -> Unit,
    onOverwrite: () -> Unit,
) {
    val hasConflict = pending.totalConflicts > 0
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.sync_backup_restore_confirm)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.sync_backup_restore_summary, formatDate(pending.createdAt), pending.totalFiles, pending.localFiles.size, pending.remoteFiles.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (hasConflict) {
                    Text(
                        stringResource(R.string.sync_backup_conflicts, pending.totalConflicts),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 4.dp))
                // 復元されるファイルの一覧 (プレビュー)。ローカル分→リモート分の順に並べる。
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    if (pending.localFiles.isNotEmpty()) {
                        item { RestoreSectionHeader(stringResource(R.string.sync_backup_restore_local, pending.localFiles.size)) }
                        items(pending.localFiles) { f -> RestoreFileRow(f.relativePath, f.sizeBytes) }
                    }
                    if (pending.remoteFiles.isNotEmpty()) {
                        item { RestoreSectionHeader(stringResource(R.string.sync_backup_restore_remote, pending.remoteFiles.size)) }
                        items(pending.remoteFiles) { f -> RestoreFileRow(f.relativePath, f.sizeBytes) }
                    }
                }
            }
        },
        confirmButton = {
            if (hasConflict) {
                TextButton(onClick = onOverwrite) { Text(stringResource(R.string.sync_backup_restore_overwrite), color = MaterialTheme.colorScheme.error) }
            } else {
                TextButton(onClick = onRestoreSkippingExisting) { Text(stringResource(R.string.sync_backup_restore)) }
            }
        },
        dismissButton = {
            Row {
                if (hasConflict) {
                    TextButton(onClick = onRestoreSkippingExisting) { Text(stringResource(R.string.sync_backup_skip_existing)) }
                }
                TextButton(onClick = onCancel) { Text(stringResource(if (hasConflict) R.string.action_abort else R.string.action_cancel)) }
            }
        },
    )
}

@Composable
private fun RestoreSectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun RestoreFileRow(path: String, sizeBytes: Long) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            path,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            formatBytes(sizeBytes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatBytes(b: Long): String = when {
    b < 1024 -> "${b}B"
    b < 1024 * 1024 -> "${"%.1f".format(b / 1024.0)}KB"
    b < 1024L * 1024 * 1024 -> "${"%.1f".format(b / 1024.0 / 1024)}MB"
    else -> "${"%.2f".format(b / 1024.0 / 1024 / 1024)}GB"
}

private fun formatDate(ms: Long): String =
    if (ms <= 0) "?" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
