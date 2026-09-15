// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.model

import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.ui.videoedit.plan.PlanNotice
import com.zerotoship.foldex.ui.videoedit.plan.RenderPlanner
import com.zerotoship.foldex.ui.videoedit.previewLayerFrameAt
import org.junit.Assert.*
import org.junit.Test

private const val S = US_PER_SECOND

class LayerEditingTest {
    private val video = MediaAsset("v", AssetKind.VIDEO, "/v.mp4", "v.mp4", 20 * S, 1920, 1080, true)
    private val image = MediaAsset("i", AssetKind.IMAGE, "/i.png", "i.png", 0, 400, 800)
    private fun project(speed: Double = 1.0) = VideoProject(
        assets = mapOf(video.id to video, image.id to image),
        mainTrack = listOf(MainClip("main", "v", 0, 10 * S)),
        layers = listOf(LayerClip(MainClip("layer", "v", 2 * S, 8 * S, speed = speed), 3 * S, centerX = 0.25f)),
    )

    @Test fun `追加したレイヤーは指定した時刻に置き音声はミュートする`() {
        val before = project()
        val after = LayerEditOps.add(before, image, "image", 2 * S)
        assertEquals(before.mainTrack, after.mainTrack)
        assertEquals(2 * S, after.layers.last().startUs)
        assertTrue(after.layers.last().clip.muted)
        assertEquals(3 * S, after.layers.last().durationUs)
        assertEquals(before, LayerEditOps.add(before, video.copy(kind = AssetKind.AUDIO), "invalid", 0))
    }

    @Test fun `左トリムは右端と他の段を固定し原本の先頭を越えない`() {
        for (speed in listOf(0.5, 1.0, 2.0)) {
            val before = project(speed)
            val shortened = LayerEditOps.trimStart(before, "layer", 4 * S)
            assertEquals(before.layers.single().endUs, shortened.layers.single().endUs)
            assertEquals(before.mainTrack, shortened.mainTrack)
            assertEquals((2 * S + speed * S).toLong(), shortened.layers.single().clip.sourceInUs)
            val extended = LayerEditOps.trimStart(before, "layer", -100 * S).layers.single()
            assertTrue(extended.startUs >= 0)
            assertTrue(extended.clip.sourceInUs >= 0)
            assertEquals(before.layers.single().endUs, extended.endUs)
        }
    }

    @Test fun `右トリムは開始と原本の上限を守る`() {
        val before = project(2.0)
        val after = LayerEditOps.trimEnd(before, "layer", 100 * S).layers.single()
        assertEquals(3 * S, after.startUs)
        assertEquals(video.durationUs, after.clip.sourceOutUs)
        assertEquals(12 * S, after.endUs)
        assertTrue(LayerEditOps.trimEnd(before, "layer", -S).layers.single().durationUs >= before.frameRate.frameDurationUs)
    }

    @Test fun `区間移動は配置と長さを固定しメインに影響しない`() {
        val before = project(2.0)
        val after = LayerEditOps.editSource(before, "layer") { VideoEditOps.shiftMainSource(it, "layer", 10 * S) }
        assertEquals(before.mainTrack, after.mainTrack)
        assertEquals(before.layers.single().copy(clip = before.layers.single().clip.copy(sourceInUs = 10 * S, sourceOutUs = 16 * S)), after.layers.single())
    }

    @Test fun `分割は異なる素材区間を連続配置し境界のプレビューを後半に切り替える`() {
        for (speed in listOf(0.5, 1.0, 2.0)) {
            val before = project(speed)
            val cut = 3 * S + before.layers.single().durationUs / 2
            val after = LayerEditOps.split(before, "layer", cut, "second")
            val (first, second) = after.layers
            assertEquals(5 * S, first.clip.sourceOutUs)
            assertEquals(first.clip.sourceOutUs, second.clip.sourceInUs)
            assertEquals(first.endUs, second.startUs)
            assertEquals(before.layers.single().endUs, second.endUs)
            assertNull(previewLayerFrameAt(after, first, cut, false))
            assertEquals(5 * S, previewLayerFrameAt(after, second, cut, true)!!.sourceTimeUs)
            assertEquals(before.mainTrack, after.mainTrack)
        }
    }

