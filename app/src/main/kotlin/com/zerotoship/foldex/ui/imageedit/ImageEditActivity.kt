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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
        if (path.isBlank()) { finish(); return }
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

                LaunchedEffect(Unit) { viewModel.load(file, name, sourceUri) }

                LaunchedEffect(Unit) {
                    viewModel.events.collect { event ->
                        when (event) {
                            is ImageEditEvent.Message -> snackbar.showSnackbar(event.text)
                            is ImageEditEvent.Saved -> {
                                Toast.makeText(
                                    this@ImageEditActivity,
                                    "保存しました: ${event.displayPath.substringAfterLast('/')}",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                setResult(Activity.RESULT_OK)
                                finish()
                            }
                        }
                    }
                }

                ImageEditScreen(
                    state = state,
                    snackbar = snackbar,
                    onBack = { finish() },
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
                    onRequestEstimate = viewModel::requestEstimate,
                    onAddStroke = viewModel::addStroke,
                    onAddText = viewModel::addText,
                    onSelectText = viewModel::selectText,
                    onChangeText = viewModel::updateText,
                    onMoveText = viewModel::moveEditingText,
                    onMoveTextEnd = viewModel::commitTextMove,
                    onDeleteText = viewModel::deleteEditingText,
                    onSave = viewModel::save,
                )
            }
        }
    }

    companion object {
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
