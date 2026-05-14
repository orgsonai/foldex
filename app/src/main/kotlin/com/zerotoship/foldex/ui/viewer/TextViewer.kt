@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.zerotoship.foldex.ui.viewer

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.WrapText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerotoship.foldex.ui.components.FastScrollbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset

// 内蔵で開ける上限 (これより大きいと外部アプリ案内のみ)。
private const val MAX_BYTES = 8L * 1024 * 1024

// 内蔵エディタで編集可能な上限 = MAX_BYTES と一致。
// 旧設計では「512KB 超は閲覧専用」を区切りにしていたが、巨大テキストでも
// 軽快に動かすため Gutter / LineCount の再計算を throttle 化したので一律編集可能に。
private const val EDITABLE_MAX_BYTES = MAX_BYTES

// フォントサイズの上下限 (ピンチズーム時)。
private const val MIN_FONT_SP = 8f
private const val MAX_FONT_SP = 32f
private const val DEFAULT_FONT_SP = 13f

private sealed interface TextLoad {
    data object Loading : TextLoad
    data class Loaded(val content: String, val charset: Charset, val sizeBytes: Long) : TextLoad
    data class TooLarge(val size: Long) : TextLoad
    data class Failed(val message: String) : TextLoad
}

/** 等幅フォントの簡易テキストビューア/エディタ。文字コードは自動判定。[editable] が true なら上書き保存できる。 */
@Composable
fun TextViewer(file: File, editable: Boolean = false, modifier: Modifier = Modifier) {
    val state by produceState<TextLoad>(TextLoad.Loading, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val len = file.length()
                if (len > MAX_BYTES) return@runCatching TextLoad.TooLarge(len)
                val bytes = file.readBytes()
                val charset = TextDecoding.detect(bytes)
                TextLoad.Loaded(String(bytes, charset), charset, len)
            }.getOrElse { TextLoad.Failed(it.message ?: "読み込みに失敗しました") }
        }
    }

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
            if (editable && s.sizeBytes <= EDITABLE_MAX_BYTES) {
                TextEditor(file, s.content, s.charset, modifier)
            } else {
                ReadOnlyText(
                    content = s.content,
                    charset = s.charset,
                    hint = if (editable) "編集するには大きいため閲覧のみ (外部アプリで編集できます)" else null,
                    modifier = modifier,
                )
            }
    }
}

// ---- 編集モード ----

@Composable
private fun TextEditor(file: File, initial: String, charset: Charset, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val textState = rememberTextFieldState(initial)

    var fontSizeSp by remember { mutableFloatStateOf(DEFAULT_FONT_SP) }
    var wordWrap by remember { mutableStateOf(true) }
    var showLineNumbers by remember { mutableStateOf(true) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var matchIndex by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    val transformState = rememberTransformableState { zoomChange, _, _ ->
        fontSizeSp = (fontSizeSp * zoomChange).coerceIn(MIN_FONT_SP, MAX_FONT_SP)
    }

    // 検索結果 (char index 範囲)。テキスト or クエリ変更で再計算するが、200ms debounce で
    // 入力中の毎キーストロークで全文検索を走らせない。
    val matches: List<IntRange> by produceState(initialValue = emptyList(), searchQuery, searchOpen, textState) {
        if (!searchOpen || searchQuery.isEmpty()) { value = emptyList(); return@produceState }
        snapshotFlow { textState.text.length }
            .distinctUntilChanged()
            .collectLatest {
                kotlinx.coroutines.delay(200)
                val text = withContext(Dispatchers.Default) { textState.text.toString() }
                value = withContext(Dispatchers.Default) { findMatches(text, searchQuery) }
                matchIndex = 0
            }
    }

    // 検索ヒットへキャレットを移動 → BasicTextField がそこへスクロールする。
    LaunchedEffect(matchIndex, matches) {
        if (matchIndex !in matches.indices) return@LaunchedEffect
        val range = matches[matchIndex]
        runCatching { textState.edit { selection = TextRange(range.first, range.last + 1) } }
    }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().transformable(transformState)) {
            EditorBody(
                state = textState,
                fontSizeSp = fontSizeSp,
                wordWrap = wordWrap,
                showLineNumbers = showLineNumbers,
            )
        }

        if (searchOpen) {
            HorizontalDivider()
            SearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                matchCount = matches.size,
                currentIndex = matchIndex,
                onNext = { if (matches.isNotEmpty()) matchIndex = (matchIndex + 1) % matches.size },
                onPrev = { if (matches.isNotEmpty()) matchIndex = (matchIndex - 1 + matches.size) % matches.size },
                onClose = { searchOpen = false; searchQuery = "" },
            )
        }

        HorizontalDivider()
        EditorBottomBar(
            charsetName = charset.name(),
            fontSizeSp = fontSizeSp,
            status = status,
            canUndo = textState.undoState.canUndo,
            canRedo = textState.undoState.canRedo,
            saving = saving,
            wordWrap = wordWrap,
            showLineNumbers = showLineNumbers,
            searchOpen = searchOpen,
            onToggleWrap = { wordWrap = !wordWrap },
            onToggleGutter = { showLineNumbers = !showLineNumbers },
            onSearch = { searchOpen = !searchOpen; if (!searchOpen) searchQuery = "" },
            onUndo = { status = null; textState.undoState.undo() },
            onRedo = { status = null; textState.undoState.redo() },
            onSave = {
                saving = true
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching { file.writeBytes(textState.text.toString().toByteArray(charset)) }.isSuccess
                    }
                    status = if (ok) "保存しました" else "保存に失敗しました"
                    saving = false
                }
            },
        )
    }
}

