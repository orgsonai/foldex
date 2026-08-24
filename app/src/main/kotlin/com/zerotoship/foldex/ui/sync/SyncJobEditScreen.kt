// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zerotoship.foldex.core.model.Connection
import com.zerotoship.foldex.core.model.ConflictPolicy
import com.zerotoship.foldex.core.model.ScheduleType
import com.zerotoship.foldex.core.model.SyncDirection
import com.zerotoship.foldex.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncJobEditScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: SyncJobEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val connections by viewModel.connections.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                // 保存できたら待たずにすぐ一覧へ戻る (snackbar.showSnackbar はサスペンドして
                // 数秒待つため、ここでメッセージを出すと戻りが遅れる)。一覧に戻ること自体が完了の合図。
                is SyncJobEditEvent.Saved -> onSaved()
                is SyncJobEditEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isNew) R.string.sync_job_add else R.string.sync_job_edit)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_back))
                    }
                },
                actions = { TextButton(onClick = viewModel::save) { Text(stringResource(R.string.action_save)) } },
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
                label = { Text(stringResource(R.string.sync_job_name)) },
                supportingText = { Text(stringResource(R.string.sync_job_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionHeader(stringResource(R.string.sync_job_folders))
            DirectionDropdown(state.direction) { d -> viewModel.update { it.copy(direction = d) } }
            var showLocalPicker by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = state.localPath,
                onValueChange = { v -> viewModel.update { it.copy(localPath = v) } },
                label = { Text(stringResource(R.string.sync_local_folder)) },
                supportingText = { Text(stringResource(R.string.sync_local_folder_hint)) },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { showLocalPicker = true }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = stringResource(R.string.folder_choose))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (showLocalPicker) {
                LocalFolderPickerDialog(
                    initialPath = state.localPath,
                    onDismiss = { showLocalPicker = false },
                    onPick = { picked ->
                        viewModel.update { it.copy(localPath = picked) }
                        showLocalPicker = false
                    },
                )
            }
            ConnectionDropdown(
                connections = connections,
                selectedId = state.connectionId,
                onSelected = { id -> viewModel.update { it.copy(connectionId = id) } },
            )
            if (connections.isEmpty()) {
                Text(
                    stringResource(R.string.sync_no_remote_connections_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedTextField(
                value = state.remotePath,
                onValueChange = { v -> viewModel.update { it.copy(remotePath = v) } },
                label = { Text(stringResource(R.string.sync_remote_folder)) },
                supportingText = { Text(stringResource(R.string.sync_remote_folder_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionHeader(stringResource(R.string.sync_schedule))
            ScheduleSection(state) { transform -> viewModel.update(transform) }

            ToggleRow(
                title = stringResource(R.string.sync_wifi_only),
                description = stringResource(R.string.sync_wifi_only_desc),
                checked = state.requiresWifi,
                onCheckedChange = { v -> viewModel.update { it.copy(requiresWifi = v) } },
            )
            ToggleRow(
                title = stringResource(R.string.sync_charging_only),
                description = stringResource(R.string.sync_charging_only_desc),
                checked = state.requiresCharging,
                onCheckedChange = { v -> viewModel.update { it.copy(requiresCharging = v) } },
            )
            ToggleRow(
                title = stringResource(R.string.sync_battery_not_low),
                description = stringResource(R.string.sync_battery_not_low_desc),
                checked = state.requiresBatteryNotLow,
                onCheckedChange = { v -> viewModel.update { it.copy(requiresBatteryNotLow = v) } },
            )

            SectionHeader(stringResource(R.string.sync_conflicts_deletion))
            ConflictPolicyDropdown(state.conflictPolicy) { p -> viewModel.update { it.copy(conflictPolicy = p) } }
            ToggleRow(
                title = stringResource(R.string.sync_deletions),
                description = stringResource(R.string.sync_deletions_desc),
                checked = state.deleteEnabled,
                onCheckedChange = { v -> viewModel.update { it.copy(deleteEnabled = v) } },
            )
            if (state.deleteEnabled) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.sync_deletions_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            SectionHeader(stringResource(R.string.sync_filters_optional))
            OutlinedTextField(
                value = state.includePatternsText,
                onValueChange = { v -> viewModel.update { it.copy(includePatternsText = v) } },
                label = { Text(stringResource(R.string.sync_include_files)) },
                placeholder = { Text("*.jpg\n*.png") },
                supportingText = { Text(stringResource(R.string.sync_include_files_hint)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.excludePatternsText,
                onValueChange = { v -> viewModel.update { it.copy(excludePatternsText = v) } },
                label = { Text(stringResource(R.string.sync_exclude_files)) },
                placeholder = { Text("**/.git/**\n*.tmp") },
                supportingText = { Text(stringResource(R.string.sync_exclude_files_hint)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.maxFileSizeMb,
                onValueChange = { v -> viewModel.update { it.copy(maxFileSizeMb = v.filter(Char::isDigit)) } },
                label = { Text(stringResource(R.string.sync_max_file_size)) },
                supportingText = { Text(stringResource(R.string.sync_max_file_size_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.sync_glob_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            ToggleRow(
                title = stringResource(R.string.enabled),
                description = stringResource(R.string.sync_enabled_desc),
                checked = state.enabled,
                onCheckedChange = { v -> viewModel.update { it.copy(enabled = v) } },
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (state.isNew) R.string.action_add else R.string.action_update))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
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

/** 端末内のローカルフォルダをブラウズして選ぶダイアログ。
 *  上部のショートカット (内部ストレージ/Download/DCIM/SD…) でジャンプし、サブフォルダを
 *  タップして掘り下げ、「このフォルダを選択」で確定する。SAF を経由しないので、選んだ
 *  絶対パスをそのまま同期の localPath にできる (要 MANAGE_EXTERNAL_STORAGE)。 */
@Composable
private fun LocalFolderPickerDialog(
    initialPath: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current
    val candidates = remember { collectLocalFolderCandidates(context) }
    val rootPath = remember {
        android.os.Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"
    }
    // 既存のパスが有効なフォルダならそこから開始、なければ内部ストレージ直下から。
    var current by remember {
        val start = java.io.File(initialPath).takeIf { it.isDirectory } ?: java.io.File(rootPath)
        mutableStateOf(start)
    }
    val subDirs = remember(current.absolutePath) {
        runCatching {
            current.listFiles()
                ?.filter { it.isDirectory && !it.isHidden }
                ?.sortedBy { it.name.lowercase(Locale.getDefault()) }
        }.getOrNull() ?: emptyList()
    }
    val canGoUp = current.parentFile != null && current.absolutePath != "/" && current.absolutePath != "/storage"

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_choose_local_folder)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // ジャンプ用ショートカット (横スクロール)。
                if (candidates.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    ) {
                        candidates.forEach { c ->
                            FilterChip(
                                selected = current.absolutePath == c.path,
                                onClick = { current = java.io.File(c.path) },
                                label = { Text(c.label) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                // 現在地 + 上へ。
                Text(
                    current.absolutePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    onClick = { current.parentFile?.let { current = it } },
                    enabled = canGoUp,
                ) { Text(stringResource(R.string.folder_up)) }
                HorizontalDivider()
                // サブフォルダ一覧。
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                ) {
                    if (subDirs.isEmpty()) {
                        Text(
                            stringResource(R.string.folder_no_subfolders),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    } else {
                        subDirs.forEach { d ->
                            TextButton(
                                onClick = { current = d },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(
                                        Icons.Default.FolderOpen,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 8.dp),
                                    )
                                    Text(
                                        d.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(current.absolutePath) }) { Text(stringResource(R.string.folder_select_this)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private data class LocalFolderCandidate(
    val label: String,
    val path: String,
    val note: String? = null,
)

/** 内部ストレージの標準サブフォルダと、リムーバブル (SD/OTG) ボリュームを列挙する。 */
private fun collectLocalFolderCandidates(context: Context): List<LocalFolderCandidate> {
    val list = mutableListOf<LocalFolderCandidate>()
    val ext = android.os.Environment.getExternalStorageDirectory()
    if (ext != null && ext.exists()) {
        list += LocalFolderCandidate(context.getString(R.string.internal_storage), ext.absolutePath)
        listOf(
            "Download" to context.getString(R.string.home_downloads),
            "DCIM" to context.getString(R.string.quick_camera),
            "Pictures" to context.getString(R.string.home_images),
            "Movies" to context.getString(R.string.home_videos),
            "Music" to context.getString(R.string.home_music),
            "Documents" to context.getString(R.string.quick_documents),
        ).forEach { (dir, label) ->
            val f = java.io.File(ext, dir)
            if (f.exists()) list += LocalFolderCandidate(label, f.absolutePath)
        }
    }
    val seen = mutableSetOf<String>()
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        runCatching {
            val sm = context.getSystemService(android.os.storage.StorageManager::class.java)
            sm?.storageVolumes?.forEach { vol ->
                if (vol.isRemovable) {
                    val dir = vol.directory
                    if (dir != null && seen.add(dir.absolutePath)) {
                        val label = runCatching { vol.getDescription(context) }.getOrNull() ?: context.getString(R.string.home_sd_card)
                        list += LocalFolderCandidate(label, dir.absolutePath)
                    }
                }
            }
        }
    }
    runCatching {
        java.io.File("/storage").listFiles()?.forEach { f ->
            if (f.isDirectory && f.name != "self" && f.name != "emulated" && seen.add(f.absolutePath)) {
                val note = if (!f.canRead()) context.getString(R.string.home_all_files_required) else null
                list += LocalFolderCandidate("SD: ${f.name}", f.absolutePath, note)
            }
        }
    }
    return list
}

@Composable
private fun scheduleTypeLabel(t: ScheduleType): String = stringResource(when (t) {
    ScheduleType.INTERVAL -> R.string.sync_schedule_interval
    ScheduleType.DAILY -> R.string.sync_type_daily
    ScheduleType.WEEKLY -> R.string.sync_type_weekly
    ScheduleType.MONTHLY -> R.string.sync_type_monthly
    ScheduleType.DATETIME -> R.string.sync_type_datetime
})

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleSection(
    state: SyncJobEditState,
    update: (transform: (SyncJobEditState) -> SyncJobEditState) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        ScheduleType.entries.forEach { t ->
            FilterChip(
                selected = state.scheduleType == t,
                onClick = { update { it.copy(scheduleType = t) } },
                label = { Text(scheduleTypeLabel(t)) },
            )
        }
    }
    when (state.scheduleType) {
        ScheduleType.INTERVAL -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                listOf(0, 15, 30, 60, 180, 360, 720, 1440).forEach { m ->
                    FilterChip(
                        selected = state.intervalMinutes == m,
                        onClick = { update { it.copy(intervalMinutes = m) } },
                        label = { Text(intervalLabel(m)) },
                    )
                }
            }
            OutlinedTextField(
                value = if (state.intervalMinutes == 0) "" else state.intervalMinutes.toString(),
                onValueChange = { v -> update { it.copy(intervalMinutes = (v.trim().toIntOrNull() ?: 0).coerceAtLeast(0)) } },
                label = { Text(stringResource(R.string.sync_custom_interval)) },
                supportingText = { Text(stringResource(R.string.sync_custom_interval_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ScheduleType.DAILY -> TimeOfDayRow(state.timeOfDayMinutes) { m -> update { it.copy(timeOfDayMinutes = m) } }
        ScheduleType.WEEKLY -> {
            Text(stringResource(R.string.sync_weekdays_multiple), style = MaterialTheme.typography.bodySmall)
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                stringArrayResource(R.array.sync_weekday_short).forEachIndexed { i, label ->
                    val bit = 1 shl i
                    FilterChip(
                        selected = (state.daysOfWeek and bit) != 0,
                        onClick = { update { it.copy(daysOfWeek = it.daysOfWeek xor bit) } },
                        label = { Text(label) },
                    )
                }
            }
            TimeOfDayRow(state.timeOfDayMinutes) { m -> update { it.copy(timeOfDayMinutes = m) } }
        }
        ScheduleType.MONTHLY -> {
            DayOfMonthDropdown(state.dayOfMonth) { d -> update { it.copy(dayOfMonth = d) } }
            TimeOfDayRow(state.timeOfDayMinutes) { m -> update { it.copy(timeOfDayMinutes = m) } }
        }
        ScheduleType.DATETIME -> DateTimePickerRow(state, update)
    }
}

@Composable
private fun TimeOfDayRow(minutesOfDay: Int, onChange: (Int) -> Unit) {
    val hour = (minutesOfDay / 60).coerceIn(0, 23)
    val minute = (minutesOfDay % 60).coerceIn(0, 59)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.sync_time), style = MaterialTheme.typography.bodyLarge)
        SmallDropdown((0..23).toList(), hour, { "%02d".format(it) }) { h -> onChange(h * 60 + minute) }
        Text(":")
        SmallDropdown((0..55 step 5).toList(), minute - (minute % 5), { "%02d".format(it) }) { m -> onChange(hour * 60 + m) }
    }
}

@Composable
private fun DayOfMonthDropdown(day: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.sync_type_monthly), style = MaterialTheme.typography.bodyLarge)
        val options = (1..31).toList() + 0 // 0 = 月末
        SmallDropdown(options, day, { if (it == 0) stringResource(R.string.sync_picker_month_end) else stringResource(R.string.sync_picker_month_day, it) }, onChange)
    }
}

@Composable
private fun <T> SmallDropdown(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text(label(selected)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(text = { Text(label(opt)) }, onClick = { onSelect(opt); expanded = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimePickerRow(
    state: SyncJobEditState,
    update: (transform: (SyncJobEditState) -> SyncJobEditState) -> Unit,
) {
    val cal = remember(state.dateTimeMillis) {
        Calendar.getInstance().apply {
            if (state.dateTimeMillis > 0) timeInMillis = state.dateTimeMillis
            else {
                add(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0)
            }
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
    }
    fun commit(c: Calendar) = update { it.copy(dateTimeMillis = c.timeInMillis) }
    // 初期値が未設定なら一度書き戻しておく
    LaunchedEffect(Unit) { if (state.dateTimeMillis <= 0) commit(cal) }

    var showDate by remember { mutableStateOf(false) }
    val locale = LocalResources.current.configuration.locales[0]
    val dateFmt = remember(locale) { SimpleDateFormat("yyyy/MM/dd (E)", locale) }
    OutlinedButton(onClick = { showDate = true }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.sync_date, dateFmt.format(cal.time)))
    }
    TimeOfDayRow(cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)) { m ->
        val c = cal.clone() as Calendar
        c.set(Calendar.HOUR_OF_DAY, m / 60); c.set(Calendar.MINUTE, m % 60)
        commit(c)
    }
    if (showDate) {
        val dpState = rememberDatePickerState(initialSelectedDateMillis = cal.timeInMillis)
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { picked ->
                        val p = Calendar.getInstance().apply { timeInMillis = picked }
                        val c = cal.clone() as Calendar
                        c.set(p.get(Calendar.YEAR), p.get(Calendar.MONTH), p.get(Calendar.DAY_OF_MONTH))
                        commit(c)
                    }
                    showDate = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state = dpState) }
    }
}

@Composable
private fun DropdownField(
    label: String,
    valueText: String,
    contentDescription: String,
    menuContent: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = valueText,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(label) },
            trailingIcon = {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = contentDescription)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            menuContent { expanded = false }
        }
    }
}

@Composable
private fun DirectionDropdown(selected: SyncDirection, onSelected: (SyncDirection) -> Unit) {
    DropdownField(label = stringResource(R.string.sync_direction), valueText = directionLabel(selected), contentDescription = stringResource(R.string.sync_choose_direction)) { dismiss ->
        SyncDirection.entries.forEach { d ->
            DropdownMenuItem(text = { Text(directionLabel(d)) }, onClick = { onSelected(d); dismiss() })
        }
    }
}

@Composable
private fun ConflictPolicyDropdown(selected: ConflictPolicy, onSelected: (ConflictPolicy) -> Unit) {
    DropdownField(label = stringResource(R.string.sync_conflict_resolution), valueText = conflictPolicyLabel(selected), contentDescription = stringResource(R.string.sync_choose_conflict_resolution)) { dismiss ->
        ConflictPolicy.entries.forEach { p ->
            DropdownMenuItem(text = { Text(conflictPolicyLabel(p)) }, onClick = { onSelected(p); dismiss() })
        }
    }
}

@Composable
private fun ConnectionDropdown(
    connections: List<Connection>,
    selectedId: String?,
    onSelected: (String) -> Unit,
) {
    val selected = connections.firstOrNull { it.id == selectedId }
    val valueText = when {
        selected != null -> "${selected.name} (${selected.protocol.scheme})"
        selectedId != null -> stringResource(R.string.connection_unknown)
        else -> stringResource(R.string.not_selected)
    }
    DropdownField(label = stringResource(R.string.sync_remote_connection), valueText = valueText, contentDescription = stringResource(R.string.connection_choose)) { dismiss ->
        if (connections.isEmpty()) {
            DropdownMenuItem(text = { Text(stringResource(R.string.connections_none)) }, onClick = { dismiss() }, enabled = false)
        } else {
            connections.forEach { c ->
                DropdownMenuItem(
                    text = { Text("${c.name} (${c.protocol.scheme})") },
                    onClick = { onSelected(c.id); dismiss() },
                )
            }
        }
    }
}
