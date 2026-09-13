// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

/** 一画素の穴も走査する。保持するのは一行だけ。 */
internal object TransparencyScan {
    fun hasAlpha(width: Int, height: Int, readRow: (Int, IntArray) -> Unit): Boolean {
        val row = IntArray(width)
        for (y in 0 until height) {
            readRow(y, row)
            if (row.any { it ushr 24 != 255 }) return true
        }
        return false
    }
}
