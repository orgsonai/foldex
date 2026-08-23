// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.settings

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.text.Selection
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Magnifier
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * ログ本文を「なぞって範囲選択できる」形で表示する。
 *
 * Compose の `SelectionContainer` では実機で 2 つ足りなかった:
 * 1. なぞりが画面の端に達しても自動スクロールしないので、映っている範囲までしか選べない。
 * 2. 長押し中に拡大鏡が出ないので、どこを選んでいるのか指で隠れて見えない。
 *
 * どちらも Compose 側からは手を入れられないため、Android 標準のテキスト選択
 * (`TextView.setTextIsSelectable`) に置き換える。選択ハンドルとコンテキストメニューは
 * OS が持っているものがそのまま出る。ただし [LogTextView] で 3 つ足している:
 * - **拡大鏡**。標準の TextView が拡大鏡を出すのは「選択ハンドルを掴んで動かしたとき」だけで、
 *   長押しからそのままなぞっている間は出ない。指で隠れて見えないので自前で出す。
 * - **上下の端の自動スクロール**。標準は画面の縁ぎりぎりまで指を運ばないと効かない。
 * - **左右の端での行頭 / 行末へのスナップ**。画面の左右の縁はシステムのジェスチャーに
 *   食われて指を置けないため、そこまで運ばないと行末が選べない。
 *
 * @param text 表示する本文 (色は [buildLogText] が付けた span をそのまま使う)。
 * @param autoScrollKey この値が変わったら末尾へスクロールする (新しい行が増えたとき)。
 */
@Composable
internal fun SelectableLogText(
    text: AnnotatedString,
    autoScrollKey: Any,
    textSizeSp: Float,
    defaultColorArgb: Int,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val edgePx = with(density) { EDGE_DP.dp.toPx() }
    val sideEdgePx = with(density) { SIDE_EDGE_DP.dp.toPx() }
    val padPx = with(density) { 8.dp.roundToPx() }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val body = LogTextView(context).apply {
                // 標準の選択 UI (長押し → なぞりで拡張・拡大鏡・ハンドル) を有効にする。
                setTextIsSelectable(true)
                typeface = Typeface.MONOSPACE
                setPadding(padPx, padPx / 2, padPx, padPx / 2)
            }
            ScrollView(context).apply {
                isFillViewport = true
                addView(
                    body,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        },
        update = { scroll ->
            val body = scroll.getChildAt(0) as LogTextView
            body.edgeSize = edgePx
            body.sideEdgeSize = sideEdgePx
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            body.setTextColor(defaultColorArgb)
            // 本文を毎回入れ直すと選択が消えるので、中身が変わったときだけ差し替える。
            if (body.source !== text) {
                body.source = text
                body.text = text.toSpannable()
            }
            if (body.autoScrollKey != autoScrollKey) {
                body.autoScrollKey = autoScrollKey
                // レイアウトが終わってからでないと高さが確定しない。
                scroll.post { scroll.scrollTo(0, body.height) }
            }
        },
    )
}

/**
 * 選択をなぞっている指が上下の端に入ったら、指を止めていてもスクロールし続ける [TextView]。
 *
 * 指が止まると `ACTION_MOVE` が来なくなるので、こちらから一定間隔で親をスクロールし、
 * ずれた分だけ指の位置を読み替えて選択を伸ばし直す。
 * 選択ハンドルを掴み直したときの拡張は OS 側が受け持つ (そちらのタッチはここへ来ない)。
 */
private class LogTextView(context: Context) : TextView(context) {

    /** いま表示している本文。同じものを入れ直して選択を消さないための目印。 */
    var source: AnnotatedString? = null

    /** 末尾へスクロール済みかの目印。 */
    var autoScrollKey: Any? = null

    /** 上下の端から何 px 以内を「端」とみなすか。 */
    var edgeSize: Float = 0f

    /** 左右の端から何 px 以内で行頭 / 行末へスナップするか。 */
    var sideEdgeSize: Float = 0f

    /** 直近の指の位置 (この View の座標系)。 */
    private var lastX = 0f
    private var lastY = 0f

    private val parentScroll: ScrollView? get() = parent as? ScrollView

