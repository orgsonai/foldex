// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.model

import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.ui.videoedit.previewFrameAt
import com.zerotoship.foldex.ui.videoedit.plan.PlannedMedia
import com.zerotoship.foldex.ui.videoedit.plan.RenderPlanner
import org.junit.Assert.*
import org.junit.Test

private const val S = US_PER_SECOND

class VideoRangeEditingTest {
    private val video = MediaAsset("v", AssetKind.VIDEO, "/video.mp4", "video.mp4", 12 * S, hasAudio = true)
    private fun project(speed: Double = 1.0) = VideoProject(
        assets = mapOf(video.id to video),
        mainTrack = listOf(MainClip("first", video.id, 2 * S, 8 * S, speed = speed, fadeInUs = S)),
    )

    @Test
    fun `使用区間の移動は速度とフェードと全長を変えず両端を同じ量ずらす`() {
        for (speed in listOf(0.5, 1.0, 2.0)) {
            val before = project(speed)
            val after = VideoEditOps.shiftMainSource(before, "first", 5 * S)
            assertEquals(before.durationUs, after.durationUs)
            assertEquals(before.mainTrack.single().copy(sourceInUs = 5 * S, sourceOutUs = 11 * S), after.mainTrack.single())
            assertEquals(before.assets, after.assets)
        }
    }

    @Test
    fun `使用区間は原本の両端で止まり短くならない`() {
        assertEquals(0L, VideoEditOps.shiftMainSource(project(), "first", -S).mainTrack.single().sourceInUs)
        val last = VideoEditOps.shiftMainSource(project(), "first", 30 * S).mainTrack.single()
        assertEquals(6 * S, last.sourceInUs)
        assertEquals(12 * S, last.sourceOutUs)
        assertEquals(6 * S, last.sourceDurationUs)
    }

    @Test
    fun `使用区間をずらしても後ろのクリップの内容と配置は変わらずUndoで戻る`() {
        val before = project().let { it.copy(mainTrack = it.mainTrack + MainClip("second", "v", 0, S)) }
        val after = VideoEditOps.shiftMainSource(before, "first", 4 * S)
        assertEquals(before.mainTrack[1], after.mainTrack[1])
        assertEquals(before.mainStartUs(1), after.mainStartUs(1))
        val history = ProjectHistory()
        history.record(before)
        assertEquals(before, history.undo(after))
        assertEquals(after, history.redo(before))
    }

    @Test
    fun `分割した前半と後半は原本の別区間としてプレビューと書き出しへ渡る`() {
        for (speed in listOf(0.5, 1.0, 2.0)) {
            val before = project(speed)
            val cutUs = before.durationUs / 2
            val after = VideoEditOps.splitMainAt(before, cutUs, "second")
            val first = after.mainTrack[0]
            val second = after.mainTrack[1]
            assertEquals(2 * S, first.sourceInUs)
            assertEquals(5 * S, first.sourceOutUs)
            assertEquals(first.sourceOutUs, second.sourceInUs)
            assertEquals(8 * S, second.sourceOutUs)
            assertEquals(before.durationUs, after.durationUs)
            assertEquals(5 * S, previewFrameAt(after, cutUs, coarse = false)!!.sourceTimeUs)
            assertEquals(5 * S, previewFrameAt(after, cutUs, coarse = true)!!.sourceTimeUs)
            val plan = (RenderPlanner.plan(after, ExportSettings()) as Result.Success).value
            val main = plan.main.filterIsInstance<PlannedMedia>()
            assertEquals(listOf(2 * S, 5 * S), main.map { it.sourceInUs })
            assertEquals(listOf(5 * S, 8 * S), main.map { it.sourceOutUs })
            assertEquals(before.durationUs, plan.durationUs)
        }
    }

    @Test
    fun `細かい位置で分割した後半の先頭を丸めて前半へ戻さない`() {
        val after = VideoEditOps.splitMainAt(project(), 1_017_000L, "second")
        val frame = previewFrameAt(after, 1_017_000L, coarse = true)!!
        assertEquals(3_017_000L, frame.sourceTimeUs)
        assertFalse(frame.coarse)
    }
}
