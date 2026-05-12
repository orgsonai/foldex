@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.zerotoship.foldex.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zerotoship.foldex.ui.components.FastScrollbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset

// 内蔵で開ける上限 (これより大きいと外部アプリ案内のみ)。
private const val MAX_BYTES = 4L * 1024 * 1024
// 内蔵エディタで編集可能な上限。これを超えるものは閲覧専用 (Compose の TextField は
// 巨大テキストで重くなるため。閲覧は 1 行ずつ遅延描画するので大きくても軽い)。
private const val EDITABLE_MAX_BYTES = 512L * 1024

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
                EditableText(file, s.content, s.charset, modifier)
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

private fun monoStyle(base: TextStyle, color: androidx.compose.ui.graphics.Color) =
    base.copy(fontFamily = FontFamily.Monospace, color = color)

@Composable
private fun EditableText(file: File, initial: String, charset: Charset, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val textState = rememberTextFieldState(initial)
    var status by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val mono = monoStyle(MaterialTheme.typography.bodySmall, LocalContentColor.current)

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = status ?: "文字コード: ${charset.name()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { status = null; textState.undoState.undo() },
                enabled = textState.undoState.canUndo,
            ) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "元に戻す") }
            IconButton(
                onClick = { status = null; textState.undoState.redo() },
                enabled = textState.undoState.canRedo,
            ) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "やり直す") }
            IconButton(
                onClick = {
                    saving = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching { file.writeBytes(textState.text.toString().toByteArray(charset)) }.isSuccess
                        }
                        status = if (ok) "保存しました" else "保存に失敗しました"
                        saving = false
                    }
                },
                enabled = !saving,
            ) { Icon(Icons.Default.Save, contentDescription = "保存") }
        }

        BasicTextField(
            state = textState,
            textStyle = mono,
            lineLimits = TextFieldLineLimits.MultiLine(),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxSize().padding(12.dp),
        )
    }
}

@Composable
private fun ReadOnlyText(content: String, charset: Charset, hint: String?, modifier: Modifier) {
    // 1 行ずつ LazyColumn で遅延描画する。1 つの巨大な Text にすると数十万行で固まるため。
    val lines = remember(content) { content.lines() }
    val mono = monoStyle(MaterialTheme.typography.bodySmall, LocalContentColor.current)
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

@Composable
private fun CenterMessage(modifier: Modifier, message: String) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
