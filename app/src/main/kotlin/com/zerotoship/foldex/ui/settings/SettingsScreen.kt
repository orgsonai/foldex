package com.zerotoship.foldex.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zerotoship.foldex.core.model.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
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
            SettingRow(
                title = "テーマ",
                subtitle = "アプリ全体の明暗",
                control = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) },
                                label = { Text(mode.displayName) },
                            )
                        }
                    }
                },
            )
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
            SettingRow(
                title = "アンドゥの表示時間",
                subtitle = "操作の取り消しバーが消えるまでの秒数",
                control = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(3, 5, 10).forEach { sec ->
                            FilterChip(
                                selected = settings.undoTimeoutSeconds == sec,
                                onClick = { viewModel.setUndoTimeoutSeconds(sec) },
                                label = { Text("${sec}秒") },
                            )
                        }
                    }
                },
            )

            HorizontalDivider()
            SettingsSectionHeader("詳細")
            SettingRow(title = "バージョン", subtitle = versionName, control = {})
            SettingRow(title = "ライセンス", subtitle = "GPL-3.0 (予定)", control = {})
        }
    }
}

private val ThemeMode.displayName: String
    get() = when (this) {
        ThemeMode.SYSTEM -> "システム"
        ThemeMode.LIGHT -> "ライト"
        ThemeMode.DARK -> "ダーク"
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

@Composable
private fun SettingRow(
    title: String,
    subtitle: String?,
    control: @Composable () -> Unit,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        control()
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
        control = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
        onClick = { onCheckedChange(!checked) },
    )
}
