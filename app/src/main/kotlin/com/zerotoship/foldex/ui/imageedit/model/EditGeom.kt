// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 画像編集モデルで使う最小限の幾何型。
 *
 * `android.graphics.RectF` / `PointF` を使わないのは、モデル層を **純 Kotlin** に保って
 * JVM ユニットテストで回せるようにするため (Android の android.jar スタブは
 * ユニットテストから触ると例外を投げる)。Android の型への変換は engine 層で行う。
 */
data class EditPoint(val x: Float, val y: Float)

/** 左上原点の矩形。[right] / [bottom] は排他 (幅 = right - left)。 */
data class EditRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    fun intersect(other: EditRect): EditRect? {
        val r = EditRect(
            left = max(left, other.left),
            top = max(top, other.top),
            right = min(right, other.right),
            bottom = min(bottom, other.bottom),
        )
        return if (r.isEmpty) null else r
    }

    /** [bounds] の内側に収まるよう平行移動 + はみ出し分を切り詰める。 */
    fun coerceIn(bounds: EditRect): EditRect {
        val w = min(width, bounds.width)
        val h = min(height, bounds.height)
        val l = left.coerceIn(bounds.left, bounds.right - w)
        val t = top.coerceIn(bounds.top, bounds.bottom - h)
        return EditRect(l, t, l + w, t + h)
    }

    companion object {
        fun ofSize(width: Int, height: Int): EditRect =
            EditRect(0f, 0f, width.toFloat(), height.toFloat())

        fun ofSize(width: Float, height: Float): EditRect = EditRect(0f, 0f, width, height)
    }
}

/** 画素サイズ。負や 0 を持たせないため生成時に 1 以上へ丸める。 */
data class EditSize(val width: Int, val height: Int) {
    init {
        require(width >= 1 && height >= 1) { "size must be positive: ${width}x$height" }
    }

    val longEdge: Int get() = max(width, height)

    /** アスペクト比を保ったまま長辺を [longEdge] に合わせる。拡大はしない場合は [allowUpscale] = false。 */
    fun fitLongEdge(longEdge: Int, allowUpscale: Boolean = true): EditSize {
        val current = this.longEdge
        if (current == longEdge) return this
        if (!allowUpscale && longEdge > current) return this
        val scale = longEdge.toFloat() / current
        return scaled(scale)
    }

    fun scaled(scale: Float): EditSize = EditSize(
        width = (width * scale).roundToInt().coerceAtLeast(1),
        height = (height * scale).roundToInt().coerceAtLeast(1),
    )
}
