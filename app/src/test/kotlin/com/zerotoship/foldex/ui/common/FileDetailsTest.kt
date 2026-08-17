// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * プロパティの数値まわりの変換だけを見る。
 * 実体を読む部分 (EXIF / MediaMetadataRetriever) は Android 実機が要るのでここでは扱わない。
 */
class FileDetailsTest {

    // --- サイズ ---

    @Test
    fun `サイズは単位を繰り上げる`() {
        assertEquals("0 B", FileDetails.formatBytes(0))
        assertEquals("512 B", FileDetails.formatBytes(512))
        assertEquals("1.0 KB", FileDetails.formatBytes(1024))
        assertEquals("1.0 MB", FileDetails.formatBytes(1024L * 1024))
        assertEquals("1.5 GB", FileDetails.formatBytes((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `負のサイズでも落ちない`() {
        assertEquals("0 B", FileDetails.formatBytes(-1))
    }

    // --- 再生時間 ---

    @Test
    fun `1時間未満は分秒 それ以上は時分秒`() {
        assertEquals("0:05", FileDetails.formatDuration(5_000))
        assertEquals("1:30", FileDetails.formatDuration(90_000))
        assertEquals("1:00:00", FileDetails.formatDuration(3_600_000))
        assertEquals("2:03:04", FileDetails.formatDuration((2 * 3600 + 3 * 60 + 4) * 1000L))
    }

    // --- 画素数 ---

    @Test
    fun `総画素数は幅かける高さ`() {
        assertEquals(12_000_000L, FileDetails.pixelCount(4000, 3000))
        assertEquals(10_000L, FileDetails.pixelCount(100, 100))
    }

    @Test
    fun `メガピクセル値に直せる`() {
        assertEquals(12.0, FileDetails.megaPixelValue(4000, 3000), 1e-9)
        assertEquals(2.0736, FileDetails.megaPixelValue(1920, 1080), 1e-9)
    }

    @Test
    fun `100万画素未満は1を下回る`() {
        // 1.0 未満のときは「N ピクセル」表記に切り替える判断に使う。
        assertTrue(FileDetails.megaPixelValue(100, 100) < 1.0)
        assertTrue(FileDetails.megaPixelValue(1920, 1080) >= 1.0)
    }

    @Test
    fun `大きな画像でも桁あふれしない`() {
        // Int どうしの掛け算だと 3 万 x 3 万でオーバーフローする。
        assertEquals(900_000_000L, FileDetails.pixelCount(30_000, 30_000))
    }

    // --- 縦横比 ---

    @Test
    fun `よくある比は名前で出す`() {
        assertEquals("16:9", FileDetails.aspectRatio(1920, 1080))
        assertEquals("9:16", FileDetails.aspectRatio(1080, 1920))
        assertEquals("4:3", FileDetails.aspectRatio(4000, 3000))
        assertEquals("1:1", FileDetails.aspectRatio(500, 500))
    }

    @Test
    fun `当てはまらない比は約分して出す`() {
        assertEquals("5:2", FileDetails.aspectRatio(1000, 400))
    }

    @Test
    fun `幅か高さが0なら比は出さない`() {
        assertNull(FileDetails.aspectRatio(0, 100))
        assertNull(FileDetails.aspectRatio(100, 0))
    }

    // --- シャッター速度 ---

    @Test
    fun `1秒未満はカメラと同じ分数になる`() {
        assertEquals(125, FileDetails.shutterDenominator(1.0 / 125))
        assertEquals(60, FileDetails.shutterDenominator(1.0 / 60))
    }

    @Test
    fun `1秒以上は分数にしない`() {
        assertNull(FileDetails.shutterDenominator(1.0))
        assertNull(FileDetails.shutterDenominator(2.5))
    }

    @Test
    fun `0以下は分数にしない`() {
        assertNull(FileDetails.shutterDenominator(0.0))
        assertNull(FileDetails.shutterDenominator(-1.0))
    }

    // --- EXIF の分数 ---

    @Test
    fun `分数表記を数値にできる`() {
        assertEquals(24.0, FileDetails.fractionToDouble("24/1")!!, 1e-9)
        assertEquals(5.5, FileDetails.fractionToDouble("11/2")!!, 1e-9)
        assertEquals(35.0, FileDetails.fractionToDouble("35")!!, 1e-9)
    }

    @Test
    fun `壊れた分数は null`() {
        assertNull(FileDetails.fractionToDouble("24/0"))
        assertNull(FileDetails.fractionToDouble("abc"))
        assertNull(FileDetails.fractionToDouble("1/2/3"))
    }
}
