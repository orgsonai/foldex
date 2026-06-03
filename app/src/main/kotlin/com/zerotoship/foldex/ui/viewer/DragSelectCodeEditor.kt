package com.zerotoship.foldex.ui.viewer

import android.content.Context
import android.view.MotionEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.util.IntPair
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow

/**
 * 標準の Sora [CodeEditor] に「長押し → 指を離さず、そのままドラッグで範囲選択」を足したエディタ。
 *
 * 素の Sora は長押しで単語を選択するだけで、範囲を広げるには一度指を離して選択ハンドルを掴み直す
 * 必要があった。本クラスは Sora が長押しで単語選択した瞬間 ([SelectionChangeEvent] の
 * [SelectionChangeEvent.CAUSE_LONG_PRESS]) に相乗りして「ドラッグ選択モード」へ入り、続く指の移動を
 * そのまま選択範囲の拡張に割り当てて掴み直しを不要にする。
 *
 * 自前タイマーで別途 selectWord する方式だと Sora の長押しと二重発火して競合する (= 範囲を伸ばした
 * 直後に単語選択へ戻ってしまう) ため、イベント駆動にして競合を避けている。
 *
 * 通常のタップ/スクロール/ピンチ/ハンドル操作はすべて [CodeEditor] (super) にそのまま委ねる。
 */
class DragSelectCodeEditor(context: Context) : CodeEditor(context) {

    private val textAction by lazy { getComponent(EditorTextActionWindow::class.java) }

    private var dragSelecting = false
    private var anchorLine = 0
    private var anchorColumn = 0

    init {
        subscribeAlways(SelectionChangeEvent::class.java) { e ->
            if (e.cause == SelectionChangeEvent.CAUSE_LONG_PRESS) {
                // 長押しで選択された単語の左端を起点 (アンカー) にして、以降の移動で範囲を伸ばす。
                anchorLine = e.left.line
                anchorColumn = e.left.column
                dragSelecting = true
                // ドラッグ中はコピー等のメニューが追従して邪魔なので、いったん閉じて指を離した時に出す。
                if (textAction.isShowing) textAction.dismiss()
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> dragSelecting = false

            MotionEvent.ACTION_MOVE -> if (dragSelecting) {
                extendSelectionTo(e.x, e.y)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragSelecting) {
                dragSelecting = false
                // Sora 側のタッチ状態 (エッジスクロール等) を正常に終了させてから、
                super.onTouchEvent(e)
                // 指を離した位置の選択範囲に対してコピー等のメニューを出す。
                if (e.actionMasked == MotionEvent.ACTION_UP && cursor.isSelected) {
                    textAction.displayWindow()
                }
                return true
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
