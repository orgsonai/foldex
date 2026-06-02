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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zerotoship.foldex.core.model.SyncJob
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncJobsScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (SyncJob) -> Unit,
    onOpenBackups: (SyncJob) -> Unit = {},
    viewModel: SyncJobsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<SyncJob?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SyncJobsViewModel.Event.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("同期") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "同期ジョブを追加")
            }
        },
    ) { padding ->
        if (state.jobs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("同期ジョブがありません。FAB から追加してください", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            // ドラッグ中はローカル liveOrder に楽観更新し、ドロップ時に確定保存する。
            var liveOrder by remember(state.jobs) { mutableStateOf(state.jobs) }
            val listState = rememberLazyListState()
            val reorderState = rememberReorderableLazyListState(listState) { from, to ->
                liveOrder = liveOrder.toMutableList().apply {
                    add(to.index, removeAt(from.index))
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                items(liveOrder, key = { it.id }) { job ->
                    ReorderableItem(reorderState, key = job.id) { _ ->
                        val dragHandle = Modifier.longPressDraggableHandle(
                            onDragStopped = {
                                val newIds = liveOrder.map { it.id }
                                if (newIds != state.jobs.map { it.id }) viewModel.applyOrder(newIds)
                            },
                        )
                        SyncJobRow(
                            job = job,
                            runStatus = state.statuses[job.id]
                                ?: com.zerotoship.foldex.sync.scheduler.SyncScheduler.JobRunStatus.IDLE,
                            dragHandleModifier = dragHandle,
                            onRunNow = { viewModel.runNow(job) },
                            onToggleEnabled = { enabled -> viewModel.setEnabled(job, enabled) },
                            onEdit = { onEdit(job) },
                            onOpenBackups = { onOpenBackups(job) },
                            onDelete = { pendingDelete = job },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("削除しますか?") },
            text = { Text("「${target.name}」を削除します。前回同期状態の記録も消えます。") },
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
private fun SyncJobRow(
    job: SyncJob,
    runStatus: com.zerotoship.foldex.sync.scheduler.SyncScheduler.JobRunStatus,
    onRunNow: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onOpenBackups: () -> Unit,
    onDelete: () -> Unit,
    dragHandleModifier: Modifier = Modifier,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // このハンドルを長押し = ドラッグ開始。
            Icon(
                Icons.Default.DragHandle,
                contentDescription = "ドラッグして並び替え",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = dragHandleModifier.padding(end = 8.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        job.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    JobStatusChip(runStatus)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "${directionShortLabel(job.direction)} ・ ${scheduleLabel(job.schedule)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                val sub = job.lastRunResult ?: "未実行"
                Text("前回: $sub", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = job.enabled, onCheckedChange = onToggleEnabled)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            // 実行中はスピナー、それ以外は通常の Sync アイコン。
            IconButton(onClick = onRunNow, enabled = runStatus == com.zerotoship.foldex.sync.scheduler.SyncScheduler.JobRunStatus.IDLE) {
                if (runStatus == com.zerotoship.foldex.sync.scheduler.SyncScheduler.JobRunStatus.RUNNING) {
                    androidx.compose.material3.CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Icon(Icons.Default.Sync, contentDescription = "今すぐ同期")
                }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "編集") }
            IconButton(onClick = onOpenBackups) { Icon(Icons.Default.History, contentDescription = "削除バックアップ") }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "削除") }
        }
    }
}

@Composable
private fun JobStatusChip(status: com.zerotoship.foldex.sync.scheduler.SyncScheduler.JobRunStatus) {
    val (label, container, content) = when (status) {
        com.zerotoship.foldex.sync.scheduler.SyncScheduler.JobRunStatus.RUNNING ->
            Triple("実行中", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
        com.zerotoship.foldex.sync.scheduler.SyncScheduler.JobRunStatus.ENQUEUED ->
            Triple("キュー中", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        com.zerotoship.foldex.sync.scheduler.SyncScheduler.JobRunStatus.IDLE -> return
    }
    androidx.compose.material3.Surface(
        color = container,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        modifier = Modifier.padding(start = 8.dp),
    ) {
        Text(
            label,
            color = content,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
