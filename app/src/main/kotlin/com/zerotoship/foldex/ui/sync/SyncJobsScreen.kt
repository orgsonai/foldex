// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.sync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zerotoship.foldex.R
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
                title = { Text(stringResource(R.string.sync_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_back))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.sync_add_job))
            }
        },
    ) { padding ->
      Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        NotificationPermissionBanner()
        SyncReliabilityBanner(
            canScheduleExact = viewModel::canScheduleExactAlarms,
            onExactAllowed = viewModel::rescheduleAll,
        )
        if (state.jobs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.sync_empty), style = MaterialTheme.typography.bodyMedium)
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
                modifier = Modifier.fillMaxSize(),
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
                            onCancelRun = { viewModel.cancelRun(job) },
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
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.sync_delete_title)) },
            text = { Text(stringResource(R.string.sync_delete_body, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target)
                    pendingDelete = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun SyncJobRow(
    job: SyncJob,
    runStatus: com.zerotoship.foldex.sync.scheduler.SyncScheduler.JobRunStatus,
    onRunNow: () -> Unit,
    onCancelRun: () -> Unit,
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
                contentDescription = stringResource(R.string.action_reorder),
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
                nextRunLabel(job)?.let { next ->
                    Text(
                        stringResource(R.string.sync_next_run, next),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(stringResource(R.string.sync_last_run, lastRunLabel(job)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = job.enabled, onCheckedChange = onToggleEnabled)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            // IDLE = 通常の Sync (今すぐ実行)。実行中 / キュー中 はタップで「解除」できる。
            // 制約 (Wi-Fi 限定など) 未充足で「キュー中」のまま滞留したジョブの脱出口。
            when (runStatus) {
                com.zerotoship.foldex.sync.scheduler.SyncScheduler.JobRunStatus.IDLE ->
                    IconButton(onClick = onRunNow) {
                        Icon(Icons.Default.Sync, contentDescription = stringResource(R.string.sync_run_now))
                    }
                com.zerotoship.foldex.sync.scheduler.SyncScheduler.JobRunStatus.ENQUEUED ->
                    IconButton(onClick = onCancelRun) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.sync_cancel_queue))
                    }
                com.zerotoship.foldex.sync.scheduler.SyncScheduler.JobRunStatus.RUNNING ->
                    IconButton(onClick = onCancelRun) {
                        Box(contentAlignment = Alignment.Center) {
                            androidx.compose.material3.CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp),
                            )
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.sync_cancel_running),
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_edit)) }
            IconButton(onClick = onOpenBackups) { Icon(Icons.Default.History, contentDescription = stringResource(R.string.sync_delete_backups)) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete)) }
        }
    }
}

/**
 * 通知 (POST_NOTIFICATIONS) が未許可のときだけ表示する案内バナー。
 * 「許可する」でランタイム要求、「設定を開く」でアプリの通知設定へ誘導する。
 * 設定から戻ったときに再判定できるよう ON_RESUME で権限状態を更新する。
 */
@Composable
private fun NotificationPermissionBanner() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(hasNotificationPermission(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = hasNotificationPermission(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (granted) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.notification_denied_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                stringResource(R.string.notification_denied_sync),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                    Text(stringResource(R.string.notification_allow))
                }
                TextButton(onClick = { openAppNotificationSettings(context) }) {
                    Text(stringResource(R.string.action_open_settings))
                }
            }
        }
    }
}

/**
 * 定期同期が端末側の省電力で止められる条件が残っているときだけ出す案内。
 *
 * 2026-08 に「毎日同期が 9 日間まるごと走らなかった」のは、端末がアプリを休止させて
 * 登録済みアラームごと落としたのが原因と見ている。コード側 (予約の貼り直し・正確アラーム)
 * だけでは防ぎ切れないので、端末側の設定への導線をここに出す。
 * 判定は画面に戻るたびに読み直し、両方とも解消したらカードごと消える。
 */
@Composable
private fun SyncReliabilityBanner(
    canScheduleExact: () -> Boolean,
    onExactAllowed: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var exactOk by remember { mutableStateOf(canScheduleExact()) }
    var batteryOk by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = canScheduleExact()
                // 許可された直後は、既に貼ってある不正確アラームを正確アラームで貼り直す。
                if (now && !exactOk) onExactAllowed()
                exactOk = now
                batteryOk = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (exactOk && batteryOk) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        val onContainer = MaterialTheme.colorScheme.onTertiaryContainer
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.sync_reliability_title),
                style = MaterialTheme.typography.titleSmall,
                color = onContainer,
            )
            if (!exactOk) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.sync_reliability_exact),
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer,
                )
                TextButton(onClick = { openExactAlarmSettings(context) }) {
                    Text(stringResource(R.string.sync_reliability_exact_action))
                }
            }
            if (!batteryOk) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.sync_reliability_battery),
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer,
                )
                TextButton(onClick = { openBatteryOptimizationSettings(context) }) {
                    Text(stringResource(R.string.sync_reliability_battery_action))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.sync_reliability_hibernation),
                style = MaterialTheme.typography.bodySmall,
                color = onContainer,
            )
            TextButton(onClick = { openAppDetailsSettings(context) }) {
                Text(stringResource(R.string.sync_reliability_app_info))
            }
        }
    }
}

/** 電池の最適化から除外されているか。読み取りだけなので特別な権限は要らない。 */
private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(PowerManager::class.java) ?: return true
    return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true)
}

/** 「アラームとリマインダー」の許可画面 (Android 12 以上)。 */
private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        .setData(Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { openAppDetailsSettings(context) }
}

/**
 * 電池の最適化の一覧を開く。
 * 直接ダイアログを出す ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS は
 * Play で用途が制限される権限を要するため使わない (一覧から Foldex を選んでもらう)。
 */
private fun openBatteryOptimizationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { openAppDetailsSettings(context) }
}

/** アプリ情報画面 (「使用されていない場合にアプリの動作を停止」はここから)。 */
private fun openAppDetailsSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

@Composable
private fun JobStatusChip(status: com.zerotoship.foldex.sync.scheduler.SyncScheduler.JobRunStatus) {
    val (label, container, content) = when (status) {
        com.zerotoship.foldex.sync.scheduler.SyncScheduler.JobRunStatus.RUNNING ->
            Triple(stringResource(R.string.sync_status_running), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
        com.zerotoship.foldex.sync.scheduler.SyncScheduler.JobRunStatus.ENQUEUED ->
            Triple(stringResource(R.string.sync_status_queued), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
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
