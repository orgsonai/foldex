// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.model

import org.junit.Assert.*
import org.junit.Test

class EditorTimeTest {
    @Test fun displayedTimecodeRoundTripsForIntegerAndFractionalRates() {
        for (rate in listOf(FrameRate(24), FrameRate(30), FrameRate(30000, 1001), FrameRate(60))) {
            for (frame in listOf(0L, 1L, 29L, 1800L, 108123L)) {
                val time = rate.timeOfFrame(frame)
                assertEquals(time, parseEditorTime(formatTimecode(time, rate), rate))
            }
        }
    }

    @Test fun secondsPreserveMicrosecondsAndAcceptDecimalComma() {
        assertEquals(12_500_000L, parseEditorTime("12.5", FrameRate.FPS_30))
        assertEquals(12_500_001L, parseEditorTime(" 12,500001 ", FrameRate.FPS_30))
    }

    @Test fun invalidOrAmbiguousInputIsRejected() {
        for (input in listOf("-1", "NaN", "", "1e3", "1:2", "00:60:00:00", "00:00:60:00", "00:00:00:30", "1.0000001")) {
            assertNull(input, parseEditorTime(input, FrameRate.FPS_30))
        }
    }
}
