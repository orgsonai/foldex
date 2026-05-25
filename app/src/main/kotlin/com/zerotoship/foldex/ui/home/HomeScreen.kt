package com.zerotoship.foldex.ui.home

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zerotoship.foldex.core.model.Connection
import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.core.model.home.HomeFunction
import com.zerotoship.foldex.core.model.home.HomeShortcut
import com.zerotoship.foldex.ui.components.AppDrawerContent
import com.zerotoship.foldex.ui.filebrowser.FileBrowserViewModel
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

/**
 * HOME 画面 (アプリ起動時の最初の画面)。
 *
 * - 上部 TopAppBar 左に **ハンバーガー**: ファイル画面と同じドロワー (クイックアクセス /
 *   お気に入り / リモート接続) を共有する [AppDrawerContent]。
 * - 中央のグリッド: 組み込み機能タイル + ユーザー追加 (ローカルフォルダ / リモート接続) のタイル。
 * - FAB: タイルを追加するダイアログを開く (種別 = ローカルフォルダ / リモート接続)。
 * - タイル長押し: 隠す / 上へ / 下へ / 削除 (削除は組み込み以外) のメニュー。
 * - 右上 ⋮: 非表示タイルの復元ダイアログ。
 *
 * 画面遷移は呼び出し元 ([com.zerotoship.foldex.ui.main.MainScreen]) のコールバックで行う。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenLocalFolder: (String) -> Unit,
    onOpenFunction: (HomeFunction) -> Unit,
    onOpenConnection: (Connection) -> Unit,
    onOpenUri: (FileUri, String) -> Unit,
    onPickFolder: () -> Unit,
    browserViewModel: FileBrowserViewModel,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val shortcuts by viewModel.shortcuts.collectAsState()
    val hidden by viewModel.hiddenShortcuts.collectAsState()
    val connections by viewModel.allConnections.collectAsState()
    val quickAccess by browserViewModel.quickAccess.collectAsStateWithLifecycle()
    val browserState by browserViewModel.state.collectAsStateWithLifecycle()
    val favorites = remember(browserState.favoriteUris) {
        browserState.favoriteUris.mapNotNull { key ->
            FileUri.fromStorageStringOrNull(key)?.let { it to key }
        }
    }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<HomeShortcut?>(null) }
    var pendingRename by remember { mutableStateOf<HomeShortcut?>(null) }
    var showHiddenDialog by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    fun closeDrawerThen(action: () -> Unit) {
        scope.launch { drawerState.close() }
        action()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawerContent(
                quickAccess = quickAccess,
                favorites = favorites,
                connections = connections,
                selectedKey = null,
                onOpenUri = { uri, name -> closeDrawerThen { onOpenUri(uri, name) } },
                onPickFolder = { closeDrawerThen { onPickFolder() } },
                onOpenConnection = { conn -> closeDrawerThen { onOpenConnection(conn) } },
            )
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
                    actions = {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "メニュー")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("非表示タイルを表示") },
                                leadingIcon = { Icon(Icons.Default.Restore, null) },
                                enabled = hidden.isNotEmpty(),
                                onClick = { showHiddenDialog = true; menuOpen = false },
                            )
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
            // ドラッグ中はローカル変数 liveOrder に楽観更新。ドロップ時に ViewModel に確定保存する。
            // ViewModel の StateFlow を毎フレーム更新するとドラッグ位置と競合してチラつくため。
            var liveOrder by remember(shortcuts) { mutableStateOf(shortcuts) }
            val gridState = rememberLazyGridState()
            val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
                liveOrder = liveOrder.toMutableList().apply {
                    add(to.index, removeAt(from.index))
                }
            }
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 120.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().padding(inner),
            ) {
                items(liveOrder, key = { it.id }) { sc ->
                    val canRemove = !sc.id.startsWith("builtin_")
                    ReorderableItem(reorderState, key = sc.id) { isDragging ->
                        val dragHandle = Modifier.longPressDraggableHandle(
                            onDragStopped = {
                                val newIds = liveOrder.map { it.id }
                                val originalIds = shortcuts.map { it.id }
                                if (newIds != originalIds) viewModel.applyOrder(newIds)
                            },
                        )
                        ShortcutTile(
                            sc = sc,
                            isDragging = isDragging,
                            dragHandleModifier = dragHandle,
                            onClick = {
                                when (sc) {
                                    is HomeShortcut.LocalFolder -> onOpenLocalFolder(sc.path)
                                    is HomeShortcut.SafFolder -> onOpenUri(FileUri.Saf(sc.documentUri), sc.label)
                                    is HomeShortcut.RemoteConnection -> {
                                        connections.firstOrNull { it.id == sc.connectionId }
                                            ?.let(onOpenConnection)
                                    }
                                    is HomeShortcut.Function -> onOpenFunction(sc.kind)
                                }
                            },
                            canRemove = canRemove,
                            onHide = { viewModel.setHidden(sc.id, true) },
                            onRemove = { pendingRemove = sc },
                            onRename = { pendingRename = sc },
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        // SAF tree picker。完了すると documentUri が返るので、表示名は末尾セグメントから自動生成。
        val context = androidx.compose.ui.platform.LocalContext.current
        val safPicker = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                val tail = java.net.URLDecoder.decode(
                    uri.toString().substringAfterLast("/tree/"),
                    "UTF-8",
                ).substringAfterLast('/').ifEmpty { uri.toString() }
                viewModel.addSafFolder(tail, uri.toString())
                showAdd = false
            }
        }
        AddShortcutDialog(
            presets = remember { viewModel.candidatePresets() },
            persistedSafTrees = remember { viewModel.persistedSafTrees() },
            connections = connections,
            onDismiss = { showAdd = false },
            onAddLocal = { label, path ->
                viewModel.addLocalFolder(label, path)
                showAdd = false
            },
            onAddSaf = { label, uri ->
                viewModel.addSafFolder(label, uri)
                showAdd = false
            },
            onPickSaf = { safPicker.launch(null) },
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
    if (showHiddenDialog) {
        HiddenShortcutsDialog(
            hidden = hidden,
            onDismiss = { showHiddenDialog = false },
            onRestore = { id -> viewModel.setHidden(id, false) },
        )
    }
    pendingRename?.let { target ->
        RenameShortcutDialog(
            current = target.label,
            onDismiss = { pendingRename = null },
            onConfirm = { newName ->
                viewModel.rename(target.id, newName)
                pendingRename = null
            },
        )
    }
}

@Composable
private fun RenameShortcutDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("表示名を変更") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("表示名") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text) },
                enabled = text.isNotBlank() && text != current,
            ) { Text("変更") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ShortcutTile(
    sc: HomeShortcut,
    onClick: () -> Unit,
    isDragging: Boolean,
    /** ReorderableItem スコープで作成された Modifier.longPressDraggableHandle(...)。 */
    dragHandleModifier: Modifier,
    canRemove: Boolean,
    onHide: () -> Unit,
    onRemove: () -> Unit,
    onRename: () -> Unit,
) {
    val style = homeTileStyle(sc)
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Card(
            colors = CardDefaults.cardColors(
                // ドラッグ中は前景タイルを少し強調。通常時は一段持ち上げた surface で
                // 色付きアイコンチップが映えるようにする。
                containerColor = if (isDragging) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = if (isDragging) 8.dp else 1.dp,
            ),
            // 長押し = ドラッグ開始 (reorderable の dragHandleModifier)。
            // タップ = タイルの本来動作 (フォルダ/接続を開く等)。
            // メニュー (改名・隠す・削除) は右上の ⋮ ボタンへ。
            modifier = Modifier
                .aspectRatio(1f)
                .then(dragHandleModifier)
                .combinedClickable(onClick = onClick, onLongClick = null),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                // 機能ごとに配色を変えた角丸アイコンチップ。色はテーマ (Material You / ライト/ダーク)
                // から取るので動的カラーでも破綻しない。
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(style.container),
                ) {
                    Icon(
                        style.icon,
                        contentDescription = null,
                        tint = style.content,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    sc.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (style.subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        style.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // 右上の ⋮ ボタン (改名/隠す/削除) — 長押しがドラッグになったのでメニューはここから。
        IconButton(
            onClick = { menuOpen = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(28.dp),
        ) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "メニュー",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("名前を変更") },
                leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick = { onRename(); menuOpen = false },
            )
            DropdownMenuItem(
                text = { Text("隠す") },
                leadingIcon = { Icon(Icons.Default.VisibilityOff, null) },
                onClick = { onHide(); menuOpen = false },
            )
            if (canRemove) {
                DropdownMenuItem(
                    text = { Text("削除") },
                    leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                    onClick = { onRemove(); menuOpen = false },
                )
            }
        }
    }
}

