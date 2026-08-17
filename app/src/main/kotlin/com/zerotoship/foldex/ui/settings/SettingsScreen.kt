// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.annotation.StringRes
import com.zerotoship.foldex.R
import com.zerotoship.foldex.core.data.update.UpdateStatus
import com.zerotoship.foldex.core.model.AppColorTheme
import com.zerotoship.foldex.core.model.DeleteBehavior
import com.zerotoship.foldex.core.model.SyncBackupPolicy
import com.zerotoship.foldex.core.model.ThemeMode
import com.zerotoship.foldex.ui.theme.appColorThemeSwatch
import com.zerotoship.foldex.ui.viewer.UNLIMITED_EDITABLE_LIMIT_KB

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenFileTypes: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onOpenLicenses: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val cacheBytes by viewModel.cacheBytes.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingClear by remember { mutableStateOf(false) }
    val updateStatus by viewModel.updateStatus.collectAsStateWithLifecycle()
    val showUpdateDialog by viewModel.showUpdateDialog.collectAsStateWithLifecycle()

    // 画面を開いたタイミングと、クリア完了直後にサイズを再計測する。
    LaunchedEffect(Unit) { viewModel.refreshCacheSize() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSectionHeader(stringResource(R.string.settings_section_display))
            SettingRow(
                title = stringResource(R.string.settings_theme),
                subtitle = stringResource(R.string.settings_theme_sub),
                wide = true,
            ) {
                ChipsControl {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            label = { Text(stringResource(mode.labelRes)) },
                        )
                    }
                }
            }
            SwitchRow(
                title = stringResource(R.string.settings_material_you),
                subtitle = stringResource(R.string.settings_material_you_sub),
                checked = settings.dynamicColor,
                onCheckedChange = viewModel::setDynamicColor,
            )
            // Material You OFF のときだけアクセント配色を選べる (ON のときは壁紙が優先)。
            SettingRow(
                title = stringResource(R.string.settings_accent),
                subtitle = stringResource(
                    if (settings.dynamicColor) R.string.settings_accent_sub_locked
                    else R.string.settings_accent_sub,
                ),
                wide = true,
            ) {
                ChipsControl {
                    AppColorTheme.entries.forEach { theme ->
                        FilterChip(
                            selected = settings.colorTheme == theme,
                            enabled = !settings.dynamicColor,
                            onClick = { viewModel.setColorTheme(theme) },
                            leadingIcon = {
                                Box(
                                    Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(appColorThemeSwatch(theme)),
                                )
                            },
                            label = { Text(stringResource(theme.labelRes)) },
                        )
                    }
                }
            }
            SwitchRow(
                title = stringResource(R.string.settings_ext_badge),
                subtitle = stringResource(R.string.settings_ext_badge_sub),
                checked = settings.showExtensionBadge,
                onCheckedChange = viewModel::setShowExtensionBadge,
            )

            HorizontalDivider()
            SettingsSectionHeader(stringResource(R.string.settings_section_behavior))
            SwitchRow(
                title = stringResource(R.string.settings_confirm_delete),
                subtitle = stringResource(R.string.settings_confirm_delete_sub),
                checked = settings.confirmBeforeDelete,
                onCheckedChange = viewModel::setConfirmBeforeDelete,
            )
            SettingRow(
                title = stringResource(R.string.settings_undo_timeout),
                subtitle = stringResource(R.string.settings_undo_timeout_sub),
                wide = true,
            ) {
                ChipsControl {
                    listOf(3, 5, 10).forEach { sec ->
                        FilterChip(
                            selected = settings.undoTimeoutSeconds == sec,
                            onClick = { viewModel.setUndoTimeoutSeconds(sec) },
                            label = { Text(pluralStringResource(R.plurals.settings_seconds, sec, sec)) },
                        )
                    }
                }
            }

            HorizontalDivider()
            SettingsSectionHeader(stringResource(R.string.settings_section_notifications))
            SwitchRow(
                title = stringResource(R.string.settings_notify_fileop),
                subtitle = stringResource(R.string.settings_notify_fileop_sub),
                checked = settings.notifyOnFileOpComplete,
                onCheckedChange = viewModel::setNotifyOnFileOpComplete,
            )
            SwitchRow(
                title = stringResource(R.string.settings_notify_extract),
                subtitle = stringResource(R.string.settings_notify_extract_sub),
                checked = settings.notifyOnExtractComplete,
                onCheckedChange = viewModel::setNotifyOnExtractComplete,
            )
            SwitchRow(
                title = stringResource(R.string.settings_notify_sync),
                subtitle = stringResource(R.string.settings_notify_sync_sub),
                checked = settings.notifyOnSyncComplete,
                onCheckedChange = viewModel::setNotifyOnSyncComplete,
            )

            HorizontalDivider()
            SettingsSectionHeader(stringResource(R.string.settings_section_trash))
            SettingRow(
                title = stringResource(R.string.settings_delete_behavior),
                subtitle = stringResource(R.string.settings_delete_behavior_sub),
                wide = true,
            ) {
                ChipsControl {
                    DeleteBehavior.entries.forEach { b ->
                        FilterChip(
                            selected = settings.deleteBehavior == b,
                            onClick = { viewModel.setDeleteBehavior(b) },
                            label = { Text(stringResource(b.labelRes)) },
                        )
                    }
                }
            }
            SettingRow(
                title = stringResource(R.string.settings_trash_retention),
                subtitle = stringResource(R.string.settings_trash_retention_sub),
                wide = true,
            ) {
                ChipsControl {
                    listOf(7, 30, 90, 0).forEach { days ->
                        FilterChip(
                            selected = settings.trashRetentionDays == days,
                            onClick = { viewModel.setTrashRetentionDays(days) },
                            label = {
                                Text(
                                    if (days == 0) stringResource(R.string.settings_forever)
                                    else pluralStringResource(R.plurals.settings_days, days, days),
                                )
                            },
                        )
                    }
                }
            }
            SettingRow(
                title = stringResource(R.string.settings_open_trash),
                subtitle = stringResource(R.string.settings_open_trash_sub),
                onClick = onOpenTrash,
            )

            HorizontalDivider()
            SettingsSectionHeader(stringResource(R.string.settings_section_sync))
            SwitchRow(
                title = stringResource(R.string.settings_sync_backup),
                subtitle = stringResource(R.string.settings_sync_backup_sub),
                checked = settings.syncDeleteBackup,
                onCheckedChange = viewModel::setSyncDeleteBackup,
            )
            if (settings.syncDeleteBackup) {
                SettingRow(
                    title = stringResource(R.string.settings_sync_generations),
                    subtitle = stringResource(R.string.settings_sync_generations_sub),
                    wide = true,
                ) {
                    ChipsControl {
                        listOf(1, 3, 5).forEach { n ->
                            FilterChip(
                                selected = settings.syncBackupGenerations == n,
                                onClick = { viewModel.setSyncBackupGenerations(n) },
                                label = { Text(pluralStringResource(R.plurals.settings_generations, n, n)) },
                            )
                        }
                    }
                }
                SettingRow(
                    title = stringResource(R.string.settings_sync_threshold),
                    subtitle = stringResource(R.string.settings_sync_threshold_sub),
                    wide = true,
                ) {
                    ChipsControl {
                        listOf(10, 50, 200, 1000).forEach { mb ->
                            FilterChip(
                                selected = settings.syncBackupThresholdMb == mb,
                                onClick = { viewModel.setSyncBackupThresholdMb(mb) },
                                label = { Text(if (mb >= 1000) "${mb / 1000}GB" else "${mb}MB") },
                            )
                        }
                    }
                }
                SettingRow(
                    title = stringResource(R.string.settings_sync_over_threshold),
                    subtitle = stringResource(R.string.settings_sync_over_threshold_sub),
                    wide = true,
                ) {
                    ChipsControl {
                        SyncBackupPolicy.entries.forEach { p ->
                            FilterChip(
                                selected = settings.syncBackupPolicyOverThreshold == p,
                                onClick = { viewModel.setSyncBackupPolicyOverThreshold(p) },
                                label = { Text(stringResource(p.labelRes)) },
                            )
                        }
                    }
                }
            }
            SwitchRow(
                title = stringResource(R.string.settings_queue_timeout_enabled),
                subtitle = stringResource(R.string.settings_queue_timeout_enabled_sub),
                checked = settings.syncQueueTimeoutEnabled,
                onCheckedChange = viewModel::setSyncQueueTimeoutEnabled,
            )
            if (settings.syncQueueTimeoutEnabled) {
                SettingRow(
                    title = stringResource(R.string.settings_queue_timeout),
                    subtitle = stringResource(R.string.settings_queue_timeout_sub),
                    wide = true,
                ) {
                    ChipsControl {
                        listOf(1, 5, 10, 30, 60).forEach { min ->
                            FilterChip(
                                selected = settings.syncQueueTimeoutMinutes == min,
                                onClick = { viewModel.setSyncQueueTimeoutMinutes(min) },
                                label = {
                                Text(
                                    if (min >= 60) pluralStringResource(R.plurals.settings_hours, min / 60, min / 60)
                                    else pluralStringResource(R.plurals.settings_minutes, min, min),
                                )
                            },
                            )
                        }
                    }
                }
            }

            HorizontalDivider()
            SettingsSectionHeader(stringResource(R.string.settings_section_files))
            SettingRow(
                title = stringResource(R.string.settings_file_types),
                subtitle = stringResource(R.string.settings_file_types_sub),
                onClick = onOpenFileTypes,
            )
            SettingRow(
                title = stringResource(R.string.settings_editor_limit),
                subtitle = stringResource(R.string.settings_editor_limit_sub),
                wide = true,
            ) {
                ChipsControl {
                    listOf(128, 256, 512, 1024, 2048, 4096, 8192, UNLIMITED_EDITABLE_LIMIT_KB).forEach { kb ->
                        FilterChip(
                            selected = settings.editorEditableLimitKb == kb,
                            onClick = { viewModel.setEditorEditableLimitKb(kb) },
                            label = {
                                Text(
                                    when {
                                        kb == UNLIMITED_EDITABLE_LIMIT_KB -> stringResource(R.string.settings_unlimited)
                                        kb >= 1024 -> "${kb / 1024}MB"
                                        else -> "${kb}KB"
                                    },
                                )
                            },
                        )
                    }
                }
            }

            HorizontalDivider()
            SettingsSectionHeader(stringResource(R.string.settings_section_logs))
            SettingRow(
                title = stringResource(R.string.settings_logs),
                subtitle = stringResource(R.string.settings_logs_sub),
                onClick = onOpenLogs,
            )

            HorizontalDivider()
            SettingsSectionHeader(stringResource(R.string.settings_section_storage))
            SettingRow(
                title = stringResource(R.string.settings_clear_cache),
                subtitle = stringResource(R.string.settings_clear_cache_sub) +
                    (cacheBytes?.let { stringResource(R.string.settings_cache_in_use, formatBytes(it)) } ?: ""),
                onClick = { pendingClear = true },
            )

            HorizontalDivider()
            SettingsSectionHeader(stringResource(R.string.settings_section_advanced))
            SettingRow(title = stringResource(R.string.settings_version), subtitle = versionName)
            SettingRow(
                title = stringResource(R.string.settings_check_update),
                subtitle = when (val s = updateStatus) {
                    is UpdateStatus.Idle -> stringResource(R.string.settings_update_idle)
                    is UpdateStatus.Checking -> stringResource(R.string.settings_update_checking)
                    is UpdateStatus.UpToDate -> stringResource(R.string.settings_update_up_to_date, s.current)
                    is UpdateStatus.Available -> stringResource(R.string.settings_update_available, s.latest)
                    is UpdateStatus.Failed -> stringResource(R.string.settings_update_failed, s.reason)
                },
                onClick = {
                    // 既に見つかっているときは、押し直しで変更履歴をもう一度出す。
                    if (updateStatus is UpdateStatus.Available) {
                        viewModel.reopenUpdateDialog()
                    } else {
                        viewModel.checkForUpdate()
                    }
                },
                control = {
                    if (updateStatus is UpdateStatus.Checking) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                },
            )
            SettingRow(
                title = stringResource(R.string.settings_license),
                subtitle = stringResource(R.string.settings_license_sub),
                onClick = onOpenLicenses,
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    // 新しいバージョンが見つかったとき、変更履歴を出して入手先へ誘導する。
    // アプリ内でダウンロード/インストールはしない (入手経路はユーザーが選ぶ)。
    val available = updateStatus as? UpdateStatus.Available
    if (showUpdateDialog && available != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissUpdateDialog() },
            title = { Text(stringResource(R.string.settings_update_dialog_title, available.latest)) },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        stringResource(R.string.settings_update_dialog_current, versionName),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (available.notes.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.settings_update_changelog), style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(available.notes, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissUpdateDialog()
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(available.pageUrl),
                            ),
                        )
                    }
                }) { Text(stringResource(R.string.settings_update_open_page)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUpdateDialog() }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }

    if (pendingClear) {
        AlertDialog(
            onDismissRequest = { pendingClear = false },
            title = { Text(stringResource(R.string.settings_clear_cache)) },
            text = {
                Text(stringResource(R.string.settings_clear_cache_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingClear = false
                    viewModel.clearCache { freed ->
                        scope.launch {
                            snackbar.showSnackbar(
                                context.getString(R.string.settings_freed, formatBytes(freed)),
                            )
                        }
                    }
                }) { Text(stringResource(R.string.settings_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

private fun formatBytes(b: Long): String = when {
    b >= 1_000_000_000 -> "%.2f GB".format(b / 1_000_000_000.0)
    b >= 1_000_000 -> "%.1f MB".format(b / 1_000_000.0)
    b >= 1_000 -> "%.0f KB".format(b / 1_000.0)
    else -> "$b B"
}

// チップのラベルは stringResource で引くため、文字列ではなくリソース ID を返す。
// (この形なら端末の言語が変わってもそのまま追従する)

@get:StringRes
private val ThemeMode.labelRes: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }

@get:StringRes
private val AppColorTheme.labelRes: Int
    get() = when (this) {
        AppColorTheme.GREEN -> R.string.color_green
        AppColorTheme.BLUE -> R.string.color_blue
        AppColorTheme.PURPLE -> R.string.color_purple
        AppColorTheme.TEAL -> R.string.color_teal
        AppColorTheme.ORANGE -> R.string.color_orange
        AppColorTheme.ROSE -> R.string.color_rose
    }

@get:StringRes
private val DeleteBehavior.labelRes: Int
    get() = when (this) {
        DeleteBehavior.TRASH -> R.string.delete_behavior_trash
        DeleteBehavior.PERMANENT -> R.string.delete_behavior_permanent
        DeleteBehavior.ASK -> R.string.delete_behavior_ask
    }

@get:StringRes
private val SyncBackupPolicy.labelRes: Int
    get() = when (this) {
        SyncBackupPolicy.ASK -> R.string.sync_backup_ask
        SyncBackupPolicy.BACKUP -> R.string.sync_backup_backup
        SyncBackupPolicy.SKIP -> R.string.sync_backup_skip
    }

@Composable
private fun SettingsSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** チップを横並びにしつつ、入り切らないときは折り返す。 */
@Composable
private fun ChipsControl(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

/**
 * 設定 1 行。
 * - [wide] = false: タイトル/説明を左、コントロール (Switch 等) を右に並べる。
 * - [wide] = true : タイトル/説明の下にコントロール (チップ群など横幅を食うもの) を置く。
 * 説明文は折り返して全文表示する (「…」で切らない)。
 */
@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    wide: Boolean = false,
    control: (@Composable () -> Unit)? = null,
) {
    val base = Modifier
        .fillMaxWidth()
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 16.dp, vertical = 12.dp)

    @Composable
    fun titleBlock() {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (wide) {
        Column(modifier = base) {
            titleBlock()
            if (control != null) {
                Spacer(Modifier.height(10.dp))
                control()
            }
        }
    } else {
        Row(modifier = base, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) { titleBlock() }
            if (control != null) {
                Spacer(Modifier.width(12.dp))
                control()
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        onClick = { onCheckedChange(!checked) },
        control = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
    )
}
