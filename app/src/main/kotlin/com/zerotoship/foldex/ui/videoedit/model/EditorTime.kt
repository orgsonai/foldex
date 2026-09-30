// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.model

import java.math.BigDecimal

/** 秒、または画面と同じ MM:SS:FF / HH:MM:SS:FF。コマ桁はその秒の先頭から数える。 */
fun parseEditorTime(value: String, rate: FrameRate): Long? {
    val text = value.trim()
    return try {
        if (':' in text) {
            val input = text.split(':')
            if (input.size !in 3..4 || input.any { !it.matches(Regex("[0-9]{1,4}")) }) return null
            val parts = if (input.size == 3) listOf("0") + input else input
            val (h, m, s, f) = parts.map { it.toLong() }
            if (m > 59 || s > 59 || f >= rate.nominalFps) return null
            val secondUs = ((h * 60 + m) * 60 + s) * US_PER_SECOND
            maxOf(secondUs, rate.timeOfFrame(rate.frameAt(secondUs) + f))
        } else {
            if (!text.matches(Regex("[0-9]{1,9}([.,][0-9]{1,6})?"))) return null
            BigDecimal(text.replace(',', '.')).multiply(BigDecimal(US_PER_SECOND)).longValueExact()
        }
    } catch (_: ArithmeticException) { null }
}
