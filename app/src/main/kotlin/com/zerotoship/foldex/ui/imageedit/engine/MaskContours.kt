// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import com.zerotoship.foldex.ui.imageedit.model.EditPoint
import com.zerotoship.foldex.ui.imageedit.model.EditSize
import com.zerotoship.foldex.ui.imageedit.model.SelectionSpec

/** マスクの境界だけを保存する。Undo に推論バッファを残さず、穴も失わない。 */
internal object MaskContours {
    fun trace(
        mask: ByteArray,
        width: Int,
        height: Int,
        canvas: EditSize,
        checkCancelled: () -> Unit = {},
    ): SelectionSpec.Contours? {
        require(width in 1..1024 && height in 1..1024 && mask.size == width * height)
        val stride = width + 1
        // 各頂点から右・下・左・上へ向かう辺をビットで記録する。
        val edges = ByteArray(stride * (height + 1))
        fun foreground(x: Int, y: Int): Boolean =
            x in 0 until width && y in 0 until height && mask[y * width + x].toInt() != 0
        fun add(x: Int, y: Int, direction: Int) {
            val index = y * stride + x
            edges[index] = (edges[index].toInt() or (1 shl direction)).toByte()
        }
        for (y in 0 until height) {
            checkCancelled()
            for (x in 0 until width) {
                if (!foreground(x, y)) continue
                if (!foreground(x, y - 1)) add(x, y, 0)
                if (!foreground(x + 1, y)) add(x + 1, y, 1)
                if (!foreground(x, y + 1)) add(x + 1, y + 1, 2)
                if (!foreground(x - 1, y)) add(x, y + 1, 3)
            }
        }
        val rings = mutableListOf<List<EditPoint>>()
        val offsets = intArrayOf(1, stride, -1, -stride)
        var vertices = 0
        for (start in edges.indices) {
            if (start % stride == 0) checkCancelled()
            while (edges[start].toInt() != 0) {
                var direction = Integer.numberOfTrailingZeros(edges[start].toInt())
                var current = start
                val ring = mutableListOf<EditPoint>()
                fun corner() {
                    ring.add(EditPoint(
                        (current % stride).toFloat() / width * canvas.width,
                        (current / stride).toFloat() / height * canvas.height,
                    ))
                    vertices++
                }
                corner()
                do {
                    edges[current] = (edges[current].toInt() and (1 shl direction).inv()).toByte()
                    current += offsets[direction]
                    if (current == start) break
                    val available = edges[current].toInt()
                    // 斜めに接する画素は別の輪郭にするため、右折を優先する。
                    val next = when {
                        available and (1 shl ((direction + 1) % 4)) != 0 -> (direction + 1) % 4
                        available and (1 shl direction) != 0 -> direction
                        available and (1 shl ((direction + 3) % 4)) != 0 -> (direction + 3) % 4
                        else -> return null
                    }
                    if (next != direction) corner()
                    direction = next
                    if (vertices > MAX_VERTICES) return null
                } while (true)
                rings.add(ring.toList())
                checkCancelled()
            }
        }
        return rings.takeUnless { it.isEmpty() }?.let { SelectionSpec.Contours(it.toList()) }
    }

    private const val MAX_VERTICES = 65_536
}
