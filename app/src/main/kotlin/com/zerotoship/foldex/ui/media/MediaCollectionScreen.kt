// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.media

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.zerotoship.foldex.core.model.filetype.Category
import com.zerotoship.foldex.ui.viewer.ViewerActivity
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MediaCollectionScreen(
    onBack: () -> Unit,
    onOpenLocalFolder: (String) -> Unit = {},
    viewModel: MediaCollectionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }

    val needed: String? = remember(state.kind) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            android.os.Environment.isExternalStorageManager()
        ) {
            null
        } else when {
            Build.VERSION.SDK_INT >= 33 -> when (state.kind) {
                MediaKind.IMAGE -> Manifest.permission.READ_MEDIA_IMAGES
                MediaKind.VIDEO -> Manifest.permission.READ_MEDIA_VIDEO
            }
            else -> Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setPermissionGranted(granted || needed == null)
    }

    LaunchedEffect(Unit) {
        val ok = when {
            needed == null -> true
            context.checkSelfPermission(needed) == PackageManager.PERMISSION_GRANTED -> true
            else -> {
                launcher.launch(needed); false
            }
        }
        if (ok) viewModel.setPermissionGranted(true)
    }

    // snackbar (削除完了などの通知)
    LaunchedEffect(state.lastMessage) {
        state.lastMessage?.let { msg ->
            snackbarHost.showSnackbar(msg)
            viewModel.consumeMessage()
        }
    }

    // 戻る挙動: 選択中なら解除、フォルダ詳細にいるならフォルダ一覧へ、それ以外は外へ。
    BackHandler(enabled = state.isSelectionMode || state.openedFolder != null) {
        when {
            state.isSelectionMode -> viewModel.clearSelection()
            state.openedFolder != null -> viewModel.openFolder(null)
        }
    }

    // 削除確認ダイアログ
    var pendingDelete by remember { mutableStateOf(false) }
    if (pendingDelete) {
        AlertDialog(
            onDismissRequest = { pendingDelete = false },
            title = { Text("${state.selectedUris.size} 件を削除") },
            text = { Text("MediaStore から削除します (実体ファイルも削除されます)。元に戻せません。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = false
                    viewModel.deleteSelected()
                }) { Text("削除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = false }) { Text("キャンセル") }
            },
        )
    }

    Scaffold(
        topBar = {
            val title = when {
                state.isSelectionMode -> "${state.selectedUris.size} 件選択中"
                state.openedFolder != null -> File(state.openedFolder!!).name
                state.kind == MediaKind.IMAGE -> "画像"
                else -> "動画"
            }
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            state.isSelectionMode -> viewModel.clearSelection()
                            state.openedFolder != null -> viewModel.openFolder(null)
                            else -> onBack()
                        }
                    }) {
                        Icon(
                            if (state.isSelectionMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "戻る",
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (state.isSelectionMode) {
                BottomAppBar {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { viewModel.copySelected() }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "コピー")
                        }
                        IconButton(onClick = { viewModel.cutSelected() }) {
                            Icon(Icons.Default.ContentCut, contentDescription = "切り取り")
                        }
                        IconButton(onClick = { shareSelected(context, state) }) {
                            Icon(Icons.Default.Share, contentDescription = "共有")
                        }
                        IconButton(onClick = { openSelectedExternally(context, state) }) {
                            Icon(Icons.Default.OpenInNew, contentDescription = "別アプリで開く")
                        }
                        IconButton(onClick = {
                            // 「場所を開く」: 選択した最初のアイテムの親フォルダを Files タブで開く。
                            val firstPath = state.items.firstOrNull { it.contentUri.toString() in state.selectedUris }?.filePath
                            val dir = firstPath?.let { File(it).parent }
                            if (dir != null) { onOpenLocalFolder(dir); viewModel.clearSelection() }
                        }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "場所を開く")
                        }
                        IconButton(onClick = { pendingDelete = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "削除",
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { inner ->
        when {
            !state.hasPermission -> NoPermissionContent(
                onGrant = { needed?.let { launcher.launch(it) } },
                inner = inner,
            )
            state.isLoading -> Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.openedFolder == null -> {
                // フォルダごとビュー (トップ)
                if (state.folders.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                        Text(
                            if (state.kind == MediaKind.IMAGE) "画像は見つかりません" else "動画は見つかりません",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 140.dp),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxSize().padding(inner),
                    ) {
                        items(state.folders, key = { it.path }) { folder ->
                            FolderTile(
                                folder = folder,
                                onClick = { viewModel.openFolder(folder.path) },
                                onLongClick = {
                                    if (folder.path != "(その他)") onOpenLocalFolder(folder.path)
                                },
                            )
                        }
                    }
                }
            }
            else -> {
                // フォルダ詳細 (中の画像/動画)
                val visible = state.visibleItems
                if (visible.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                        Text("空のフォルダです", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 110.dp),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxSize().padding(inner),
                    ) {
                        items(visible, key = { it.contentUri.toString() }) { item ->
                            MediaTile(
                                item = item,
                                selected = item.contentUri.toString() in state.selectedUris,
                                inSelectionMode = state.isSelectionMode,
                                onClick = {
                                    if (state.isSelectionMode) {
                                        viewModel.toggleSelection(item.contentUri)
                                    } else {
                                        openItem(context, item, state.kind, visible)
                                    }
                                },
                                onLongClick = { viewModel.toggleSelection(item.contentUri) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTile(folder: MediaFolder, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        modifier = Modifier
            .aspectRatio(0.85f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            folder.sampleUri?.let { uri ->
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(uri).crossfade(false).build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text("${folder.count}", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            folder.displayName,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaTile(
    item: MediaItem,
    selected: Boolean,
    inSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(
                if (selected) Modifier.border(
                    3.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(6.dp),
                ) else Modifier,
            ),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(item.contentUri)
                .crossfade(false)
                .build(),
            contentDescription = item.displayName,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.durationMs > 0L) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(formatDuration(item.durationMs), style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
            )
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(2.dp),
                )
            }
        }
    }
}

@Composable
private fun NoPermissionContent(onGrant: () -> Unit, inner: PaddingValues) {
    Column(
        modifier = Modifier.fillMaxSize().padding(inner).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("メディアにアクセスするには権限が必要です", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "「すべてのファイルへのアクセス」を許可済みの場合は\n一度戻って再度開いてください。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGrant) { Text("メディアアクセスを許可") }
    }
}

private fun openItem(
    context: android.content.Context,
    item: MediaItem,
    kind: MediaKind,
    visible: List<MediaItem>,
) {
    val file = item.filePath?.let { File(it).takeIf { f -> f.exists() } }
    if (file != null) {
        val category = if (kind == MediaKind.IMAGE) Category.IMAGE else Category.VIDEO
        // 画面で見えている順序 (DATE_MODIFIED DESC + フォルダ絞り込み + 画像のみ) をそのまま使う。
        // 旧実装は File.parentFile.listFiles() を使っており FS順 + 画像以外混入の問題があった。
        val siblings: List<String> = visible.mapNotNull { it.filePath }
        context.startActivity(
            ViewerActivity.intent(
                context = context,
                localPath = file.absolutePath,
                name = item.displayName,
                category = category,
                editable = false,
                siblings = if (kind == MediaKind.IMAGE) siblings else emptyList(),
            ),
        )
    } else {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.contentUri, if (kind == MediaKind.IMAGE) "image/*" else "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, item.displayName)) }
    }
}

private fun shareSelected(context: android.content.Context, state: MediaCollectionState) {
    val uris: ArrayList<Uri> = state.items
        .filter { it.contentUri.toString() in state.selectedUris }
        .map { item ->
            // 実体ファイルがあれば FileProvider 経由、無ければ MediaStore URI を直接渡す。
            item.filePath?.let { p ->
                runCatching {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(p))
                }.getOrNull()
            } ?: item.contentUri
        }.let { ArrayList(it) }
    if (uris.isEmpty()) return
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uris[0])
            type = if (state.kind == MediaKind.IMAGE) "image/*" else "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            type = if (state.kind == MediaKind.IMAGE) "image/*" else "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    runCatching { context.startActivity(Intent.createChooser(send, "共有")) }
}

private fun openSelectedExternally(context: android.content.Context, state: MediaCollectionState) {
    val item = state.items.firstOrNull { it.contentUri.toString() in state.selectedUris } ?: return
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(item.contentUri, if (state.kind == MediaKind.IMAGE) "image/*" else "video/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(view, item.displayName)) }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
}
