@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.zerotoship.foldex.ui.viewer

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.WrapText
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zerotoship.foldex.ui.components.FastScrollbar
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import io.github.rosemoe.sora.widget.schemes.SchemeGitHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset

// 内蔵で開ける上限 (これより大きいと外部アプリ案内のみ)。
// ただし「無制限」設定 + 編集モードのときはこの上限も外す ([UNLIMITED_EDITABLE_LIMIT_KB])。
private const val MAX_BYTES = 8L * 1024 * 1024

// 編集可能の既定上限 (KB)。Sora の Canvas 描画 + 仮想化により ~4MB まで快適。
private const val DEFAULT_EDITABLE_LIMIT_KB = 2048

/**
 * 編集可能上限の「無制限」を表すセンチネル値 (KB)。設定画面・ViewerActivity でこの値を渡すと
 * サイズによる編集ロック ( + 内蔵ビューアの読み込み上限) を外す。
 */
const val UNLIMITED_EDITABLE_LIMIT_KB = Int.MAX_VALUE

private sealed interface TextLoad {
    data object Loading : TextLoad
    data class Loaded(val content: String, val charset: Charset, val sizeBytes: Long) : TextLoad
    data class TooLarge(val size: Long) : TextLoad
    data class Failed(val message: String) : TextLoad
}

/**
 * 内蔵テキストビューア/エディタ。Sora-editor を AndroidView で埋め込む。
 *
 * - 文字コードは [TextDecoding.detect] で自動判定 (BOM / NUL の頻度 / juniversalchardet)
 * - [editable] が true で [editableLimitKb] KB 以下のときだけ編集可能なエディタを起動
 * - 上限を超えるテキストは [ReadOnlyText] (LazyColumn) で表示
 */
@Composable
fun TextViewer(
    file: File,
    editable: Boolean = false,
    editableLimitKb: Int = DEFAULT_EDITABLE_LIMIT_KB,
    /**
     * Remote / SAF 経由で開いたファイルを「保存」押下と同時に元 URI へ書き戻すためのフック。
     * 非 null のとき、エディタは `file.writeBytes()` 直後にこのラムダを await する。
     * true を返せば「保存しました」、false なら「リモートへの保存に失敗しました」と表示する。
     */
    onSaveRemote: (suspend (File) -> Boolean)? = null,
    modifier: Modifier = Modifier,
) {
    // 「無制限」(= 編集モードでサイズ制限なし)。読み込み上限 MAX_BYTES もこの時だけ外す。
    val unlimited = editableLimitKb >= UNLIMITED_EDITABLE_LIMIT_KB

    val state by produceState<TextLoad>(TextLoad.Loading, file, editable, unlimited) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val len = file.length()
                if (len > MAX_BYTES && !(editable && unlimited)) return@runCatching TextLoad.TooLarge(len)
                val bytes = file.readBytes()
                val charset = TextDecoding.detect(bytes)
                TextLoad.Loaded(String(bytes, charset), charset, len)
            }.getOrElse { TextLoad.Failed(it.message ?: "読み込みに失敗しました") }
        }
    }

    val editableLimitBytes = if (unlimited) Long.MAX_VALUE else editableLimitKb.toLong() * 1024L

    when (val s = state) {
        TextLoad.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is TextLoad.TooLarge -> CenterMessage(
            modifier,
            "ファイルが大きすぎます (${s.size / 1024}KB)。右上の「別のアプリで開く」を使ってください。",
        )
        is TextLoad.Failed -> CenterMessage(modifier, s.message)
        is TextLoad.Loaded ->
            if (editable && s.sizeBytes <= editableLimitBytes) {
                SoraEditor(file, s.content, s.charset, onSaveRemote, modifier)
            } else {
                ReadOnlyText(
                    content = s.content,
                    charset = s.charset,
                    hint = if (editable)
                        "編集するには大きいため閲覧のみ (上限: ${editableLimitKb}KB / 設定で変更可)"
                    else null,
                    modifier = modifier,
                )
            }
    }
}

// ---- 編集モード (Sora-editor) ----