/** タイル 1 枚分の見た目 (アイコン + 配色チップ色 + サブタイトル)。 */
private data class HomeTileStyle(
    val icon: ImageVector,
    val container: Color,
    val content: Color,
    val subtitle: String?,
)

/**
 * ショートカット種別ごとのアイコン・配色を決める。色はすべて [MaterialTheme.colorScheme] 由来なので
 * Material You / ライト・ダークいずれでも自然に追従する。系統で色を分けて一目で見分けられるようにする:
 * フォルダ=primary / メディア・SAF追加=secondary / ネットワーク=tertiary / ゴミ箱=error / システム=neutral。
 */
@Composable
private fun homeTileStyle(sc: HomeShortcut): HomeTileStyle {
    val cs = MaterialTheme.colorScheme
    return when (sc) {
        is HomeShortcut.LocalFolder ->
            HomeTileStyle(Icons.Rounded.Folder, cs.primaryContainer, cs.onPrimaryContainer, sc.path)
        is HomeShortcut.SafFolder ->
            HomeTileStyle(Icons.Rounded.FolderSpecial, cs.primaryContainer, cs.onPrimaryContainer, "SAF")
        is HomeShortcut.RemoteConnection ->
            HomeTileStyle(Icons.Rounded.Cloud, cs.tertiaryContainer, cs.onTertiaryContainer, "リモート")
        is HomeShortcut.Function -> when (sc.kind) {
            HomeFunction.INTERNAL_STORAGE ->
                HomeTileStyle(Icons.Rounded.PhoneAndroid, cs.primaryContainer, cs.onPrimaryContainer, null)
            HomeFunction.TRASH ->
                HomeTileStyle(Icons.Rounded.Delete, cs.errorContainer, cs.onErrorContainer, null)
            HomeFunction.SERVERS ->
                HomeTileStyle(Icons.Rounded.Dns, cs.tertiaryContainer, cs.onTertiaryContainer, null)
            HomeFunction.SYNC ->
                HomeTileStyle(Icons.Rounded.Sync, cs.tertiaryContainer, cs.onTertiaryContainer, null)
            HomeFunction.SETTINGS ->
                HomeTileStyle(Icons.Rounded.Settings, cs.surfaceContainerHighest, cs.onSurfaceVariant, null)
            HomeFunction.PERMISSIONS ->
                HomeTileStyle(Icons.Rounded.Shield, cs.surfaceContainerHighest, cs.onSurfaceVariant, null)
            HomeFunction.SAF_PICK ->
                HomeTileStyle(Icons.Rounded.CreateNewFolder, cs.secondaryContainer, cs.onSecondaryContainer, null)
            HomeFunction.ALL_IMAGES ->
                HomeTileStyle(Icons.Rounded.PhotoLibrary, cs.secondaryContainer, cs.onSecondaryContainer, null)
            HomeFunction.ALL_VIDEOS ->
                HomeTileStyle(Icons.Rounded.VideoLibrary, cs.secondaryContainer, cs.onSecondaryContainer, null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddShortcutDialog(
    presets: List<HomeViewModel.PresetPath>,
    persistedSafTrees: List<HomeViewModel.SafTree>,
    connections: List<Connection>,
    onDismiss: () -> Unit,
    onAddLocal: (label: String, path: String) -> Unit,
    onAddSaf: (label: String, documentUri: String) -> Unit,
    onPickSaf: () -> Unit,
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
                // 種別の切替: ローカル / SAF / リモート。AlertDialog の本文幅では
                // 3 つを横並びにすると 3 つ目 (リモート) が見切れるため、FlowRow で折り返す。
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FilterChip(
                        selected = mode == AddMode.LOCAL,
                        onClick = { mode = AddMode.LOCAL },
                        label = { Text("ローカル") },
                    )
                    FilterChip(
                        selected = mode == AddMode.SAF,
                        onClick = { mode = AddMode.SAF },
                        label = { Text("SAF") },
                    )
                    FilterChip(
                        selected = mode == AddMode.REMOTE,
                        onClick = { mode = AddMode.REMOTE },
                        label = { Text("リモート") },
                    )
                }
                Spacer(Modifier.height(12.dp))
                when (mode) {
                    AddMode.SAF -> {
                        Text(
                            "Termux / SD カードなどはアプリの UID から直接読めないため、" +
                                "SAF で許可した tree URI を経由します。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onPickSaf, modifier = Modifier.fillMaxWidth()) {
                            Text("SAF からフォルダを選ぶ")
                        }
                        if (persistedSafTrees.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("許可済みの SAF tree:", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(4.dp))
                            persistedSafTrees.forEach { tree ->
                                TextButton(
                                    onClick = { onAddSaf(tree.label, tree.documentUri) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
                                        Text(tree.label, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            tree.documentUri,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
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

private enum class AddMode { LOCAL, SAF, REMOTE }

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

@Composable
private fun HiddenShortcutsDialog(
    hidden: List<HomeShortcut>,
    onDismiss: () -> Unit,
    onRestore: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("非表示タイル") },
        text = {
            if (hidden.isEmpty()) {
                Text("非表示にされたタイルはありません")
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    hidden.forEach { sc ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Text(
                                sc.label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onRestore(sc.id) }) {
                                Icon(Icons.Default.Visibility, null, modifier = Modifier.height(18.dp))
                                Spacer(Modifier.height(4.dp))
                                Text("表示する")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
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
