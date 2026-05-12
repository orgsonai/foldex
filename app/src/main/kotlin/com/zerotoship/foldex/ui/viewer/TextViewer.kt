package com.zerotoship.foldex.ui.viewer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset

private const val MAX_BYTES = 2L * 1024 * 1024 // 2MB を超えたら開かない (HANDOFF §10-D)

private sealed interface TextLoad {
    data object Loading : TextLoad
    data class Loaded(val content: String, val charset: Charset) : TextLoad
    data class TooLarge(val size: Long) : TextLoad
    data class Failed(val message: String) : TextLoad
}

/** 等幅フォントの簡易テキストビューア。文字コードは自動判定。[editable] が true なら簡易編集→上書き保存。 */
@Composable
fun TextViewer(file: File, editable: Boolean = false, modifier: Modifier = Modifier) {
    val state by produceState<TextLoad>(TextLoad.Loading, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val len = file.length()
                if (len > MAX_BYTES) return@runCatching TextLoad.TooLarge(len)
                val bytes = file.readBytes()
                val charset = TextDecoding.detect(bytes)
                TextLoad.Loaded(String(bytes, charset), charset)
            }.getOrElse { TextLoad.Failed(it.message ?: "読み込みに失敗しました") }
        }
    }

    when (val s = state) {
        TextLoad.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is TextLoad.TooLarge -> CenterMessage(
            modifier,
            "ファイルが大きすぎます (${s.size / 1024}KB)。外部エディタで開いてください。",
        )
        is TextLoad.Failed -> CenterMessage(modifier, s.message)
        is TextLoad.Loaded -> LoadedText(file, s.content, s.charset, editable, modifier)
    }
}

@Composable
private fun LoadedText(file: File, initial: String, charset: Charset, editable: Boolean, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(initial) }
    var status by remember { mutableStateOf<String?>(null) }
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = MaterialTheme.typography.bodySmall.fontSize)

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = status ?: "文字コード: ${charset.name()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (editable) {
                if (editing) {
                    TextButton(onClick = { text = initial; editing = false; status = null }) { Text("取消") }
                    TextButton(onClick = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                runCatching { file.writeBytes(text.toByteArray(charset)) }.isSuccess
                            }
                            status = if (ok) "保存しました" else "保存に失敗しました"
                            if (ok) editing = false
                        }
                    }) {
                        androidx.compose.material3.Icon(Icons.Default.Save, null, Modifier.padding(end = 4.dp))
                        Text("保存")
                    }
                } else {
                    TextButton(onClick = { editing = true; status = null }) {
                        androidx.compose.material3.Icon(Icons.Default.Edit, null, Modifier.padding(end = 4.dp))
                        Text("編集")
                    }
                }
            }
        }

        if (editing) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = mono.copy(color = LocalContentColor.current),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        } else {
            SelectionContainer(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                Text(text = text, style = mono)
            }
        }
    }
}

@Composable
private fun CenterMessage(modifier: Modifier, message: String) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
