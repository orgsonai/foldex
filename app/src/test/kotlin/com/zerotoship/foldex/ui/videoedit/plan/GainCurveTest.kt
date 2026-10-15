// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.plan

import com.zerotoship.foldex.ui.videoedit.model.US_PER_SECOND
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val S = US_PER_SECOND

class GainCurveTest {

    @Test
    fun `フェードインの途中は経過に比例して小さくなる`() {
        val curve = GainCurve.of(volume = 0.5f, muted = false, fadeInUs = 2 * S, fadeOutUs = 0, durationUs = 10 * S)
        assertEquals(0f, curve.gainAt(0), 1e-6f)
        assertEquals(0.25f, curve.gainAt(1 * S), 1e-6f)
        assertEquals(0.5f, curve.gainAt(5 * S), 1e-6f)
    }

    @Test
    fun `フェードアウトは終わりに向かって 0 になる`() {
        val curve = GainCurve.of(volume = 1f, muted = false, fadeInUs = 0, fadeOutUs = 2 * S, durationUs = 10 * S)
        assertEquals(1f, curve.gainAt(8 * S), 1e-6f)
        assertEquals(0.5f, curve.gainAt(9 * S), 1e-6f)
        assertEquals(0f, curve.gainAt(10 * S), 1e-6f)
    }

    @Test
    fun `フェードが重なる短いクリップは小さい方の値を使う`() {
        val curve = GainCurve.of(volume = 1f, muted = false, fadeInUs = 4 * S, fadeOutUs = 4 * S, durationUs = 4 * S)
        // 1 秒: イン 1/4、アウト 3/4 → 1/4。3 秒: イン 3/4、アウト 1/4 → 1/4。
        assertEquals(0.25f, curve.gainAt(1 * S), 1e-6f)
        assertEquals(0.25f, curve.gainAt(3 * S), 1e-6f)
    }

    @Test
    fun `ミュートは常に 0 で、音量 200 パーセントは 2 倍のまま`() {
        assertEquals(0f, GainCurve.of(2f, true, 0, 0, S).gainAt(S / 2), 0f)
        assertEquals(2f, GainCurve.of(2f, false, 0, 0, S).gainAt(S / 2), 1e-6f)
    }

    @Test
    fun `加工の要らない曲線を見分ける`() {
        assertTrue(GainCurve.of(1f, false, 0, 0, S).isUnity)
        assertFalse(GainCurve.of(1f, true, 0, 0, S).isUnity)
        assertFalse(GainCurve.of(1f, false, 1, 0, S).isUnity)
    }

    @Test
    fun `フェードはクリップの長さに収める`() {
        val curve = GainCurve.of(1f, false, fadeInUs = 20 * S, fadeOutUs = -5, durationUs = 10 * S)
        assertEquals(10 * S, curve.fadeInUs)
        assertEquals(0L, curve.fadeOutUs)
    }
}
