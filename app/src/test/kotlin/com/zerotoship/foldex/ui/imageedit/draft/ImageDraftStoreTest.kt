// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.draft

import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.core.model.Protocol
import com.zerotoship.foldex.ui.imageedit.engine.ExportReceipt
import com.zerotoship.foldex.ui.imageedit.engine.FileFingerprint
import com.zerotoship.foldex.ui.imageedit.model.*
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

class ImageDraftStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root get() = File(temporary.root, "drafts")
    private fun source() = temporary.newFile().apply { writeText("immutable-original-pixels") }
    private fun document(file: File) = EditDocument.ofSingleImage("base", "画像.jpg", file.absolutePath,
        EditSize(1200, 800), ImageFormat.PNG, sourceTransparency = Transparency.OPAQUE)
    private fun create(store: ImageDraftStore, file: File = source()): ImageDraft =
        (store.create(UUID.randomUUID().toString(), file, "画像.jpg", null, document(file)) as Result.Success).value
    private fun manifests(id: String) = File(root, id).listFiles()!!.filter { it.name.endsWith(".bin") }.sortedBy { it.name }
    private fun open(id: String) = ImageDraftStore(root).open(id).getOrNull()!!
    private fun changed(draft: ImageDraft, revision: Long = draft.revision + 1) = draft.copy(
        revision = revision, updatedAt = draft.updatedAt + revision, dirty = true,
        document = draft.document.copy(output = draft.document.output.copy(quality = (90 - revision).toInt())),
    )

    @Test fun `原本が移動削除されても別のストアから素材と編集を復元できる`() {
        val store = ImageDraftStore(root)
        val source = source()
        val draft = create(store, source)
        val updated = changed(draft)
        assertTrue(store.save(updated).isSuccess)
        assertTrue(source.delete())
        store.close()
        val restored = open(draft.id)
        assertEquals(updated, restored.draft)
        assertFalse(restored.recoveredPrevious)
        val asset = ImageDraftStore(root).assetFile(draft.id, draft.assets.keys.single())
        assertEquals("immutable-original-pixels", asset.readText())
    }

    @Test fun `保存直前の容量不足で最後の正常な記録と原本を残す`() {
        var fail = false
        val store = ImageDraftStore(root) { if (fail) throw IOException("disk full") }
        val file = source()
        val draft = create(store, file)
        fail = true
        assertEquals(DraftFailure.IO, store.save(changed(draft)).errorOrNull())
        assertEquals("immutable-original-pixels", file.readText())
        assertEquals(1, manifests(draft.id).size)
        assertFalse(File(root, draft.id).listFiles()!!.any { it.name.endsWith(".tmp") })
        store.close()
        assertEquals(draft, open(draft.id).draft)
    }

    @Test fun `途中までの世代を復元せず正常な二世代だけを保持する`() {
        val store = ImageDraftStore(root)
        val draft = create(store)
        (1L..5L).forEach { assertTrue(store.save(changed(draft, it)).isSuccess) }
        assertEquals(2, manifests(draft.id).size)
        val pending = File(File(root, draft.id), "pending-interrupted.tmp").apply { writeText("partial") }
        store.close()
        assertEquals(5L, open(draft.id).draft.revision)
        assertFalse(pending.exists())
    }

    @Test fun `最新世代の同じ長さの破損から前の正常な記録へ戻れる`() {
        val store = ImageDraftStore(root)
        val draft = create(store)
        assertTrue(store.save(changed(draft)).isSuccess)
        val latest = manifests(draft.id).last()
        val bytes = latest.readBytes().apply { this[50] = (this[50].toInt() xor 1).toByte() }
        latest.writeBytes(bytes)
        store.close()
        val restored = open(draft.id)
        assertTrue(restored.recoveredPrevious)
        assertEquals(draft, restored.draft)
    }

    @Test fun `壊れた最新世代の番号を飛ばして復旧後の編集を保存する`() {
        val store = ImageDraftStore(root)
        val draft = create(store)
        store.save(changed(draft))
        manifests(draft.id).last().writeText("truncated")
        store.close()
        val reopened = ImageDraftStore(root)
        val restored = reopened.open(draft.id).getOrNull()!!.draft
        val revision = reopened.nextRevision(draft.id, restored.revision)
        assertEquals(2L, revision)
        assertTrue(reopened.save(changed(restored, revision)).isSuccess)
        assertEquals(2, manifests(draft.id).size)
    }

    @Test fun `未知の新しい形式では旧世代に戻して上書きしない`() {
        val store = ImageDraftStore(root)
        val draft = create(store)
        store.save(changed(draft))
        val latest = manifests(draft.id).last()
        val bytes = latest.readBytes().dropLast(32).toByteArray().apply { this[7] = (ImageDraftCodec.SCHEMA + 1).toByte() }
        latest.writeBytes(bytes + MessageDigest.getInstance("SHA-256").digest(bytes))
        store.close()
        assertEquals(DraftFailure.UNSUPPORTED, ImageDraftStore(root).open(draft.id).errorOrNull())
        assertEquals(2, manifests(draft.id).size)
        assertEquals(DraftFailure.UNSUPPORTED, ImageDraftStore(root).list().getOrNull()!!.single().problem)
    }

    @Test fun `素材の同サイズ改変は古い世代への復元で隠さない`() {
        val store = ImageDraftStore(root)
        val draft = create(store)
        val asset = store.assetFile(draft.id, draft.assets.keys.single())
        asset.writeBytes(asset.readBytes().apply { this[0] = 0 })
        store.close()
        assertEquals(DraftFailure.MISSING_SOURCE, ImageDraftStore(root).open(draft.id).errorOrNull())
    }

    @Test fun `素材が欠落したら編集画面へ異なる画像を渡さない`() {
        val store = ImageDraftStore(root)
        val draft = create(store)
        store.assetFile(draft.id, draft.assets.keys.single()).delete()
        store.close()
        assertEquals(DraftFailure.MISSING_SOURCE, ImageDraftStore(root).open(draft.id).errorOrNull())
    }

    @Test fun `利用中の下書きは別の画面から開くことも削除することもできない`() {
        val store = ImageDraftStore(root)
        val draft = create(store)
        val other = ImageDraftStore(root)
        assertTrue(other.list().getOrNull()!!.single().inUse)
        assertEquals(DraftFailure.BUSY, other.open(draft.id).errorOrNull())
        assertEquals(DraftFailure.BUSY, other.delete(draft.id).errorOrNull())
        store.close()
        assertTrue(other.open(draft.id).isSuccess)
    }

    @Test fun `古い遅延保存は新しい編集を巻き戻さない`() {
        val store = ImageDraftStore(root)
        val draft = create(store)
        val latest = changed(draft, 8)
        assertTrue(store.save(latest).isSuccess)
        assertEquals(8L, store.save(changed(draft, 2)).getOrNull())
        store.close()
        assertEquals(latest, open(draft.id).draft)
    }

    @Test fun `同じ世代で別の内容を上書きしない`() {
        val store = ImageDraftStore(root)
        val draft = create(store)
        assertEquals(DraftFailure.CONFLICT, store.save(draft.copy(dirty = true)).errorOrNull())
    }

    @Test fun `下書きを削除しても原本と別の下書きの素材を残す`() {
        val store = ImageDraftStore(root)
        val file = source()
        val first = create(store, file)
        val second = create(store, file)
        store.close()
        assertTrue(ImageDraftStore(root).delete(first.id).isSuccess)
        assertTrue(file.isFile)
        assertTrue(ImageDraftStore(root).open(second.id).isSuccess)
        assertFalse(File(root, first.id).exists())
    }

    @Test fun `所有者による破棄の後に遅い自動保存で下書きを再作成しない`() {
        val store = ImageDraftStore(root)
        val draft = create(store)
        assertTrue(store.discard(draft.id).isSuccess)
        assertEquals(DraftFailure.BUSY, store.save(changed(draft)).errorOrNull())
        assertFalse(File(root, draft.id).exists())
    }

    @Test fun `初回素材コピーをキャンセルしても原本を残し不完全な下書きを作らない`() {
        val file = source()
        val store = ImageDraftStore(root)
        try {
            store.create(UUID.randomUUID().toString(), file, "photo", null, document(file)) { throw CancellationException() }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals("immutable-original-pixels", file.readText())
        assertTrue(store.list().getOrNull()!!.isEmpty())
    }

    @Test fun `不正な下書きIDで保存領域の外を削除しない`() {
        val file = source()
        assertEquals(DraftFailure.CORRUPT, ImageDraftStore(root).delete("../${file.name}").errorOrNull())
        assertTrue(file.exists())
    }

    @Test fun `壊れた下書きも一覧に残り使用容量を確認して削除できる`() {
        val store = ImageDraftStore(root)
        val draft = create(store)
        manifests(draft.id).single().writeText("bad")
        store.close()
        val entry = ImageDraftStore(root).list().getOrNull()!!.single()
        assertEquals(DraftFailure.CORRUPT, entry.problem)
        assertTrue(entry.bytes > 3)
        assertTrue(ImageDraftStore(root).delete(draft.id).isSuccess)
    }

    @Test fun `素材コピーの保存先と原本の書き戻し先を混同しない`() {
        val store = ImageDraftStore(root)
        val source = source()
        val original = temporary.newFile("real-original.jpg")
        val draft = store.create(UUID.randomUUID().toString(), source, original.name,
            FileUri.Local(original.absolutePath).toStorageString(), document(source)).getOrNull()!!
        assertEquals(original.absolutePath, draft.origin.localPath)
        assertNotEquals(source.absolutePath, store.assetFile(draft.id, draft.assets.keys.single()).absolutePath)
    }

    @Test fun `出力の再確認先とハッシュを復元し二重送信せず検証できる`() {
        val store = ImageDraftStore(root)
        val draft = create(store)
        val receipt = ExportReceipt(FileUri.Remote(Protocol.SFTP, "connection", "folder/image.png"),
            FileFingerprint(100, "a".repeat(64)))
        val updated = changed(draft).copy(pendingExport = receipt, pendingExportMetadataWarning = true)
        assertTrue(store.save(updated).isSuccess)
        store.close()
        val restored = open(draft.id).draft
        assertEquals(receipt, restored.pendingExport)
        assertTrue(restored.pendingExportMetadataWarning)
    }

    @Test fun `原本を上書きした後も素材のハッシュを変えず次の原本照合値だけ更新する`() {
        val store = ImageDraftStore(root)
        val draft = create(store)
        val updated = changed(draft).copy(origin = draft.origin.copy(fingerprint = FileFingerprint(50, "b".repeat(64))))
        assertTrue(store.save(updated).isSuccess)
        store.close()
        val restored = open(draft.id).draft
        assertEquals(draft.assets, restored.assets)
        assertEquals(updated.origin.fingerprint, restored.origin.fingerprint)
    }

    @Test fun `並行した新旧の保存でも最大世代を保持する`() {
        val store = ImageDraftStore(root)
        val draft = create(store)
        val threads = (1L..8).map { revision -> Thread { store.save(changed(draft, revision)) }.apply { start() } }
        threads.forEach { it.join() }
        store.close()
        assertEquals(8L, open(draft.id).draft.revision)
    }

    @Test fun `永続領域を作れなければ成功扱いにせず原本を残す`() {
        root.writeText("not a directory")
        val file = source()
        assertTrue(ImageDraftStore(root).create(UUID.randomUUID().toString(), file, "image", null, document(file)).isFailure)
        assertEquals("immutable-original-pixels", file.readText())
    }
    @Test fun `保存上限を超えた編集で前の正常な世代を失わない`() {
        val store = ImageDraftStore(root)
        val draft = create(store)
        val tooLarge = Layer.Text("huge", "text", text = "x".repeat(1024 * 1024 + 1), style = TextStyleSpec(20f, -1))
        assertEquals(DraftFailure.TOO_LARGE, store.save(changed(draft).copy(document =
            draft.document.copy(layers = draft.document.layers + tooLarge))).errorOrNull())
        store.close()
        assertEquals(draft, open(draft.id).draft)
    }

    @Test fun `削除は素材ディレクトリのリンク先まで辿らない`() {
        val store = ImageDraftStore(root)
        val draft = create(store)
        val outside = temporary.newFolder("outside")
        val valuable = File(outside, "keep.txt").apply { writeText("keep") }
        java.nio.file.Files.createSymbolicLink(File(File(root, draft.id), "linked").toPath(), outside.toPath())
        store.close()
        assertTrue(ImageDraftStore(root).delete(draft.id).isSuccess)
        assertEquals("keep", valuable.readText())
    }

    @Test fun `欠落した素材を参照したまま保存済みにしない`() {
        val store = ImageDraftStore(root)
        val draft = create(store)
        store.assetFile(draft.id, draft.assets.keys.single()).delete()
        assertEquals(DraftFailure.MISSING_SOURCE, store.save(changed(draft)).errorOrNull())
    }

    @Test fun `大きな輪郭を保存復元しても点を欠落させない`() {
        val store = ImageDraftStore(root)
        val draft = create(store)
        val points = List(65_536) { EditPoint((it % 1200).toFloat(), (it / 1200).toFloat()) }
        val updated = changed(draft).copy(document = draft.document.copy(selection = SelectionSpec.Contours(listOf(points))))
        assertTrue(store.save(updated).isSuccess)
        store.close()
        assertEquals(updated, open(draft.id).draft)
    }

    @Test fun `旧schemaを残しながらブラシを含む新しい世代へ更新できる`() {
        val store = ImageDraftStore(root)
        val draft = create(store)
        val file = manifests(draft.id).single()
        // schema 1 と 2 で共通の旧型。旧版の実バイト列との互換は codec の固定資料でも検証する。
        val legacy = file.readBytes().dropLast(32).toByteArray().apply { this[7] = 1 }
        file.writeBytes(legacy + MessageDigest.getInstance("SHA-256").digest(legacy))
        store.close()
        val nextStore = ImageDraftStore(root)
        assertEquals(draft, nextStore.open(draft.id).getOrNull()!!.draft)
        val updated = changed(draft).copy(document = draft.document.copy(selection =
            SelectionSpec.BrushStroke(listOf(EditPoint(5f, 5f)), 4f)))
        assertTrue(nextStore.save(updated).isSuccess)
        assertEquals(1, manifests(draft.id).first().readBytes()[7].toInt())
        assertEquals(ImageDraftCodec.SCHEMA, manifests(draft.id).last().readBytes()[7].toInt())
        nextStore.close()
        assertEquals(updated, open(draft.id).draft)
    }

}
