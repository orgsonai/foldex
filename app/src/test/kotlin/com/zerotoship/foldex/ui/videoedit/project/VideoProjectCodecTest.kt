// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.project

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
