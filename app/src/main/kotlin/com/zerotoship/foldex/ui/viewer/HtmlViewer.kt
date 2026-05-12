package com.zerotoship.foldex.ui.viewer

import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * WebView による HTML の簡易表示。
 * JavaScript はデフォルト無効、ファイルアクセスも無効。相対リソースは同一ディレクトリ基準で解決する。
 */
@Composable
fun HtmlViewer(file: File, modifier: Modifier = Modifier) {
    val html by produceState<String?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching { file.readBytes().let { String(it, TextDecoding.detect(it)) } }.getOrNull()
        }
    }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                @Suppress("SetJavaScriptEnabled")
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
            }
        },
        update = { web ->
            val content = html
            if (content != null) {
                val base = "file://${file.parentFile?.absolutePath ?: ""}/"
                web.loadDataWithBaseURL(base, content, "text/html", "UTF-8", null)
            }
        },
    )
}
