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
 * 各ページ独立に「2 指ピンチでズーム / ダブルタップで拡大」。
 *
 * 重要: 各ページの zoom/pan は `detectTransformGestures` を使う。
 * これは **2 指以上のジェスチャ** が来るまで反応しないので、1 指の左右ドラッグは
 * 外側の [HorizontalPager] に流れる。旧実装の `Modifier.transformable` は
 * 1 指 pan も検出していたため pager のスワイプを阻害していた。
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

/** 1 枚の画像。2 指ピンチで zoom、拡大中の 1 指 pan、ダブルタップでトグル拡大。
 *
 * 注意: Compose 標準の `detectTransformGestures` は (公式 KDoc に反して) 1 指 pan も
 * 検出して consume するため、外側の HorizontalPager の左右スワイプが効かなくなる。
 * ここでは `awaitEachGesture` を直接使い、**ポインタが 2 本以上のときだけ** zoom/pan を
 * 適用 + consume する。1 指ジェスチャは consume しないので Pager に届く。
 *
 * scale > 1 (拡大中) のときは 1 指 pan を「画像を動かす」用途に使うのも自然だが、
 * その挙動だと「拡大したまま隣の画像に切り替えたい」が永遠にできなくなるので、
 * 拡大中であっても 1 指ドラッグは Pager に渡す方針にする (拡大は 2 指で続けて pan 可)。
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
                    // 最初の指の down を待つ (consume しない → Pager にも流す)。
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val active = event.changes.count { it.pressed }
                        if (active >= 2) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoom != 1f || pan != Offset.Zero) {
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                if (scale > 1f) {
                                    offsetX += pan.x
                                    offsetY += pan.y
                                } else {
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                                // 2 指のときだけ consume — Pager に流れない。
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                        // 1 指のときは何も consume しない → Pager がスワイプを検出。
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
