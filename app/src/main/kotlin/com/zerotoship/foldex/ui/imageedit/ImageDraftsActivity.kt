// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.zerotoship.foldex.ui.imageedit.draft.DraftFailure
import com.zerotoship.foldex.ui.imageedit.draft.ImageDraftEntry
import com.zerotoship.foldex.ui.theme.FoldexTheme
import dagger.hilt.android.AndroidEntryPoint
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@AndroidEntryPoint
class ImageDraftsActivity : ComponentActivity() {
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
                val model: ImageDraftsViewModel = hiltViewModel()
                val state by model.state.collectAsStateWithLifecycle()
                DisposableEffect(model) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) model.refresh()
                    }
                    lifecycle.addObserver(observer)
                    model.refresh()
                    onDispose { lifecycle.removeObserver(observer) }
                }
                ImageDraftsScreen(state, onBack = { finish() }, onOpen = {
                    startActivity(ImageEditActivity.draftIntent(this@ImageDraftsActivity, it))
                }, onDelete = model::delete, onRefresh = model::refresh)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageDraftsScreen(
    state: ImageDraftsState,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    var deleting by remember { mutableStateOf<ImageDraftEntry?>(null) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.image_drafts_title)) }, navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.navigate_back))
            }
        }, actions = {
            TextButton(onClick = onRefresh, enabled = !state.busy) { Text(stringResource(R.string.image_draft_refresh)) }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.image_drafts_storage, state.entries.size,
                        formatBytes(state.entries.sumOf { it.bytes })), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.image_drafts_description), style = MaterialTheme.typography.bodySmall)
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.entries.isEmpty() && !state.busy && state.error == null) {
                        Text(stringResource(R.string.image_drafts_empty), Modifier.padding(top = 24.dp))
                    }
                }
            }
            items(state.entries, key = { it.id }) { entry ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy && !entry.inUse && entry.problem == null) {
                    onOpen(entry.id)
                }.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.updatedAt)) +
                            " · " + formatBytes(entry.bytes), style = MaterialTheme.typography.bodySmall)
                        if (entry.inUse || entry.problem != null) Text(stringResource(when {
                            entry.inUse -> R.string.image_draft_busy
                            entry.problem == DraftFailure.UNSUPPORTED -> R.string.image_draft_unsupported
                            else -> R.string.image_draft_corrupt
                        }), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { deleting = entry }, enabled = !entry.inUse && !state.busy) {
                        Icon(Icons.Default.Delete, stringResource(R.string.image_draft_delete_named, entry.name))
                    }
                }
                HorizontalDivider()
            }
        }
    }
    deleting?.let { entry ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.image_draft_delete_title)) },
            text = { Text(stringResource(R.string.image_draft_delete_body, entry.name)) },
            confirmButton = {
                TextButton(onClick = { deleting = null; onDelete(entry.id) }) {
                    Text(stringResource(R.string.image_draft_delete))
                }
            }, dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) }
            })
    }
}

@Preview(showBackground = true)
@Composable
private fun ImageDraftsPreview() {
    FoldexTheme { ImageDraftsScreen(ImageDraftsState(), {}, {}, {}, {}) }
}