    /**
     * なぞっている最中に指の上へ出す拡大鏡。
     * 既定の [Magnifier] は指定した点の少し上に窓を出すので、指で隠れている部分が見える。
     */
    private val magnifier: Magnifier? by lazy {
        @Suppress("DEPRECATION")
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                runCatching { Magnifier.Builder(this).build() }.getOrNull()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                runCatching { Magnifier(this) }.getOrNull()
            else -> null
        }
    }

    private val edgeScroller = object : Runnable {
        override fun run() {
            val scroll = parentScroll ?: return
            if (!hasSelection()) return
            val step = edgeStep(scroll)
            if (step == 0) return
            val before = scroll.scrollY
            scroll.scrollBy(0, step)
            val moved = scroll.scrollY - before
            if (moved == 0) return
            // 指は画面上の同じ場所にあるので、スクロールした分だけこの View の座標ではずれる。
            lastY += moved
            extendSelectionTo(lastX, lastY)
            snapHorizontalEdge(lastX, lastY)
            // 中身が流れていくので拡大鏡も更新する。
            runCatching { magnifier?.show(lastX, lastY) }
            postDelayed(this, EDGE_INTERVAL_MS)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> removeCallbacks(edgeScroller)

            MotionEvent.ACTION_MOVE -> {
                lastX = event.x
                lastY = event.y
                removeCallbacks(edgeScroller)
                if (hasSelection()) {
                    // 標準は「ハンドルを掴んで動かすとき」しか拡大鏡を出さないので自前で出す。
                    runCatching { magnifier?.show(event.x, event.y) }
                    snapHorizontalEdge(event.x, event.y)
                    val scroll = parentScroll
                    if (scroll != null && edgeStep(scroll) != 0) {
                        postDelayed(edgeScroller, EDGE_INTERVAL_MS)
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(edgeScroller)
                runCatching { magnifier?.dismiss() }
            }
        }
        return handled
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(edgeScroller)
        runCatching { magnifier?.dismiss() }
        super.onDetachedFromWindow()
    }

    /**
     * 指が左右の端に来たら、その表示行の行頭 / 行末まで選択を伸ばす。
     *
     * 画面の左右の縁 (数十 px) はシステムの「戻る」ジェスチャーに取られていて指を置けないため、
     * そこまで運ばないと届かない行末が選べなかった。手前でスナップさせて届くようにする。
     * 標準の選択更新 (super) の後に呼ぶので、こちらの結果が残る。
     */
    private fun snapHorizontalEdge(x: Float, y: Float) {
        if (sideEdgeSize <= 0f) return
        val target = when {
            x > width - sideEdgeSize -> (width - paddingRight).toFloat()
            x < sideEdgeSize -> paddingLeft.toFloat()
            else -> return
        }
        extendSelectionTo(target, y)
    }

    /** 上下の端にいるなら 1 コマで何 px 進めるか (端に深く入るほど速い)。端でなければ 0。 */
    private fun edgeStep(scroll: ScrollView): Int {
        val visibleTop = scroll.scrollY.toFloat()
        val visibleBottom = visibleTop + scroll.height
        if (edgeSize <= 0f) return 0
        return when {
            lastY - visibleTop < edgeSize -> -stepPx(edgeSize - (lastY - visibleTop))
            visibleBottom - lastY < edgeSize -> stepPx(edgeSize - (visibleBottom - lastY))
            else -> 0
        }
    }

    private fun stepPx(depth: Float): Int =
        (depth / edgeSize * MAX_STEP_PX).toInt().coerceIn(MIN_STEP_PX, MAX_STEP_PX)

    private fun extendSelectionTo(x: Float, y: Float) {
        val spannable = text as? Spannable ?: return
        val offset = getOffsetForPosition(x, y)
        if (offset < 0) return
        runCatching { Selection.extendSelection(spannable, offset) }
    }
}

/**
 * Compose の [AnnotatedString] を [TextView] に渡せる [Spannable] へ変換する。
 * 色と太字だけを移す (ログ表示で使っているのはこの 2 つだけ)。
 */
private fun AnnotatedString.toSpannable(): Spannable {
    val out = SpannableStringBuilder(text)
    spanStyles.forEach { range ->
        val style = range.item
        if (style.color.isSpecified) {
            out.setSpan(
                ForegroundColorSpan(style.color.toArgb()),
                range.start,
                range.end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        val weight = style.fontWeight
        if (weight != null && weight.weight >= FontWeight.Bold.weight) {
            out.setSpan(
                StyleSpan(Typeface.BOLD),
                range.start,
                range.end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }
    return out
}

/**
 * 画面の端から何 dp を「端」とみなすか。
 * 標準の自動スクロールは縁ぎりぎりまで指を運ばないと効かず「端まで選べない」ので、
 * だいぶ内側から効くようにしてある。
 */
private const val EDGE_DP = 96f

/**
 * 左右の端から何 dp で行頭 / 行末へスナップするか。
 * 画面の左右の縁はシステムの「戻る」ジェスチャー領域で指を置けないため、その手前で効かせる。
 */
private const val SIDE_EDGE_DP = 32f

/** 端スクロールの 1 コマの間隔 (ms)。 */
private const val EDGE_INTERVAL_MS = 16L

/** 1 コマで進む px。端の手前ではゆっくり、縁に近いほど速くなる。 */
private const val MIN_STEP_PX = 2
private const val MAX_STEP_PX = 24
