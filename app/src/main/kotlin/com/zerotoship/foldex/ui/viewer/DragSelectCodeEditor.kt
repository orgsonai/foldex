// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.viewer

import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.widget.Magnifier as SystemMagnifier
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.util.IntPair
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow
import io.github.rosemoe.sora.widget.component.Magnifier

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
 * なぞっている指が画面の端に近づいたら、指を止めていても一定速度でスクロールし続ける
 * ([edgeScroller])。これが無いと「いま画面に映っている範囲」までしか選べない。
 *
 * なぞっている間は指の上に拡大鏡を出す ([showMagnifier])。指で隠れて選択位置が見えないため。
 * Sora 内蔵の拡大鏡は使わない (下の無効化のコメント参照) — あれは
 * `PixelCopy.request(Window, ...)` を自前で回す実装で落ちていた。ここで使うのは
 * フレームワーク標準の `android.widget.Magnifier` ([SystemMagnifier]) で、別経路。
 *
 * 通常のタップ/スクロール/ピンチ/ハンドル操作はすべて [CodeEditor] (super) にそのまま委ねる。
 */
class DragSelectCodeEditor(context: Context) : CodeEditor(context) {

    private val textAction by lazy { getComponent(EditorTextActionWindow::class.java) }

    private var dragSelecting = false
    private var anchorLine = 0
    private var anchorColumn = 0

    /** 直近の指の位置。指が止まっている間の端スクロールでも使うので覚えておく。 */
    private var lastX = 0f
    private var lastY = 0f

    /** 端から何 px 以内を「端」とみなすか。 */
    private val edgeSize = EDGE_DP * context.resources.displayMetrics.density

    /** なぞっている最中に指の上へ出す拡大鏡。行の高さが決まってから作るので遅延生成。 */
    private var magnifier: SystemMagnifier? = null
    private var magnifierPrepared = false

    /**
     * 指が端にある間だけ動き続けるスクロール。
     *
     * 指が止まると `ACTION_MOVE` が来なくなるので、こちらから一定間隔で
     * 「いま指が指している位置の少し先」を画面内に入れてやり、その都度選択を伸ばす。
     * 画面座標 ([lastX], [lastY]) は変わらないが、スクロールした分だけ別の行・桁を指すので
     * 選択が伸び続ける。指を離す ([ACTION_UP][MotionEvent.ACTION_UP]) まで止めない。
     */
    private val edgeScroller = object : Runnable {
        override fun run() {
            if (!dragSelecting) return
            val stepLines = edgeStepY()
            val stepColumns = edgeStepX()
            if (stepLines == 0 && stepColumns == 0) return
            val pos = getPointPositionOnScreen(lastX, lastY)
            val line = IntPair.getFirst(pos)
            val column = IntPair.getSecond(pos)
            val targetLine = (line + stepLines).coerceIn(0, text.lineCount - 1)
            val targetColumn = (column + stepColumns).coerceIn(0, text.getColumnCount(targetLine))
            runCatching { ensurePositionVisible(targetLine, targetColumn, true) }
            extendSelectionTo(lastX, lastY)
            // 中身が流れていくので拡大鏡も更新する。
            showMagnifier(lastX, lastY)
            postDelayed(this, EDGE_INTERVAL_MS)
        }
    }

