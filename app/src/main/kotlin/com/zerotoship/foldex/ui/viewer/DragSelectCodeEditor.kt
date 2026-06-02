package com.zerotoship.foldex.ui.viewer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import io.github.rosemoe.sora.util.IntPair
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow
import kotlin.math.abs

/**
 * 標準の Sora [CodeEditor] に「長押し → 指を離さず、そのままドラッグで範囲選択」を足したエディタ。
 *
 * 素の Sora は長押しで単語を選択するだけで、範囲を広げるには一度指を離して選択ハンドルを
 * 掴み直す必要があった。本クラスは長押し成立後の指の移動をそのまま選択範囲の拡張に割り当てて
 * 掴み直しを不要にする。あわせて長押し判定を自前のタイマーで行うことで、「長押ししても選択
 * されない」ばらつきも抑える。
 *
 * 通常のタップ/スクロール/ピンチ/ハンドル操作はすべて [CodeEditor] (super) にそのまま委ねる。
 */
class DragSelectCodeEditor(context: Context) : CodeEditor(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    private val textAction by lazy { getComponent(EditorTextActionWindow::class.java) }

    private var downX = 0f
    private var downY = 0f
    private var dragSelecting = false
    private var anchorLine = 0
    private var anchorColumn = 0

    private val longPressRunnable = Runnable { beginDragSelect() }

    private fun beginDragSelect() {
        // ハンドルを掴んでいる/ピンチ中なら長押し選択は始めない (誤作動防止)。
        val eh = eventHandler
        if (eh.hasAnyHeldHandle() || eh.isScaling) return
        val pos = getPointPositionOnScreen(downX, downY)
        // まず指の位置の単語を選択して起点 (アンカー) を確定する。以降の移動でここから伸ばす。
        selectWord(IntPair.getFirst(pos), IntPair.getSecond(pos))
        anchorLine = cursor.leftLine
        anchorColumn = cursor.leftColumn
        dragSelecting = true
        // ドラッグ中はコピー等のメニューが追従して邪魔なので、いったん閉じて指を離した時に出す。
        if (textAction.isShowing) textAction.dismiss()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                dragSelecting = false
                handler.removeCallbacks(longPressRunnable)
                handler.postDelayed(longPressRunnable, longPressTimeout)
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragSelecting) {
                    extendSelectionTo(e.x, e.y)
                    return true
                }
                // 長押し成立前に指が動いたら、スクロール等とみなして長押しをキャンセル。
                if (abs(e.x - downX) > touchSlop || abs(e.y - downY) > touchSlop) {
                    handler.removeCallbacks(longPressRunnable)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (dragSelecting) {
                    dragSelecting = false
                    // 指を離した位置の選択範囲に対してコピー等のメニューを出す。
                    if (e.actionMasked == MotionEvent.ACTION_UP && cursor.isSelected) {
                        textAction.displayWindow()
                    }
                    return true
                }
            }
        }
        return super.onTouchEvent(e)
    }

    private fun extendSelectionTo(x: Float, y: Float) {
        val pos = getPointPositionOnScreen(x, y)
        val line = IntPair.getFirst(pos)
        val column = IntPair.getSecond(pos)
        // アンカーと現在位置の前後関係を整えてから範囲指定する (左へ逆ドラッグしても破綻しないように)。
        val anchorFirst = anchorLine < line || (anchorLine == line && anchorColumn <= column)
        val sl = if (anchorFirst) anchorLine else line
        val sc = if (anchorFirst) anchorColumn else column
        val el = if (anchorFirst) line else anchorLine
        val ec = if (anchorFirst) column else anchorColumn
        // makeRightVisible=false: 1 文字動くたびに自動スクロールで画面が飛ぶのを防ぐ。
        runCatching { setSelectionRegion(sl, sc, el, ec, false) }
    }
}
