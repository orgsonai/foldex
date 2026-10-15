// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zerotoship.foldex.R
import com.zerotoship.foldex.core.data.repo.SettingsRepository
import com.zerotoship.foldex.core.data.repo.UserSettings
import com.zerotoship.foldex.core.model.ThemeMode
import com.zerotoship.foldex.ui.theme.FoldexTheme
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject

/**
 * 内蔵画像エディタの単独 Activity。
 *
 * 呼び出し側 (ビューア / ファイル一覧) は、対象がローカルの実体になった状態
 * (リモート・SAF はキャッシュ済み) でここを起動する。リモート由来なら
 * [EXTRA_SOURCE_URI] に元の URI を渡すと、保存時にそこへ書き戻す。
 *
 * 保存に成功したら [Activity.RESULT_OK] を返すので、呼び出し側は一覧を更新できる。
 */
@AndroidEntryPoint
class ImageEditActivity : ComponentActivity() {

    @Inject lateinit var settingsRepo: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val draftId = intent.getStringExtra(EXTRA_DRAFT_ID)
        if (path.isBlank() && draftId == null) { finish(); return }
        val file = File(path)
        val name = intent.getStringExtra(EXTRA_NAME) ?: file.name
        val sourceUri = intent.getStringExtra(EXTRA_SOURCE_URI)

