// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.model

import org.junit.Assert.*
import org.junit.Test

class ClipEditingTest {
    private val s = US_PER_SECOND
    private val asset = MediaAsset("v", AssetKind.VIDEO, "/video.mp4", "video.mp4", 20 * s)
    private val project = VideoProject(
        assets = mapOf(asset.id to asset),
        mainTrack = listOf(MainClip("a", asset.id, 2 * s, 10 * s, speed = 2.0), MainClip("b", asset.id, 0L, 4 * s)),
        texts = listOf(TextClip("t", s, 4 * s, "caption")),
        audioTrack = listOf(AudioClip("m", asset.id, s, 3 * s, 8 * s)),
        layers = listOf(LayerClip(MainClip("l", asset.id, 2 * s, 10 * s, speed = 2.0), s)),
    )

    @Test fun selectedMainCannotSplitAnotherMainUnderPlayhead() {
        assertFalse(ClipEditing.canCut(project, ClipRef.Main("a"), 5 * s))
        assertEquals(project, ClipEditing.split(project, ClipRef.Main("a"), 5 * s, "new"))
    }

    @Test fun trimBeforeAtDoubleSpeedUsesSourceTimeAndRipplesMainOnly() {
        val after = ClipEditing.trimToPlayhead(project, ClipRef.Main("a"), 2 * s, start = true)
        assertEquals(6 * s, after.mainTrack[0].sourceInUs)
        assertEquals(10 * s, after.mainTrack[0].sourceOutUs)
        assertEquals(2 * s, after.mainStartUs(1))
        assertEquals(project.texts, after.texts)
        assertEquals(project.audioTrack, after.audioTrack)
        assertEquals(project.layers, after.layers)
    }

    @Test fun trimAfterKeepsMainStartAndUsesSourceTime() {
        val after = ClipEditing.trimToPlayhead(project, ClipRef.Main("a"), 2 * s, start = false)
        assertEquals(2 * s, after.mainTrack[0].sourceInUs)
        assertEquals(6 * s, after.mainTrack[0].sourceOutUs)
    }

    @Test fun layerTrimPreservesSourcePositionAndOtherTracks() {
        val after = ClipEditing.trimToPlayhead(project, ClipRef.Layer("l"), 3 * s, start = true)
        assertEquals(3 * s, after.layers.single().startUs)
        assertEquals(project.layers.single().endUs, after.layers.single().endUs)
        assertEquals(6 * s, after.layers.single().clip.sourceInUs)
        assertEquals(project.mainTrack, after.mainTrack)
    }

    @Test fun audioTrimKeepsItsRightEdgeAndAdjustsSource() {
        val after = ClipEditing.trimToPlayhead(project, ClipRef.Audio("m"), 3 * s, start = true)
        assertEquals(3 * s, after.audioTrack.single().startUs)
        assertEquals(5 * s, after.audioTrack.single().sourceInUs)
        assertEquals(project.audioTrack.single().endUs, after.audioTrack.single().endUs)
    }

    @Test fun textSplitPreservesAppearanceAndCoversOriginalRange() {
        val after = ClipEditing.split(project, ClipRef.Text("t"), 3 * s, "new")
        assertEquals(listOf(2 * s, 2 * s), after.texts.map { it.durationUs })
        assertEquals(after.texts[0].endUs, after.texts[1].startUs)
        assertEquals(project.texts.single().endUs, after.texts.last().endUs)
        assertEquals(listOf("caption", "caption"), after.texts.map { it.text })
    }

    @Test fun trimsOutsideSelectionAndWithinOneFrameDoNothing() {
        val ref = ClipRef.Main("a")
        for (time in listOf(-s, 0L, 1L, 4 * s - 1, 4 * s, 9 * s)) {
            assertEquals(project, ClipEditing.trimToPlayhead(project, ref, time, start = true))
            assertEquals(project, ClipEditing.trimToPlayhead(project, ref, time, start = false))
        }
    }

    @Test fun boundaryNavigationSkipsCurrentBoundaryAndStopsAtEnds() {
        assertEquals(4 * s, ClipEditing.neighborBoundary(project, 0L, forward = true))
        assertEquals(8 * s, ClipEditing.neighborBoundary(project, 4 * s, forward = true))
        assertEquals(0L, ClipEditing.neighborBoundary(project, 4 * s, forward = false))
        assertEquals(4 * s, ClipEditing.neighborBoundary(project, 5 * s, forward = false))
        assertEquals(0L, ClipEditing.neighborBoundary(project, 0L, forward = false))
        assertEquals(project.durationUs, ClipEditing.neighborBoundary(project, project.durationUs, forward = true))
    }
}
