package com.zerotoship.foldex.ui.viewer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_BYTES = 2L * 1024 * 1024 // 2MB を超えたら開かない (HANDOFF §10-D)

private sealed interface TextLoad {
    data object Loading : TextLoad
    data class Loaded(val content: String, val charset: String) : TextLoad
    data class TooLarge(val size: Long) : TextLoad
    data class Failed(val message: String) : TextLoad
}

/** 等幅フォントの簡易テキストビューア。文字コードは自動判定。編集は P7 後続 (TextEditor) で。 */
@Composable
fun TextViewer(file: File, modifier: Modifier = Modifier) {
    val state by produceState<TextLoad>(TextLoad.Loading, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val len = file.length()
                if (len > MAX_BYTES) return@runCatching TextLoad.TooLarge(len)
                val bytes = file.readBytes()
                val charset = TextDecoding.detect(bytes)
                TextLoad.Loaded(String(bytes, charset), charset.name())
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
        is TextLoad.Loaded -> Column(modifier.fillMaxSize()) {
            Text(
                text = "文字コード: ${s.charset}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            SelectionContainer(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                Text(
                    text = s.content,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
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
