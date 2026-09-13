// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import com.zerotoship.foldex.ui.imageedit.model.EditPoint
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class MagicTouchTensorTest {
    @Test
    fun `input uses RGB order zero to one and a single tap prior`() {
        val input = ByteBuffer.allocate(MagicTouchTensor.INPUT_BYTES).order(ByteOrder.nativeOrder())
        MagicTouchTensor.writeInput(IntArray(MagicTouchTensor.PIXELS) { 0xFF804020.toInt() }, EditPoint(0.5f, 0.25f), input)
        assertEquals(128f / 255, input.getFloat(0), 0f)
        assertEquals(64f / 255, input.getFloat(4), 0f)
        assertEquals(32f / 255, input.getFloat(8), 0f)
        val prior = (0 until MagicTouchTensor.PIXELS).filter { input.getFloat(it * 16 + 12) == 1f }
        assertEquals(listOf(128 * 512 + 256), prior)
        assertEquals(0, input.position())
    }

    @Test
    fun `tap on last pixel remains inside tensor`() {
        val input = ByteBuffer.allocate(MagicTouchTensor.INPUT_BYTES).order(ByteOrder.nativeOrder())
        MagicTouchTensor.writeInput(IntArray(MagicTouchTensor.PIXELS), EditPoint(1f, 1f), input)
        assertEquals(1f, input.getFloat(input.capacity() - 4), 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid tap is rejected`() {
        MagicTouchTensor.writeInput(IntArray(MagicTouchTensor.PIXELS), EditPoint(Float.NaN, 0f),
            ByteBuffer.allocate(MagicTouchTensor.INPUT_BYTES))
    }

    @Test
    fun `output is one foreground probability channel not two logits`() {
        val output = ByteBuffer.allocate(MagicTouchTensor.OUTPUT_BYTES).order(ByteOrder.nativeOrder())
        output.putFloat(0.49f).putFloat(0.5f).putFloat(1f).putFloat(Float.NaN).putFloat(Float.POSITIVE_INFINITY)
        val mask = MagicTouchTensor.readMask(output)
        assertArrayEquals(byteArrayOf(0, 1, 1, 0, 0), mask.take(5).toByteArray())
    }

    @Test
    fun `bundled model exactly matches reviewed revision`() {
        val model = File("src/main/assets/models/magic_touch.tflite")
        assertEquals(6_227_884L, model.length())
        val hash = MessageDigest.getInstance("SHA-256").digest(model.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals("e24338a717c1b7ad8d159666677ef400babb7f33b8ad60c4d96db4ecf694cd25", hash)
    }
}
