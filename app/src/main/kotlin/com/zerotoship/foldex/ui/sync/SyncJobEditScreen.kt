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
                is SyncJobEditEvent.Saved -> {
                    snackbar.showSnackbar("保存しました")
                    onSaved()
                }
                is SyncJobEditEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "同期ジョブを追加" else "同期ジョブを編集") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = { TextButton(onClick = viewModel::save) { Text("保存") } },
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
                label = { Text("名前") },
                supportingText = { Text("一覧に表示される名前 (例: カメラ写真をNASへ)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionHeader("対象フォルダ")
            DirectionDropdown(state.direction) { d -> viewModel.update { it.copy(direction = d) } }
            var showLocalPicker by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = state.localPath,
                onValueChange = { v -> viewModel.update { it.copy(localPath = v) } },
                label = { Text("ローカルのフォルダ") },
                supportingText = { Text("端末内のフォルダの絶対パス (例: /storage/emulated/0/DCIM)") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { showLocalPicker = true }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "フォルダを選ぶ")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (showLocalPicker) {
                LocalFolderPickerDialog(
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
                    "リモートの接続が登録されていません。先に「接続を管理」で追加してください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedTextField(
                value = state.remotePath,
                onValueChange = { v -> viewModel.update { it.copy(remotePath = v) } },
                label = { Text("リモートのフォルダ") },
                supportingText = { Text("接続のルートからの相対パス。空欄ならルート (例: backup/photos)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionHeader("スケジュール")
            ScheduleSection(state) { transform -> viewModel.update(transform) }

            ToggleRow(
                title = "Wi-Fi のみ",
                description = "従量制でない接続 (Wi-Fi 等) のときだけ実行する",
                checked = state.requiresWifi,
                onCheckedChange = { v -> viewModel.update { it.copy(requiresWifi = v) } },
            )
            ToggleRow(
                title = "充電中のみ",
                description = "端末が充電中のときだけ実行する",
                checked = state.requiresCharging,
                onCheckedChange = { v -> viewModel.update { it.copy(requiresCharging = v) } },
            )
            ToggleRow(
                title = "バッテリー低下時は実行しない",
                description = "バッテリー残量が少ないときは実行を延期する",
                checked = state.requiresBatteryNotLow,
                onCheckedChange = { v -> viewModel.update { it.copy(requiresBatteryNotLow = v) } },
            )

            SectionHeader("競合と削除")
            ConflictPolicyDropdown(state.conflictPolicy) { p -> viewModel.update { it.copy(conflictPolicy = p) } }
            ToggleRow(
                title = "削除も同期する",
                description = "同期元から消えたファイルを同期先からも削除する",
                checked = state.deleteEnabled,
                onCheckedChange = { v -> viewModel.update { it.copy(deleteEnabled = v) } },
            )
            if (state.deleteEnabled) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "同期元のファイルを消すと、次回同期で同期先のファイルも消えます。誤削除に注意してください。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            SectionHeader("フィルタ (省略可)")
            OutlinedTextField(
                value = state.includePatternsText,
                onValueChange = { v -> viewModel.update { it.copy(includePatternsText = v) } },
                label = { Text("含めるファイル") },
                placeholder = { Text("*.jpg\n*.png") },
                supportingText = { Text("glob パターンを 1 行 1 つ。空欄ならすべてのファイルが対象。") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.excludePatternsText,
                onValueChange = { v -> viewModel.update { it.copy(excludePatternsText = v) } },
                label = { Text("除外するファイル") },
                placeholder = { Text("**/.git/**\n*.tmp") },
                supportingText = { Text("ここに一致するファイル/フォルダは同期しません。") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.maxFileSizeMb,
                onValueChange = { v -> viewModel.update { it.copy(maxFileSizeMb = v.filter(Char::isDigit)) } },
                label = { Text("これより大きいファイルは同期しない (MB)") },
                supportingText = { Text("空欄なら無制限。例: 100 と入れると 100MB 超のファイルを除外。") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "glob の例: * は任意の文字列、? は任意の 1 文字、** は任意の階層。" +
                    " 例) *.jpg = 拡張子 jpg のファイル、**/cache/** = どこかの cache フォルダ以下すべて。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            ToggleRow(
                title = "有効",
                description = "オフにすると定期実行も手動実行もしない",
                checked = state.enabled,
                onCheckedChange = { v -> viewModel.update { it.copy(enabled = v) } },
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.isNew) "追加" else "更新")
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

/** 端末内のローカルフォルダを候補から選ぶダイアログ。
 *  SAF を経由しないため、選んだパスはそのまま同期の localPath にセットできる。
 *  /storage/XXXX-XXXX 形式の SD カード/OTG も列挙対象 (要 MANAGE_EXTERNAL_STORAGE)。 */
@Composable
private fun LocalFolderPickerDialog(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current
    val candidates = remember { collectLocalFolderCandidates(context) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ローカルフォルダを選ぶ") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (candidates.isEmpty()) {
                    Text(
                        "候補が見つかりません。ストレージへのアクセス権限を確認してください。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    candidates.forEach { c ->
                        TextButton(
                            onClick = { onPick(c.path) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                horizontalAlignment = Alignment.Start,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(c.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    c.path,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (c.note != null) {
                                    Text(
                                        c.note,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
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
        list += LocalFolderCandidate("内部ストレージ", ext.absolutePath)
        listOf(
            "Download" to "ダウンロード",
            "DCIM" to "DCIM (カメラ)",
            "Pictures" to "画像",
            "Movies" to "動画",
            "Music" to "音楽",
            "Documents" to "ドキュメント",
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
                        val label = runCatching { vol.getDescription(context) }.getOrNull() ?: "SDカード"
                        list += LocalFolderCandidate(label, dir.absolutePath)
                    }
                }
            }
        }
    }
    runCatching {
        java.io.File("/storage").listFiles()?.forEach { f ->
            if (f.isDirectory && f.name != "self" && f.name != "emulated" && seen.add(f.absolutePath)) {
                val note = if (!f.canRead()) "全ファイルへのアクセス権限が必要" else null
                list += LocalFolderCandidate("SD: ${f.name}", f.absolutePath, note)
            }
        }
    }
    return list
}

private fun scheduleTypeLabel(t: ScheduleType): String = when (t) {
    ScheduleType.INTERVAL -> "間隔"
    ScheduleType.DAILY -> "毎日"
    ScheduleType.WEEKLY -> "毎週"
    ScheduleType.MONTHLY -> "毎月"
    ScheduleType.DATETIME -> "日時指定"
}

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
                listOf(0 to "手動のみ", 15 to "15分", 30 to "30分", 60 to "1時間", 180 to "3時間", 360 to "6時間", 720 to "12時間", 1440 to "1日").forEach { (m, label) ->
                    FilterChip(
                        selected = state.intervalMinutes == m,
                        onClick = { update { it.copy(intervalMinutes = m) } },
                        label = { Text(label) },
                    )
                }
            }
            OutlinedTextField(
                value = if (state.intervalMinutes == 0) "" else state.intervalMinutes.toString(),
                onValueChange = { v -> update { it.copy(intervalMinutes = (v.trim().toIntOrNull() ?: 0).coerceAtLeast(0)) } },
                label = { Text("実行間隔 (分) — 細かく指定する場合") },
                supportingText = { Text("空欄/0 で手動のみ。Android の制約で定期実行は最短 15 分間隔です。") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ScheduleType.DAILY -> TimeOfDayRow(state.timeOfDayMinutes) { m -> update { it.copy(timeOfDayMinutes = m) } }
        ScheduleType.WEEKLY -> {
            Text("曜日 (複数選択可)", style = MaterialTheme.typography.bodySmall)
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                listOf("月", "火", "水", "木", "金", "土", "日").forEachIndexed { i, label ->
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
        Text("時刻", style = MaterialTheme.typography.bodyLarge)
        SmallDropdown((0..23).toList(), hour, { "%02d".format(it) }) { h -> onChange(h * 60 + minute) }
        Text(":")
        SmallDropdown((0..55 step 5).toList(), minute - (minute % 5), { "%02d".format(it) }) { m -> onChange(hour * 60 + m) }
    }
}

@Composable
private fun DayOfMonthDropdown(day: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("毎月", style = MaterialTheme.typography.bodyLarge)
        val options = (1..31).toList() + 0 // 0 = 月末
        SmallDropdown(options, day, { if (it == 0) "月末" else "${it}日" }, onChange)
    }
}

@Composable
private fun <T> SmallDropdown(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
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
    val dateFmt = remember { SimpleDateFormat("yyyy/MM/dd (E)", Locale.getDefault()) }
    OutlinedButton(onClick = { showDate = true }, modifier = Modifier.fillMaxWidth()) {
        Text("日付: ${dateFmt.format(cal.time)}")
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
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("キャンセル") } },
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
    DropdownField(label = "方向", valueText = directionLabel(selected), contentDescription = "方向を選ぶ") { dismiss ->
        SyncDirection.entries.forEach { d ->
            DropdownMenuItem(text = { Text(directionLabel(d)) }, onClick = { onSelected(d); dismiss() })
        }
    }
}

@Composable
private fun ConflictPolicyDropdown(selected: ConflictPolicy, onSelected: (ConflictPolicy) -> Unit) {
    DropdownField(label = "競合解決", valueText = conflictPolicyLabel(selected), contentDescription = "競合解決を選ぶ") { dismiss ->
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
        selectedId != null -> "(不明な接続)"
        else -> "未選択"
    }
    DropdownField(label = "リモートの接続", valueText = valueText, contentDescription = "接続を選ぶ") { dismiss ->
        if (connections.isEmpty()) {
            DropdownMenuItem(text = { Text("接続がありません") }, onClick = { dismiss() }, enabled = false)
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
