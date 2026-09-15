// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.project

import com.zerotoship.foldex.core.common.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

class VideoProjectStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private val root get() = File(temporary.root, "video-drafts")

    private fun create(store: VideoProjectStore): VideoDraft =
        (store.create(sampleDraft(id = UUID.randomUUID().toString())) as Result.Success).value

    private fun revised(draft: VideoDraft, revision: Long) =
        draft.copy(revision = revision, updatedAt = draft.updatedAt + revision, name = "版 $revision")

    private fun manifests(id: String) = File(root, id).listFiles()!!.filter { it.name.endsWith(".bin") }.sortedBy { it.name }

    @Test
    fun `保存した最新の内容を別のストアから開ける`() {
        val store = VideoProjectStore(root)
        val draft = create(store)
        val updated = revised(draft, 1)
        assertEquals(1L, store.save(updated).getOrNull())
        store.close()
        val opened = VideoProjectStore(root).open(draft.id).getOrNull()!!
        assertEquals(updated, opened.draft)
        assertFalse(opened.recoveredPrevious)
    }

    @Test
    fun `正常な世代は新しい 2 つだけ残す`() {
        val store = VideoProjectStore(root)
        val draft = create(store)
        (1L..3L).forEach { assertTrue(store.save(revised(draft, it)).isSuccess) }
        val kept = manifests(draft.id).map { it.name.removePrefix("manifest-").removeSuffix(".bin").toLong() }
        assertEquals(listOf(2L, 3L), kept)
    }

    @Test
    fun `最新の世代が壊れていたら 1 つ前を開き、戻したことを返す`() {
        val store = VideoProjectStore(root)
        val draft = create(store)
        assertTrue(store.save(revised(draft, 1)).isSuccess)
        store.close()
        val latest = manifests(draft.id).last()
        latest.writeBytes(latest.readBytes().also { it[12] = (it[12] + 1).toByte() })
        val opened = VideoProjectStore(root).open(draft.id).getOrNull()!!
        assertEquals(draft, opened.draft)
        assertTrue(opened.recoveredPrevious)
    }

    @Test
    fun `新しい版で書かれた最新の世代は古い世代で隠さない`() {
        val store = VideoProjectStore(root)
        val draft = create(store)
        store.close()
        val bytes = ByteArrayOutputStream().also { VideoProjectCodec.write(revised(draft, 1), it) }.toByteArray()
        bytes[7] = 9
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        File(root, "${draft.id}/manifest-${"1".padStart(20, '0')}.bin").writeBytes(bytes + digest)
        assertEquals(VideoDraftFailure.UNSUPPORTED, VideoProjectStore(root).open(draft.id).errorOrNull())
    }

    @Test
    fun `使用中の下書きは別のストアから開けず、削除もできない`() {
        val store = VideoProjectStore(root)
        val draft = create(store)
        val other = VideoProjectStore(root)
        assertEquals(VideoDraftFailure.BUSY, other.open(draft.id).errorOrNull())
        assertEquals(VideoDraftFailure.BUSY, other.delete(draft.id).errorOrNull())
        assertTrue(other.list().getOrNull()!!.single().inUse)
        store.release(draft.id)
        assertTrue(other.delete(draft.id).isSuccess)
        assertTrue(other.list().getOrNull()!!.isEmpty())
    }

    @Test
    fun `保存の直前に失敗しても、最後に保存した内容が残る`() {
        var fail = false
        val store = VideoProjectStore(root) { if (fail) throw IOException("disk full") }
        val draft = create(store)
        fail = true
        assertEquals(VideoDraftFailure.IO, store.save(revised(draft, 1)).errorOrNull())
        assertEquals(1, manifests(draft.id).size)
        store.close()
        assertEquals(draft, VideoProjectStore(root).open(draft.id).getOrNull()!!.draft)
    }

    @Test
    fun `古い revision の保存は無視し、同じ revision で内容が違えば衝突にする`() {
        val store = VideoProjectStore(root)
        val draft = create(store)
        val first = revised(draft, 1)
        assertTrue(store.save(first).isSuccess)
        assertEquals(1L, store.save(revised(draft, 0)).getOrNull())
        assertEquals(VideoDraftFailure.CONFLICT, store.save(first.copy(name = "別の内容")).errorOrNull())
        assertEquals(1L, store.save(first).getOrNull())
    }

    @Test
    fun `破棄した下書きは一覧に残らない`() {
        val store = VideoProjectStore(root)
        val draft = create(store)
        assertTrue(store.discard(draft.id).isSuccess)
        assertTrue(VideoProjectStore(root).list().getOrNull()!!.isEmpty())
    }
}
