// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.project

import com.zerotoship.foldex.ui.videoedit.model.LayerClip
import com.zerotoship.foldex.ui.videoedit.model.MainClip
import com.zerotoship.foldex.ui.videoedit.model.US_PER_SECOND
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class VideoProjectCodecTest {

    private fun encode(draft: VideoDraft): ByteArray = ByteArrayOutputStream().also { VideoProjectCodec.write(draft, it) }.toByteArray()

    private fun decode(bytes: ByteArray): VideoDraft = VideoProjectCodec.read(ByteArrayInputStream(bytes))

    @Test
    fun `1_12_1が保存した下書きを値を落とさずに開く`() {
        val restored = javaClass.getResourceAsStream("/videoedit/schema1.fdxv")!!.use { VideoProjectCodec.read(it) }
        assertEquals(sampleDraft(), restored)
        assertEquals(restored, decode(encode(restored)))
    }

    @Test
    fun `映像レイヤーの配置順序と全項目を復元する`() {
        val before = sampleDraft()
        val layer = LayerClip(before.project.mainTrack.first().copy(id = "layer"), 2 * US_PER_SECOND,
            centerX = 0.2f, centerY = 0.7f, widthFraction = 1.3f, opacity = 0.4f, visible = false)
        val draft = before.copy(project = before.project.copy(layers = listOf(layer, layer.copy(clip = layer.clip.copy(id = "top")))))
        assertEquals(draft, decode(encode(draft)))
    }

    @Test
    fun `不正なレイヤーの大きさや重複IDを拒否する`() {
        val before = sampleDraft()
        val layer = LayerClip(before.project.mainTrack.first(), 0)
        for (bad in listOf(layer, layer.copy(clip = layer.clip.copy(id = "new"), widthFraction = Float.NaN),
            layer.copy(clip = layer.clip.copy(id = "new"), opacity = 2f))) {
            val draft = before.copy(project = before.project.copy(layers = listOf(bad)))
            assertThrows(IllegalArgumentException::class.java) { decode(encode(draft)) }
        }
    }

    @Test
    fun `すべての項目が書いて読んで一致する`() {
        val draft = sampleDraft()
        assertEquals(draft, decode(encode(draft)))
    }

    @Test
    fun `新しい版で書かれた記録は UNSUPPORTED で止める`() {
        val bytes = encode(sampleDraft())
        // 先頭 4 バイトが識別子、次の 4 バイトが版番号 (ビッグエンディアン)。
        bytes[7] = 9
        val error = assertThrows(VideoDraftException::class.java) { decode(bytes) }
        assertEquals(VideoDraftFailure.UNSUPPORTED, error.reason)
    }

    @Test
    fun `存在しない素材を指すクリップや末尾の余分なデータは読まない`() {
        val draft = sampleDraft()
        val broken = draft.copy(project = draft.project.copy(mainTrack = draft.project.mainTrack + MainClip("c9", "missing", 0, US_PER_SECOND)))
        assertThrows(IllegalArgumentException::class.java) { decode(encode(broken)) }
        assertThrows(IllegalArgumentException::class.java) { decode(encode(draft) + byteArrayOf(0)) }
    }

    @Test
    fun `同じ ID のクリップが重なる記録は読まない`() {
        val draft = sampleDraft()
        val duplicated = draft.copy(project = draft.project.copy(texts = draft.project.texts + draft.project.texts))
        assertThrows(IllegalArgumentException::class.java) { decode(encode(duplicated)) }
    }
}
