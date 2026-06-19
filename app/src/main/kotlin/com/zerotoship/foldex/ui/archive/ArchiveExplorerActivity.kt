// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.archive

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zerotoship.foldex.core.data.repo.SettingsRepository
import com.zerotoship.foldex.core.data.repo.UserSettings
import com.zerotoship.foldex.core.model.ThemeMode
import com.zerotoship.foldex.core.model.filetype.Category
import com.zerotoship.foldex.core.model.filetype.FileTypeRegistry
import com.zerotoship.foldex.ui.filebrowser.iconFor
import com.zerotoship.foldex.ui.theme.FoldexTheme
import com.zerotoship.foldex.ui.viewer.ViewerActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * ZIP を「展開せずに」中身を閲覧する画面 (FOLDEX-HANDOFF.md §10 中身プレビュー)。
 *
 * - 起動時点で zip 本体はローカルの実体になっている前提 (Remote/SAF は呼び出し側でキャッシュ済み)。
 * - 通常のフォルダのように潜れる (パンくず + フォルダタップ)。
 * - ファイルタップ → そのエントリ「1つだけ」をキャッシュへ展開して内蔵ビューア/外部アプリで開く。
 * - 暗号化 zip はファイルを開くタイミングでパスワードを尋ねる (一覧表示自体はパスワード不要)。
 */
@AndroidEntryPoint
class ArchiveExplorerActivity : ComponentActivity() {

    @Inject lateinit var settingsRepo: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_ZIP_PATH).orEmpty()
        if (path.isBlank()) { finish(); return }
        val zip = File(path)
        val name = intent.getStringExtra(EXTRA_NAME) ?: zip.name

        enableEdgeToEdge()
        setContent {
            val settings by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = UserSettings())
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            FoldexTheme(darkTheme = darkTheme, dynamicColor = settings.dynamicColor) {
                ArchiveExplorerScreen(
                    zip = zip,
                    title = name,
                    onBack = { finish() },
                    onOpenExtracted = { file, displayName -> openExtracted(file, displayName) },
                )
            }
        }
    }

    /** 展開済みの実ファイルを内蔵ビューア (対応カテゴリ) か外部アプリで開く。 */
    private fun openExtracted(file: File, displayName: String) {
        val category = FileTypeRegistry.categorize(displayName)
        val intent = if (category.hasBuiltInViewer && category != Category.APK) {
            ViewerActivity.intent(
                context = this,
                localPath = file.absolutePath,
                name = displayName,
                category = category,
                // zip 内ファイルは展開コピーなので編集は無効 (zip へは書き戻さない)。
                editable = false,
            )
        } else {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val mime = FileTypeRegistry.mimeTypeFor(displayName) ?: "*/*"
            Intent.createChooser(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                displayName,
            )
        }
        runCatching { startActivity(intent) }
    }

    companion object {
        private const val EXTRA_ZIP_PATH = "foldex.archive.zip_path"
        private const val EXTRA_NAME = "foldex.archive.name"

        fun intent(context: Context, localZipPath: String, name: String): Intent =
            Intent(context, ArchiveExplorerActivity::class.java)
                .putExtra(EXTRA_ZIP_PATH, localZipPath)
                .putExtra(EXTRA_NAME, name)
    }
}

