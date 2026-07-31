// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目標容量から品質を決める探索のテスト。
 * 実際のエンコードは差し替えられるようにしてあるので、端末なしで挙動を確かめられる。
 */
class ImageEncoderTest {

    /** 品質に比例してサイズが増える、という素直なモデル (品質 1 あたり 10KB)。 */
    private fun linearSize(quality: Int): Int = quality * 10_000

    @Test
    fun `目標に収まる最大の品質を選ぶ`() {
        // 500KB 以下 → 品質 50 まで (50 * 10KB = 500KB)
        val result = ImageEncoder.findQualityForTarget(500_000, measure = ::linearSize)
        assertTrue(result.withinTarget)
        assertEquals(50, result.quality)
        assertEquals(500_000, result.bytes)
    }

    @Test
    fun `目標が大きければ上限の品質になる`() {
        val result = ImageEncoder.findQualityForTarget(10_000_000, measure = ::linearSize)
        assertTrue(result.withinTarget)
        assertEquals(ImageEncoder.MAX_QUALITY, result.quality)
    }

    @Test
    fun `最低品質でも収まらないときは最低品質で妥協し収まらなかったと伝える`() {
        val result = ImageEncoder.findQualityForTarget(1_000, measure = ::linearSize)
        assertFalse(result.withinTarget)
        assertEquals(ImageEncoder.MIN_QUALITY, result.quality)
    }

    @Test
    fun `目標が緩ければエンコードは1回で済む`() {
        var calls = 0
        val result = ImageEncoder.findQualityForTarget(10_000_000) { q ->
            calls++
            linearSize(q)
        }
        assertEquals(1, calls)
        assertEquals(ImageEncoder.MAX_QUALITY, result.quality)
    }

    @Test
    fun `エンコード回数は上限を超えない`() {
        var calls = 0
        ImageEncoder.findQualityForTarget(500_000, maxSteps = 4) { q ->
            calls++
            linearSize(q)
        }
        // 最高品質の 1 回 + 探索 4 回 + 収まらなかった場合の最低品質 1 回。
        assertTrue("呼び出しは $calls 回", calls <= 6)
    }

    @Test
    fun `測定に失敗した品質は候補から外す`() {
        // -1 (エンコード失敗) を目標以下と誤判定しないこと。
        val result = ImageEncoder.findQualityForTarget(500_000) { -1 }
        assertFalse(result.withinTarget)
    }
}