@Composable
private fun SoraEditor(
    file: File,
    initial: String,
    charset: Charset,
    onSaveRemote: (suspend (File) -> Boolean)?,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // テーマ色を Compose 側から取って Sora の ColorScheme に流し込む。
    // isDark は「適用中の Material テーマ」から判定する (= アプリのテーマ設定に追従)。
    // isSystemInDarkTheme() 固定だと、手動でライト/ダークを選んでいる時にエディタの
    // ベース配色 (Darcula/GitHub) だけがシステム側に引っ張られてしまうため。
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val onSurface = MaterialTheme.colorScheme.onSurface.toArgb()
    val surface = MaterialTheme.colorScheme.surface.toArgb()
    val primary = MaterialTheme.colorScheme.primary.toArgb()
    val onPrimary = MaterialTheme.colorScheme.onPrimary.toArgb()
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant.toArgb()
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val selection = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f).toArgb()

    // 編集状態
    var wordWrap by remember { mutableStateOf(true) }
    var showLineNumbers by remember { mutableStateOf(true) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var matchCount by remember { mutableIntStateOf(0) }
    var matchIndex by remember { mutableIntStateOf(0) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    // 未保存の編集が下書きとして退避されているかの目印。最初の編集で true、保存成功でリセット。
    // 何も触っていない時に下書きを書かないようにするためのフラグ。
    var dirty by remember(file) { mutableStateOf(false) }
    // 前回の未保存下書きが見つかったとき、復元可否を尋ねるためのテキスト (null なら問わない)。
    var draftToRestore by remember(file) { mutableStateOf<String?>(null) }

    // CodeEditor インスタンスを Composable のライフサイクルで保持。
    val editor = remember {
        // 標準 CodeEditor ではなく、長押し→そのままドラッグで範囲選択できる派生クラスを使う。
        DragSelectCodeEditor(context).apply {
            // 補完ポップアップ等は非コード用途なので無効化。
            getComponent(EditorAutoCompletion::class.java).isEnabled = false
            // 等幅 + 13sp 既定。サイズは Sora 内部で px なので sp→px 変換。
            typefaceText = Typeface.MONOSPACE
            setTextSize(13f)
            // 既定の値: ワードラップ ON、行番号 ON、選択行ハイライト ON
            isWordwrap = true
            // 行番号: Sora の API は setLineNumberEnabled
            setLineNumberEnabled(true)
            // カーソルブリンクを少し遅くする (= 描画頻度減)。0 で完全停止だが視認性が下がる。
            setCursorBlinkPeriod(750)
            // カーソル移動アニメーションを無効化。既定 ON だとカーソルが新しい位置へ「滑って」
            // 追従するため、連続入力や (特に) 連続削除でカーソルが追いつかず、動きが遅い=ラグと
            // 体感される。OFF にすると即座に移動して入力/削除がきびきび感じられる。
            isCursorAnimationEnabled = false
            // 選択ハンドル (しずく) を一回り大きくする。掴み判定はハンドルの矩形 + 約7dp なので、
            // ハンドルを拡大するとラフにドラッグしても掴めるようになる (= 大体の位置で掴める)。
            handleStyle.setScale(1.5f)
        }
    }

    // 初期テキスト + 文字コードの設定。引数 (file) が変わったら再ロード。
    LaunchedEffect(file) {
        // ContentCreator を使うと巨大テキストでもメモリ効率良くロード可能。ここでは直接 setText で OK。
        editor.setText(initial)
        // ロード直後は undo スタックが空 / 未編集扱い。
        canUndo = false
        canRedo = false
        dirty = false
        // 前回タスクキル等で残った下書きがあれば復元を提案する。ファイル内容と同じなら陳腐化と
        // みなして黙って消す。
        val draft = withContext(Dispatchers.IO) { EditorDraftStore.read(context, file) }
        when {
            draft == null -> Unit
            draft != initial -> draftToRestore = draft
            else -> withContext(Dispatchers.IO) { EditorDraftStore.clear(context, file) }
        }
    }

    // 編集イベントを購読して Undo/Redo の有効状態を反映。
    // 毎キーストロークで Compose state を書くとスナップショット書込みのオーバーヘッドで入力
    // レイテンシが出る。さらに以前のようにキーストローク毎にコルーチンを起動/キャンセルすると
    // メインスレッドにも細かい負荷が乗るため、変更通知は CONFLATED チャネルに trySend するだけ
    // (= 確保ゼロ・非ブロッキング) にし、収集側で「150ms 静かになったらまとめて 1 回反映」する
    // trailing debounce にして入力経路を軽くする。
    val changeTick = remember { Channel<Unit>(Channel.CONFLATED) }
    DisposableEffect(editor) {
        val sub = editor.subscribeAlways(ContentChangeEvent::class.java) { _ ->
            changeTick.trySend(Unit)
            dirty = true
        }
        onDispose { sub.unsubscribe() }
    }
    LaunchedEffect(editor) {
        while (true) {
            changeTick.receive() // 最初の変更を待つ
            // 150ms 入力が途切れるまで待つ (途切れない限り反映しない = recomposition を抑制)
            while (kotlinx.coroutines.withTimeoutOrNull(150) { changeTick.receive() } != null) {
                // 連続入力中は何もせず待ち続ける
            }
            val newCanUndo = editor.canUndo()
            val newCanRedo = editor.canRedo()
            if (canUndo != newCanUndo) canUndo = newCanUndo
            if (canRedo != newCanRedo) canRedo = newCanRedo
            if (status != null) status = null
            // 入力が落ち着いたタイミングで未保存テキストを下書きへ退避する。
            // 読みは main、書き込みは IO に逃がす。
            if (dirty) {
                val snapshot = editor.text.toString()
                withContext(Dispatchers.IO) { EditorDraftStore.save(context, file, snapshot) }
            }
        }
    }

    // 画面が止まる (バックグラウンド回収 / タスクキルの直前) ときに、その時点の編集テキストを
    // 同期的に下書きへ退避する。debounce が走る前にプロセスが消えても変更を残すための保険。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, editor, file) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && dirty) {
                EditorDraftStore.save(context, file, editor.text.toString())
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // テーマ色の動的更新。Material 色を Sora の ColorScheme に流す。
    LaunchedEffect(isDark, onSurface, surface, primary, surfaceVariant) {
        val scheme: EditorColorScheme = if (isDark) SchemeDarcula() else SchemeGitHub()
        // Sora の主要色を上書き
        scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, surface)
        scheme.setColor(EditorColorScheme.TEXT_NORMAL, onSurface)
        scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, surfaceVariant)
        scheme.setColor(EditorColorScheme.LINE_NUMBER, onSurfaceVariant)
        // 現在行の番号は本文色で強調 (ダークでベース配色の既定値が背景と被るのを防ぐ)。
        scheme.setColor(EditorColorScheme.LINE_NUMBER_CURRENT, onSurface)
        // スクロール/ドラッグ時に出る行番号バブル。未設定だと既定色が背景と被って読めないので、
        // ファストスクロールバーのラベルと同じ primary / onPrimary で高コントラストにする。
        scheme.setColor(EditorColorScheme.LINE_NUMBER_PANEL, primary)
        scheme.setColor(EditorColorScheme.LINE_NUMBER_PANEL_TEXT, onPrimary)
        scheme.setColor(EditorColorScheme.LINE_DIVIDER, onSurfaceVariant)
        scheme.setColor(EditorColorScheme.SELECTION_INSERT, primary)
        scheme.setColor(EditorColorScheme.SELECTION_HANDLE, primary)
        scheme.setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, selection)
        scheme.setColor(EditorColorScheme.CURRENT_LINE, surfaceVariant)
        editor.colorScheme = scheme
    }

    // 折り返しトグル
    LaunchedEffect(wordWrap) { editor.isWordwrap = wordWrap }
    // 行番号トグル
    LaunchedEffect(showLineNumbers) { editor.setLineNumberEnabled(showLineNumbers) }

    // 検索: クエリ変更時に編集中のエディタへ検索を反映。
    LaunchedEffect(searchOpen, searchQuery) {
        if (searchOpen && searchQuery.isNotEmpty()) {
            runCatching {
                // SearchOptions(type, caseInsensitive) — type = NORMAL/REGEX、caseInsensitive=true で大小区別なし。
                editor.searcher.search(
                    searchQuery,
                    io.github.rosemoe.sora.widget.EditorSearcher.SearchOptions(
                        io.github.rosemoe.sora.widget.EditorSearcher.SearchOptions.TYPE_NORMAL,
                        /* caseInsensitive = */ true,
                    ),
                )
                matchCount = editor.searcher.matchedPositionCount
                matchIndex = 0
            }
        } else {
            runCatching { editor.searcher.stopSearch() }
            matchCount = 0
            matchIndex = 0
        }
    }

    // ViewerActivity は enableEdgeToEdge() 済みなので、Scaffold 経由で content insets が
    // 流れてくる前提だが、Sora-editor (AndroidView) はその下に潜って描画されることがあり、
    // 画面最下段の行番号が 3 ボタンナビ/ジェスチャバーに齧られる問題が報告された。
    // ここで明示的に navigationBars インセットを当てて、エディタの底が必ずナビバーの上で
    // 終わるようにする。IME 開閉は imePadding が別途処理。
    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            .imePadding(),
    ) {
        // 編集本体: Sora の CodeEditor をそのまま埋め込む。weight(1f) で残り高さを占有。
        AndroidView(
            factory = { editor },
            // 行番号 (gutter) がぴったり画面左端から描画されるとシステムジェスチャ領域 +
            // 視覚的に詰まって読みにくいので、左端に少し余白を入れる。
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 4.dp)
                // 画面左端はシステムの「戻る」ジェスチャ領域 (~20dp) で、選択ハンドルを左端まで
                // ドラッグしようとするとそのジェスチャに横取りされ、行頭文字までカーソル/選択を
                // 伸ばせなかった。エディタ領域をジェスチャ対象から除外して端まで届くようにする。
                .systemGestureExclusion(),
            update = { /* 状態反映は LaunchedEffect 側で行う */ },
        )

        if (searchOpen) {
            HorizontalDivider()
            SearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                matchCount = matchCount,
                currentIndex = matchIndex,
                onNext = {
                    if (matchCount > 0) {
                        matchIndex = (matchIndex + 1) % matchCount
                        runCatching { editor.searcher.gotoNext() }
                    }
                },
                onPrev = {
                    if (matchCount > 0) {
                        matchIndex = (matchIndex - 1 + matchCount) % matchCount
                        runCatching { editor.searcher.gotoPrevious() }
                    }
                },
                onClose = { searchOpen = false; searchQuery = "" },
            )
        }

        HorizontalDivider()
        EditorBottomBar(
            charsetName = charset.name(),
            status = status,
            canUndo = canUndo,
            canRedo = canRedo,
            saving = saving,
            wordWrap = wordWrap,
            showLineNumbers = showLineNumbers,
            searchOpen = searchOpen,
            onPaste = {
                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val text = cb?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                if (text.isNotEmpty()) {
                    editor.pasteText(text)
                } else {
                    status = "クリップボードが空です"
                }
            },
            onToggleWrap = { wordWrap = !wordWrap },
            onToggleGutter = { showLineNumbers = !showLineNumbers },
            onSearch = { searchOpen = !searchOpen; if (!searchOpen) searchQuery = "" },
            onUndo = { editor.undo() },
            onRedo = { editor.redo() },
            onSave = {
                saving = true
                scope.launch {
                    val text = editor.text.toString()
                    // 1) まずローカル (キャッシュ or 実体) に書き込む。
                    val localOk = withContext(Dispatchers.IO) {
                        runCatching { file.writeBytes(text.toByteArray(charset)) }.isSuccess
                    }
                    // 2) Remote / SAF キャッシュ編集なら、続けて元 URI へ書き戻す。
                    //    ここを Activity 終了まで待たないことで、「保存後すぐタスクキル」しても
                    //    変更がリモートに反映されている状態を作る。
                    status = when {
                        !localOk -> "保存に失敗しました"
                        onSaveRemote == null -> "保存しました"
                        onSaveRemote(file) -> "保存しました"
                        else -> "リモートへの保存に失敗しました (再試行してください)"
                    }
                    // ローカルへ書き出せた時点で下書きは陳腐化するので消す (リモート失敗時も
                    // ローカルには反映済みなので、再オープン時の内容は下書きと一致する)。
                    if (localOk) {
                        dirty = false
                        withContext(Dispatchers.IO) { EditorDraftStore.clear(context, file) }
                    }
                    saving = false
                }
            },
        )
    }

    // 前回の未保存編集が見つかったときの復元確認。
    draftToRestore?.let { draft ->
        AlertDialog(
            onDismissRequest = { draftToRestore = null },
            title = { Text("未保存の編集が見つかりました") },
            text = { Text("前回このファイルを編集中にアプリが閉じられたようです。保存前の内容を復元しますか?") },
            confirmButton = {
                TextButton(onClick = {
                    editor.setText(draft)
                    dirty = true
                    draftToRestore = null
                }) { Text("復元") }
            },
            dismissButton = {
                TextButton(onClick = {
                    scope.launch { withContext(Dispatchers.IO) { EditorDraftStore.clear(context, file) } }
                    draftToRestore = null
                }) { Text("破棄") }
            },
        )
    }
}

