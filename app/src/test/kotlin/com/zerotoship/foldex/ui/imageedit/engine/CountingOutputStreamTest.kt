// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CountingOutputStreamTest {
    @Test fun `counts single bytes whole buffers and slices`() {
        val output = CountingOutputStream()
        output.write(123)
        output.write(byteArrayOf(1, 2, 3))
        output.write(ByteArray(20), 5, 10)
        output.write(ByteArray(0), 0, 0)
        assertEquals(14L, output.count)
        assertEquals(14, output.sizeOrFailure())
    }

    @Test fun `over two gigabytes can be measured using only a reusable small buffer`() {
        val output = CountingOutputStream()
        val buffer = ByteArray(64 * 1024)
        repeat(32768) { output.write(buffer) }
        assertEquals(2_147_483_648L, output.count)
        assertEquals(-1, output.sizeOrFailure())
    }

    @Test fun `invalid buffer slices do not change the count`() {
        val output = CountingOutputStream()
        for ((offset, size) in listOf(-1 to 1, 0 to -1, 1 to 5, Int.MAX_VALUE to 2)) {
            assertThrows(IndexOutOfBoundsException::class.java) { output.write(ByteArray(5), offset, size) }
        }
        assertEquals(0L, output.count)
    }

    @Test fun `cancellation interrupts measurement without being counted`() {
        val output = CountingOutputStream { throw CancellationException() }
        assertThrows(CancellationException::class.java) { output.write(ByteArray(10)) }
        assertThrows(CancellationException::class.java) { output.write(1) }
        assertEquals(0L, output.count)
    }
}
