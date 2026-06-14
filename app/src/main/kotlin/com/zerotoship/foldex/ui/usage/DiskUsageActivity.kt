package com.zerotoship.foldex.ui.usage

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zerotoship.foldex.core.data.repo.SettingsRepository
import com.zerotoship.foldex.core.data.repo.UserSettings
import com.zerotoship.foldex.core.model.NodeType
import com.zerotoship.foldex.core.model.ThemeMode
import com.zerotoship.foldex.ui.filebrowser.iconFor
import com.zerotoship.foldex.ui.theme.FoldexTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 使用量分析画面 (gdu 風) — FOLDEX 追加機能。
 *
 * 指定フォルダ直下の各項目を「配下を含む合計サイズ」で大きい順に並べ、割合バー付きで見せる。
 * フォルダをタップすると中へ潜って同じ分析を続ける (ドリルダウン)。リモート/SAF も対象だが、
 * 大きいフォルダは再帰実測に時間がかかるため進捗を出し、中断ボタンで止められる。
 */
@AndroidEntryPoint
class DiskUsageActivity : ComponentActivity() {

    @Inject lateinit var settingsRepo: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uriString = intent.getStringExtra(EXTRA_URI).orEmpty()
        if (uriString.isBlank()) { finish(); return }
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()

        enableEdgeToEdge()
        setContent {
            val settings by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = UserSettings())
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            FoldexTheme(darkTheme = darkTheme, dynamicColor = settings.dynamicColor) {
                DiskUsageScreen(startUri = uriString, startName = name, onFinish = { finish() })
            }
        }
    }

    companion object {
        private const val EXTRA_URI = "foldex.usage.uri"
        private const val EXTRA_NAME = "foldex.usage.name"

        fun intent(context: Context, uriString: String, name: String): Intent =
            Intent(context, DiskUsageActivity::class.java)
                .putExtra(EXTRA_URI, uriString)
                .putExtra(EXTRA_NAME, name)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiskUsageScreen(
    startUri: String,
    startName: String,
    onFinish: () -> Unit,
    viewModel: DiskUsageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 最初の対象フォルダで分析開始 (1 回だけ。ViewModel 側でも二重開始は弾く)。
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.start(startUri, startName) }

    // 端末の戻る: 1 階層上がる。最上位なら画面を閉じる。
    BackHandler { if (!viewModel.goUp()) onFinish() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (state.title.isBlank()) "使用量" else state.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "合計 ${humanBytes(state.totalBytes)}" +
                                if (state.scanning) " ・ 集計中…" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (!viewModel.goUp()) onFinish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    if (state.scanning) {
                        TextButton(onClick = { viewModel.cancelScan() }) { Text("中断") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.scanning) {
                val frac = if (state.totalCount > 0) state.scannedCount.toFloat() / state.totalCount else 0f
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            when {
                state.entries.isEmpty() && state.scanning ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("使用量を集計中…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                state.entries.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("このフォルダは空です",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.entries, key = { it.node.uri.toStorageString() }) { entry ->
                        UsageRow(
                            entry = entry,
                            total = state.totalBytes,
                            onClick = { viewModel.enter(entry) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageRow(
    entry: DiskUsageViewModel.Entry,
    total: Long,
    onClick: () -> Unit,
) {
    val isDir = entry.node.type == NodeType.DIRECTORY
    val fraction = if (total > 0) (entry.bytes.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isDir, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            iconFor(entry.node),
            contentDescription = null,
            tint = if (isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.node.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    humanBytes(entry.bytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${(fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            // 割合バー: そのフォルダ合計に対する占有率。
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            if (isDir) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.tertiary,
                        ),
                )
            }
        }
    }
}

/** バイト数を読みやすい単位へ整形する。 */
private fun humanBytes(b: Long): String = when {
    b >= 1024L * 1024 * 1024 -> "%.2fGB".format(b / (1024.0 * 1024 * 1024))
    b >= 1024L * 1024 -> "%.1fMB".format(b / (1024.0 * 1024))
    b >= 1024L -> "%.1fKB".format(b / 1024.0)
    else -> "${b}B"
}
