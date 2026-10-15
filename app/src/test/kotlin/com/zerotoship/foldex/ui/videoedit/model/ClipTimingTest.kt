// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.model

import com.zerotoship.foldex.ui.videoedit.model.ClipTiming.Field
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ClipTimingTest {
    private val asset = MediaAsset("v", AssetKind.VIDEO, "/video.mp4", "video.mp4", 20_000_000L)
    private val main = MainClip("m", "v", 2_000_000L, 12_000_000L, speed = 2.0, fadeInUs = 2_000_000L, fadeOutUs = 2_000_000L)
    private val text = TextClip("t", 1_000_000L, 3_000_000L, "字幕")
    private val audio = AudioClip("a", "v", 4_000_000L, 3_000_000L, 13_000_000L, fadeInUs = 4_000_000L, fadeOutUs = 4_000_000L)
    private val project = VideoProject(assets = mapOf(asset.id to asset), mainTrack = listOf(main),
        texts = listOf(text), audioTrack = listOf(audio))

    @Test
    fun mainDurationUsesSourceSpeedAndClampsFadesWithoutMovingOtherTracks() {
        val edited = ClipTiming.set(project, ClipRef.Main("m"), Field.DURATION, 1_500_000L)
        assertEquals(5_000_000L, edited.mainTrack.single().sourceOutUs)
        assertEquals(1_500_000L, edited.mainTrack.single().durationUs)
        assertEquals(1_500_000L, edited.mainTrack.single().fadeInUs)
        assertEquals(0L, edited.mainTrack.single().fadeOutUs)
        assertEquals(project.texts, edited.texts)
        assertEquals(project.audioTrack, edited.audioTrack)
    }

    @Test
    fun sourceStartTrimsOnlyTheChosenLayerAndKeepsItsTimelinePosition() {
        val layer = LayerClip(main.copy(id = "l"), 4_000_000L, centerX = 0.25f, opacity = 0.3f)
        val base = project.copy(layers = listOf(layer))
        val edited = ClipTiming.set(base, ClipRef.Layer("l"), Field.SOURCE_IN, 6_000_000L)
        assertEquals(4_000_000L, edited.layers.single().startUs)
        assertEquals(3_000_000L, edited.layers.single().durationUs)
        assertEquals(0.25f, edited.layers.single().centerX)
        assertEquals(0.3f, edited.layers.single().opacity)
        assertEquals(base.mainTrack, edited.mainTrack)
    }

    @Test
    fun audioSourceTrimKeepsItsStartAndLimitsOverlappingFades() {
        val edited = ClipTiming.set(project, ClipRef.Audio("a"), Field.SOURCE_IN, 8_000_000L)
        val clip = edited.audioTrack.single()
        assertEquals(4_000_000L, clip.startUs)
        assertEquals(5_000_000L, clip.durationUs)
        assertEquals(4_000_000L, clip.fadeInUs)
        assertEquals(1_000_000L, clip.fadeOutUs)
    }

    @Test
    fun captionCanBePlacedAtAnExactSubframeTimeAndRetainsItsStyle() {
        val moved = ClipTiming.set(project, ClipRef.Text("t"), Field.START, 2_512_345L)
        val resized = ClipTiming.set(moved, ClipRef.Text("t"), Field.DURATION, 1_712_345L)
        assertEquals(text.copy(startUs = 2_512_345L, durationUs = 1_712_345L), resized.texts.single())
        assertEquals(project.mainTrack, resized.mainTrack)
        assertNull(ClipTiming.bounds(project, ClipRef.Text("t"), Field.SOURCE_IN))
    }

    @Test
    fun invalidOrOutOfSourceTimeIsRejectedWithoutRoundingIntoAnotherEdit() {
        assertSame(project, ClipTiming.set(project, ClipRef.Main("m"), Field.DURATION, 9_000_001L))
        assertSame(project, ClipTiming.set(project, ClipRef.Audio("a"), Field.SOURCE_OUT, 20_000_001L))
        assertSame(project, ClipTiming.set(project, ClipRef.Text("t"), Field.DURATION, 1L))
        assertSame(project, ClipTiming.set(project, ClipRef.Audio("a"), Field.START, -1L))
        assertSame(project, ClipTiming.set(project, ClipRef.Main("missing"), Field.DURATION, 1_000_000L))
        assertNull(ClipTiming.bounds(project, ClipRef.Main("m"), Field.START))
    }

    @Test
    fun stillImageDurationHasNoSourceTimingControls() {
        val image = asset.copy(kind = AssetKind.IMAGE, durationUs = 0L)
        val base = project.copy(assets = mapOf(image.id to image), mainTrack = listOf(main.copy(speed = 1.0)))
        val edited = ClipTiming.set(base, ClipRef.Main("m"), Field.DURATION, 6_750_000L)
        assertEquals(6_750_000L, edited.mainTrack.single().durationUs)
        assertNull(ClipTiming.bounds(base, ClipRef.Main("m"), Field.SOURCE_IN))
        assertNull(ClipTiming.bounds(base, ClipRef.Main("m"), Field.SOURCE_OUT))
    }

    @Test
    fun untouchedNumericInputRetainsAllMicroseconds() {
        val input = editorSeconds(2_512_345L)
        assertEquals("2.512345", input)
        assertEquals(2_512_345L, parseEditorTime(input, FrameRate(30_000, 1_001)))
        assertEquals("12", editorSeconds(12_000_000L))
    }
}
