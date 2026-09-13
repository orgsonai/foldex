// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

/** 透明画素の隠れた RGB が粒の色に混ざらないよう、アルファで重み付けする。 */
internal object MosaicColors {
    fun average(pixels: IntArray, count: Int = pixels.size): Int {
        require(count in 1..pixels.size)
        var alpha = 0L
        var red = 0L
        var green = 0L
        var blue = 0L
        for (i in 0 until count) {
            val color = pixels[i]
            val a = (color ushr 24).toLong()
            alpha += a
            red += ((color ushr 16) and 255) * a
            green += ((color ushr 8) and 255) * a
            blue += (color and 255) * a
        }
        if (alpha == 0L) return 0
        return ((alpha / count).toInt() shl 24) or
            ((red / alpha).toInt() shl 16) or
            ((green / alpha).toInt() shl 8) or (blue / alpha).toInt()
    }
}
