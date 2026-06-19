package com.zerotoship.foldex

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zerotoship.foldex.core.model.ThemeMode
import com.zerotoship.foldex.ui.filebrowser.FileBrowserViewModel
import com.zerotoship.foldex.ui.main.MainScreen
import com.zerotoship.foldex.ui.settings.SettingsViewModel
import com.zerotoship.foldex.ui.theme.FoldexTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** App Shortcuts (manifest 静的) から渡される `foldex.shortcut` extra。MainScreen が消費する。 */
    private val pendingShortcut = mutableStateOf<String?>(null)

    /** ACTION_SEND / ACTION_SEND_MULTIPLE で他アプリから渡されたファイル群。 */
    private val pendingShares = mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
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
            FoldexTheme(darkTheme = darkTheme, dynamicColor = settings.dynamicColor) {
                MainScreen(
                    browserViewModel = browserViewModel,
                    shortcutAction = shortcut,
                    onShortcutConsumed = { shortcut = null },
                    sharedUris = shares,
                    onSharedUrisConsumed = { shares = emptyList() },
                )
            }
        }
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
        const val EXTRA_SHORTCUT = "foldex.shortcut"
    }
}
