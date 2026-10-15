// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val S = US_PER_SECOND

class VideoEditOpsTest {

    private val video = MediaAsset("a", AssetKind.VIDEO, "/v.mp4", "v.mp4", durationUs = 10 * S)
    private val music = MediaAsset("m", AssetKind.AUDIO, "/m.mp3", "m.mp3", durationUs = 30 * S)

    private fun project(vararg clips: MainClip) = VideoProject(assets = mapOf("a" to video, "m" to music), mainTrack = clips.toList())

    private fun clip(id: String, inUs: Long, outUs: Long, speed: Double = 1.0) = MainClip(id, "a", inUs, outUs, speed = speed)

    @Test
    fun `再生位置で分割しても全体の長さは変わらない`() {
        val before = project(clip("c1", 0, 10 * S), clip("c2", 0, 4 * S))
        val after = VideoEditOps.splitMainAt(before, 3 * S, "new")
        assertEquals(listOf("c1", "new", "c2"), after.mainTrack.map { it.id })
        assertEquals(3 * S, after.mainTrack[0].durationUs)
        assertEquals(7 * S, after.mainTrack[1].durationUs)
        assertEquals(before.durationUs, after.durationUs)
    }

    @Test
    fun `速くしてもタイムライン上で 1 コマ未満にならない`() {
        val frame = FrameRate.FPS_30.frameDurationUs
        val fast = VideoEditOps.setMainSpeed(project(clip("c", 0, 4 * frame)), "c", 16.0).mainTrack.single()
        assertEquals(4.0, fast.speed, 1e-9)
        assertEquals(frame, fast.durationUs)
        val normal = VideoEditOps.setMainSpeed(project(clip("c", 0, 10 * S)), "c", 2.0).mainTrack.single()
        assertEquals(2.0, normal.speed, 1e-9)
    }

    @Test
    fun `2 倍速のクリップは素材の時刻を 2 倍進めた位置で分ける`() {
        val after = VideoEditOps.splitMainAt(project(clip("c1", 0, 10 * S, speed = 2.0)), 2 * S, "n")
        assertEquals(4 * S, after.mainTrack[0].sourceOutUs)
        assertEquals(4 * S, after.mainTrack[1].sourceInUs)
    }

    @Test
    fun `端から 1 コマ未満では分割しない`() {
        val before = project(clip("c1", 0, 10 * S))
        assertEquals(before, VideoEditOps.splitMainAt(before, 10_000L, "n"))
        assertEquals(before, VideoEditOps.splitMainAt(before, 10 * S - 10_000L, "n"))
    }

    @Test
    fun `区間ごとの速度の違いが全体の長さに反映される`() {
        // 設計書 §4 の例: 0〜4 秒を 1 倍、4〜6 秒を 0.5 倍、6〜10 秒を 1 倍 → 4 + 4 + 4 = 12 秒。
        val p = project(clip("a1", 0, 4 * S), clip("a2", 4 * S, 6 * S, speed = 0.5), clip("a3", 6 * S, 10 * S))
        assertEquals(12 * S, p.durationUs)
    }

    @Test
    fun `トリムは素材の範囲と 1 コマの長さに収める`() {
        val p = project(clip("c1", 2 * S, 8 * S))
        assertEquals(0L, VideoEditOps.trimMainStart(p, "c1", -5 * S).mainTrack[0].sourceInUs)
        assertEquals(8 * S - p.frameRate.frameDurationUs, VideoEditOps.trimMainStart(p, "c1", 9 * S).mainTrack[0].sourceInUs)
        assertEquals(10 * S, VideoEditOps.trimMainEnd(p, "c1", 99 * S).mainTrack[0].sourceOutUs)
    }

    @Test
    fun `並べ替えと削除で後ろのクリップが前に詰まる`() {
        val p = project(clip("x", 0, 1 * S), clip("y", 0, 2 * S), clip("z", 0, 3 * S))
        val moved = VideoEditOps.moveMain(p, 0, 2)
        assertEquals(listOf("y", "z", "x"), moved.mainTrack.map { it.id })
        assertEquals(5 * S, moved.mainStartUs(2))
        val deleted = VideoEditOps.delete(p, ClipRef.Main("y"))
        assertEquals(1 * S, deleted.mainStartUs(1))
        assertFalse(deleted.contains(ClipRef.Main("y")))
    }

    @Test
    fun `音声の開始側のトリムは中身の位置を保つ`() {
        val p = VideoProject(
            assets = mapOf("m" to music),
            audioTrack = listOf(AudioClip("s", "m", startUs = 5 * S, sourceInUs = 2 * S, sourceOutUs = 12 * S)),
        )
        val trimmed = VideoEditOps.trimAudioStart(p, "s", 7 * S).audioTrack[0]
        assertEquals(7 * S, trimmed.startUs)
        assertEquals(4 * S, trimmed.sourceInUs)
        assertEquals(15 * S, trimmed.endUs)
        // 素材の先頭 (タイムライン上 3 秒) より前には伸ばせない。
        assertEquals(3 * S, VideoEditOps.trimAudioStart(p, "s", 0L).audioTrack[0].startUs)
        // 素材の終わり (30 秒 → タイムライン上 33 秒) より後ろには伸ばせない。
        assertEquals(33 * S, VideoEditOps.trimAudioEnd(p, "s", 99 * S).audioTrack[0].endUs)
    }

    @Test
    fun `フェードはクリップの長さに収める`() {
        val p = project(clip("c1", 0, 10 * S))
        val faded = VideoEditOps.setMainFades(p, "c1", 8 * S, 8 * S).mainTrack[0]
        assertEquals(8 * S, faded.fadeInUs)
        assertEquals(2 * S, faded.fadeOutUs)
    }

    @Test
    fun `長さの分からない映像は追加しない`() {
        val broken = video.copy(id = "b", durationUs = 0L)
        assertEquals(VideoProject(), VideoEditOps.appendMedia(VideoProject(), broken, "c", 0L))
        val withImage = VideoEditOps.appendMedia(VideoProject(), video.copy(id = "i", kind = AssetKind.IMAGE, durationUs = 0L), "c", 0L)
        assertEquals(VideoEditOps.DEFAULT_IMAGE_DURATION_US, withImage.durationUs)
    }

    @Test
    fun `複製は直後に同じ内容を置く`() {
        val p = project(clip("x", 0, 1 * S), clip("y", 0, 2 * S))
        val copied = VideoEditOps.duplicate(p, ClipRef.Main("x"), "x2")
        assertEquals(listOf("x", "x2", "y"), copied.mainTrack.map { it.id })
        assertEquals(4 * S, copied.durationUs)
    }

    @Test
    fun `履歴は上限を超えた古い手を捨て、新しい操作でやり直しを消す`() {
        val history = ProjectHistory(limit = 2)
        val a = project(clip("a", 0, 1 * S))
        val b = project(clip("b", 0, 1 * S))
        val c = project(clip("c", 0, 1 * S))
        history.record(a)
        history.record(b)
        history.record(c)
        val current = VideoProject()
        assertEquals(c, history.undo(current))
        assertEquals(b, history.undo(c))
        assertNull(history.undo(b))
        assertTrue(history.canRedo)
        history.record(b)
        assertFalse(history.canRedo)
    }
}
