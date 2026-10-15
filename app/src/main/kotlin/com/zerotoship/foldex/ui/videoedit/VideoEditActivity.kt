// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zerotoship.foldex.R
import com.zerotoship.foldex.core.data.repo.SettingsRepository
import com.zerotoship.foldex.core.data.repo.UserSettings
import com.zerotoship.foldex.core.model.ThemeMode
import com.zerotoship.foldex.ui.theme.FoldexTheme
import com.zerotoship.foldex.ui.videoedit.model.AssetKind
import com.zerotoship.foldex.ui.videoedit.model.ExportPreset
import com.zerotoship.foldex.ui.videoedit.model.VideoSaveNaming
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * 動画編集 (試作) の単独 Activity。
 *
 * 呼び出し側 (ビューア / ファイル一覧の複数選択) は、端末内の実ファイルのパスを渡す。
 * 試作版は元のファイルに何も書き込まないので、結果は返さない。
 */
@AndroidEntryPoint
class VideoEditActivity : ComponentActivity() {

    @Inject lateinit var settingsRepo: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val paths = intent.getStringArrayExtra(EXTRA_PATHS)?.toList().orEmpty()
        val names = intent.getStringArrayExtra(EXTRA_NAMES)?.toList().orEmpty()
        val draftId = intent.getStringExtra(EXTRA_DRAFT_ID)

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
                val viewModel: VideoEditViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                val snackbar = remember { SnackbarHostState() }
                // 音声を置く位置。ファイル選択から戻るまで覚えておく。
                var pendingAddAtUs by rememberSaveable { mutableLongStateOf(0L) }
                var pendingLayer by rememberSaveable { mutableStateOf(false) }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                    uris.forEach { uri ->
                        try {
                            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        } catch (_: SecurityException) {
                            // 永続的な権限を出さない提供元がある。この画面を開いている間は読める。
                        }
                    }
                    viewModel.addSources(uris.map { it.toString() }, pendingAddAtUs, pendingLayer)
                }
                // 「場所を選んで書き出す」。形式ごとに MIME が違うので、設定が変わったら登録し直す。
                val exportMime = VideoSaveNaming.mimeType(state.exportSettings.preset == ExportPreset.AUDIO_ONLY)
                val createDocument = remember(exportMime) { ActivityResultContracts.CreateDocument(exportMime) }
                val documentCreator = rememberLauncherForActivityResult(createDocument) { uri ->
                    if (uri != null) viewModel.exportToDocument(uri)
                }

                LaunchedEffect(Unit) { viewModel.load(paths, names, draftId) }
                // 画面が裏に回ったら、1 秒を待たずに下書きを保存する (設計書 §11)。
                DisposableEffect(viewModel) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_STOP) viewModel.flushDraftOnBackground()
                    }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }
                LaunchedEffect(Unit) {
                    viewModel.events.collect { event ->
                        when (event) {
                            // 表示の間も次のイベント (閉じる等) を受け取れるよう、スナックバーは別に出す。
                            is VideoEditEvent.Message -> launch {
                                snackbar.showSnackbar(getString(event.resId, *event.args.toTypedArray()))
                            }
                            VideoEditEvent.Close -> finish()
                            is VideoEditEvent.CloseWithMessage -> {
                                Toast.makeText(this@VideoEditActivity, event.resId, Toast.LENGTH_LONG).show()
                                finish()
                            }
                        }
                    }
                }

                VideoEditScreen(
                    state = state,
                    actions = viewModel,
                    snackbar = snackbar,
                    onAddMedia = { kind, atUs, asLayer ->
                        pendingLayer = asLayer
                        pendingAddAtUs = atUs
                        val mime = when (kind) {
                            AssetKind.VIDEO -> "video/*"
                            AssetKind.IMAGE -> "image/*"
                            AssetKind.AUDIO -> "audio/*"
                        }
                        try {
                            picker.launch(arrayOf(mime))
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(this@VideoEditActivity, R.string.video_edit_picker_unavailable, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onExportElsewhere = { suggestedName ->
                        try {
                            documentCreator.launch(suggestedName)
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(this@VideoEditActivity, R.string.video_edit_picker_unavailable, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_PATHS = "foldex.videoedit.paths"
        private const val EXTRA_NAMES = "foldex.videoedit.names"
        private const val EXTRA_DRAFT_ID = "foldex.videoedit.draft_id"

        /** 下書き一覧から開く。 */
        fun draftIntent(context: Context, draftId: String): Intent =
            Intent(context, VideoEditActivity::class.java).putExtra(EXTRA_DRAFT_ID, draftId)

        fun intent(context: Context, localPaths: List<String>, names: List<String>): Intent =
            Intent(context, VideoEditActivity::class.java)
                .putExtra(EXTRA_PATHS, localPaths.toTypedArray())
                .putExtra(EXTRA_NAMES, names.toTypedArray())
    }
}
