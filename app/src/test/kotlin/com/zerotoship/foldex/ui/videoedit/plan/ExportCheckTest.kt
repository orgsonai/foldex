// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.plan

import com.zerotoship.foldex.ui.videoedit.model.ExportEstimate.MIB
import com.zerotoship.foldex.ui.videoedit.model.US_PER_SECOND
import com.zerotoship.foldex.ui.videoedit.model.VideoCodecChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val S = US_PER_SECOND

class ExportCheckTest {

    private val video = OutputSpec(1920, 1080, 30, audioOnly = false, codec = VideoCodecChoice.H264, videoKbps = 10_000, audioKbps = 192, targetBytes = null)

    private fun facts(
        width: Int = 1920,
        height: Int = 1080,
        videoMime: String? = "video/avc",
        audioMime: String? = "audio/mp4a-latm",
        durationUs: Long = 10 * S,
        sizeBytes: Long = 1_000,
    ) = OutputFacts(width, height, videoMime, audioMime, durationUs, sizeBytes)

    @Test
    fun `指定どおりなら条件未達は無い`() {
        assertTrue(ExportCheck.unmet(video, 10 * S, facts()).isEmpty())
    }

    @Test
    fun `解像度とコーデックが切り替わったら要求と実際を返す`() {
        val unmet = ExportCheck.unmet(video.copy(codec = VideoCodecChoice.HEVC), 10 * S, facts(width = 1280, height = 720))
        assertEquals(
            listOf(UnmetCondition.Size(1920, 1080, 1280, 720), UnmetCondition.Codec("video/hevc", "video/avc")),
            unmet,
        )
    }

    @Test
    fun `長さの差は 3 コマか 0_1 秒の大きい方まで許す`() {
        assertEquals(100_000L, ExportCheck.durationToleranceUs(30))
        assertEquals(125_000L, ExportCheck.durationToleranceUs(24))
        assertEquals(100_000L, ExportCheck.durationToleranceUs(60))
        assertTrue(ExportCheck.unmet(video, 10 * S, facts(durationUs = 10 * S + 100_000)).isEmpty())
        assertEquals(
            listOf(UnmetCondition.Duration(10 * S, 10 * S + 100_001)),
            ExportCheck.unmet(video, 10 * S, facts(durationUs = 10 * S + 100_001)),
        )
    }

    @Test
    fun `容量指定で目標を超えたら条件未達`() {
        val target = video.copy(targetBytes = 50 * MIB)
        assertEquals(listOf(UnmetCondition.TargetSize(50 * MIB, 60 * MIB)), ExportCheck.unmet(target, 10 * S, facts(sizeBytes = 60 * MIB)))
    }

    @Test
    fun `書き出し直しのビットレートは目標との比に余裕を掛ける`() {
        // 2,000 × 50 / 60 × 0.95 = 1,583.3
        assertEquals(1_583, ExportCheck.retryVideoKbps(2_000, 50 * MIB, 60 * MIB))
        assertEquals(2_000, ExportCheck.retryVideoKbps(2_000, 50 * MIB, 0))
    }

    @Test
    fun `空・音声なし・映像の書き出しで映像なしの出力は壊れている`() {
        assertFalse(ExportCheck.isBroken(video, facts()))
        assertTrue(ExportCheck.isBroken(video, facts(sizeBytes = 0)))
        assertTrue(ExportCheck.isBroken(video, facts(audioMime = null)))
        assertTrue(ExportCheck.isBroken(video, facts(videoMime = null, width = 0, height = 0)))
        val audioOnly = video.copy(width = 0, height = 0, audioOnly = true, videoKbps = 0)
        assertFalse(ExportCheck.isBroken(audioOnly, facts(videoMime = null, width = 0, height = 0)))
    }

    @Test
    fun `必要な空き容量は見積もりの 1_1 倍に 32MiB を足す`() {
        assertEquals(142 * MIB, ExportCheck.requiredFreeBytes(100 * MIB))
        assertEquals(32 * MIB, ExportCheck.requiredFreeBytes(-1))
    }
}
