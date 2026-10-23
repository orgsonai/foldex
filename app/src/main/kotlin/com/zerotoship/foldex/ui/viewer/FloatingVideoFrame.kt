// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Foldex の小窓の外枠。1 本指でずらすと窓を動かし、2 本指でつまむと大きさを変える。
 * 右下のつまみ ([resizeHandleEnabled] が true のとき) をずらしても大きさを変えられる。
 *
 * 窓そのものが動くので、中の Compose (窓に対する座標) では指の動きを正しく測れない。
 * ここで画面に対する座標 (rawX / rawY) を使って測り、タップだけを中のボタンへ通す。
 */
internal class FloatingVideoFrame(
    context: Context,
    private val callbacks: Callbacks,
) : FrameLayout(context) {

    interface Callbacks {
        fun onMove(dx: Float, dy: Float)
        fun onScale(factor: Float)
        fun onResize(dx: Float, dy: Float)
        fun onGestureEnd()
    }

    /** 右下のつまみが出ているか (操作ボタンを出している間だけ true)。 */
    var resizeHandleEnabled: () -> Boolean = { false }

    private enum class Mode { NONE, MOVE, SCALE, RESIZE }

    private var mode = Mode.NONE
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val handleSizePx = HANDLE_SIZE_DP * resources.displayMetrics.density

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (mode == Mode.SCALE) callbacks.onScale(detector.scaleFactor)
                return true
            }
        },
    )

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                start(ev)
                scaleDetector.onTouchEvent(ev)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                scaleDetector.onTouchEvent(ev)
                if (mode != Mode.RESIZE) mode = Mode.SCALE
            }
            MotionEvent.ACTION_MOVE -> if (mode == Mode.NONE && beyondSlop(ev)) {
                mode = Mode.MOVE
                lastRawX = ev.rawX
                lastRawY = ev.rawY
            }
        }
        return mode != Mode.NONE
    }

    // タップは中の Compose が受けるので performClick は要らない。
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            // 中のどれも受けなかった指や、つまみを押した指 (onInterceptTouchEvent で横取り) はここへ届く。
            MotionEvent.ACTION_DOWN -> start(ev)
            MotionEvent.ACTION_POINTER_DOWN -> if (mode != Mode.RESIZE) mode = Mode.SCALE
            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    Mode.NONE -> if (beyondSlop(ev)) mode = Mode.MOVE
                    Mode.MOVE -> callbacks.onMove(ev.rawX - lastRawX, ev.rawY - lastRawY)
                    Mode.RESIZE -> callbacks.onResize(ev.rawX - lastRawX, ev.rawY - lastRawY)
                    // 2 本指のあとで 1 本残っても、急に窓が飛ばないよう指を全部離すまで動かさない。
                    Mode.SCALE -> Unit
                }
                lastRawX = ev.rawX
                lastRawY = ev.rawY
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode != Mode.NONE) callbacks.onGestureEnd()
                mode = Mode.NONE
            }
        }
        return true
    }

    private fun start(ev: MotionEvent) {
        val onHandle = resizeHandleEnabled() && ev.x > width - handleSizePx && ev.y > height - handleSizePx
        mode = if (onHandle) Mode.RESIZE else Mode.NONE
        downRawX = ev.rawX
        downRawY = ev.rawY
        lastRawX = ev.rawX
        lastRawY = ev.rawY
    }

    private fun beyondSlop(ev: MotionEvent): Boolean =
        abs(ev.rawX - downRawX) > touchSlop || abs(ev.rawY - downRawY) > touchSlop

    private companion object {
        const val HANDLE_SIZE_DP = 40f
    }
}
