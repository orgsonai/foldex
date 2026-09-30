// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.timeline

import org.junit.Assert.*
import org.junit.Test

class TimelineLanesTest {
    @Test fun overlappingIntervalsRemainIndividuallySelectableAndAdjacentIntervalsShareARow() {
        val clips = listOf(LaneInterval("a", 0, 4), LaneInterval("b", 1, 2), LaneInterval("c", 4, 5), LaneInterval("d", 2, 4))
        assertEquals(mapOf("a" to 0, "b" to 1, "c" to 0, "d" to 1), packTimelineLanes(clips))
    }
    @Test fun unsortedIntervalsUseOnlyAsManyRowsAsSimultaneousClips() {
        val clips = listOf(LaneInterval("b", 5, 8), LaneInterval("a", 0, 10), LaneInterval("c", 4, 9))
        val rows = packTimelineLanes(clips)
        assertEquals(3, rows.values.toSet().size)
        assertEquals(0, rows["a"])
    }
    @Test fun consecutiveCaptionsStayOnOneRow() {
        assertEquals(setOf(0), packTimelineLanes((0L..100L).map { LaneInterval("$it", it, it + 1) }).values.toSet())
        assertTrue(packTimelineLanes(emptyList()).isEmpty())
    }
}