        enableEdgeToEdge()
        setContent {
            val settings by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = UserSettings())
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            FoldexTheme(
                darkTheme = darkTheme,
                dynamicColor = settings.dynamicColor,
                colorTheme = settings.colorTheme,
            ) {
                val viewModel: ImageEditViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                val snackbar = remember { SnackbarHostState() }
                val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    val uri = result.data?.data?.takeIf { result.resultCode == Activity.RESULT_OK }
                    if (uri != null) {
                        try {
                            contentResolver.takePersistableUriPermission(uri, (result.data?.flags ?: 0) and
                                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                        } catch (_: SecurityException) { /* 保存先によって再起動後の権限は提供されない。 */ }
                    }
                    viewModel.destinationSelected(uri?.toString())
                }

                LaunchedEffect(Unit) { viewModel.load(file, name, sourceUri, draftId) }
                androidx.compose.runtime.DisposableEffect(viewModel) {
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) viewModel.flushDraftOnBackground()
                    }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }

                LaunchedEffect(Unit) {
                    viewModel.events.collect { event ->
                        when (event) {
                            ImageEditEvent.Close -> finish()
                            is ImageEditEvent.Message -> snackbar.showSnackbar(event.text)
                            is ImageEditEvent.Saved -> {
                                Toast.makeText(
                                    this@ImageEditActivity,
                                    getString(R.string.image_saved_path, event.displayPath.substringAfterLast('/')) +
                                        if (event.metadataWarning) "\n" + getString(R.string.image_export_metadata_warning) else "",
                                    if (event.metadataWarning) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                                ).show()
                                setResult(Activity.RESULT_OK)
                                com.zerotoship.foldex.ui.common.SavedFileChanges.notifySaved()
                            }
                            is ImageEditEvent.ChooseDestination -> {
                                try {
                                    createDocument.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                        type = event.mimeType
                                        putExtra(Intent.EXTRA_TITLE, event.name)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                                    })
                                } catch (_: android.content.ActivityNotFoundException) {
                                    viewModel.destinationSelected(null)
                                    snackbar.showSnackbar(getString(R.string.image_export_picker_unavailable))
                                }
                            }
                        }
                    }
                }

                ImageEditScreen(
                    state = state,
                    snackbar = snackbar,
                    onBack = { viewModel.closeEditor(keepDraft = true) },
                    onDiscardDraft = { viewModel.closeEditor(keepDraft = false) },
                    onRetryDraft = viewModel::retryDraft,
                    onOpenDrafts = { startActivity(Intent(this@ImageEditActivity, ImageDraftsActivity::class.java)) },
                    onUndo = viewModel::undo,
                    onRedo = viewModel::redo,
                    onSelectTool = viewModel::selectTool,
                    onRotateLeft = viewModel::rotateLeft,
                    onRotateRight = viewModel::rotateRight,
                    onFlipHorizontal = viewModel::flipHorizontal,
                    onFlipVertical = viewModel::flipVertical,
                    onApplyCrop = viewModel::applyCrop,
                    onResetCrop = viewModel::resetCrop,
                    onSetLongEdge = viewModel::setOutputLongEdge,
                    onSetPercent = viewModel::setOutputPercent,
                    onSetFormat = viewModel::setFormat,
                    onSetQuality = viewModel::setQuality,
                    onSetBackground = viewModel::setJpegBackground,
                    onSetMetadata = viewModel::setMetadataPolicy,
                    onSetTarget = viewModel::setTargetBytes,
                    onRequestEstimate = viewModel::requestEstimate,
                    onAddStroke = viewModel::addStroke,
                    onAddText = viewModel::addText,
                    onSelectText = viewModel::selectText,
                    onChangeText = viewModel::updateText,
                    onMoveText = viewModel::moveEditingText,
                    onMoveTextEnd = viewModel::commitTextMove,
                    onDeleteText = viewModel::deleteEditingText,
                    onApplySelection = viewModel::applySelection,
                    onClearSelection = viewModel::clearSelection,
                    onInvertSelection = viewModel::invertSelection,
                    onFillSelection = viewModel::fillSelection,
                    onEraseSelection = viewModel::eraseSelection,
                    onBeginMosaic = viewModel::beginMosaic,
                    onMosaicBlockSize = viewModel::setMosaicBlockSize,
                    onMosaicSizeFinished = viewModel::commitMosaicSize,
                    onEditMosaic = viewModel::editMosaic,
                    onToggleMosaicArea = viewModel::toggleMosaicArea,
                    onDeleteMosaic = viewModel::deleteMosaic,
                    onSelectionBrushFailure = viewModel::selectionBrushFailed,
                    onCommitMosaic = viewModel::commitMosaic,
                    onCancelMosaic = viewModel::cancelMosaic,
                    onSelectObject = viewModel::selectObject,
                    onCancelObjectSelection = viewModel::cancelAutomaticSelection,
                    onSave = viewModel::save,
                )
                state.targetSizeFailure?.let { failure ->
                    AlertDialog(
                        onDismissRequest = viewModel::dismissTargetSizeFailure,
                        title = { Text(stringResource(R.string.image_target_failed_title)) },
                        text = {
                            androidx.compose.foundation.layout.Column {
                                Text(stringResource(R.string.image_target_failed_body,
                                    formatBytes(failure.targetBytes.toLong()), formatBytes(failure.actualBytes)))
                                failure.suggestedSize?.let { size ->
                                    Text(stringResource(R.string.image_target_suggestion, size.width, size.height))
                                }
                            }
                        },
                        confirmButton = {
                            failure.suggestedSize?.let {
                                TextButton(onClick = viewModel::applyTargetSizeSuggestion) {
                                    Text(stringResource(R.string.image_memory_apply))
                                }
                            }
                        },
                        dismissButton = {
                            androidx.compose.foundation.layout.Column {
                                TextButton(onClick = viewModel::saveWithoutTarget) {
                                    Text(stringResource(R.string.image_target_remove_save))
                                }
                                TextButton(onClick = viewModel::dismissTargetSizeFailure) {
                                    Text(stringResource(R.string.image_export_back_to_edit))
                                }
                            }
                        },
                    )
                }
                state.memorySuggestion?.let { size ->
                    AlertDialog(
                        onDismissRequest = viewModel::dismissMemorySuggestion,
                        title = { Text(stringResource(R.string.image_memory_title)) },
                        text = { Text(stringResource(R.string.image_memory_suggestion, size.width, size.height)) },
                        confirmButton = {
                            TextButton(onClick = viewModel::applyMemorySuggestion) {
                                Text(stringResource(R.string.image_memory_apply))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = viewModel::dismissMemorySuggestion) {
                                Text(stringResource(R.string.image_export_back_to_edit))
                            }
                        },
                    )
                }
                state.unconfirmedSave?.let { name ->
                    AlertDialog(
                        onDismissRequest = viewModel::dismissSaveVerification,
                        title = { Text(stringResource(R.string.image_export_unconfirmed_title)) },
                        text = { Text(stringResource(R.string.image_export_unconfirmed_body, name)) },
                        confirmButton = {
                            TextButton(enabled = !state.saving, onClick = viewModel::verifySavedImage) {
                                Text(stringResource(R.string.image_export_verify_again))
                            }
                        },
                        dismissButton = {
                            TextButton(enabled = !state.saving, onClick = viewModel::dismissSaveVerification) {
                                Text(stringResource(R.string.image_export_back_to_edit))
                            }
                        },
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_DRAFT_ID = "foldex.imageedit.draft_id"

        fun draftIntent(context: Context, id: String): Intent = Intent(context, ImageEditActivity::class.java)
            .putExtra(EXTRA_DRAFT_ID, id)

        private const val EXTRA_PATH = "foldex.imageedit.path"
        private const val EXTRA_NAME = "foldex.imageedit.name"
        private const val EXTRA_SOURCE_URI = "foldex.imageedit.source_uri"

        fun intent(
            context: Context,
            localPath: String,
            name: String,
            sourceUriString: String? = null,
        ): Intent = Intent(context, ImageEditActivity::class.java)
            .putExtra(EXTRA_PATH, localPath)
            .putExtra(EXTRA_NAME, name)
            .apply {
                if (!sourceUriString.isNullOrBlank()) putExtra(EXTRA_SOURCE_URI, sourceUriString)
            }
    }
}
