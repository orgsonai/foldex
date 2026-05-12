package com.zerotoship.foldex.ui.filebrowser

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zerotoship.foldex.core.model.Connection
import com.zerotoship.foldex.core.model.FileNode
import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.core.model.NodeType
import com.zerotoship.foldex.ui.components.FastScrollbar
import com.zerotoship.foldex.ui.connections.ConnectionsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileBrowserScreen(
    viewModel: FileBrowserViewModel = hiltViewModel(),
    connectionsViewModel: ConnectionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val connections by connectionsViewModel.connections.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()

    // Snackbar events from ViewModel
    LaunchedEffect(Unit) {
        viewModel.snackbarEvents.collect { event ->
            val result = snackbarHostState.showSnackbar(
                message = event.message,
                actionLabel = event.actionLabel,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                event.onAction?.invoke()
            }
        }
    }

    // Re-check permission when returning from Settings
    LaunchedEffect(Unit) {
        if (!state.hasStoragePermission && viewModel.checkStoragePermission()) {
            viewModel.onStoragePermissionGranted()
        }
    }

    // Back: clear selection > close search > navigate up
    BackHandler(enabled = state.isSelectionMode || state.isSearchActive || state.canGoUp) {
        when {
            state.isSelectionMode -> viewModel.clearSelection()
            state.isSearchActive -> viewModel.closeSearch()
            else -> viewModel.navigateUp()
        }
    }

    val safLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.onSafRootPicked(it) }
    }

    // Keyboard shortcuts
    val keyModifier = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when {
            event.isCtrlPressed && event.key == Key.C -> { viewModel.copySelected(); true }
            event.isCtrlPressed && event.key == Key.X -> { viewModel.cutSelected(); true }
            event.isCtrlPressed && event.key == Key.V -> { viewModel.paste(); true }
            event.isCtrlPressed && event.key == Key.A -> { viewModel.selectAll(); true }
            event.isCtrlPressed && event.key == Key.F -> { viewModel.toggleSearch(); true }
            event.key == Key.Delete -> { viewModel.requestDelete(); true }
            event.key == Key.F2 -> {
                val sel = state.selectedNodes
                if (sel.size == 1) viewModel.requestRename(sel[0])
                true
            }
            event.key == Key.Escape -> {
                when {
                    state.isSelectionMode -> viewModel.clearSelection()
                    state.isSearchActive -> viewModel.closeSearch()
                }
                true
            }
            else -> false
        }
    }

    val quickAccess by viewModel.quickAccess.collectAsStateWithLifecycle()
    val favorites = remember(state.favoriteUris) {
        state.favoriteUris.mapNotNull { key -> FileUri.fromStorageStringOrNull(key)?.let { it to key } }
    }
    fun closeDrawerThen(action: () -> Unit) {
        drawerScope.launch { drawerState.close() }
        action()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Foldex",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                    HorizontalDivider()
                    DrawerSectionLabel("クイックアクセス")
                    quickAccess.forEach { entry ->
                        val selected = (state.currentUri as? FileUri.Local)?.absolutePath ==
                            (entry.uri as? FileUri.Local)?.absolutePath
                        NavigationDrawerItem(
                            icon = { Icon(quickAccessIcon(entry.kind), contentDescription = null) },
                            label = { Text(entry.label) },
                            selected = selected,
                            onClick = { closeDrawerThen { viewModel.open(entry.uri, entry.label) } },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                        label = { Text("別のフォルダを開く…") },
                        selected = false,
                        onClick = { closeDrawerThen { safLauncher.launch(null) } },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )

                    if (favorites.isNotEmpty()) {
                        DrawerSectionLabel("お気に入り")
                        favorites.forEach { (uri, key) ->
                            NavigationDrawerItem(
                                icon = { Icon(Icons.Outlined.Star, contentDescription = null) },
                                label = { Text(favoriteLabel(uri), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                selected = state.currentUri?.toStorageString() == key,
                                onClick = { closeDrawerThen { viewModel.open(uri, favoriteLabel(uri)) } },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }

                    if (connections.isNotEmpty()) {
                        DrawerSectionLabel("リモート接続")
                        connections.forEach { connection ->
                            val selected = (state.currentUri as? FileUri.Remote)?.connectionId == connection.id
                            NavigationDrawerItem(
                                icon = { Icon(Icons.Outlined.Storage, contentDescription = null) },
                                label = { Text(connection.name) },
                                selected = selected,
                                onClick = {
                                    closeDrawerThen {
                                        if (connection is Connection.Smb) {
                                            viewModel.openSmbConnection(connection.id, connection.name)
                                        }
                                    }
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
        modifier = keyModifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (state.isSearchActive) {
                TopAppBar(
                    title = {
                        TextField(
                            value = state.searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = { Text("ファイル名を検索…") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    navigationIcon = {
                        Icon(Icons.Default.Search, contentDescription = null,
                            modifier = Modifier.padding(start = 8.dp))
                    },
                    actions = {
                        IconButton(onClick = { viewModel.closeSearch() }) {
                            Icon(Icons.Default.Close, contentDescription = "検索を閉じる")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        if (state.isSelectionMode) {
                            Text("${state.selectedUris.size}件選択中")
                        } else {
                            Text(
                                text = state.breadcrumbs.lastOrNull()?.displayName ?: "Foldex",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        if (state.isSelectionMode) {
                            IconButton(onClick = { viewModel.clearSelection() }) {
                                Icon(Icons.Default.Close, contentDescription = "選択解除")
                            }
                        } else if (state.canGoUp) {
                            IconButton(onClick = { viewModel.navigateUp() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上へ")
                            }
                        } else {
                            IconButton(onClick = {
                                drawerScope.launch { drawerState.open() }
                            }) {
                                Icon(Icons.Default.Menu, contentDescription = "メニュー")
                            }
                        }
                    },
                    actions = {
                        if (state.isSelectionMode) {
                            IconButton(onClick = { viewModel.selectAll() }) {
                                Icon(Icons.Default.SelectAll, contentDescription = "全選択")
                            }
                            IconButton(onClick = { viewModel.copySelected() }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "コピー")
                            }
                            IconButton(onClick = { viewModel.cutSelected() }) {
                                Icon(Icons.Default.ContentCut, contentDescription = "切り取り")
                            }
                            IconButton(
                                onClick = {
                                    val sel = state.selectedNodes
                                    if (sel.size == 1) viewModel.requestRename(sel[0])
                                },
                                enabled = state.selectedUris.size == 1,
                            ) {
                                Icon(Icons.Default.DriveFileRenameOutline, contentDescription = "名前変更")
                            }
                            IconButton(onClick = { viewModel.requestDelete() }) {
                                Icon(Icons.Default.Delete, contentDescription = "削除",
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            val curUri = state.currentUri
                            if (curUri != null) {
                                val isFav = curUri.toStorageString() in state.favoriteUris
                                IconButton(onClick = { viewModel.toggleFavorite(curUri) }) {
                                    Icon(
                                        imageVector = if (isFav) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                        contentDescription = if (isFav) "お気に入りから外す" else "お気に入りに追加",
                                        tint = if (isFav) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            IconButton(onClick = { viewModel.toggleSearch() }) {
                                Icon(Icons.Default.Search, contentDescription = "検索")
                            }
                            IconButton(onClick = { viewModel.setViewMode(ViewMode.LIST) }) {
                                Icon(Icons.AutoMirrored.Outlined.List, contentDescription = "リスト表示",
                                    tint = if (state.viewMode == ViewMode.LIST) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { viewModel.setViewMode(ViewMode.DETAILED) }) {
                                Icon(Icons.Outlined.ViewList, contentDescription = "詳細表示",
                                    tint = if (state.viewMode == ViewMode.DETAILED) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { viewModel.setViewMode(ViewMode.GRID) }) {
                                Icon(Icons.Outlined.GridView, contentDescription = "グリッド表示",
                                    tint = if (state.viewMode == ViewMode.GRID) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { viewModel.refresh() }) {
                                Icon(Icons.Outlined.Refresh, contentDescription = "更新")
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (state.canPaste && !state.isSelectionMode) {
                BottomAppBar {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val opLabel = when (state.clipboard) {
                            is ClipboardOperation.Copy -> "コピー: ${state.clipboard!!.nodes.size}件"
                            is ClipboardOperation.Cut -> "切り取り: ${state.clipboard!!.nodes.size}件"
                            null -> ""
                        }
                        Text(opLabel, style = MaterialTheme.typography.bodyMedium)
                        Row {
                            TextButton(onClick = { viewModel.clearClipboard() }) { Text("クリア") }
                            Button(onClick = { viewModel.paste() }) {
                                Icon(Icons.Default.ContentPaste, contentDescription = null,
                                    modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(4.dp))
                                Text("貼り付け")
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!state.isSelectionMode && !state.canPaste && state.currentUri != null) {
                FloatingActionButton(onClick = { viewModel.showCreateFolderDialog() }) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "フォルダを作成")
                }
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            // パンくずナビ
            if (!state.isSearchActive && state.breadcrumbs.size > 1) {
                BreadcrumbRow(
                    breadcrumbs = state.breadcrumbs,
                    onCrumbClick = { index -> viewModel.navigateToIndex(index) },
                )
                HorizontalDivider()
            }

            when {
                !state.hasStoragePermission && !state.hasSafRoot -> NoAccessContent(
                    onRequestPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.fromParts("package", context.packageName, null))
                            )
                        }
                    },
                    onPickFolder = { safLauncher.launch(null) },
                )
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.error != null -> ErrorContent(message = state.error!!, onRetry = { viewModel.refresh() })
                state.filteredFiles.isEmpty() && state.searchQuery.isNotEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("「${state.searchQuery}」に一致するファイルはありません",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                state.filteredFiles.isEmpty() -> EmptyContent()
                else -> FileListContent(
                    files = state.filteredFiles,
                    viewMode = state.viewMode,
                    selectedUris = state.selectedUris,
                    onFileClick = { node ->
                        if (state.isSelectionMode) {
                            viewModel.toggleSelection(node)
                        } else if (node.type == NodeType.DIRECTORY) {
                            viewModel.navigateTo(node.uri, node.name)
                        }
                    },
                    onFileLongClick = { node -> viewModel.toggleSelection(node) },
                )
            }
        }
    }

    // Dialogs
    if (state.pendingDeleteNodes.isNotEmpty()) {
        DeleteConfirmDialog(
            nodes = state.pendingDeleteNodes,
            onConfirm = { viewModel.confirmDelete() },
            onDismiss = { viewModel.dismissDeleteDialog() },
        )
    }
    state.renameTarget?.let { node ->
        RenameDialog(
            node = node,
            onConfirm = { viewModel.confirmRename(it) },
            onDismiss = { viewModel.dismissRenameDialog() },
        )
    }
    if (state.showCreateFolderDialog) {
        CreateFolderDialog(
            onConfirm = { viewModel.createFolder(it) },
            onDismiss = { viewModel.dismissCreateFolderDialog() },
        )
    }
    } // end ModalNavigationDrawer content
}

@Composable
private fun BreadcrumbRow(
    breadcrumbs: List<BreadcrumbItem>,
    onCrumbClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        breadcrumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { onCrumbClick(index) }) {
                Text(
                    text = crumb.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (index == breadcrumbs.lastIndex) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileListContent(
    files: List<FileNode>,
    viewMode: ViewMode,
    selectedUris: Set<String>,
    onFileClick: (FileNode) -> Unit,
    onFileLongClick: (FileNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 選択が空のときは行ごとの toStorageString() アロケーションを避ける (スクロール最適化)
    val hasSelection = selectedUris.isNotEmpty()
    // 先頭文字をファストスクローラのラベルに使う (五十音/アルファベット順スクラブで便利)
    val labelProvider: (Int) -> String = remember(files) {
        { idx -> files.getOrNull(idx)?.name?.firstOrNull()?.uppercase() ?: "" }
    }
    when (viewMode) {
        ViewMode.LIST -> {
            val listState = rememberLazyListState()
            Box(modifier = modifier.fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(
                        items = files,
                        key = { it.uri.toStorageString() },
                        contentType = { "file-list" },
                    ) { node ->
                        val selected = hasSelection && node.uri.toStorageString() in selectedUris
                        FileListItem(
                            node = node,
                            selected = selected,
                            onClick = { onFileClick(node) },
                            onLongClick = { onFileLongClick(node) },
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    }
                }
                FastScrollbar(listState, files.size, Modifier.align(Alignment.CenterEnd), labelProvider)
            }
        }
        ViewMode.DETAILED -> {
            val listState = rememberLazyListState()
            Box(modifier = modifier.fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(
                        items = files,
                        key = { it.uri.toStorageString() },
                        contentType = { "file-detailed" },
                    ) { node ->
                        val selected = hasSelection && node.uri.toStorageString() in selectedUris
                        FileDetailedItem(
                            node = node,
                            selected = selected,
                            onClick = { onFileClick(node) },
                            onLongClick = { onFileLongClick(node) },
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    }
                }
                FastScrollbar(listState, files.size, Modifier.align(Alignment.CenterEnd), labelProvider)
            }
        }
        ViewMode.GRID -> {
            val gridState = rememberLazyGridState()
            Box(modifier = modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 96.dp),
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(
                        items = files,
                        key = { it.uri.toStorageString() },
                        contentType = { "file-grid" },
                    ) { node ->
                        val selected = hasSelection && node.uri.toStorageString() in selectedUris
                        GridFileItem(
                            node = node,
                            selected = selected,
                            onClick = { onFileClick(node) },
                            onLongClick = { onFileLongClick(node) },
                        )
                    }
                }
                FastScrollbar(gridState, files.size, Modifier.align(Alignment.CenterEnd), labelProvider)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridFileItem(
    node: FileNode,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            FileLeadingIcon(node = node, selected = false, size = 48.dp)
            if (selected) {
                Icon(
                    Icons.Default.CheckBox,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).align(Alignment.TopEnd),
                    tint = colors.primary,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = node.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NoAccessContent(onRequestPermission: () -> Unit, onPickFolder: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("ストレージへのアクセスが必要です", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Button(onClick = onRequestPermission) {
                Text("すべてのファイルへのアクセスを許可")
            }
            Spacer(Modifier.height(8.dp))
            Text("または", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
        Button(onClick = onPickFolder) {
            Text("フォルダを選択して開く")
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("再試行") }
    }
}

@Composable
private fun EmptyContent() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("このフォルダは空です", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DrawerSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 28.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

private fun quickAccessIcon(kind: QuickAccessKind): ImageVector = when (kind) {
    QuickAccessKind.INTERNAL_STORAGE -> Icons.Outlined.PhoneAndroid
    QuickAccessKind.DOWNLOAD -> Icons.Outlined.Download
    QuickAccessKind.IMAGES -> Icons.Outlined.Image
    QuickAccessKind.CAMERA -> Icons.Outlined.PhotoCamera
    QuickAccessKind.VIDEO -> Icons.Outlined.Movie
    QuickAccessKind.MUSIC -> Icons.Outlined.MusicNote
    QuickAccessKind.DOCUMENTS -> Icons.Outlined.Description
    QuickAccessKind.SD_CARD -> Icons.Outlined.SdStorage
}

private fun favoriteLabel(uri: FileUri): String {
    val s = uri.toStorageString().trimEnd('/')
    return s.substringAfterLast('/').ifEmpty { s }
}
