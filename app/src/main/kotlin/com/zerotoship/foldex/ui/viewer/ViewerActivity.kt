// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.viewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.core.data.repo.SettingsRepository
import com.zerotoship.foldex.core.data.repo.UserSettings
import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.core.model.ThemeMode
import com.zerotoship.foldex.core.model.WriteMode
import com.zerotoship.foldex.core.model.filetype.Category
import com.zerotoship.foldex.core.model.filetype.FileTypeRegistry
import com.zerotoship.foldex.storage.StorageProviderRouter
import com.zerotoship.foldex.ui.theme.FoldexTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 内蔵ビューア用の単独 Activity。呼び出し側 (ファイルブラウザ) は、開く対象が
 * ローカルの実体 (リモートはキャッシュ済み) になった状態でこの Activity を起動する。
 *
 * Remote / SAF 由来のキャッシュを編集するときは [EXTRA_SOURCE_URI] に元の URI を渡す。
 * テキストエディタの「保存」押下時、キャッシュへの書き込みと同期して元 URI への
 * アップロードバックも即座に行う ([buildRemoteSaver] が返すラムダで実装)。
 * 押し忘れた場合の保険として FileBrowser 側の ON_RESUME 走査 (checkPendingUploads)
 * も残っている。
 */
@AndroidEntryPoint
class ViewerActivity : ComponentActivity() {

    @Inject lateinit var storage: StorageProviderRouter

    @Inject lateinit var settingsRepo: SettingsRepository

