package com.zerotoship.foldex.ui.viewer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.FileProvider
import com.zerotoship.foldex.core.model.filetype.Category
import com.zerotoship.foldex.core.model.filetype.FileTypeRegistry
import com.zerotoship.foldex.ui.theme.FoldexTheme
import java.io.File

/**
 * 内蔵ビューア用の単独 Activity。呼び出し側 (ファイルブラウザ) は、開く対象が
 * ローカルの実体 (リモートはキャッシュ済み) になった状態でこの Activity を起動する。
 */
class ViewerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path.isNullOrBlank()) { finish(); return }
        val file = File(path)
        val name = intent.getStringExtra(EXTRA_NAME) ?: file.name
        val category = runCatching { Category.valueOf(intent.getStringExtra(EXTRA_CATEGORY) ?: "") }
            .getOrDefault(FileTypeRegistry.categorize(name))
        val editable = intent.getBooleanExtra(EXTRA_EDITABLE, false)

        enableEdgeToEdge()
        setContent {
            FoldexTheme(darkTheme = isSystemInDarkTheme()) {
                ViewerScreen(
                    file = file,
                    name = name,
                    category = category,
                    editable = editable,
                    onBack = { finish() },
                    onOpenExternally = { openExternally(file, name) },
                )
            }
        }
    }

    private fun openExternally(file: File, name: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val mime = FileTypeRegistry.mimeTypeFor(name) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, name)) }
    }

    companion object {
        private const val EXTRA_PATH = "foldex.viewer.path"
        private const val EXTRA_NAME = "foldex.viewer.name"
        private const val EXTRA_CATEGORY = "foldex.viewer.category"
        private const val EXTRA_EDITABLE = "foldex.viewer.editable"

        fun intent(
            context: Context,
            localPath: String,
            name: String,
            category: Category,
            editable: Boolean = false,
        ): Intent =
            Intent(context, ViewerActivity::class.java)
                .putExtra(EXTRA_PATH, localPath)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_CATEGORY, category.name)
                .putExtra(EXTRA_EDITABLE, editable)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerScreen(
    file: File,
    name: String,
    category: Category,
    editable: Boolean,
    onBack: () -> Unit,
    onOpenExternally: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenExternally) {
                        Icon(Icons.Default.OpenInNew, contentDescription = "別のアプリで開く")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (category) {
                Category.IMAGE -> ImageViewer(file, Modifier.fillMaxSize())
                Category.MARKDOWN -> MarkdownViewer(file, Modifier.fillMaxSize())
                Category.HTML -> HtmlViewer(file, Modifier.fillMaxSize())
                Category.TEXT -> TextViewer(file, editable = editable, modifier = Modifier.fillMaxSize())
                Category.AUDIO -> AudioPlayer(file, name, Modifier.fillMaxSize())
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "このファイルはアプリ内で表示できません。\n右上の「別のアプリで開く」を使ってください。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
