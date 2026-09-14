// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportEstimateTest {

    @Test
    fun `容量指定は設計書の計算例と一致する`() {
        // 50MiB・180 秒・音声 128kbps → 全体 2,330kbps → 余裕 2% と音声を引いて 2,155kbps。
        assertEquals(2_155, ExportEstimate.videoKbpsForTarget(50L * ExportEstimate.MIB, 180L * US_PER_SECOND, 128))
    }

    @Test
    fun `1080p の下限を下回ると 720p を提案する`() {
        val result = ExportEstimate.estimate(
            ExportSettings(preset = ExportPreset.TARGET_SIZE, resolution = ExportResolution.P1080, targetMiB = 50, audioKbps = 128),
            180L * US_PER_SECOND,
        )!!
        assertTrue(result.belowMinimum)
        assertEquals(ExportResolution.P720, result.suggestedResolution)
        assertEquals(50L * ExportEstimate.MIB, result.estimatedBytes)
    }

    @Test
    fun `どの解像度でも足りなければ提案せず、映像は 0 にする`() {
        val result = ExportEstimate.estimate(
            ExportSettings(preset = ExportPreset.TARGET_SIZE, targetMiB = 1, audioKbps = 128),
            600L * US_PER_SECOND,
        )!!
        assertTrue(result.belowMinimum)
        assertNull(result.suggestedResolution)
        assertEquals(0, result.videoKbps)
    }

    @Test
    fun `標準は映像と音声の合計に容器の余裕を足す`() {
        val result = ExportEstimate.estimate(ExportSettings(audioKbps = 192), 60L * US_PER_SECOND)!!
        assertEquals(10_000, result.videoKbps)
        // (10,000 + 192) kbps × 60 秒 ÷ 8 × 1.02
        assertEquals(77_968_800L, result.estimatedBytes)
        assertFalse(result.belowMinimum)
    }

    @Test
    fun `60fps と HEVC の補正`() {
        assertEquals(9_000, ExportEstimate.defaultVideoKbps(ExportResolution.P1080, 60, VideoCodecChoice.HEVC))
        assertEquals(16_000, ExportEstimate.estimate(ExportSettings(preset = ExportPreset.HIGH_QUALITY), US_PER_SECOND)!!.videoKbps)
    }

    @Test
    fun `音声のみは映像を 0 にし、無劣化カットは見積もらない`() {
        val audio = ExportEstimate.estimate(ExportSettings(preset = ExportPreset.AUDIO_ONLY, audioKbps = 160), 10L * US_PER_SECOND)!!
        assertEquals(0, audio.videoKbps)
        assertEquals(204_000L, audio.estimatedBytes)
        assertNull(ExportEstimate.estimate(ExportSettings(preset = ExportPreset.LOSSLESS_CUT), 10L * US_PER_SECOND))
    }

    @Test
    fun `無劣化カットは加工のない動画 1 本のときだけ使える`() {
        val asset = MediaAsset("a", AssetKind.VIDEO, "/v.mp4", "v.mp4", durationUs = 10L * US_PER_SECOND)
        val single = VideoProject(assets = mapOf("a" to asset), mainTrack = listOf(MainClip("c", "a", 0L, 5L * US_PER_SECOND)))
        assertTrue(ExportEstimate.isLosslessCandidate(single))
        assertFalse(ExportEstimate.isLosslessCandidate(VideoEditOps.setMainSpeed(single, "c", 2.0)))
        assertFalse(ExportEstimate.isLosslessCandidate(VideoEditOps.addText(single, "t", 0L, "x")))
        assertFalse(ExportEstimate.isLosslessCandidate(VideoProject()))
    }
}