    /**
     * 内蔵画像エディタから戻ったときの受け口。保存されたらビューアを閉じて一覧へ戻す。
     * (表示中の画像が差し替わっており、サムネ/画像キャッシュが古いまま残るため)
     */
    private val imageEditLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 他アプリの「アプリで開く」(ACTION_VIEW) から起動されたケース。
        // 渡された content:// / file:// URI をキャッシュへコピーしてから内蔵ビューアで開く。
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            handleExternalView(intent.data!!)
            return
        }
        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val streamingMediaUri = intent.getStringExtra(EXTRA_STREAMING_URI)
        // streaming 経路は localPath=空でも OK (Mediauri から再生)。それ以外は path 必須。
        if (path.isBlank() && streamingMediaUri.isNullOrBlank()) { finish(); return }
        val file = if (path.isNotBlank()) File(path) else File(intent.getStringExtra(EXTRA_NAME) ?: "stream")
        val name = intent.getStringExtra(EXTRA_NAME) ?: file.name
        val category = runCatching { Category.valueOf(intent.getStringExtra(EXTRA_CATEGORY) ?: "") }
            .getOrDefault(FileTypeRegistry.categorize(name))
        val editable = intent.getBooleanExtra(EXTRA_EDITABLE, false)
        val editableLimitKb = intent.getIntExtra(EXTRA_EDITABLE_LIMIT_KB, 512)
        val siblings: List<String> = intent.getStringArrayExtra(EXTRA_SIBLINGS)?.toList().orEmpty()
        val initialImageId: String = intent.getStringExtra(EXTRA_INITIAL_ID) ?: file.absolutePath
        val sourceUri: FileUri? = intent.getStringExtra(EXTRA_SOURCE_URI)
            ?.let { FileUri.fromStorageStringOrNull(it) }

        enableEdgeToEdge()
        setContent {
            // ビューア/エディタもメイン画面と同じテーマ設定 (システム/ライト/ダーク + Material You)
            // に追従させる。以前は isSystemInDarkTheme() 固定で、手動でライト/ダークを選んでいると
            // エディタだけ別テーマになっていた。
            val settings by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = UserSettings())
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            FoldexTheme(
                darkTheme = darkTheme,
                dynamicColor = settings.dynamicColor,
                colorTheme = settings.colorTheme,
            ) {
                ViewerScreen(
                    file = file,
                    name = name,
                    category = category,
                    editable = editable,
                    editableLimitKb = editableLimitKb,
                    siblings = siblings,
                    initialImageId = initialImageId,
                    // SAF/Remote は file がキャッシュ実体 (親フォルダに無関係な画像が同居する) なので、
                    // 親フォルダ走査 fallback はローカルの実ファイルのときだけ許す。
                    allowParentScan = sourceUri == null,
                    streamingMediaUri = streamingMediaUri,
                    onBack = { finish() },
                    onOpenExternally = { f -> openExternally(f, f.name) },
                    onOpenExternallyId = { id -> openIdExternally(id) },
                    onSaveRemote = sourceUri?.let { buildRemoteSaver(it) },
                    onEditImage = { path, name2 -> openImageEditor(path, name2, sourceUri?.toStorageString()) },
                )
            }
        }
    }

    /**
     * Remote/SAF へキャッシュファイルの内容を OVERWRITE で書き戻すラムダを作る。
     * `file.writeBytes` で更新したばかりの [cacheFile] の中身を、ここで元 URI に
     * ストリームコピーする。成功時 true。
     */
    private fun buildRemoteSaver(sourceUri: FileUri): suspend (File) -> Boolean = { cacheFile ->
        withContext(Dispatchers.IO) {
            runCatching {
                when (val r = storage.openOutput(sourceUri, WriteMode.OVERWRITE)) {
                    is Result.Success -> r.value.use { out ->
                        cacheFile.inputStream().use { it.copyTo(out) }
                    }
                    is Result.Failure -> error(r.error.message ?: "openOutput failed")
                }
                true
            }.getOrElse { false }
        }
    }

    /**
     * 他アプリの「アプリで開く」で渡された URI を扱う。ビューアはローカルの [File] を前提とするため、
     * まず URI の中身をキャッシュへ非同期コピーし、完了したら既存の [ViewerScreen] で表示する。
     * 外部由来なので閲覧専用 (editable=false / 書き戻しなし)。
     */
    private fun handleExternalView(uri: Uri) {
        val name = queryDisplayName(uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            ?: "file"
        val category = FileTypeRegistry.categorize(name)

        // null=コピー中 / File=表示可能 / true=失敗。setContent の外で持ち、コピー完了時に更新する。
        val fileState = mutableStateOf<File?>(null)
        val errorState = mutableStateOf(false)

        enableEdgeToEdge()
        setContent {
            val settings by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = UserSettings())
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            FoldexTheme(
                darkTheme = darkTheme,
                dynamicColor = settings.dynamicColor,
                colorTheme = settings.colorTheme,
            ) {
                val file by fileState
                val failed by errorState
                when {
                    failed -> ExternalOpenMessage("このファイルを開けませんでした。", onBack = { finish() })
                    file == null -> ExternalOpenLoading(name)
                    else -> ViewerScreen(
                        file = file!!,
                        name = name,
                        category = category,
                        editable = false,
                        editableLimitKb = 512,
                        siblings = emptyList(),
                        initialImageId = file!!.absolutePath,
                        allowParentScan = false,
                        streamingMediaUri = null,
                        onBack = { finish() },
                        onOpenExternally = { f -> openExternally(f, f.name) },
                        onOpenExternallyId = { id -> openIdExternally(id) },
                        onSaveRemote = null,
                    )
                }
            }
        }

        lifecycleScope.launch {
            val copied = withContext(Dispatchers.IO) { copyUriToCache(uri, name) }
            if (copied != null) fileState.value = copied else errorState.value = true
        }
    }

    /** content:// の DISPLAY_NAME を引く。file:// は lastPathSegment を使う。取れなければ null。 */
    private fun queryDisplayName(uri: Uri): String? = runCatching {
        if (uri.scheme == "file") return@runCatching uri.lastPathSegment?.substringAfterLast('/')
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else {
                null
            }
        }
    }.getOrNull()

    /** URI の中身を `cacheDir/external-view/<name>` へコピーして返す。失敗時 null。 */
    private fun copyUriToCache(uri: Uri, name: String): File? = runCatching {
        val dir = File(cacheDir, "external-view").apply {
            mkdirs()
            // 直前に開いたファイルは掃除し、1件だけ残す (巨大ファイルの溜め込みを防ぐ)。
            listFiles()?.forEach { it.delete() }
        }
        val safeName = name.replace(Regex("[/\\\\]+"), "_").ifBlank { "file" }
        val out = File(dir, safeName)
        (contentResolver.openInputStream(uri) ?: error("openInputStream returned null")).use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        out
    }.getOrNull()

    /**
     * 表示中の画像識別子を外部アプリで開く。content:// URI (SAF 兄弟) はそのまま ACTION_VIEW、
     * ローカル絶対パスは FileProvider 経由 ([openExternally]) に振り分ける。
     */
    private fun openIdExternally(id: String) {
        if (id.startsWith("content://")) {
            val uri = id.toUri()
            val dispName = imageDisplayName(id)
            val mime = FileTypeRegistry.mimeTypeFor(dispName) ?: "image/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { startActivity(Intent.createChooser(intent, dispName)) }
        } else {
            val f = File(id)
            openExternally(f, f.name)
        }
    }

    /** 内蔵画像エディタを開く。保存されたら [imageEditLauncher] 側でこの画面を閉じる。 */
    private fun openImageEditor(localPath: String, name: String, sourceUriString: String?) {
        runCatching {
            imageEditLauncher.launch(
                com.zerotoship.foldex.ui.imageedit.ImageEditActivity.intent(
                    context = this,
                    localPath = localPath,
                    name = name,
                    sourceUriString = sourceUriString,
                ),
            )
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
        private const val EXTRA_EDITABLE_LIMIT_KB = "foldex.viewer.editable_limit_kb"
        private const val EXTRA_SIBLINGS = "foldex.viewer.siblings"
        private const val EXTRA_INITIAL_ID = "foldex.viewer.initial_id"
        private const val EXTRA_STREAMING_URI = "foldex.viewer.streaming_uri"
        private const val EXTRA_SOURCE_URI = "foldex.viewer.source_uri"

        fun intent(
            context: Context,
            localPath: String,
            name: String,
            category: Category,
            editable: Boolean = false,
            editableLimitKb: Int = 512,
            siblings: List<String> = emptyList(),
            /**
             * [siblings] のうち「最初に開く1枚」を指す識別子。ローカルは絶対パス、SAF は content:// URI。
             * 未指定なら [localPath] を初期表示とみなす (ローカル画像・従来の呼び出し互換)。
             */
            initialImageId: String? = null,
            streamingMediaUri: String? = null,
            sourceUriString: String? = null,
        ): Intent =
            Intent(context, ViewerActivity::class.java)
                .putExtra(EXTRA_PATH, localPath)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_CATEGORY, category.name)
                .putExtra(EXTRA_EDITABLE, editable)
                .putExtra(EXTRA_EDITABLE_LIMIT_KB, editableLimitKb)
                .apply {
                    if (siblings.isNotEmpty()) putExtra(EXTRA_SIBLINGS, siblings.toTypedArray())
                    if (!initialImageId.isNullOrBlank()) putExtra(EXTRA_INITIAL_ID, initialImageId)
                    if (!streamingMediaUri.isNullOrBlank()) {
                        putExtra(EXTRA_STREAMING_URI, streamingMediaUri)
                    }
                    if (!sourceUriString.isNullOrBlank()) {
                        putExtra(EXTRA_SOURCE_URI, sourceUriString)
                    }
                }
    }
}