    @Test fun `端での分割は何も変えない`() {
        val before = project()
        assertEquals(before, LayerEditOps.split(before, "layer", 3 * S, "second"))
        assertEquals(before, LayerEditOps.split(before, "layer", 9 * S, "second"))
        assertEquals(before, LayerEditOps.split(before, "layer", 2 * S, "second"))
    }

    @Test fun `段の移動と前後関係は独立してUndoで戻る`() {
        val before = LayerEditOps.add(project(), image, "top", 0)
        val moved = LayerEditOps.move(before, "layer", S)
        val after = LayerEditOps.reorder(moved, "layer", 1)
        assertEquals(listOf("top", "layer"), after.layers.map { it.id })
        assertEquals(S, after.layers.last().startUs)
        assertEquals(before.mainTrack, after.mainTrack)
        val history = ProjectHistory()
        history.record(before)
        assertEquals(before, history.undo(after))
        assertEquals(after, history.redo(before))
    }

    @Test fun `書き出しはレイヤーの開始終了を保持しメインの終わりで切る`() {
        val project = project(0.5)
        val plan = (RenderPlanner.plan(project, ExportSettings()) as Result.Success).value
        val layer = plan.layers.single()
        assertEquals(3 * S, layer.startUs)
        assertEquals(10 * S, layer.endUs)
        assertEquals(5_500_000L, layer.media.sourceOutUs)
        assertEquals(7 * S, layer.media.durationUs)
        assertEquals(10 * S, layer.items(plan.durationUs).sumOf { it.durationUs })
        assertTrue(PlanNotice.TRIMMED_AFTER_VIDEO in plan.notices)
        assertEquals(listOf("v"), plan.media.map { it.assetId })
    }

    @Test fun `非表示のレイヤーはプレビューと書き出しの両方から除外する`() {
        val p = LayerEditOps.update(project(), "layer") { it.copy(visible = false) }
        assertNull(previewLayerFrameAt(p, p.layers.single(), 4 * S, false))
        val plan = (RenderPlanner.plan(p, ExportSettings()) as Result.Success).value
        assertTrue(plan.layers.isEmpty())
    }

    @Test fun `静止画の時間と回転後の比率を計画へ渡す`() {
        val p = LayerEditOps.add(project().copy(layers = emptyList()), image, "image", S)
        val rotated = LayerEditOps.update(p, "image") { it.copy(clip = it.clip.copy(quarterTurns = 1), widthFraction = 0.7f, opacity = 0.4f) }
        val plan = (RenderPlanner.plan(rotated, ExportSettings()) as Result.Success).value
        assertEquals(2f, plan.layers.single().aspectRatio, 0f)
        assertEquals(0.7f, plan.layers.single().widthFraction, 0f)
        assertEquals(0.4f, plan.layers.single().opacity, 0f)
        assertEquals(0L, plan.layers.single().media.sourceOutUs)
        assertEquals(setOf("v", "i"), plan.media.map { it.assetId }.toSet())
        assertEquals(10 * S, plan.layers.single().items(plan.durationUs).sumOf { it.durationUs })
    }

    @Test fun `音声だけの出力にも動画レイヤーの速度と音量を引き継ぐ`() {
        val p = project(0.5).copy(mainTrack = emptyList())
        val plan = (RenderPlanner.plan(p, ExportSettings(preset = ExportPreset.AUDIO_ONLY)) as Result.Success).value
        assertEquals(15 * S, plan.durationUs)
        assertEquals(0.5, plan.layers.single().media.speed, 0.0)
        assertTrue(plan.main.isEmpty())
        assertTrue(plan.output.audioOnly)
    }

    @Test fun `削除は選択レイヤーだけで複製は終了時刻に置く`() {
        val before = project()
        val ref = ClipRef.Layer("layer")
        val after = VideoEditOps.duplicate(before, ref, "copy")
        assertEquals(9 * S, after.layers.last().startUs)
        assertEquals(before.layers.first().clip.copy(id = "copy"), after.layers.last().clip)
        assertFalse(VideoEditOps.delete(after, ref).contains(ref))
        assertEquals(before.mainTrack, VideoEditOps.delete(after, ref).mainTrack)
    }
}
