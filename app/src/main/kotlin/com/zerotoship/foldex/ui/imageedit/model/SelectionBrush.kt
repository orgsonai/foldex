// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.model

/** ジェスチャ開始時の表示枠から論理座標へ変換する。ズーム倍率で保存時の太さを変えない。 */
object SelectionBrush {
    fun fromViewport(
        points: List<EditPoint>, viewport: EditRect, canvas: EditSize, diameter: Float,
    ): SelectionSpec.BrushStroke? {
        if (viewport.isEmpty || canvas.width <= 0 || canvas.height <= 0 || points.isEmpty() ||
            points.size > SelectionSpec.MAX_BRUSH_POINTS || !diameter.isFinite() || diameter <= 0f ||
            points.any { !it.x.isFinite() || !it.y.isFinite() } ||
            listOf(viewport.left, viewport.top, viewport.right, viewport.bottom, viewport.width, viewport.height).any { !it.isFinite() }
        ) return null
        val sx = canvas.width / viewport.width
        val sy = canvas.height / viewport.height
        val mapped = points.map { EditPoint((it.x - viewport.left) * sx, (it.y - viewport.top) * sy) }
        if (mapped.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        return SelectionSpec.BrushStroke(mapped, diameter / 2f, EditRect.ofSize(canvas.width, canvas.height))
            .takeUnless { it.isEmpty }
    }
}
