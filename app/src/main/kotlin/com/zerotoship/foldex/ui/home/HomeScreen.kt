package com.zerotoship.foldex.ui.home

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zerotoship.foldex.core.model.Connection
import com.zerotoship.foldex.core.model.home.HomeFunction
import com.zerotoship.foldex.core.model.home.HomeShortcut
import kotlinx.coroutines.launch

/**
 * HOME 画面 (アプリ起動時の最初の画面)。
 *
 * - 上部 TopAppBar 左に **ハンバーガー**: ModalNavigationDrawer を開き、登録済み接続の一覧を出す。
 * - 中央のグリッド: 組み込み機能タイル + ユーザー追加 (ローカルフォルダ / リモート接続) のタイル。
 * - FAB: タイルを追加するダイアログを開く (種別 = ローカルフォルダ / リモート接続)。
 * - タイル長押し: ユーザー追加分のみ削除可。
 *
 * 画面遷移は呼び出し元 ([com.zerotoship.foldex.ui.main.MainScreen]) のコールバックで行う。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenLocalFolder: (String) -> Unit,
    onOpenFunction: (HomeFunction) -> Unit,
    onOpenConnection: (Connection) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val shortcuts by viewModel.shortcuts.collectAsState()
    val connections by viewModel.allConnections.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<HomeShortcut?>(null) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Foldex",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                    HorizontalDivider()
                    Text(
                        "リモート接続",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (connections.isEmpty()) {
                        Text(
                            "登録された接続はありません",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        )
                    } else {
                        connections.forEach { conn ->
                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.Storage, contentDescription = null) },
                                label = { Text("${conn.name} (${conn.protocol.name})") },
                                selected = false,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    onOpenConnection(conn)
                                },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("HOME", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "メニュー")
                        }
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { showAdd = true }) {
                    Icon(Icons.Default.Add, contentDescription = "ショートカットを追加")
                }
            },
        ) { inner ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 112.dp),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().padding(inner),
            ) {
                items(shortcuts, key = { it.id }) { sc ->
                    ShortcutTile(
                        sc = sc,
                        onClick = {
                            when (sc) {
                                is HomeShortcut.LocalFolder -> onOpenLocalFolder(sc.path)
                                is HomeShortcut.RemoteConnection -> {
                                    connections.firstOrNull { it.id == sc.connectionId }
                                        ?.let(onOpenConnection)
                                }
                                is HomeShortcut.Function -> onOpenFunction(sc.kind)
                            }
                        },
                        onLongClick = {
                            // 組み込みタイル (builtin_ で始まる id) は削除不可。
                            if (!sc.id.startsWith("builtin_")) pendingRemove = sc
                        },
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddShortcutDialog(
            presets = remember { viewModel.candidatePresets() },
            connections = connections,
            onDismiss = { showAdd = false },
            onAddLocal = { label, path ->
                viewModel.addLocalFolder(label, path)
                showAdd = false
            },
            onAddRemote = { connectionId ->
                viewModel.addRemoteConnection(connectionId)
                showAdd = false
            },
        )
    }
    pendingRemove?.let { target ->
        RemoveConfirmDialog(
            label = target.label,
            onDismiss = { pendingRemove = null },
            onConfirm = {
                viewModel.remove(target.id)
                pendingRemove = null
            },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ShortcutTile(sc: HomeShortcut, onClick: () -> Unit, onLongClick: () -> Unit) {
    val icon: ImageVector
    val tint = MaterialTheme.colorScheme.primary
    val subtitle: String?
    when (sc) {
        is HomeShortcut.LocalFolder -> {
            icon = Icons.Outlined.FolderOpen
            subtitle = sc.path
        }
        is HomeShortcut.RemoteConnection -> {
            icon = Icons.Default.Cloud
            subtitle = "リモート"
        }
        is HomeShortcut.Function -> {
            icon = when (sc.kind) {
                HomeFunction.INTERNAL_STORAGE -> Icons.Default.Folder
                HomeFunction.TRASH -> Icons.Default.Delete
                HomeFunction.SERVERS -> Icons.Default.Storage
                HomeFunction.SYNC -> Icons.Default.Cloud
                HomeFunction.SETTINGS -> Icons.Default.Lock
                HomeFunction.PERMISSIONS -> Icons.Default.Lock
                HomeFunction.SAF_PICK -> Icons.Default.Cloud
                HomeFunction.ALL_IMAGES -> Icons.Default.Image
                HomeFunction.ALL_VIDEOS -> Icons.Default.Movie
            }
            subtitle = null
        }
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier
            .aspectRatio(1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.height(40.dp))
            Spacer(Modifier.height(6.dp))
            Text(
                sc.label,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddShortcutDialog(
    presets: List<HomeViewModel.PresetPath>,
    connections: List<Connection>,
    onDismiss: () -> Unit,
    onAddLocal: (label: String, path: String) -> Unit,
    onAddRemote: (connectionId: String) -> Unit,
) {
    var mode by remember { mutableStateOf(AddMode.LOCAL) }
    var label by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("HOME にタイルを追加") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // 種別の切替: ローカル / リモート
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == AddMode.LOCAL,
                        onClick = { mode = AddMode.LOCAL },
                        label = { Text("ローカルフォルダ") },
                    )
                    FilterChip(
                        selected = mode == AddMode.REMOTE,
                        onClick = { mode = AddMode.REMOTE },
                        label = { Text("リモート接続") },
                    )
                }
                Spacer(Modifier.height(12.dp))
                when (mode) {
                    AddMode.LOCAL -> {
                        OutlinedTextField(
                            value = label, onValueChange = { label = it },
                            label = { Text("表示名 (空ならパス)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = path, onValueChange = { path = it },
                            label = { Text("絶対パス") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (presets.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text("候補:", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(4.dp))
                            presets.forEach { preset ->
                                TextButton(
                                    onClick = {
                                        path = preset.path
                                        if (label.isEmpty()) label = preset.label
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
                                        Text(
                                            "${preset.label}${if (!preset.accessible) " ⚠" else ""}",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            preset.path,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (preset.note != null) {
                                            Text(
                                                preset.note,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    AddMode.REMOTE -> {
                        if (connections.isEmpty()) {
                            Text(
                                "登録された接続がありません。先に「接続」タブから追加してください。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            connections.forEach { conn ->
                                TextButton(
                                    onClick = { onAddRemote(conn.id) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
                                        Text("${conn.name} (${conn.protocol.name})", style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "${conn.username ?: "(no user)"}@${conn.host}:${conn.port}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (mode == AddMode.LOCAL && path.isNotBlank()) onAddLocal(label, path)
                },
                enabled = mode == AddMode.LOCAL && path.isNotBlank(),
            ) { Text(if (mode == AddMode.LOCAL) "追加" else "") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}

private enum class AddMode { LOCAL, REMOTE }

@Composable
private fun RemoveConfirmDialog(label: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ショートカットを削除") },
        text = { Text("「$label」を HOME から削除しますか?") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("削除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/** SAF (StorageAccessFramework) ピッカーを起動して結果を ViewModel に通知する用のホスト Composable。 */
@Composable
fun rememberSafTreeLauncher(onPicked: (Uri) -> Unit) = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocumentTree(),
    onResult = { uri -> uri?.let(onPicked) },
)

/** 権限管理画面 (MANAGE_EXTERNAL_STORAGE) を開くインテント。 */
fun openPermissionsSettings(context: android.content.Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }
    runCatching { context.startActivity(intent) }
}
