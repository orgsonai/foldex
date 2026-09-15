// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoExportResultTest {

    @Test
    fun `Media3 のエラーコードを案内の分類に対応付ける`() {
        val cases = mapOf(
            2005 to VideoExportFailure.SOURCE_MISSING,
            2006 to VideoExportFailure.SOURCE_MISSING,
            2001 to VideoExportFailure.READ_FAILED,
            3001 to VideoExportFailure.DECODER_FAILED,
            3003 to VideoExportFailure.UNSUPPORTED_FORMAT,
            4001 to VideoExportFailure.ENCODER_FAILED,
            4003 to VideoExportFailure.ENCODER_UNSUPPORTED,
            5001 to VideoExportFailure.VIDEO_PROCESSING_FAILED,
            6001 to VideoExportFailure.AUDIO_PROCESSING_FAILED,
            7002 to VideoExportFailure.WRITE_FAILED,
            1000 to VideoExportFailure.UNKNOWN,
            1001 to VideoExportFailure.UNKNOWN,
        )
        cases.forEach { (code, expected) -> assertEquals("code $code", expected, VideoExportFailure.fromMedia3ErrorCode(code)) }
    }

    @Test
    fun `保存名は _edited を付け、重なれば番号を足す`() {
        assertEquals("trip_edited.mp4", VideoSaveNaming.candidate(VideoSaveNaming.baseName("trip.mp4"), "mp4", 1))
        assertEquals("trip_edited2.mp4", VideoSaveNaming.candidate(VideoSaveNaming.baseName("trip_edited.mov"), "mp4", 2))
        assertEquals("video_edited.m4a", VideoSaveNaming.candidate(VideoSaveNaming.baseName(".mp4"), VideoSaveNaming.extension(audioOnly = true), 1))
    }

    @Test
    fun `端末内の最初の素材のフォルダを保存先にし、content だけなら保存先なし`() {
        val local = MediaAsset("a", AssetKind.VIDEO, "/sdcard/DCIM/trip.mp4", "trip.mp4", 10)
        val saf = MediaAsset("b", AssetKind.VIDEO, "content://docs/1", "clip.mp4", 10)
        val both = VideoProject(assets = mapOf("a" to local, "b" to saf), mainTrack = listOf(MainClip("1", "b", 0, 10), MainClip("2", "a", 0, 10)))
        assertEquals("/sdcard/DCIM", VideoSaveNaming.localFolder(both))
        // 名前は最初のクリップの素材から付ける。
        assertEquals("clip_edited.mp4", VideoSaveNaming.suggestedName(both, audioOnly = false))
        assertNull(VideoSaveNaming.localFolder(both.copy(mainTrack = listOf(MainClip("1", "b", 0, 10)))))
    }

    @Test
    fun `照合値は両方で取れた項目だけを比べる`() {
        val recorded = AssetStamp(sizeBytes = 1_000, modifiedMs = 5_000)
        assertTrue(recorded.matches(AssetStamp(1_000, 5_000)))
        assertFalse(recorded.matches(AssetStamp(1_001, 5_000)))
        assertFalse(recorded.matches(AssetStamp(1_000, 6_000)))
        // SAF の提供元によってはサイズも更新時刻も取れない。
        assertTrue(recorded.matches(AssetStamp(-1, -1)))
        assertTrue(AssetStamp(-1, -1).matches(AssetStamp(2_000, 9_000)))
    }
}
