package com.zerotoship.foldex.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// この件数以下のリストにはファストスクローラを出さない (短いリストでは不要)。
private const val MIN_ITEMS_FOR_SCROLLBAR = 20

/**
 * 縦スクロール用ファストスクローラの中核実装。
 *
 * パフォーマンス上の要点: スクロール位置 (`firstVisibleIndex`) は **レイアウト段階で読む**
 * (`Modifier.offset { }` のラムダ内) ことで、スクロール中に再コンポーズが発生しないようにする。
 * 以前は `BoxWithConstraints` の中で位置を読んでいたため毎フレームでサブコンポジションが
 * 走り、スクロールがもたついていた。
 *
 * - 通常時は右端に細いトラックを薄く表示し、スクロールが止まると数秒でフェードアウト。
 * - 右端付近 (太めの当たり判定) をつかんで上下にドラッグするとその位置までジャンプ。
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
    if (itemCount < MIN_ITEMS_FOR_SCROLLBAR) return

    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragIndex by remember { mutableIntStateOf(0) }
    var lastActiveAt by remember { mutableLongStateOf(0L) }
    var trackHeightPx by remember { mutableFloatStateOf(0f) }

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
    if (alpha <= 0.01f && !dragging) return

    val denom = (itemCount - 1).coerceAtLeast(1)
    val thumbFraction = (visibleItemCount().coerceAtLeast(1).toFloat() / itemCount).coerceIn(0.05f, 1f)
    val primary = MaterialTheme.colorScheme.primary

    fun thumbOffsetY(): Int {
        if (trackHeightPx <= 0f) return 0
        val maxOff = (trackHeightPx * (1f - thumbFraction)).coerceAtLeast(0f)
        val pf = (if (dragging) dragIndex.toFloat() else firstVisibleIndex().toFloat()) / denom
        return (maxOff * pf.coerceIn(0f, 1f)).roundToInt()
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(28.dp)
            .zIndex(2f)
            .onSizeChanged { trackHeightPx = it.height.toFloat() }
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
        // つまみ。位置は offset ラムダ (= レイアウト段階) で計算するのでスクロール中に再コンポーズしない。
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 2.dp)
                .offset { IntOffset(0, thumbOffsetY()) }
                .fillMaxHeight(thumbFraction)
                .heightIn(min = 24.dp)
                .width(if (dragging) 10.dp else 4.dp)
                .background(
                    primary.copy(alpha = (if (dragging) 1f else 0.5f * alpha).coerceIn(0f, 1f)),
                    RoundedCornerShape(50),
                ),
        )

        if (dragging) {
            val label = labelProvider?.invoke(dragIndex) ?: "${dragIndex + 1} / $itemCount"
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = primary,
                shadowElevation = 3.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(-40.dp.roundToPx(), thumbOffsetY()) },
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
    // visibleItemsInfo は毎フレーム変わるので derivedStateOf で「件数が変わったときだけ」に絞る。
    val visibleCount by remember(listState) { derivedStateOf { listState.layoutInfo.visibleItemsInfo.size } }
    FastScrollbarCore(
        itemCount = itemCount,
        firstVisibleIndex = { listState.firstVisibleItemIndex },
        visibleItemCount = { visibleCount },
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
    val visibleCount by remember(gridState) { derivedStateOf { gridState.layoutInfo.visibleItemsInfo.size } }
    FastScrollbarCore(
        itemCount = itemCount,
        firstVisibleIndex = { gridState.firstVisibleItemIndex },
        visibleItemCount = { visibleCount },
        isScrollInProgress = { gridState.isScrollInProgress },
        scrollToIndex = { gridState.scrollToItem(it.coerceIn(0, (itemCount - 1).coerceAtLeast(0))) },
        modifier = modifier,
        labelProvider = labelProvider,
    )
}