/**
 * 編集本体。BasicTextField の `scrollState` を Gutter と共有することで
 * スクロール位置を完全同期させ、Gutter は BasicTextField の [TextLayoutResult] を
 * 元に Canvas で行番号を描画する (= ワードラップ時も論理行と差分なしで揃う)。
 *
 * パフォーマンス対策:
 *  - `state.text.toString()` を毎 recomposition で呼ぶと 4MB 級では数十 ms かかる
 *    のでフレームスキップ要因になる。代わりに「論理行の先頭 char offset」を
 *    [snapshotFlow] + 200ms `debounce` で background 計算し、Gutter に渡す。
 *  - GutterCanvas は表示中の visual line だけ走査し、`tl.getLineForVerticalPosition`
 *    で開始位置を二分検索 (= O(log N))。論理行番号も IntArray の binarySearch で求める。
 */
@Composable
private fun EditorBody(
    state: TextFieldState,
    fontSizeSp: Float,
    wordWrap: Boolean,
    showLineNumbers: Boolean,
) {
    val color = LocalContentColor.current
    val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSizeSp.sp, color = color)
    val gutterStyle = mono.copy(color = gutterColor, textAlign = TextAlign.End)
    val measurer = rememberTextMeasurer()

    val sharedScroll = rememberScrollState()
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }

    // 論理行の先頭 offset を IntArray で保持。debounce で 200ms 入力が落ち着いてから更新。
    var lineStarts by remember { mutableStateOf(intArrayOf(0)) }
    LaunchedEffect(state) {
        snapshotFlow { state.text.length }
            .distinctUntilChanged()
            .collectLatest { _ ->
                kotlinx.coroutines.delay(200)
                val snapshot = state.text.toString()
                val computed = withContext(Dispatchers.Default) { computeLineStarts(snapshot) }
                lineStarts = computed
            }
    }

    val logicalLineCount = lineStarts.size
    val density = LocalDensity.current
    val gutterWidth: Dp = remember(fontSizeSp, logicalLineCount) {
        // 等幅フォントの 1 文字幅は概ね fontSize × 0.6 sp。+ 左右パディング。
        val digits = logicalLineCount.toString().length.coerceAtLeast(2)
        with(density) {
            (fontSizeSp.sp.toPx() * 0.62f * digits).toDp() + 12.dp
        }
    }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            if (showLineNumbers) {
                GutterCanvas(
                    lineStarts = lineStarts,
                    textLayout = textLayout,
                    scrollOffsetPx = sharedScroll.value,
                    style = gutterStyle,
                    measurer = measurer,
                    modifier = Modifier.fillMaxHeight().width(gutterWidth)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                )
            }
            val editorModifier = Modifier.fillMaxSize().padding(horizontal = if (showLineNumbers) 6.dp else 12.dp, vertical = 4.dp)
            if (wordWrap) {
                BasicTextField(
                    state = state,
                    textStyle = mono,
                    lineLimits = TextFieldLineLimits.MultiLine(),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    onTextLayout = { getResult -> textLayout = getResult() },
                    scrollState = sharedScroll,
                    modifier = editorModifier,
                )
            } else {
                // 折り返し OFF: 横スクロール可。縦スクロールは sharedScroll で gutter と同期。
                Box(editorModifier.horizontalScroll(rememberScrollState())) {
                    BasicTextField(
                        state = state,
                        textStyle = mono,
                        lineLimits = TextFieldLineLimits.MultiLine(),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        onTextLayout = { getResult -> textLayout = getResult() },
                        scrollState = sharedScroll,
                        modifier = Modifier.width(8000.dp),
                    )
                }
            }
        }
        // 右端の縦スクロールバー (スクロール可能なときだけ表示)。
        if (sharedScroll.maxValue > 0) {
            EditorScrollbar(
                scrollState = sharedScroll,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

/** テキストを 1 度だけ走査して論理行 (= '\n' 区切り) の先頭 offset を IntArray で返す。 */
private fun computeLineStarts(text: String): IntArray {
    // 最初は 0 から始まる。容量は `text.length / 30 + 1` 程度の概算。
    val initial = (text.length / 30 + 1).coerceAtLeast(8)
    var arr = IntArray(initial)
    arr[0] = 0
    var size = 1
    for (i in text.indices) {
        if (text[i] == '\n') {
            if (size == arr.size) arr = arr.copyOf(arr.size * 2)
            arr[size++] = i + 1
        }
    }
    return arr.copyOf(size)
}

/** BasicTextField の縦スクロールに追従する操作可能スクロールバー。
 *
 * 右端 28dp 幅の透明な当たり判定エリアをドラッグで掴め、つまみを動かすと
 * 対応する位置まで `scrollState.scrollTo` で飛ばす。FastScrollbar と同じ流儀。
 */
@Composable
private fun EditorScrollbar(scrollState: androidx.compose.foundation.ScrollState, modifier: Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    val visibleNow = remember { mutableStateOf(false) }
    LaunchedEffect(scrollState.isScrollInProgress, scrollState.value, dragging) {
        if (scrollState.isScrollInProgress || dragging) {
            visibleNow.value = true
        } else if (visibleNow.value) {
            kotlinx.coroutines.delay(1200)
            if (!scrollState.isScrollInProgress && !dragging) visibleNow.value = false
        }
    }
    val alpha = if (visibleNow.value || dragging) 0.7f else 0.25f

    fun scrollToFraction(fraction: Float) {
        val target = (scrollState.maxValue * fraction.coerceIn(0f, 1f)).toInt()
        scope.launch { scrollState.scrollTo(target) }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(28.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        scrollToFraction(offset.y / h)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        scrollToFraction(change.position.y / h)
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                )
            },
    ) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 2.dp)
                .fillMaxHeight()
                .width(if (dragging) 10.dp else 4.dp),
        ) {
            val total = scrollState.maxValue.toFloat() + size.height
            if (total <= 0f) return@Canvas
            val ratio = (size.height / total).coerceIn(0.05f, 1f)
            val thumbHeight = (size.height * ratio).coerceAtLeast(24.dp.toPx())
            val track = (size.height - thumbHeight).coerceAtLeast(0f)
            val pos = if (scrollState.maxValue == 0) 0f
                      else (scrollState.value.toFloat() / scrollState.maxValue) * track
            drawRoundRect(
                color = primary.copy(alpha = if (dragging) 1f else alpha),
                topLeft = androidx.compose.ui.geometry.Offset(0f, pos),
                size = androidx.compose.ui.geometry.Size(size.width, thumbHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
            )
        }
    }
}