/** Sora の CodeEditor にクリップボードのテキストを挿入する拡張。 */
private fun CodeEditor.pasteText(text: String) {
    // 選択中の有無に関わらず commitText で OK (Sora 側で既存選択を置換)。
    commitText(text)
}

@Composable
private fun EditorBottomBar(
    charsetName: String,
    status: String?,
    canUndo: Boolean,
    canRedo: Boolean,
    saving: Boolean,
    wordWrap: Boolean,
    showLineNumbers: Boolean,
    searchOpen: Boolean,
    onPaste: () -> Unit,
    onToggleWrap: () -> Unit,
    onToggleGutter: () -> Unit,
    onSearch: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
) {
    val active = MaterialTheme.colorScheme.primary
    val inactive = LocalContentColor.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            // 状態行 (charset / 保存メッセージ)。
            Text(
                text = status ?: charsetName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            )
            // ボタン行: 貼付 / 行番号 / 折り返し / 戻す / 進む / 検索 / 保存
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolbarButton(
                    onClick = onPaste,
                    icon = Icons.Default.ContentPaste,
                    label = "貼付",
                    inactiveColor = inactive,
                    modifier = Modifier.weight(1f),
                )
                ToolbarButton(
                    onClick = onToggleGutter,
                    icon = Icons.Default.Numbers,
                    label = "行番号",
                    active = showLineNumbers,
                    activeColor = active,
                    inactiveColor = inactive,
                    modifier = Modifier.weight(1f),
                )
                ToolbarButton(
                    onClick = onToggleWrap,
                    icon = Icons.Outlined.WrapText,
                    label = "折り返し",
                    active = wordWrap,
                    activeColor = active,
                    inactiveColor = inactive,
                    modifier = Modifier.weight(1f),
                )
                ToolbarButton(
                    onClick = onUndo,
                    icon = Icons.AutoMirrored.Filled.Undo,
                    label = "戻す",
                    enabled = canUndo,
                    inactiveColor = inactive,
                    modifier = Modifier.weight(1f),
                )
                ToolbarButton(
                    onClick = onRedo,
                    icon = Icons.AutoMirrored.Filled.Redo,
                    label = "進む",
                    enabled = canRedo,
                    inactiveColor = inactive,
                    modifier = Modifier.weight(1f),
                )
                ToolbarButton(
                    onClick = onSearch,
                    icon = Icons.Default.Search,
                    label = "検索",
                    active = searchOpen,
                    activeColor = active,
                    inactiveColor = inactive,
                    modifier = Modifier.weight(1f),
                )
                ToolbarButton(
                    onClick = onSave,
                    icon = Icons.Default.Save,
                    label = "保存",
                    enabled = !saving,
                    inactiveColor = inactive,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** アイコン + ラベルの縦並びボタン。weight(1f) で均等配置できる。 */
@Composable
private fun ToolbarButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = LocalContentColor.current,
) {
    val tint = when {
        !enabled -> inactiveColor.copy(alpha = 0.38f)
        active -> activeColor
        else -> inactiveColor
    }
    androidx.compose.material3.Surface(
        onClick = onClick,
        enabled = enabled,
        color = Color.Transparent,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    currentIndex: Int,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("検索…") },
            singleLine = true,
            modifier = Modifier.weight(1f),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
            ),
        )
        Text(
            text = if (matchCount == 0) "0/0" else "${currentIndex + 1}/$matchCount",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        IconButton(onClick = onPrev, enabled = matchCount > 0) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "前へ")
        }
        IconButton(onClick = onNext, enabled = matchCount > 0) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "次へ")
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "検索を閉じる")
        }
    }
}

// ---- 閲覧専用モード ----

@Composable
private fun ReadOnlyText(content: String, charset: Charset, hint: String?, modifier: Modifier) {
    // 1 行ずつ LazyColumn で遅延描画する。1 つの巨大な Text にすると数十万行で固まるため。
    val lines = remember(content) { content.lines() }
    val mono = TextStyle(fontFamily = FontFamily.Monospace, color = LocalContentColor.current)
    val listState = rememberLazyListState()

    Column(modifier.fillMaxSize()) {
        Text(
            text = buildString {
                append("文字コード: ${charset.name()}  ·  ${lines.size}行")
                if (hint != null) append("  ·  ").append(hint)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        Box(Modifier.fillMaxSize()) {
            SelectionContainer {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(count = lines.size, key = { it }) { i ->
                        Text(text = lines[i].ifEmpty { " " }, style = mono)
                    }
                }
            }
            FastScrollbar(listState, lines.size, Modifier.align(Alignment.CenterEnd))
        }
    }
}

// ---- helpers ----

@Composable
private fun CenterMessage(modifier: Modifier, message: String) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
