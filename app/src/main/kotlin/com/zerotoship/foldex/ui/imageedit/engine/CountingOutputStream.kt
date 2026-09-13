// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import java.io.OutputStream

/** 圧縮結果を保持しない。計測が Int の表示上限を超えても負数へ折り返さない。 */
internal class CountingOutputStream(private val checkCancelled: () -> Unit = {}) : OutputStream() {
    var count: Long = 0
        private set

    override fun write(value: Int) {
        checkCancelled()
        count = Math.addExact(count, 1)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
        checkCancelled()
        count = Math.addExact(count, length.toLong())
    }

    fun sizeOrFailure(): Int = if (count <= Int.MAX_VALUE) count.toInt() else -1
}
