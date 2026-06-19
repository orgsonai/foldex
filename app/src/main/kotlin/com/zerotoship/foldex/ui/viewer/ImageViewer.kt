// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.zerotoship.foldex.ui.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File
import kotlin.math.abs

/**
 * 1 枚版の互換 API。単独表示用に残してある (旧呼び出し元)。
 * 新しい呼び出しは [ImagePagerViewer] を使うこと。
 */
@Composable
fun ImageViewer(file: File, modifier: Modifier = Modifier) {
    ImagePagerViewer(
        paths = listOf(file.absolutePath),
        initialIndex = 0,
        onPageChanged = {},
        modifier = modifier,
    )
}

/**
 * 同フォルダの兄弟画像を [paths] で受け取り、左右スワイプで切り替えできるビューア。
 * 各ページ独立に「ピンチでズーム / ダブルタップで拡大 / 拡大中は 1 指 pan」。
 *
 * 拡大中の 1 指 pan は内側 ([ZoomableImage]) が消費する。端まで pan しきった先で
 * さらに横方向にドラッグすると未消費の差分が外側の [HorizontalPager] に渡って、
 * 前後の画像へスワイプできる (= ギャラリーアプリ標準挙動)。
 */
@Composable
fun ImagePagerViewer(
    paths: List<String>,
    initialIndex: Int,
    onPageChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (paths.isEmpty()) return
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, paths.lastIndex)) { paths.size }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect(onPageChanged)
    }
    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
    ) { page ->
        ZoomableImage(File(paths[page]), Modifier.fillMaxSize())
    }
}

/** 1 枚の画像。
 *
 * 操作:
 *  - 2 指ピンチ: zoom 0.5x〜6x。0.5〜1x を許容することで「縮小プレビュー」も可能。
 *  - 拡大中 (scale > 1) の 1 指ドラッグ: 画像を pan して動かす (画像端で止まる)。
 *    画像端まで pan しきった「先」の余剰ドラッグは consume せず外側 HorizontalPager に
 *    渡るので、ページめくりが自然に繋がる。
 *  - 非拡大時 (scale <= 1) の 1 指ドラッグ: 何も consume せず即 HorizontalPager へ。
 *  - ダブルタップ: scale 1↔2.5 をトグル。
 */
@Composable
private fun ZoomableImage(file: File, modifier: Modifier = Modifier) {
    var scale by remember(file) { mutableFloatStateOf(1f) }
    var offsetX by remember(file) { mutableFloatStateOf(0f) }
    var offsetY by remember(file) { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(file) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val active = event.changes.count { it.pressed }
                        when {
                            active >= 2 -> {
                                // 2 指ジェスチャ: zoom + pan を同時に処理し、必ず consume。
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (zoom != 1f || pan != Offset.Zero) {
                                    // 0.5x まで縮小、6x まで拡大を許容。
                                    scale = (scale * zoom).coerceIn(0.5f, 6f)
                                    // 拡大時のみ pan を反映 (等倍以下では中央固定)。
                                    if (scale > 1f) {
                                        val (maxX, maxY) = maxPan(size.width, size.height, scale)
                                        offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                                        offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                                    } else {
                                        offsetX = 0f
                                        offsetY = 0f
                                    }
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            }
                            active == 1 && scale > 1f -> {
                                // 拡大中の 1 指ドラッグ: 画像 pan。端で詰まった分は Pager に渡す。
                                val pan = event.calculatePan()
                                if (pan != Offset.Zero) {
                                    val (maxX, maxY) = maxPan(size.width, size.height, scale)
                                    val newX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                                    val newY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                                    val movedX = newX - offsetX
                                    val movedY = newY - offsetY
                                    offsetX = newX
                                    offsetY = newY
                                    // 画像が動いた = 自分で消化した分。横方向に余剰があるとき
                                    // (= 画像端を越えるドラッグ) は consume しない → Pager へ流す。
                                    val absorbedX = abs(movedX) >= abs(pan.x) - 0.5f
                                    if (absorbedX) {
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                    // 縦方向は Pager と干渉しないので、movedY のみで判断する必要はない。
                                    // 横方向が absorbed なら全体を consume、そうでないなら未 consume のまま
                                    // 残しておくと Pager が横スワイプとして拾う。
                                }
                            }
                            // 等倍以下の 1 指ドラッグは何も consume しない → Pager がスワイプ判定。
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(file) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f; offsetX = 0f; offsetY = 0f
                        } else {
                            scale = 2.5f
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(file).build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                ),
        )
    }
}

/**
 * ビューポート [width]x[height] (px) に対し、[scale] 倍に拡大したコンテンツが
 * 画面外にはみ出す最大量 (= 許容される pan 量) を返す。
 * Pair(maxOffsetX, maxOffsetY)。等倍以下なら (0,0)。
 */
private fun maxPan(width: Int, height: Int, scale: Float): Pair<Float, Float> {
    if (scale <= 1f) return 0f to 0f
    val mx = width * (scale - 1f) / 2f
    val my = height * (scale - 1f) / 2f
    return mx to my
}
