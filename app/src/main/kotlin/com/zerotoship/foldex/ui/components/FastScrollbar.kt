package com.zerotoship.foldex.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 縦スクロール用ファストスクローラの中核実装。リスト/グリッドの状態を関数で受け取り、
 * 表示やドラッグ操作の共通部分を担う。`BoxScope` 内で `Modifier.align(...)` を渡して重ねる想定。
 *
 * - 通常時は右端に細いトラックを薄く表示し、スクロールが止まると数秒でフェードアウト。
 * - 右端付近 (太めの当たり判定) をつかんで上下にドラッグするとつまみが拡大し、その位置までジャンプ。
 *   軽い左スワイプでも「つかむ」判定になる。
 * - ドラッグ中は現在位置ラベルを左側にオーバーレイ表示。
 */
@Composable
private fun FastScrollbarCore(
    itemCount: Int,
    firstVisibleIndex: () -> Int,
    visibleItemCount: () -> Int,
    isScrollInProgress: () -> Boolean,
    scrollToIndex: suspend (Int) -> Unit,
    modifier: Modifier,
    labelProvider: ((Int) -> String)?,
) {
    if (itemCount <= 1) return

    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragIndex by remember { mutableIntStateOf(0) }
    var lastActiveAt by remember { mutableLongStateOf(0L) }

    val scrolling = isScrollInProgress()
    var visibleNow by remember { mutableStateOf(false) }
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) {
            lastActiveAt = System.currentTimeMillis()
            visibleNow = true
        } else if (lastActiveAt != 0L) {
            delay(1400)
            if (!isScrollInProgress() && !dragging) visibleNow = false
        }
    }

    val alpha by animateFloatAsState(
        if (visibleNow || dragging) 1f else 0f,
        tween(220),
        label = "fastScrollbarAlpha",
    )
    val thumbWidth by animateDpAsState(if (dragging) 10.dp else 4.dp, tween(150), label = "fastScrollbarWidth")
    if (alpha <= 0.01f && !dragging) return

    val density = LocalDensity.current
    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .width(28.dp)
            .zIndex(2f)
            .pointerInput(itemCount) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        dragIndex = ((offset.y / h).coerceIn(0f, 1f) * (itemCount - 1)).roundToInt()
                        scope.launch { scrollToIndex(dragIndex) }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        val idx = ((change.position.y / h).coerceIn(0f, 1f) * (itemCount - 1)).roundToInt()
                        if (idx != dragIndex) {
                            dragIndex = idx
                            scope.launch { scrollToIndex(idx) }
                        }
                    },
                    onDragEnd = { dragging = false; lastActiveAt = System.currentTimeMillis() },
                    onDragCancel = { dragging = false; lastActiveAt = System.currentTimeMillis() },
                )
            },
    ) {
        val trackHeightPx = with(density) { maxHeight.toPx() }
        val thumbFraction = (visibleItemCount().coerceAtLeast(1).toFloat() / itemCount).coerceIn(0.04f, 1f)
        val thumbHeight = (maxHeight * thumbFraction).coerceAtLeast(24.dp)
        val denom = (itemCount - 1).coerceAtLeast(1)
        val posFraction = (if (dragging) dragIndex.toFloat() / denom else firstVisibleIndex().toFloat() / denom)
            .coerceIn(0f, 1f)
        val maxOffsetPx = (trackHeightPx - with(density) { thumbHeight.toPx() }).coerceAtLeast(0f)
        val thumbOffsetDp = with(density) { (maxOffsetPx * posFraction).toDp() }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 2.dp)
                .offset(y = thumbOffsetDp)
                .width(thumbWidth)
                .height(thumbHeight)
                .clip(RoundedCornerShape(50))
                .background(
                    MaterialTheme.colorScheme.primary.copy(
                        alpha = (if (dragging) 1f else 0.5f * alpha).coerceIn(0f, 1f),
                    ),
                ),
        )

        if (dragging) {
            val label = labelProvider?.invoke(dragIndex) ?: "${dragIndex + 1} / $itemCount"
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 3.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-40).dp, y = thumbOffsetDp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** [LazyListState] 向けのファストスクローラ。`BoxScope` 内で `Modifier.align(Alignment.CenterEnd)` を渡す。 */
@Composable
fun FastScrollbar(
    listState: LazyListState,
    itemCount: Int,
    modifier: Modifier = Modifier,
    labelProvider: ((Int) -> String)? = null,
) {
    val first by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    val visible by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.size } }
    FastScrollbarCore(
        itemCount = itemCount,
        firstVisibleIndex = { first },
        visibleItemCount = { visible },
        isScrollInProgress = { listState.isScrollInProgress },
        scrollToIndex = { listState.scrollToItem(it.coerceIn(0, (itemCount - 1).coerceAtLeast(0))) },
        modifier = modifier,
        labelProvider = labelProvider,
    )
}

/** [LazyGridState] 向けのファストスクローラ。 */
@Composable
fun FastScrollbar(
    gridState: LazyGridState,
    itemCount: Int,
    modifier: Modifier = Modifier,
    labelProvider: ((Int) -> String)? = null,
) {
    val first by remember { derivedStateOf { gridState.firstVisibleItemIndex } }
    val visible by remember { derivedStateOf { gridState.layoutInfo.visibleItemsInfo.size } }
    FastScrollbarCore(
        itemCount = itemCount,
        firstVisibleIndex = { first },
        visibleItemCount = { visible },
        isScrollInProgress = { gridState.isScrollInProgress },
        scrollToIndex = { gridState.scrollToItem(it.coerceIn(0, (itemCount - 1).coerceAtLeast(0))) },
        modifier = modifier,
        labelProvider = labelProvider,
    )
}