private sealed interface ArchiveLoad {
    data object Loading : ArchiveLoad
    data class Loaded(val entries: List<ArchiveExplorer.Entry>) : ArchiveLoad
    data object Failed : ArchiveLoad
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArchiveExplorerScreen(
    zip: File,
    title: String,
    onBack: () -> Unit,
    onOpenExtracted: (File, String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val load by produceState<ArchiveLoad>(ArchiveLoad.Loading, zip) {
        value = withContext(Dispatchers.IO) {
            ArchiveExplorer.listEntries(zip)?.let { ArchiveLoad.Loaded(it) } ?: ArchiveLoad.Failed
        }
    }

    // ナビゲーション状態 (ルート = "")。
    var currentPath by remember { mutableStateOf("") }
    // 暗号化 zip 用に一度入力したパスワードを保持。
    var password by remember { mutableStateOf<String?>(null) }
    var passwordPromptFor by remember { mutableStateOf<ArchiveExplorer.Entry?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // zip 内ファイルを 1 つだけ展開して開く。暗号化で失敗したらパスワードを尋ねる。
    fun openEntry(entry: ArchiveExplorer.Entry) {
        if (busy) return
        scope.launch {
            busy = true
            val cacheDir = File(context.cacheDir, "archive")
            val result = withContext(Dispatchers.IO) {
                runCatching { ArchiveExplorer.extractEntry(zip, entry, cacheDir, password) }
            }
            busy = false
            result.fold(
                onSuccess = { file -> onOpenExtracted(file, entry.name) },
                onFailure = { t ->
                    if (t is ArchiveExplorer.WrongPassword) {
                        passwordError = if (password == null) null else "パスワードが違います"
                        passwordPromptFor = entry
                    } else {
                        snackbar.showSnackbar("展開に失敗しました")
                    }
                },
            )
        }
    }

    androidx.activity.compose.BackHandler(enabled = currentPath.isNotEmpty()) {
        currentPath = currentPath.substringBeforeLast('/', missingDelimiterValue = "")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentPath.isEmpty()) onBack()
                        else currentPath = currentPath.substringBeforeLast('/', missingDelimiterValue = "")
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = load) {
                ArchiveLoad.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                ArchiveLoad.Failed -> Box(
                    Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "この ZIP を読み込めませんでした。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is ArchiveLoad.Loaded -> {
                    val children = remember(s.entries, currentPath) {
                        ArchiveExplorer.childrenOf(s.entries, currentPath)
                    }
                    Column(Modifier.fillMaxSize()) {
                        Breadcrumb(currentPath = currentPath, onNavigate = { currentPath = it })
                        HorizontalDivider()
                        if (children.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    "(空のフォルダ)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(children, key = { node ->
                                    when (node) {
                                        is ArchiveExplorer.Node.Folder -> "d:${node.path}"
                                        is ArchiveExplorer.Node.FileItem -> "f:${node.entry.path}"
                                    }
                                }) { node ->
                                    when (node) {
                                        is ArchiveExplorer.Node.Folder -> ArchiveRow(
                                            icon = Icons.Rounded.Folder,
                                            iconTint = MaterialTheme.colorScheme.primary,
                                            name = node.name,
                                            sub = "フォルダ",
                                            onClick = { currentPath = node.path },
                                        )
                                        is ArchiveExplorer.Node.FileItem -> ArchiveRow(
                                            icon = iconFor(FileTypeRegistry.categorize(node.name)),
                                            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            name = node.name,
                                            sub = formatBytes(node.entry.size) +
                                                if (node.entry.encrypted) "  ·  🔒" else "",
                                            onClick = { openEntry(node.entry) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    passwordPromptFor?.let { entry ->
        ArchivePasswordDialog(
            error = passwordError,
            onDismiss = { passwordPromptFor = null },
            onConfirm = { pw ->
                password = pw
                passwordPromptFor = null
                openEntry(entry)
            },
        )
    }
}

/** パスのパンくず。ルート (zip 名相当) + 各セグメント。タップでそこへジャンプ。 */
@Composable
private fun Breadcrumb(currentPath: String, onNavigate: (String) -> Unit) {
    val segments = if (currentPath.isEmpty()) emptyList() else currentPath.split('/')
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            "ルート",
            style = MaterialTheme.typography.labelLarge,
            color = if (segments.isEmpty()) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onNavigate("") },
        )
        var acc = ""
        segments.forEachIndexed { i, seg ->
            acc = if (acc.isEmpty()) seg else "$acc/$seg"
            val target = acc
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                seg,
                style = MaterialTheme.typography.labelLarge,
                color = if (i == segments.lastIndex) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier.clickable { onNavigate(target) },
            )
        }
    }
}

@Composable
private fun ArchiveRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    name: String,
    sub: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(28.dp))
        Column(Modifier.padding(start = 16.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                sub,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ArchivePasswordDialog(
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("パスワード") },
        text = {
            Column {
                Text(
                    "パスワード保護された ZIP です。展開するにはパスワードを入力してください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (error != null) {
                    androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("パスワード") },
                    singleLine = true,
                    visualTransformation = if (show) androidx.compose.ui.text.input.VisualTransformation.None
                    else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = {
                        androidx.compose.material3.TextButton(onClick = { show = !show }) {
                            Text(if (show) "隠す" else "表示")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onConfirm(password) },
                enabled = password.isNotEmpty(),
            ) { Text("開く") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

private fun formatBytes(b: Long): String = when {
    b < 0 -> "—"
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / (1024.0 * 1024))
    else -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
}
