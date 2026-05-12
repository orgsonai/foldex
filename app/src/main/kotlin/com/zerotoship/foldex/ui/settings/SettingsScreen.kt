package com.zerotoship.foldex.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zerotoship.foldex.core.model.DeleteBehavior
import com.zerotoship.foldex.core.model.SyncBackupPolicy
import com.zerotoship.foldex.core.model.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenFileTypes: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("設定") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSectionHeader("表示")
            SettingRow(title = "テーマ", subtitle = "アプリ全体の明暗", wide = true) {
                ChipsControl {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            label = { Text(mode.displayName) },
                        )
                    }
                }
            }
            SwitchRow(
                title = "Material You",
                subtitle = "壁紙に合わせた配色 (Android 12 以上)",
                checked = settings.dynamicColor,
                onCheckedChange = viewModel::setDynamicColor,
            )
            SwitchRow(
                title = "拡張子バッジ",
                subtitle = "ファイル名の後ろに拡張子を表示",
                checked = settings.showExtensionBadge,
                onCheckedChange = viewModel::setShowExtensionBadge,
            )

            HorizontalDivider()
            SettingsSectionHeader("動作")
            SwitchRow(
                title = "削除前に確認",
                subtitle = "削除のたびにダイアログを表示",
                checked = settings.confirmBeforeDelete,
                onCheckedChange = viewModel::setConfirmBeforeDelete,
            )
            SettingRow(title = "アンドゥの表示時間", subtitle = "操作の取り消しバーが消えるまでの秒数", wide = true) {
                ChipsControl {
                    listOf(3, 5, 10).forEach { sec ->
                        FilterChip(
                            selected = settings.undoTimeoutSeconds == sec,
                            onClick = { viewModel.setUndoTimeoutSeconds(sec) },
                            label = { Text("${sec}秒") },
                        )
                    }
                }
            }

            HorizontalDivider()
            SettingsSectionHeader("ゴミ箱")
            SettingRow(title = "削除の行き先", subtitle = "ファイルを削除したときの動作", wide = true) {
                ChipsControl {
                    DeleteBehavior.entries.forEach { b ->
                        FilterChip(
                            selected = settings.deleteBehavior == b,
                            onClick = { viewModel.setDeleteBehavior(b) },
                            label = { Text(b.displayName) },
                        )
                    }
                }
            }
            SettingRow(title = "ゴミ箱の保持期間", subtitle = "これより古いものは自動で完全削除", wide = true) {
                ChipsControl {
                    listOf(7 to "7日", 30 to "30日", 90 to "90日", 0 to "無期限").forEach { (days, label) ->
                        FilterChip(
                            selected = settings.trashRetentionDays == days,
                            onClick = { viewModel.setTrashRetentionDays(days) },
                            label = { Text(label) },
                        )
                    }
                }
            }
            SettingRow(
                title = "ゴミ箱を開く",
                subtitle = "削除したファイルの復元・完全削除",
                onClick = onOpenTrash,
            )

            HorizontalDivider()
            SettingsSectionHeader("同期")
            SwitchRow(
                title = "削除時にバックアップ",
                subtitle = "delete 同期で消えるファイルをアプリ内に世代保存",
                checked = settings.syncDeleteBackup,
                onCheckedChange = viewModel::setSyncDeleteBackup,
            )
            if (settings.syncDeleteBackup) {
                SettingRow(title = "保存する世代数", subtitle = "古い世代から自動で削除", wide = true) {
                    ChipsControl {
                        listOf(1, 3, 5).forEach { n ->
                            FilterChip(
                                selected = settings.syncBackupGenerations == n,
                                onClick = { viewModel.setSyncBackupGenerations(n) },
                                label = { Text("${n}世代") },
                            )
                        }
                    }
                }
                SettingRow(
                    title = "確認なしでバックアップする上限",
                    subtitle = "これより大きいファイルは下の設定に従う",
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
                    title = "上限を超えたファイル",
                    subtitle = "バックグラウンド実行では「確認」は安全側 (バックアップ) になります",
                    wide = true,
                ) {
                    ChipsControl {
                        SyncBackupPolicy.entries.forEach { p ->
                            FilterChip(
                                selected = settings.syncBackupPolicyOverThreshold == p,
                                onClick = { viewModel.setSyncBackupPolicyOverThreshold(p) },
                                label = { Text(p.displayName) },
                            )
                        }
                    }
                }
            }

            HorizontalDivider()
            SettingsSectionHeader("ファイル")
            SettingRow(
                title = "ファイルの開き方",
                subtitle = "拡張子ごとに内蔵ビューア / 毎回選択 / 外部アプリ を指定",
                onClick = onOpenFileTypes,
            )

            HorizontalDivider()
            SettingsSectionHeader("詳細")
            SettingRow(title = "バージョン", subtitle = versionName)
            SettingRow(title = "ライセンス", subtitle = "GPL-3.0 (予定)")
            Spacer(Modifier.height(16.dp))
        }
    }
}

private val ThemeMode.displayName: String
    get() = when (this) {
        ThemeMode.SYSTEM -> "システム"
        ThemeMode.LIGHT -> "ライト"
        ThemeMode.DARK -> "ダーク"
    }

private val DeleteBehavior.displayName: String
    get() = when (this) {
        DeleteBehavior.TRASH -> "ゴミ箱へ"
        DeleteBehavior.PERMANENT -> "完全削除"
        DeleteBehavior.ASK -> "毎回確認"
    }

private val SyncBackupPolicy.displayName: String
    get() = when (this) {
        SyncBackupPolicy.ASK -> "確認"
        SyncBackupPolicy.BACKUP -> "バックアップ"
        SyncBackupPolicy.SKIP -> "バックアップしない"
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
