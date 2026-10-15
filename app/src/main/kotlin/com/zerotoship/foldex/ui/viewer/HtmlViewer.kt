// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.viewer

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zerotoship.foldex.R
import com.zerotoship.foldex.ui.common.setPlainText
import kotlinx.coroutines.launch
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
 *
 * ページの JavaScript のエラーは画面の上に件数を出し、押すと全文を見てコピーできる。
 * 端末から WebView のログを見る手段が無く、ブラウザでは動くのにここで動かないページの原因を探せないため。
 */
@Composable
internal fun HtmlViewer(file: File, modifier: Modifier = Modifier, site: HtmlSite? = null) {
    key(file, site) {
        val source = remember { site ?: LocalHtmlSite(file) }
        var webView by remember { mutableStateOf<WebView?>(null) }
        var canGoBack by remember { mutableStateOf(false) }
        val errors = remember { mutableStateListOf<String>() }
        var showErrors by remember { mutableStateOf(false) }

        // ページ内リンクで移動した後は、戻るボタンで前のページへ戻す
        BackHandler(enabled = canGoBack) { webView?.goBack() }

        Column(modifier.fillMaxSize()) {
            if (errors.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth().clickable { showErrors = true },
                ) {
                    Text(
                        stringResource(R.string.html_page_errors, errors.size, errors.first()),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        // AndroidView の既定 (WRAP_CONTENT) では、WebView が中身に合わせて伸びる前提で高さを決め、
                        // CSS の 100vh / 100dvh が表示枠の高さとずれることがある。表示枠いっぱいを明示する。
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
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR && errors.size < MAX_ERRORS) {
                                    val source = message.sourceId().substringAfterLast('/').ifEmpty { "?" }
                                    errors += "${message.message()} ($source:${message.lineNumber()})"
                                }
                                return super.onConsoleMessage(message)
                            }
                        }
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
        if (showErrors) PageErrorsDialog(errors.toList(), onDismiss = { showErrors = false })
    }
}

@Composable
private fun PageErrorsDialog(errors: List<String>, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.html_page_errors_title)
    val text = errors.joinToString("\n\n")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.verticalScroll(rememberScrollState()))
            }
        },
        confirmButton = {
            TextButton(onClick = { scope.launch { clipboard.setPlainText(title, text) } }) {
                Text(stringResource(R.string.action_copy))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

/** 同じエラーが繰り返し出るページで、一覧が際限なく伸びないようにする。 */
private const val MAX_ERRORS = 50

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