/** 外部 URI をキャッシュへコピー中に見せるローディング画面。 */
@Composable
private fun ExternalOpenLoading(name: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                "$name を読み込み中…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 外部オープンに失敗したときのメッセージ画面。 */
@Composable
private fun ExternalOpenMessage(text: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onBack) { Text("閉じる") }
        }
    }
}

/** 画像識別子 (絶対パス or content:// URI) から表示用のファイル名を得る。 */
private fun imageDisplayName(id: String): String =
    if (id.startsWith("content://")) {
        // content://.../tree/xxx/document/yyy%2Fname.jpg → 末尾セグメントを URL デコードした名前。
        id.toUri().lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            ?.ifEmpty { id }
            ?: id
    } else {
        File(id).name
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
    editableLimitKb: Int,
    siblings: List<String>,
    /** [siblings] のうち最初に表示する1枚の識別子 (ローカル絶対パス or content:// URI)。 */
    initialImageId: String = file.absolutePath,
    /** ローカルの実ファイルのみ true。SAF/Remote キャッシュでは親フォルダ走査を抑止する。 */
    allowParentScan: Boolean = true,
    /** リモートストリーミング再生に使う content URI 文字列。VIDEO/AUDIO カテゴリ時のみ参照。 */
    streamingMediaUri: String?,
    onBack: () -> Unit,
    onOpenExternally: (File) -> Unit,
    /** 現在表示中の対象 (絶対パス or content:// URI) を外部アプリで開く。トップバーの「別のアプリで開く」用。 */
    onOpenExternallyId: (String) -> Unit,
    /** Remote / SAF 由来のキャッシュ編集時、エディタ「保存」押下で即時アップロードするためのフック。
     *  ローカル直編集時は null (= file.writeBytes だけで完結)。 */
    onSaveRemote: (suspend (File) -> Boolean)? = null,
    /** 内蔵画像エディタを開く (ローカル実体のパス, 表示名)。画像以外では呼ばれない。 */
    onEditImage: ((String, String) -> Unit)? = null,
) {
    // Markdown / HTML はソース編集をデフォルトにし、プレビューはトグルで切替える
    // (HANDOFF §10-E / §10-F: 「ソース表示とプレビュー表示の切替」)。
    val canPreview = category == Category.MARKDOWN || category == Category.HTML
    var previewMode by remember { mutableStateOf(false) }

    // 画像はスワイプで前後の画像へ。Intent で運ばれた siblings を最優先、
    // それが無い/不完全なら同フォルダから listFiles で集める (ローカル実ファイル限定の fallback)。
    // SAF/Remote は file がキャッシュ実体なので親フォルダ走査は使わず、開いた1枚のみを表示する。
    val imagePaths: List<String> = remember(siblings, initialImageId, allowParentScan) {
        if (category != Category.IMAGE) return@remember emptyList()
        val provided = siblings.takeIf { it.size > 1 && it.contains(initialImageId) }
        provided ?: if (allowParentScan) collectImagesFromParent(file) else listOf(initialImageId)
    }
    var imageIndex by remember(imagePaths) {
        mutableStateOf(imagePaths.indexOf(initialImageId).coerceAtLeast(0))
    }
    // 現在表示中の対象を識別子 (ローカル絶対パス or content:// URI) で持つ。
    // 画像以外は file をそのまま指す。
    val displayedId = if (category == Category.IMAGE) {
        imagePaths.getOrNull(imageIndex) ?: initialImageId
    } else {
        file.absolutePath
    }
    // 開いた1枚は Intent 由来の正式な表示名 (name) を使い、他の兄弟は識別子から導出する。
    val displayedName = remember(displayedId, name, initialImageId) {
        if (displayedId == initialImageId) name else imageDisplayName(displayedId)
    }

    // 内蔵画像エディタに渡せるローカル実体のパス。null なら編集ボタンを出さない。
    // SAF の兄弟画像 (content://) はキャッシュ実体が無いので対象外にする。
    val editableImageTarget: String? = remember(category, displayedId, initialImageId) {
        when {
            category != Category.IMAGE -> null
            !displayedId.startsWith("content://") -> displayedId
            displayedId == initialImageId -> file.absolutePath
            else -> null
        }
    }

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
                    if (editableImageTarget != null && onEditImage != null) {
                        IconButton(onClick = { onEditImage(editableImageTarget, displayedName) }) {
                            Icon(Icons.Default.Tune, contentDescription = "画像を編集")
                        }
                    }
                    IconButton(onClick = { onOpenExternallyId(displayedId) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "別のアプリで開く")
                    }
                },
            )
        },
    ) { padding ->
        // Scaffold が確保した system bar 分を消費済みとして子へ伝える。
        // これが無いと TextViewer 側の navigationBarsPadding と重なり、3 ボタン
        // ナビゲーションの上にバー1個分の空白が追加される。
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            when (category) {
                Category.IMAGE -> ImagePagerViewer(
                    paths = imagePaths,
                    initialIndex = imageIndex,
                    onPageChanged = { imageIndex = it },
                    modifier = Modifier.fillMaxSize(),
                )
                Category.MARKDOWN ->
                    if (previewMode) MarkdownViewer(file, Modifier.fillMaxSize())
                    else TextViewer(
                        file = file,
                        editable = editable,
                        editableLimitKb = editableLimitKb,
                        onSaveRemote = onSaveRemote,
                        modifier = Modifier.fillMaxSize(),
                    )
                Category.HTML ->
                    if (previewMode) HtmlViewer(file, Modifier.fillMaxSize())
                    else TextViewer(
                        file = file,
                        editable = editable,
                        editableLimitKb = editableLimitKb,
                        onSaveRemote = onSaveRemote,
                        modifier = Modifier.fillMaxSize(),
                    )
                Category.TEXT -> TextViewer(
                    file = file,
                    editable = editable,
                    editableLimitKb = editableLimitKb,
                    onSaveRemote = onSaveRemote,
                    modifier = Modifier.fillMaxSize(),
                )
                Category.AUDIO -> AudioPlayer(file, name, Modifier.fillMaxSize())
                Category.VIDEO -> VideoViewer(
                    file = file,
                    displayName = name,
                    mediaUri = streamingMediaUri,
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
