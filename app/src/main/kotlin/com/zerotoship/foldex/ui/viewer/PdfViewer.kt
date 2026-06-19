// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.zerotoship.foldex.ui.viewer

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.zerotoship.foldex.ui.components.FastScrollbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

private const val TAG = "PdfViewer"

/**
 * Android 標準 [PdfRenderer] による PDF ビューア。
 *
 * - 縦スクロールでページを送る (連続スクロール、PDF リーダー風)
 * - ピンチズーム / パン
 * - ページは表示直前にレンダリング (LazyColumn の遅延描画)
 * - 暗号化 PDF は PdfRenderer が IOException で開かないのでメッセージ表示にフォールバック
 *
 * (HANDOFF §10-F: 「P7 以降に内蔵検討」)
 */
@Composable
fun PdfViewer(file: File, modifier: Modifier = Modifier) {
    // ParcelFileDescriptor + PdfRenderer のライフサイクル: produceState のスコープが終わる時
    // (Composable から外れる or key 変更) に awaitDispose で close する。
    // 旧実装の DisposableEffect(rendererState) は登録時の値ではなく現在値を見るため、
    // null → renderer に変わった直後に「前回登録の onDispose」が new renderer を close してしまい
    // 「Document already closed」例外を引き起こしていた。
    var error by remember(file) { mutableStateOf<String?>(null) }
    val rendererState by produceState<PdfRenderer?>(null, file) {
        val opened: PdfRenderer? = withContext(Dispatchers.IO) {
            try {
                android.util.Log.d(TAG, "Loading PDF: ${file.absolutePath}")
                if (!file.exists()) {
                    error = "ファイルが見つかりません: ${file.absolutePath}"
                    return@withContext null
                }
                if (!file.canRead()) {
                    error = "ファイルを読み込めません (権限なし): ${file.absolutePath}"
                    return@withContext null
                }
                android.util.Log.d(TAG, "File OK, size=${file.length()}, opening PFD…")
                withTimeout(30_000) {
                    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val r = try {
                        PdfRenderer(pfd)
                    } catch (t: Throwable) {
                        runCatching { pfd.close() }
                        throw t
                    }
                    android.util.Log.d(TAG, "PdfRenderer ready: pageCount=${r.pageCount}")
                    r
                }
            } catch (t: TimeoutCancellationException) {
                android.util.Log.e(TAG, "Open timed out for ${file.absolutePath}")
                error = "PDF を開くのに時間がかかりすぎました (30秒)。"
                null
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "Failed to open PDF: ${t.javaClass.simpleName}: ${t.message}", t)
                error = when {
                    t.message?.contains("password", ignoreCase = true) == true ->
                        "暗号化された PDF は内蔵ビューアで開けません。別のアプリで開いてください。"
                    else ->
                        "PDF を開けませんでした: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}"
                }
                null
            }
        }
        value = opened
        // awaitDispose は produceState のスコープが終わる (= Composable から外れる) 時に
        // 必ず呼ばれる。ここでだけ close することで二重 close / 早期 close を回避する。
        awaitDispose { runCatching { opened?.close() } }
    }

    val renderer = rendererState
    when {
        error != null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                error!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        renderer == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> PdfPageList(renderer, modifier)
    }
}

// LRU で直近 N ページの Bitmap を保持する簡易キャッシュ。
// 1080x1500 ARGB_8888 で約 6.5MB/page なので 12 で約 80MB。スクロールで戻ったときの再レンダリングを避ける。
private class PdfBitmapCache(private val maxSize: Int) {
    private val map = object : LinkedHashMap<Int, Bitmap>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>): Boolean = size > maxSize
    }

    @Synchronized
    fun get(index: Int): Bitmap? = map[index]

    @Synchronized
    fun put(index: Int, bitmap: Bitmap) {
        map[index] = bitmap
    }

    @Synchronized
    fun clear() {
        map.clear()
    }
}

@Composable
private fun PdfPageList(renderer: PdfRenderer, modifier: Modifier) {
    // pageCount は recomposition のたびに renderer 状態を問い合わせるので
    // remember で 1 回だけ取得。renderer が close されたあとに参照されないようにする保険。
    val pageCount = remember(renderer) { runCatching { renderer.pageCount }.getOrDefault(0) }
    if (pageCount == 0) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "ページがありません",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val listState = rememberLazyListState()

    // PdfRenderer はページを 1 枚ずつしか open できないので、レンダリング時にロックして取得する。
    val mutex = remember { Any() }

    // Bitmap キャッシュ。renderer 単位で 1 つ。Composable から外れたら clear。
    val cache = remember(renderer) { PdfBitmapCache(maxSize = 12) }
    DisposableEffect(cache) { onDispose { cache.clear() } }

    // ピンチズーム (全ページ共通)。
    // 注意: Compose 標準の detectTransformGestures / Modifier.transformable は 1 指 pan も
    // 検出・consume するため、LazyColumn の縦スクロールや FastScrollbar のドラッグを阻害する。
    // ここでは awaitEachGesture を直接使い、**2 指以上のときだけ** zoom/pan を適用 + consume する。
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val active = event.changes.count { it.pressed }
                        if (active >= 2) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoom != 1f || pan != Offset.Zero) {
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                if (scale > 1f) { offsetX += pan.x; offsetY += pan.y }
                                else { offsetX = 0f; offsetY = 0f }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) { scale = 1f; offsetX = 0f; offsetY = 0f }
                        else scale = 2.5f
                    },
                )
            },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY),
        ) {
            items(count = pageCount, key = { it }) { index ->
                PdfPageItem(renderer, mutex, cache, index, modifier = Modifier.fillMaxWidth())
            }
        }
        FastScrollbar(listState, pageCount, Modifier.align(Alignment.CenterEnd)) { idx ->
            "${idx + 1} / $pageCount"
        }
    }
}

@Composable
private fun PdfPageItem(
    renderer: PdfRenderer,
    mutex: Any,
    cache: PdfBitmapCache,
    index: Int,
    modifier: Modifier,
) {
    // 各ページは BG スレッドで Bitmap にレンダリング。表示サイズは画面幅基準で固定 (1080px 程度)。
    // キャッシュにあれば即座に返し、なければ render してキャッシュに保存。
    // produceState の初期値にキャッシュ値を渡すことで、再 composition 時に「ちらつき」なく即表示できる。
    val cached = cache.get(index)
    val bitmap by produceState<Bitmap?>(cached, index, renderer) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                synchronized(mutex) {
                    // 同期内で再チェック (別 item からの並行レンダリング後に当たることがある)
                    cache.get(index)?.let { return@synchronized it }
                    val page = renderer.openPage(index)
                    try {
                        val targetWidth = 1080
                        val ratio = page.height.toFloat() / page.width
                        val targetHeight = (targetWidth * ratio).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(AndroidColor.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        cache.put(index, bmp)
                        bmp
                    } finally {
                        page.close()
                    }
                }
            }.getOrNull()
        }
    }
    Box(modifier = modifier.padding(8.dp), contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        } ?: Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

