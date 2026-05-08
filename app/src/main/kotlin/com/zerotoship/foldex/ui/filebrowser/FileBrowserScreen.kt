package com.zerotoship.foldex.ui.filebrowser

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zerotoship.foldex.core.model.FileNode
import com.zerotoship.foldex.core.model.NodeType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(viewModel: FileBrowserViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    BackHandler(enabled = state.canGoUp) { viewModel.navigateUp() }

    // Re-check permission when returning from Settings
    LaunchedEffect(Unit) {
        if (!state.hasStoragePermission && viewModel.checkStoragePermission()) {
            viewModel.onStoragePermissionGranted()
        }
    }

    val safLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.onSafRootPicked(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.breadcrumbs.lastOrNull()?.displayName ?: "Foldex",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (state.canGoUp) {
                        IconButton(onClick = { viewModel.navigateUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上へ")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.setViewMode(ViewMode.LIST) }) {
                        Icon(Icons.AutoMirrored.Outlined.List, contentDescription = "リスト表示",
                            tint = if (state.viewMode == ViewMode.LIST) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { viewModel.setViewMode(ViewMode.DETAILED) }) {
                        Icon(Icons.Outlined.ViewList, contentDescription = "詳細表示",
                            tint = if (state.viewMode == ViewMode.DETAILED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { viewModel.setViewMode(ViewMode.GRID) }) {
                        Icon(Icons.Outlined.GridView, contentDescription = "グリッド表示",
                            tint = if (state.viewMode == ViewMode.GRID) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "更新")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            // パンくずナビ
            if (state.breadcrumbs.size > 1) {
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
                state.files.isEmpty() -> EmptyContent()
                else -> FileListContent(
                    files = state.files,
                    viewMode = state.viewMode,
                    onFileClick = { node ->
                        if (node.type == NodeType.DIRECTORY) {
                            viewModel.navigateTo(node.uri, node.name)
                        }
                    },
                )
            }
        }
    }
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

@Composable
private fun FileListContent(
    files: List<FileNode>,
    viewMode: ViewMode,
    onFileClick: (FileNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (viewMode) {
        ViewMode.LIST -> LazyColumn(modifier = modifier.fillMaxSize()) {
            items(files, key = { it.uri.toStorageString() }) { node ->
                FileListItem(node = node, onClick = { onFileClick(node) })
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
            }
        }
        ViewMode.DETAILED -> LazyColumn(modifier = modifier.fillMaxSize()) {
            items(files, key = { it.uri.toStorageString() }) { node ->
                FileDetailedItem(node = node, onClick = { onFileClick(node) })
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
            }
        }
        ViewMode.GRID -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 96.dp),
            modifier = modifier.fillMaxSize().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(files, key = { it.uri.toStorageString() }) { node ->
                GridFileItem(node = node, onClick = { onFileClick(node) })
            }
        }
    }
}

@Composable
private fun GridFileItem(node: FileNode, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = if (node.type == NodeType.DIRECTORY) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = if (node.type == NodeType.DIRECTORY) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
