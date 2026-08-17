// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `100万画素以上はメガピクセル表記`() {
        assertEquals("約 12.0 メガピクセル", FileDetails.megaPixels(4000, 3000))
        assertEquals("約 2.1 メガピクセル", FileDetails.megaPixels(1920, 1080))
    }

    @Test
    fun `小さい画像は実数で出す`() {
        assertEquals("10,000 ピクセル", FileDetails.megaPixels(100, 100))
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
    fun `1秒未満はカメラと同じ分数表記`() {
        assertEquals("1/125 秒", FileDetails.shutterText(1.0 / 125))
        assertEquals("1/60 秒", FileDetails.shutterText(1.0 / 60))
    }

    @Test
    fun `1秒以上は小数の秒`() {
        assertEquals("1.0 秒", FileDetails.shutterText(1.0))
        assertEquals("2.5 秒", FileDetails.shutterText(2.5))
    }

    @Test
    fun `0以下は横棒`() {
        assertEquals("-", FileDetails.shutterText(0.0))
        assertEquals("-", FileDetails.shutterText(-1.0))
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
