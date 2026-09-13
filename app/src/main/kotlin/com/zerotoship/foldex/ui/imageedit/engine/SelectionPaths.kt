// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import android.graphics.Path
import android.graphics.RectF
import com.zerotoship.foldex.ui.imageedit.model.BoolOp
import com.zerotoship.foldex.ui.imageedit.model.SelectionSpec

/**
 * [SelectionSpec] (純 Kotlin) を `android.graphics.Path` に変換する。
 *
 * モデル側は Android の型を持たない約束なので、Path を作るのはこの engine 層だけ。
 * 描画も当たり判定も同じここを通すので、「輪郭は出ているのに塗ると形が違う」が起きない。
 */
object SelectionPaths {

    /**
     * @param scale 論理キャンバス座標 → 出力画素へのスケール。プレビューでも保存でも
     *   同じ [SelectionSpec] にその解像度の倍率を掛けるだけでよい。
     */
    fun toPath(spec: SelectionSpec, scale: Float): Path = when (spec) {
        is SelectionSpec.Rect -> Path().apply {
            val r = spec.rect
            addRect(
                RectF(r.left * scale, r.top * scale, r.right * scale, r.bottom * scale),
                Path.Direction.CW,
            )
        }

        is SelectionSpec.Ellipse -> Path().apply {
            val r = spec.rect
            addOval(
                RectF(r.left * scale, r.top * scale, r.right * scale, r.bottom * scale),
                Path.Direction.CW,
            )
        }

        is SelectionSpec.Lasso -> Path().apply {
            val pts = spec.points
            if (pts.size < 2) return@apply
            moveTo(pts[0].x * scale, pts[0].y * scale)
            for (i in 1 until pts.size) lineTo(pts[i].x * scale, pts[i].y * scale)
            close()
            // 自分と交差する軌跡でも、モデルの contains (even-odd) と同じ結果になるよう揃える。
            fillType = Path.FillType.EVEN_ODD
        }

        is SelectionSpec.Contours -> Path().apply {
            fillType = Path.FillType.EVEN_ODD
            for (ring in spec.rings) {
                if (ring.size < 3) continue
                moveTo(ring[0].x * scale, ring[0].y * scale)
                for (i in 1 until ring.size) lineTo(ring[i].x * scale, ring[i].y * scale)
                close()
            }
        }

        is SelectionSpec.Combined -> {
            val out = Path()
            for (part in spec.parts) {
                val piece = toPath(part.spec, scale)
                val op = when (part.op) {
                    BoolOp.ADD -> Path.Op.UNION
                    BoolOp.SUBTRACT -> Path.Op.DIFFERENCE
                }
                // 合成失敗を無視すると、モザイクなどの保存範囲が欠落する。
                check(out.op(piece, op)) { "Could not combine selection paths" }
            }
            out
        }
    }
}
