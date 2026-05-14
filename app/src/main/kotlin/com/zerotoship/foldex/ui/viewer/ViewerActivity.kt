package com.zerotoship.foldex.ui.viewer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
        val siblings: List<String> = intent.getStringArrayExtra(EXTRA_SIBLINGS)?.toList().orEmpty()

        enableEdgeToEdge()
        setContent {
            FoldexTheme(darkTheme = isSystemInDarkTheme()) {
                ViewerScreen(
                    file = file,
                    name = name,
                    category = category,
                    editable = editable,
                    siblings = siblings,
                    onBack = { finish() },
                    onOpenExternally = { f -> openExternally(f, f.name) },
                )
            }
        }
    }

    private fun openExternally(file: File, name: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val mime = FileTypeRegistry.mimeTypeFor(name) ?: "*/*"
        // 外部エディタが保存できるよう WRITE 権限も付与する (READ だけだと
        // androidx.core.content.FileProvider の Permission Denial で書き込み拒否)。
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, name)) }
    }

    companion object {
        private const val EXTRA_PATH = "foldex.viewer.path"
        private const val EXTRA_NAME = "foldex.viewer.name"
        private const val EXTRA_CATEGORY = "foldex.viewer.category"
        private const val EXTRA_EDITABLE = "foldex.viewer.editable"
        private const val EXTRA_SIBLINGS = "foldex.viewer.siblings"

        fun intent(
            context: Context,
            localPath: String,
            name: String,
            category: Category,
            editable: Boolean = false,
            siblings: List<String> = emptyList(),
        ): Intent =
            Intent(context, ViewerActivity::class.java)
                .putExtra(EXTRA_PATH, localPath)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_CATEGORY, category.name)
                .putExtra(EXTRA_EDITABLE, editable)
                .apply {
                    if (siblings.isNotEmpty()) putExtra(EXTRA_SIBLINGS, siblings.toTypedArray())
                }
    }
}

/** ローカル画像をスワイプ閲覧するための同フォルダ画像列挙 (ViewerActivity の fallback)。 */
private fun collectImagesFromParent(file: File): List<String> {
    val parent = file.parentFile ?: return listOf(file.absolutePath)
    val list = runCatching {
        parent.listFiles { f -> f.isFile && FileTypeRegistry.categorize(f.name) == Category.IMAGE }
            ?.sortedBy { it.name.lowercase() }
            ?.map { it.absolutePath }
            ?: emptyList()
    }.getOrElse { emptyList() }
    return if (list.isNotEmpty() && file.absolutePath in list) list else listOf(file.absolutePath)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerScreen(
    file: File,
    name: String,
    category: Category,
    editable: Boolean,
    siblings: List<String>,
    onBack: () -> Unit,
    onOpenExternally: (File) -> Unit,
) {
    // Markdown / HTML はソース編集をデフォルトにし、プレビューはトグルで切替える
    // (HANDOFF §10-E / §10-F: 「ソース表示とプレビュー表示の切替」)。
    val canPreview = category == Category.MARKDOWN || category == Category.HTML
    var previewMode by remember { mutableStateOf(false) }

    // 画像はスワイプで前後の画像へ。Intent で運ばれた siblings を最優先、
    // それが無い/不完全なら同フォルダから listFiles で集める (ローカル限定の fallback)。
    val imagePaths: List<String> = remember(siblings, file) {
        if (category != Category.IMAGE) return@remember emptyList()
        val provided = siblings.takeIf { it.size > 1 && it.contains(file.absolutePath) }
        provided ?: collectImagesFromParent(file)
    }
    var imageIndex by remember(imagePaths) {
        mutableStateOf(imagePaths.indexOf(file.absolutePath).coerceAtLeast(0))
    }
    val displayedFile = remember(imageIndex, imagePaths) {
        imagePaths.getOrNull(imageIndex)?.let { File(it) } ?: file
    }
    val displayedName = remember(displayedFile) { displayedFile.name }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(displayedName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (category == Category.IMAGE && imagePaths.size > 1) {
                            Text(
                                "${imageIndex + 1} / ${imagePaths.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    if (canPreview) {
                        IconButton(onClick = { previewMode = !previewMode }) {
                            if (previewMode) {
                                Icon(Icons.Default.Edit, contentDescription = "ソースを編集")
                            } else {
                                Icon(Icons.Default.Visibility, contentDescription = "プレビュー")
                            }
                        }
                    }
                    IconButton(onClick = { onOpenExternally(displayedFile) }) {
                        Icon(Icons.Default.OpenInNew, contentDescription = "別のアプリで開く")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (category) {
                Category.IMAGE -> ImagePagerViewer(
                    paths = imagePaths,
                    initialIndex = imageIndex,
                    onPageChanged = { imageIndex = it },
                    modifier = Modifier.fillMaxSize(),
                )
                Category.MARKDOWN ->
                    if (previewMode) MarkdownViewer(file, Modifier.fillMaxSize())
                    else TextViewer(file, editable = editable, modifier = Modifier.fillMaxSize())
                Category.HTML ->
                    if (previewMode) HtmlViewer(file, Modifier.fillMaxSize())
                    else TextViewer(file, editable = editable, modifier = Modifier.fillMaxSize())
                Category.TEXT -> TextViewer(file, editable = editable, modifier = Modifier.fillMaxSize())
                Category.AUDIO -> AudioPlayer(file, name, Modifier.fillMaxSize())
                Category.VIDEO -> VideoViewer(
                    file = file,
                    modifier = Modifier.fillMaxSize(),
                    onOpenExternally = onOpenExternally,
                )
                Category.PDF -> PdfViewer(file, Modifier.fillMaxSize())
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
