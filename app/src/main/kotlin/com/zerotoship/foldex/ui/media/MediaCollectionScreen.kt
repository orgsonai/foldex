package com.zerotoship.foldex.ui.media

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.zerotoship.foldex.core.model.filetype.Category
import com.zerotoship.foldex.ui.viewer.ViewerActivity
import java.io.File
import java.util.Locale

/**
 * HOME の「画像」/「動画」タイルから開かれる横断ビューア。
 * MediaStore を直接読み、サムネイルグリッドで一覧する。タップで内蔵ビューアへ。
 *
 * 権限: Android 13+ は READ_MEDIA_IMAGES / READ_MEDIA_VIDEO、それ以下は
 * READ_EXTERNAL_STORAGE。MANAGE_EXTERNAL_STORAGE が既にあるなら追加要求はしない。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaCollectionScreen(
    onBack: () -> Unit,
    viewModel: MediaCollectionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    val needed: String? = remember(state.kind) {
        // MANAGE_EXTERNAL_STORAGE がある (Android 11+) ならランタイム要求はスキップ。
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
        // 起動時の許可判定: ① MANAGE_EXTERNAL_STORAGE がある (needed==null) → 即許可
        //                  ② 既に該当パーミッションが granted ならそのまま読み込み
        //                  ③ それ以外は requestPermission を発火
        val ok = when {
            needed == null -> true
            context.checkSelfPermission(needed) == PackageManager.PERMISSION_GRANTED -> true
            else -> {
                launcher.launch(needed)
                false
            }
        }
        if (ok) viewModel.setPermissionGranted(true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.kind == MediaKind.IMAGE) "画像" else "動画",
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { inner ->
        when {
            !state.hasPermission -> NoPermissionContent(
                onGrant = { needed?.let { launcher.launch(it) } },
                inner = inner,
            )
            state.isLoading -> Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Text(
                    if (state.kind == MediaKind.IMAGE) "画像は見つかりません" else "動画は見つかりません",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize().padding(inner),
                ) {
                    items(state.items, key = { it.contentUri.toString() }) { item ->
                        MediaTile(item) {
                            openItem(context, item, state.kind)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaTile(item: MediaItem, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(item.contentUri)
                .crossfade(false)
                .build(),
            contentDescription = item.displayName,
            modifier = Modifier.fillMaxSize(),
        )
        // 動画は左下に再生アイコン、右下に時間表示。画像はファイル名を下部に小さく。
        if (item.durationMs > 0L) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(
                    formatDuration(item.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp),
            )
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
        Text(
            "メディアにアクセスするには権限が必要です",
            style = MaterialTheme.typography.titleMedium,
        )
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

private fun openItem(context: android.content.Context, item: MediaItem, kind: MediaKind) {
    // MediaStore.DATA が読める (MANAGE_EXTERNAL_STORAGE) なら File ベースの内蔵ビューアで開く。
    // 取れなければ content:// として外部アプリにフォールバック。
    val file = item.filePath?.let { File(it).takeIf { f -> f.exists() } }
    if (file != null) {
        val category = if (kind == MediaKind.IMAGE) Category.IMAGE else Category.VIDEO
        // 画像のスワイプ用に同じ収集の他項目を渡す。
        val siblings: List<String> = file.parentFile?.listFiles()
            ?.filter { it.isFile }
            ?.map { it.absolutePath }
            ?: emptyList()
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
            setDataAndType(
                item.contentUri,
                if (kind == MediaKind.IMAGE) "image/*" else "video/*",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, item.displayName)) }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return String.format(Locale.US, "%d:%02d", m, s)
}