/**
 * 行番号を Canvas で描画する gutter。
 *
 * 表示中の visual line だけを走査することで、巨大ファイルでもフレームを落とさない:
 *  1. `tl.getLineForVerticalPosition(scrollOffsetPx)` で先頭の visual line を二分検索 (O(log N))。
 *  2. 各 visual line の char start を `tl.getLineStart` で取り、IntArray に対し binarySearch で論理行番号を解決。
 *  3. visual line が論理行の先頭と一致するときだけ番号を描く (= ワードラップで折り返した行は無番号)。
 */
@Composable
private fun GutterCanvas(
    lineStarts: IntArray,
    textLayout: TextLayoutResult?,
    scrollOffsetPx: Int,
    style: TextStyle,
    measurer: TextMeasurer,
    modifier: Modifier,
) {
    androidx.compose.foundation.Canvas(modifier = modifier.padding(end = 6.dp, top = 4.dp)) {
        val tl = textLayout ?: return@Canvas
        val visibleHeight = size.height
        val visualLineCount = tl.lineCount
        if (visualLineCount <= 0) return@Canvas
        // 先頭の visible visual line。getLineForVerticalPosition は O(log N)。
        val firstVisible = runCatching {
            tl.getLineForVerticalPosition(scrollOffsetPx.toFloat().coerceAtLeast(0f))
        }.getOrNull() ?: 0

        var v = firstVisible
        while (v < visualLineCount) {
            val top = tl.getLineTop(v) - scrollOffsetPx
            if (top > visibleHeight) break
            val charStart = tl.getLineStart(v)
            // この visual line が「論理行の先頭」と一致しているか?
            val idx = lineStarts.binarySearch(charStart)
            if (idx >= 0) {
                val label = (idx + 1).toString()
                val layout = measurer.measure(label, style = style)
                val x = (size.width - layout.size.width).coerceAtLeast(0f)
                drawText(textLayoutResult = layout, topLeft = Offset(x, top))
            }
            v++
        }
    }
}

@Composable
private fun EditorBottomBar(
    charsetName: String,
    fontSizeSp: Float,
    status: String?,
    canUndo: Boolean,
    canRedo: Boolean,
    saving: Boolean,
    wordWrap: Boolean,
    showLineNumbers: Boolean,
    searchOpen: Boolean,
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
            // 薄い status 行 (charset / フォントサイズ / 保存メッセージ)。
            Text(
                text = status ?: "$charsetName  ·  ${fontSizeSp.toInt()}sp",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            )
            // ボタン行: weight(1f) で均等配置、幅をフルに使う。
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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

/** プレーンテキスト検索。case-insensitive で出現位置 (char index 範囲) を全部返す。 */
internal fun findMatches(text: String, query: String): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val q = query.lowercase()
    val src = text.lowercase()
    val out = ArrayList<IntRange>()
    var i = 0
    while (true) {
        val idx = src.indexOf(q, i)
        if (idx < 0) break
        out.add(idx until idx + q.length)
        i = idx + q.length
    }
    return out
}
