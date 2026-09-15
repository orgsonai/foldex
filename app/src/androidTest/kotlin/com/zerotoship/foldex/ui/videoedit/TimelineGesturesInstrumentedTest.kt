// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zerotoship.foldex.ui.videoedit.model.*
import com.zerotoship.foldex.ui.videoedit.timeline.TimelineState
import com.zerotoship.foldex.ui.videoedit.timeline.TimelineView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val S = US_PER_SECOND

/** 手でつかむ位置・ドラッグの経路を使い、スクロールとの競合と実描画を確かめる。 */
@RunWith(AndroidJUnit4::class)
class TimelineGesturesInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private var project by mutableStateOf(VideoProject())
    private var selection by mutableStateOf<ClipRef?>(null)
    private val timeline = TimelineState()
    private var density = 1f
    private val verticalScroll = ScrollState(0)
    private var edits = 0
    private var gestureBase: VideoProject? = null

    private fun show(firstSeconds: Long = 10, text: Boolean = false, selected: Boolean = true, layer: Boolean = false, scrollRows: Boolean = false) {
        val asset = MediaAsset("video", AssetKind.VIDEO, "/missing-test-video.mp4", "test.mp4", 12 * S)
        project = VideoProject(assets = mapOf(asset.id to asset), mainTrack = listOf(
            MainClip("a", asset.id, 0L, firstSeconds * S), MainClip("b", asset.id, 0L, 2 * S),
        ), texts = if (text) listOf(TextClip("text", S, 2 * S, "A"), TextClip("next", 5 * S, S, "B")) else emptyList())
        if (layer) project = project.copy(layers = listOf(LayerClip(MainClip("layer", asset.id, 2 * S, 4 * S), S),
            LayerClip(MainClip("top", asset.id, 0, S), 5 * S)))
        selection = if (layer) ClipRef.Layer("layer") else if (!selected) null else if (text) ClipRef.Text("text") else ClipRef.Main("a")
        compose.setContent {
            density = LocalDensity.current.density
            MaterialTheme(colorScheme = lightColorScheme(primary = Color.Magenta)) {
                Column(Modifier.width(400.dp).then(if (scrollRows) Modifier.height(130.dp).verticalScroll(verticalScroll) else Modifier)) {
                    TimelineView(project, selection, timeline, "", onSelect = { selection = it },
                        onPreview = { transform ->
                            if (gestureBase == null) gestureBase = project
                            project = transform(project)
                        },
                        onCommit = {
                            if (gestureBase != null && gestureBase != project) edits++
                            gestureBase = null
                        },
                        onEdit = { transform -> project = transform(project); edits++ },
                        onSplitAtPlayhead = {}, modifier = Modifier.testTag("timeline"))
                }
            }
        }
        compose.runOnIdle { timeline.pxPerSecond = 15f * density }
    }

    private val node get() = compose.onNodeWithTag("timeline")
    private fun dp(value: Float) = value * density

    @Test
    fun leftTrimKeepsRulerAndFollowingClipStillWhileFingerIsDown() {
        show()
        node.performTouchInput {
            down(Offset(centerX - dp(7f), dp(54f)))
            moveTo(Offset(centerX + dp(33f), dp(54f)), delayMillis = 120)
        }
        compose.runOnIdle {
            assertEquals(0L, timeline.playheadUs)
            assertTrue(project.mainTrack[0].sourceInUs > 2 * S)
            assertEquals(10 * S, project.mainTrack[0].sourceOutUs)
            assertEquals(0, edits)
        }
        // 左端の取っ手が動き、元の位置には残っていない。ルーラーは playhead=0 のまま。
        val image = node.captureToImage().toPixelMap()
        val center = image.width / 2
        // 取っ手中央の白いグリップ線 (33dp) を避け、塗りの位置を調べる。
        assertEquals(Color.Magenta, image[center + dp(28f).toInt(), dp(54f).toInt()])
        assertNotEquals(Color.Magenta, image[center - dp(7f).toInt(), dp(54f).toInt()])
        node.performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, edits); assertFalse(timeline.scrubbing) }
    }

    @Test
    fun selectedBodyMovesWithoutLongPressAndJoinsTheNeighbor() {
        show()
        val original = project
        node.performTouchInput {
            down(Offset(centerX + dp(75f), dp(54f)))
            moveTo(Offset(centerX + dp(110f), dp(54f)), delayMillis = 100)
            up()
        }
        compose.runOnIdle {
            assertEquals(listOf("b", "a"), project.mainTrack.map { it.id })
            assertEquals(original.mainTrack[0], project.mainTrack[1])
            assertEquals(project.mainTrack[0].durationUs, project.mainStartUs(1))
            assertEquals(original.durationUs, project.durationUs)
            assertEquals(0L, timeline.playheadUs)
            assertEquals(1, edits)
        }
    }

    @Test
    fun shortClipHasAMiddleThatMovesInsteadOfTrimming() {
        show(firstSeconds = 1)
        node.performTouchInput {
            down(Offset(centerX + dp(7.5f), dp(54f)))
            moveTo(Offset(centerX + dp(42.5f), dp(54f)), delayMillis = 100)
            up()
        }
        compose.runOnIdle {
            assertEquals(listOf("b", "a"), project.mainTrack.map { it.id })
            assertEquals(S, project.mainTrack[1].sourceDurationUs)
        }
    }

    @Test
    fun movingTextSnapsItsRightEdgeToTheNextClipAndShowsGuide() {
        show(text = true)
        node.performTouchInput {
            down(Offset(centerX + dp(30f), dp(100f)))
            moveTo(Offset(centerX + dp(59f), dp(100f)), delayMillis = 100)
        }
        compose.runOnIdle {
            val clip = project.texts.first()
            assertEquals(3 * S, clip.startUs)
            assertEquals(2 * S, clip.durationUs)
            assertEquals(project.texts[1].startUs, clip.endUs)
        }
        val image = node.captureToImage().toPixelMap()
        assertEquals(Color.Magenta, image[image.width / 2 + dp(75f).toInt(), dp(2f).toInt()])
        node.performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, edits) }
    }

    @Test
    fun secondFingerCancelsAnUncommittedTrim() {
        show()
        val original = project
        node.performTouchInput {
            down(0, Offset(centerX - dp(7f), dp(54f)))
            moveTo(0, Offset(centerX + dp(33f), dp(54f)), delayMillis = 120)
            down(1, Offset(centerX + dp(80f), dp(54f)))
            up(1)
            up(0)
        }
        compose.runOnIdle { assertEquals(original, project); assertEquals(0, edits); assertFalse(timeline.scrubbing) }
    }

    @Test
    fun layerBodySnapsToAnotherLayersEdgeWithoutMovingMainTrack() {
        show(layer = true)
        val main = project.mainTrack
        // main=26..82dp, top layer=86..122dp, selected lower layer=126..162dp.
        node.performTouchInput {
            down(Offset(centerX + dp(30f), dp(144f)))
            moveTo(Offset(centerX + dp(59f), dp(144f)), delayMillis = 100)
            up()
        }
        compose.runOnIdle {
            assertEquals(3 * S, project.layers.first().startUs)
            assertEquals(5 * S, project.layers.first().endUs)
            assertEquals(main, project.mainTrack)
            assertEquals(1, edits)
        }
    }

    @Test
    fun layerLeftTrimKeepsItsRightEdgeAndOtherTracksStill() {
        show(layer = true)
        val before = project
        node.performTouchInput {
            down(Offset(centerX + dp(8f), dp(144f)))
            moveTo(Offset(centerX + dp(25f), dp(144f)), delayMillis = 100)
            up()
        }
        compose.runOnIdle {
            assertTrue(project.layers.first().startUs > before.layers.first().startUs)
            assertEquals(before.layers.first().endUs, project.layers.first().endUs)
            assertEquals(before.layers.last(), project.layers.last())
            assertEquals(before.mainTrack, project.mainTrack)
            assertEquals(0L, timeline.playheadUs)
        }
    }


    @Test
    fun verticalDragOverSelectedLayerScrollsRowsWithoutEditingTime() {
        show(layer = true, scrollRows = true)
        val before = project
        compose.runOnIdle { selection = ClipRef.Layer("top") }
        node.performTouchInput {
            down(Offset(centerX + dp(82.5f), dp(104f)))
            moveTo(Offset(centerX + dp(82.5f), dp(44f)), delayMillis = 200)
            up()
        }
        compose.runOnIdle {
            assertTrue(verticalScroll.value > 0)
            assertEquals(before, project)
            assertEquals(0L, timeline.playheadUs)
            assertEquals(0, edits)
        }
    }

}