    init {
        // ビュー全体をソフトウェアレイヤーで描画させる (= フレームワークの RenderNode/ハード
        // ウェアレンダリング経路を一切通さない)。
        // 内蔵エディタを開いた瞬間に "Current state = RESET, new state = FLUSHED"
        // (IllegalStateException) で落ちることがあった。これはビューがアタッチ/レイアウトされる
        // 際に、ビュー自身の RenderNode 状態機械が録画とフラッシュの競合で不正遷移するために起きる。
        // 下の Sora 内部キャッシュ無効化だけでは「Sora が作る RenderNode」しか抑えられず、
        // ビュー本体 (CodeEditor View) のハードウェアレイヤーが残るため稀に再発していた。
        // ソフトウェアレイヤーに固定すると描画は毎回ソフトウェア Canvas へ直接行われ、RenderNode を
        // まったく介さなくなるので、この状態遷移を構造的に踏めなくなり確実に開けるようになる
        // (テキスト編集用途では描画コストの増分は実用上問題にならない)。
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        // RenderNode によるハードウェアアクセラレーション描画キャッシュも合わせて無効化する。
        // Sora は行ごとの描画を android.graphics.RenderNode に beginRecording/endRecording で
        // 焼き込んで再利用するが、編集中の頻繁な無効化や再録画と描画の競合で同じ例外を投げ得る。
        isHardwareAcceleratedDrawAllowed = false
        // 長い行専用の RenderNode キャッシュも同根なので合わせて切る。
        props.cacheRenderNodeForLongLines = false

        // 選択拡大鏡 (Magnifier) を無効化する。これがクラッシュの真因と判断した。
        // Sora の Magnifier は内部で android.view.PixelCopy.request(Window, ...) を使い、
        // ウィンドウのサーフェスを「ビューの描画レイヤーとは独立に」コピー/レンダリングする。
        // そのため上の setLayerType(SOFTWARE) や RenderNode 無効化では一切抑えられず、
        // "Current state = RESET, new state = FLUSHED" (framework/HWUI 由来の IllegalStateException)
        // を踏み続けていた。本クラスは ACTION_MOVE のたびにドラッグ選択を更新するため Magnifier の
        // 表示更新 (= PixelCopy) が高頻度で走り、コピー経路の状態機械が不正遷移して落ちる。
        // 拡大鏡を切れば PixelCopy 経路そのものが消える (選択操作自体は従来どおり動く)。
        getComponent(Magnifier::class.java).isEnabled = false

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
            MotionEvent.ACTION_DOWN -> {
                dragSelecting = false
                removeCallbacks(edgeScroller)
            }

            MotionEvent.ACTION_MOVE -> if (dragSelecting) {
                lastX = e.x
                lastY = e.y
                extendSelectionTo(e.x, e.y)
                showMagnifier(e.x, e.y)
                // 端に入ったら、指が止まってもスクロールが続くようにループを回す。
                // 端から出たら止める (毎回貼り直すので二重には走らない)。
                removeCallbacks(edgeScroller)
                if (edgeStepX() != 0 || edgeStepY() != 0) postDelayed(edgeScroller, EDGE_INTERVAL_MS)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragSelecting) {
                dragSelecting = false
                removeCallbacks(edgeScroller)
                runCatching { magnifier?.dismiss() }
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

    override fun onDetachedFromWindow() {
        removeCallbacks(edgeScroller)
        runCatching { magnifier?.dismiss() }
        super.onDetachedFromWindow()
    }

    /** なぞっている位置を指の上に拡大して見せる。 */
    private fun showMagnifier(x: Float, y: Float) {
        if (!magnifierPrepared) {
            magnifierPrepared = true
            magnifier = createMagnifier()
        }
        runCatching { magnifier?.show(x, y) }
    }

    /**
     * 拡大鏡を作る。既定のままだと窓が小さく、指のすぐ上 (-42dp) に出るので指や手に隠れる。
     * 選択中の行とその上下が入る大きさにして、指からしっかり離した位置に出す。
     */
    private fun createMagnifier(): SystemMagnifier? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return runCatching { SystemMagnifier(this) }.getOrNull()
        }
        val density = context.resources.displayMetrics.density
        // 行の高さ × 3 行分。行の高さがまだ決まっていなければ最低限の高さで作る。
        val rows = (rowHeight * MAGNIFIER_ROWS).coerceAtLeast((MIN_MAGNIFIER_DP * density).toInt())
        val windowWidth = (width * MAGNIFIER_WIDTH_RATIO).toInt().coerceAtLeast((MIN_MAGNIFIER_DP * density).toInt())
        val windowHeight = (rows * MAGNIFIER_ZOOM).toInt()
        return runCatching {
            SystemMagnifier.Builder(this)
                .setSize(windowWidth, windowHeight)
                .setInitialZoom(MAGNIFIER_ZOOM)
                .setDefaultSourceToMagnifierOffset(0, -(MAGNIFIER_OFFSET_DP * density).toInt())
                .build()
        }.getOrNull()
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
        // (端まで届かない分は [edgeScroller] が自前でスクロールして補う)
        runCatching { setSelectionRegion(sl, sc, el, ec, false) }
    }

    /** 上下の端にいるなら 1 回あたり何行進めるか (端に深く入るほど速い)。端でなければ 0。 */
    private fun edgeStepY(): Int = when {
        lastY < edgeSize -> -stepFor(edgeSize - lastY)
        lastY > height - edgeSize -> stepFor(lastY - (height - edgeSize))
        else -> 0
    }

    /** 左右の端にいるなら 1 回あたり何桁進めるか。端でなければ 0。 */
    private fun edgeStepX(): Int = when {
        lastX < edgeSize -> -stepFor(edgeSize - lastX) * COLUMNS_PER_STEP
        lastX > width - edgeSize -> stepFor(lastX - (width - edgeSize)) * COLUMNS_PER_STEP
        else -> 0
    }

    /** 端に入り込んだ深さ (px) を 1〜[MAX_STEP] の速さに変える。 */
    private fun stepFor(depth: Float): Int =
        (depth / edgeSize * MAX_STEP).toInt().coerceIn(1, MAX_STEP)

    private companion object {
        /** 画面端から何 dp を「端」とみなすか。 */
        const val EDGE_DP = 48f

        /** 端スクロールの 1 コマの間隔 (ms)。60ms × 最大 3 行 = 秒間 50 行くらい。 */
        const val EDGE_INTERVAL_MS = 60L

        /** 1 コマで進む最大の行数。 */
        const val MAX_STEP = 3

        /** 横は 1 コマで進む桁数を行数より多めにする (1 文字が細いため)。 */
        const val COLUMNS_PER_STEP = 4

        /** 拡大鏡の倍率。 */
        const val MAGNIFIER_ZOOM = 1.6f

        /** 拡大鏡に何行分入れるか。 */
        const val MAGNIFIER_ROWS = 3

        /** 拡大鏡の幅を画面幅の何割にするか。 */
        const val MAGNIFIER_WIDTH_RATIO = 0.6f

        /** 拡大鏡を指から何 dp 上に離すか (既定の 42dp では指や手に隠れる)。 */
        const val MAGNIFIER_OFFSET_DP = 88f

        /** 行の高さが取れないときの拡大鏡の最小サイズ (dp)。 */
        const val MIN_MAGNIFIER_DP = 56f
    }
}
