// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zerotoship.foldex.R
import com.zerotoship.foldex.core.data.repo.SettingsRepository
import com.zerotoship.foldex.core.data.repo.UserSettings
import com.zerotoship.foldex.core.model.ThemeMode
import com.zerotoship.foldex.ui.imageedit.formatBytes
import com.zerotoship.foldex.ui.theme.FoldexTheme
import com.zerotoship.foldex.ui.videoedit.model.formatClock
import com.zerotoship.foldex.ui.videoedit.project.VideoDraftEntry
import com.zerotoship.foldex.ui.videoedit.project.VideoDraftFailure
import dagger.hilt.android.AndroidEntryPoint
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/** 動画編集の下書きの一覧 (設計書 §7 の導線「ホームの下書き一覧」)。開く・削除・容量の確認。 */
@AndroidEntryPoint
class VideoDraftsActivity : ComponentActivity() {
    @Inject lateinit var settingsRepo: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = UserSettings())
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            FoldexTheme(darkTheme = dark, dynamicColor = settings.dynamicColor, colorTheme = settings.colorTheme) {
                val model: VideoDraftsViewModel = hiltViewModel()
                val state by model.state.collectAsStateWithLifecycle()
                // 編集画面から戻ったときに、更新日時と使用中の表示を読み直す。
                DisposableEffect(model) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) model.refresh()
                    }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }
                VideoDraftsScreen(
                    state = state,
                    onBack = { finish() },
                    onOpen = { id -> startActivity(VideoEditActivity.draftIntent(this@VideoDraftsActivity, id)) },
                    onDelete = model::delete,
                    onRefresh = model::refresh,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoDraftsScreen(
    state: VideoDraftsState,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    var deleting by remember { mutableStateOf<VideoDraftEntry?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.video_drafts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.navigate_back))
                    }
                },
                actions = {
                    TextButton(onClick = onRefresh, enabled = !state.busy) { Text(stringResource(R.string.video_drafts_refresh)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.video_drafts_storage, state.entries.size, formatBytes(state.entries.sumOf { it.bytes })),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(stringResource(R.string.video_drafts_description), style = MaterialTheme.typography.bodySmall)
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                    if (state.entries.isEmpty() && !state.busy && state.error == null) {
                        Text(stringResource(R.string.video_drafts_empty), Modifier.padding(top = 24.dp))
                    }
                }
            }
            items(state.entries, key = { it.id }) { entry ->
                val openable = !state.busy && !entry.inUse && entry.problem == null
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = openable) { onOpen(entry.id) }
                        .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.video_edit_summary, entry.clipCount, formatClock(entry.durationUs)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.updatedAt)) +
                                " · " + formatBytes(entry.bytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        problemText(entry)?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    IconButton(onClick = { deleting = entry }, enabled = !entry.inUse && !state.busy) {
                        Icon(Icons.Default.Delete, stringResource(R.string.video_draft_delete_named, entry.name))
                    }
                }
                HorizontalDivider()
            }
        }
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.video_draft_delete_title)) },
            text = { Text(stringResource(R.string.video_draft_delete_body, entry.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onDelete(entry.id)
                }) { Text(stringResource(R.string.video_draft_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun problemText(entry: VideoDraftEntry): String? = when {
    entry.inUse -> stringResource(R.string.video_draft_in_use)
    entry.problem == VideoDraftFailure.UNSUPPORTED -> stringResource(R.string.video_draft_open_unsupported)
    entry.problem != null -> stringResource(R.string.video_draft_open_corrupt)
    else -> null
}

@Preview(showBackground = true, locale = "ja")
@Composable
private fun VideoDraftsPreview() {
    FoldexTheme {
        VideoDraftsScreen(
            state = VideoDraftsState(
                entries = listOf(
                    VideoDraftEntry("1", "旅行.mp4", 1_700_000_000_000, 48_000, clipCount = 6, durationUs = 95_000_000),
                    VideoDraftEntry("2", "画面録画.mp4", 1_690_000_000_000, 12_000, problem = VideoDraftFailure.CORRUPT),
                ),
            ),
            onBack = {},
            onOpen = {},
            onDelete = {},
            onRefresh = {},
        )
    }
}
