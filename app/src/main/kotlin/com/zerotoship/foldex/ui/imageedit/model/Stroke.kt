// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.model

import kotlin.math.abs

/**
 * ブラシ 1 ストローク。指を置いてから離すまでが 1 本。
 *
 * 座標も太さも**論理キャンバス座標**で持つ (画素に焼かない)。表示は縮小版、保存は原寸と
 * 解像度が変わっても同じ形で描き直せるので、拡大してもブラシがボケない。
 */
data class Stroke(
    val points: List<EditPoint>,
    val widthPx: Float,
    /** ARGB。消しゴム ([StrokeMode.ERASE]) では使われない。 */
    val color: Int,
    val mode: StrokeMode = StrokeMode.DRAW,
    /** 1f = 輪郭がくっきり。小さいほど縁をぼかす。 */
    val hardness: Float = 1f,
) {
    companion object {
        /**
         * 指の軌跡から要らない点を間引く。
         *
         * タッチイベントは 1 本の線でも数百点来るが、[minDistance] 未満の移動は
         * 見た目に効かない。捨てておくと履歴もレンダリングも軽くなる。
         */
        fun simplify(points: List<EditPoint>, minDistance: Float): List<EditPoint> {
            if (points.size <= 2) return points
            val out = ArrayList<EditPoint>(points.size)
            out.add(points.first())
            for (i in 1 until points.lastIndex) {
                val last = out.last()
                val p = points[i]
                // ユークリッド距離ではなくマンハッタン距離で足りる (閾値の目安にしか使わない)。
                if (abs(p.x - last.x) + abs(p.y - last.y) >= minDistance) out.add(p)
            }
            out.add(points.last())
            return out
        }
    }
}

enum class StrokeMode { DRAW, ERASE }
