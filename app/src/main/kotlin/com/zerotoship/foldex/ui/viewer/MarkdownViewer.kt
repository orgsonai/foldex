// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.viewer

import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zerotoship.foldex.ui.components.FastScrollbar
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_BYTES = 2L * 1024 * 1024

/** Markwon による Markdown の内蔵レンダリング。大きすぎる場合はテキスト扱いにフォールバック。 */
@Composable
fun MarkdownViewer(file: File, modifier: Modifier = Modifier) {
    val source by produceState<String?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (file.length() > MAX_BYTES) null
                else file.readBytes().let { String(it, TextDecoding.detect(it)) }
            }.getOrNull()
        }
    }
    when (val md = source) {
        null -> {
            // 読み込み中 or 大きすぎ → プレーンテキストビューアにフォールバック (こちらが大小判定も表示する)
            if (file.length() > MAX_BYTES) TextViewer(file, modifier = modifier)
            else Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        else -> {
            val onSurface = MaterialTheme.colorScheme.onSurface.toArgb()
            // verticalScroll の状態を hoist して、掴めるファストスクロールバーと共有する。
            val scrollState = rememberScrollState()
            Box(modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp),
                    factory = { ctx ->
                        TextView(ctx).apply {
                            setTextColor(onSurface)
                            setTextIsSelectable(true)
                        }
                    },
                    update = { tv ->
                        // 表 (GFM tables) / 取り消し線 / タスクリスト / リンク自動化を有効化。
                        val markwon = Markwon.builder(tv.context)
                            .usePlugin(TablePlugin.create(tv.context))
                            .usePlugin(StrikethroughPlugin.create())
                            .usePlugin(TaskListPlugin.create(tv.context))
                            .usePlugin(LinkifyPlugin.create())
                            .build()
                        markwon.setMarkdown(tv, md)
                    },
                )
                FastScrollbar(scrollState, Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}
