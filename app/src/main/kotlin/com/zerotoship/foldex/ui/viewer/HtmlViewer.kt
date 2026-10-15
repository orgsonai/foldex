// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.viewer

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

/**
 * WebView による HTML 表示。JavaScript を有効にし、外部の CSS / JS / 画像や別ページへのリンクも読めるようにする。
 *
 * `file://` で直接開くと ES Modules・fetch・XHR が CORS で拒否されるため、
 * Android が予約しているローカル用ホスト ([LOCAL_HOST]) の https URL に見せかけ、
 * [WebViewClient.shouldInterceptRequest] で [siteRoot] 配下の実ファイルを返す。
 * これで `../css/a.css` のような上位フォルダ参照も、ページ間リンクも普通のサイトと同じに動く。
 * WebView 自身のファイルアクセス (allowFileAccess) は無効のまま。
 */
@Composable
fun HtmlViewer(file: File, modifier: Modifier = Modifier) {
    key(file) {
        val root = remember { siteRoot(file) }
        var webView by remember { mutableStateOf<WebView?>(null) }
        var canGoBack by remember { mutableStateOf(false) }

        // ページ内リンクで移動した後は、戻るボタンで前のページへ戻す
        BackHandler(enabled = canGoBack) { webView?.goBack() }

        AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    @Suppress("SetJavaScriptEnabled")
                    settings.javaScriptEnabled = true
                    // localStorage / sessionStorage を使うページがあるため
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    // file:// で開いていた頃と同じく、http の外部リソース (CDN 等) も読めるようにする
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    // WebChromeClient が無いと alert / confirm / prompt が黙って無視される
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            val url = request.url
                            if (url.host != LOCAL_HOST) return null
                            return serveLocal(root, url)
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url
                            if (url.host == LOCAL_HOST) return false
                            // 外部サイト・mailto などは既定のアプリ (ブラウザ等) に任せる
                            try {
                                view.context.startActivity(Intent(Intent.ACTION_VIEW, url))
                            } catch (_: ActivityNotFoundException) {
                                // 開けるアプリが無ければ何もしない
                            }
                            return true
                        }

                        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                            canGoBack = view.canGoBack()
                        }
                    }
                    loadUrl(localUrl(root, file))
                    webView = this
                }
            },
            onRelease = { it.destroy() },
        )
    }
}

/** Android がアプリ内ローカル配信用に予約しているホスト名 (実在サイトと衝突しない)。 */
private const val LOCAL_HOST = "appassets.androidplatform.net"

/**
 * リンク先として読ませてよい最上位フォルダ。
 * 端末の共有ストレージ上なら、そのボリューム (`/storage/emulated/0` や SD カード) 全体。
 * キャッシュ実体 (リモート / 他アプリから受け取ったファイル) は開いたファイルのフォルダだけ。
 */
private fun siteRoot(file: File): File {
    val target = runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }
    val parts = target.path.split('/').filter { it.isNotEmpty() }
    val volume = when {
        parts.size > 3 && parts[0] == "storage" && parts[1] == "emulated" -> "/storage/emulated/${parts[2]}"
        parts.size > 2 && parts[0] == "storage" && parts[1] != "emulated" && parts[1] != "self" -> "/storage/${parts[1]}"
        parts.size > 2 && parts[0] == "sdcard" -> "/sdcard"
        else -> null
    }
    return File(volume ?: target.parentFile?.path ?: "/").canonicalFile
}

private fun localUrl(root: File, file: File): String {
    val relative = file.canonicalFile.path.removePrefix(root.path).trimStart('/')
    return Uri.Builder()
        .scheme("https")
        .authority(LOCAL_HOST)
        .path("/$relative")
        .build()
        .toString()
}

/** [url] のパスを [root] 配下の実ファイルとして返す。root の外や存在しないものは 404。 */
private fun serveLocal(root: File, url: Uri): WebResourceResponse {
    val relative = url.path.orEmpty().trimStart('/')
    val requested = runCatching { File(root, relative).canonicalFile }.getOrNull()
    val inside = requested != null &&
        (requested.path == root.path || requested.path.startsWith(root.path + File.separator))
    val target = when {
        !inside -> null
        requested!!.isDirectory -> File(requested, "index.html").takeIf { it.isFile }
        requested.isFile -> requested
        else -> null
    }
    if (target == null || !target.canRead()) return notFound()

    val mime = mimeTypeOf(target)
    return runCatching {
        if (isText(mime)) {
            // Shift_JIS などの HTML / CSS / JS も文字化けしないよう、テキストは文字コードを判定して渡す
            val bytes = target.readBytes()
            WebResourceResponse(mime, TextDecoding.detect(bytes).name(), ByteArrayInputStream(bytes))
        } else {
            WebResourceResponse(mime, null, FileInputStream(target))
        }
    }.getOrElse { notFound() }
}

private fun notFound(): WebResourceResponse =
    WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

private fun mimeTypeOf(file: File): String {
    val ext = file.extension.lowercase()
    // ES Modules は MIME が JavaScript でないと実行されないため、端末の対応表に頼らず固定する
    return when (ext) {
        "html", "htm" -> "text/html"
        "js", "mjs", "cjs" -> "text/javascript"
        "css" -> "text/css"
        "json", "map" -> "application/json"
        "svg" -> "image/svg+xml"
        "wasm" -> "application/wasm"
        "xml" -> "application/xml"
        "txt" -> "text/plain"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}

private fun isText(mime: String): Boolean =
    mime.startsWith("text/") || mime == "application/json" || mime == "application/xml" || mime == "image/svg+xml"
