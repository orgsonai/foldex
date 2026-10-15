// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.viewer

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
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

/**
 * WebView による HTML 表示。JavaScript を有効にし、外部の CSS / JS / 画像や別ページへのリンクも読めるようにする。
 *
 * `file://` で直接開くと ES Modules・fetch・XHR が CORS で拒否されるため、
 * Android が予約しているローカル用ホスト ([LOCAL_HOST]) の https URL に見せかけ、
 * [WebViewClient.shouldInterceptRequest] で [HtmlSite] から中身を返す。
 * これで `../css/a.css` のような上位フォルダ参照も、ページ間リンクも普通のサイトと同じに動く。
 * WebView 自身のファイルアクセス (allowFileAccess) は無効のまま。
 *
 * [site] を省くと端末内のファイルとして [file] の周りを読む。SAF・NAS は [StorageHtmlSite] を渡す。
 */
@Composable
internal fun HtmlViewer(file: File, modifier: Modifier = Modifier, site: HtmlSite? = null) {
    key(file, site) {
        val source = remember { site ?: LocalHtmlSite(file) }
        var webView by remember { mutableStateOf<WebView?>(null) }
        var canGoBack by remember { mutableStateOf(false) }

        // ページ内リンクで移動した後は、戻るボタンで前のページへ戻す
        BackHandler(enabled = canGoBack) { webView?.goBack() }

        AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    // AndroidView の既定 (WRAP_CONTENT) のままだと、WebView は中身に合わせて伸びる前提で高さを決め、
                    // CSS の 100vh / 100dvh が画面の高さにならない。高さを画面に合わせるページ (portal.html など) で
                    // 本文が縦に引き伸ばされ、ヘッダーとフッターしか見えなくなるため、親いっぱいを明示する。
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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
                            return serve(source, url)
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
                    loadUrl(siteUrl(source.startSegments))
                    webView = this
                }
            },
            onRelease = { it.destroy() },
        )
    }
}

/** Android がアプリ内ローカル配信用に予約しているホスト名 (実在サイトと衝突しない)。 */
private const val LOCAL_HOST = "appassets.androidplatform.net"

private fun siteUrl(segments: List<String>): String =
    Uri.Builder().scheme("https").authority(LOCAL_HOST).apply { segments.forEach { appendPath(it) } }.build().toString()

/** [url] のパスに当たるファイルを返す。見つからない・読めないものは 404。 */
private fun serve(site: HtmlSite, url: Uri): WebResourceResponse {
    val target = runCatching { site.open(url.pathSegments) }.getOrNull() ?: return notFound()
    val mime = mimeTypeOf(target.name)
    return runCatching {
        if (isText(mime)) {
            // Shift_JIS などの HTML / CSS / JS も文字化けしないよう、テキストは文字コードを判定して渡す
            val bytes = target.stream().use { it.readBytes() }
            WebResourceResponse(mime, TextDecoding.detect(bytes).name(), ByteArrayInputStream(bytes))
        } else {
            WebResourceResponse(mime, null, target.stream())
        }
    }.getOrElse { notFound() }
}

private fun notFound(): WebResourceResponse =
    WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

private fun mimeTypeOf(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
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
