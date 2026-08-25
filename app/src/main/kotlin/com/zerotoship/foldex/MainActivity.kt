// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.zerotoship.foldex.core.data.repo.SettingsRepository
import com.zerotoship.foldex.core.model.ThemeMode
import com.zerotoship.foldex.ui.filebrowser.FileBrowserViewModel
import com.zerotoship.foldex.ui.main.MainScreen
import com.zerotoship.foldex.ui.perf.JankTracker
import com.zerotoship.foldex.ui.settings.SettingsViewModel
import com.zerotoship.foldex.ui.theme.FoldexTheme
import com.zerotoship.foldex.sync.scheduler.SyncScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** App Shortcuts (manifest 静的) から渡される `foldex.shortcut` extra。MainScreen が消費する。 */
    private val pendingShortcut = mutableStateOf<String?>(null)

    /** ACTION_SEND / ACTION_SEND_MULTIPLE で他アプリから渡されたファイル群。 */
    private val pendingShares = mutableStateOf<List<Uri>>(emptyList())

    @Inject lateinit var syncScheduler: SyncScheduler

    /** 起動時、設定が読めるまで最初の描画を止めるために見る。 */
    @Inject lateinit var settingsRepository: SettingsRepository

    /** 「動作の重さを記録する」が ON のときだけフレームの遅れを測る。 */
    @Inject lateinit var jankTracker: JankTracker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        // 定期同期の予約を貼り直す。端末がアプリを休止させるとアラームごと落とされ、
        // 「発火したら次を貼る」の鎖が切れたままになるため、アプリを開いた時点で必ず復旧させる。
        lifecycleScope.launch { runCatching { syncScheduler.rescheduleAll() } }
        setContent {
            val browserViewModel: FileBrowserViewModel = hiltViewModel(this@MainActivity)
            val settingsViewModel: SettingsViewModel = hiltViewModel(this@MainActivity)
            val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            var shortcut by pendingShortcut
            var shares by pendingShares

            // 設定が ON の間だけ計測する。画面を閉じるときに必ず外す
            // (残したままだとフレームごとのコールバックが生き続ける)。
            DisposableEffect(settings.logFramePerformance) {
                if (settings.logFramePerformance) jankTracker.attach(this@MainActivity)
                onDispose { jankTracker.detach() }
            }
            FoldexTheme(
                darkTheme = darkTheme,
                dynamicColor = settings.dynamicColor,
                colorTheme = settings.colorTheme,
            ) {
                MainScreen(
                    browserViewModel = browserViewModel,
                    shortcutAction = shortcut,
                    onShortcutConsumed = { shortcut = null },
                    sharedUris = shares,
                    onSharedUrisConsumed = { shares = emptyList() },
                    onRouteChanged = jankTracker::setScreen,
                )
            }
        }
        holdFirstFrameUntilSettingsLoaded()
    }

    /**
     * 設定が DataStore から届くまで**最初の 1 枚を描かせない**。
     *
     * 設定は非同期に届くため、そのまま描くと既定テーマ (ライト/GREEN) で 1 度描いてから
     * 本来のテーマに切り替わる = 起動のたびにテーマがちらつく。描画を数ミリ秒待たせれば、
     * その間はシステムが出している起動画面のままなので、切り替わりが見えない。
     *
     * 万一 DataStore が応答しなくてもアプリが開かなくなっては困るので、上限を設けて
     * 時間切れなら既定値のまま描き始める。
     */
    private fun holdFirstFrameUntilSettingsLoaded() {
        val content = findViewById<View>(android.R.id.content) ?: return
        val deadline = SystemClock.uptimeMillis() + SETTINGS_WAIT_MAX_MS
        content.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val ready = settingsRepository.loaded.value != null ||
                        SystemClock.uptimeMillis() >= deadline
                    if (ready) content.viewTreeObserver.removeOnPreDrawListener(this)
                    return ready
                }
            },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_SHORTCUT)?.let { pendingShortcut.value = it }
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                }
                if (uri != null) pendingShares.value = listOf(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list: List<Uri> = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                }
                if (list.isNotEmpty()) pendingShares.value = list
            }
        }
    }

    companion object {
        /** 設定の読み込みを待つ上限 (ms)。これを過ぎたら既定値のまま描き始める。 */
        private const val SETTINGS_WAIT_MAX_MS = 700L

        const val EXTRA_SHORTCUT = "foldex.shortcut"
    }
}
