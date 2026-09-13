// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import com.zerotoship.foldex.ui.imageedit.model.ImageFormat
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files

class LocalImageExportTest {
    @get:Rule val folder = TemporaryFolder()

    private fun original(name: String = "photo.jpg") = folder.newFile(name).apply { writeText("original-image") }
    private fun prepared(): PreparedImage {
        val file = folder.newFile("encoded.tmp").apply { writeBytes(ByteArray(150_000) { (it % 251).toByte() }) }
        return PreparedImage(file, FileFingerprint.read(file), false)
    }
    private fun assertOriginal(source: File) {
        assertEquals("original-image", source.readText())
        assertFalse(folder.root.listFiles()!!.any { it.name.startsWith(".foldex-export-") })
    }

    @Test fun `検証した出力で原本を原子的に置き換える`() {
        val source = original()
        prepared().use {
            val result = LocalImageExport().deliver(it, source, FileFingerprint.read(source), ImageFormat.JPEG, true)
            assertTrue(result is ImageExportResult.Saved)
            assertEquals(it.fingerprint, FileFingerprint.read(source))
        }
    }

    @Test fun `書き込み途中の容量不足で原本を切り詰めない`() {
        val source = original()
        val ops = object : ExportFileOperations() {
            override fun copyAndSync(source: File, target: File, checkCancelled: () -> Unit) {
                target.writeText("partial")
                throw IOException("disk full")
            }
        }
        prepared().use {
            assertTrue(LocalImageExport(ops).deliver(it, source, FileFingerprint.read(source), ImageFormat.JPEG, true) is ImageExportResult.Failed)
        }
        assertOriginal(source)
    }

    @Test fun `syncまたはclose後に失敗しても原本を残す`() {
        val source = original()
        val ops = object : ExportFileOperations() {
            override fun copyAndSync(source: File, target: File, checkCancelled: () -> Unit) {
                super.copyAndSync(source, target, checkCancelled)
                throw IOException("close failed")
            }
        }
        prepared().use { LocalImageExport(ops).deliver(it, source, FileFingerprint.read(source), ImageFormat.JPEG, true) }
        assertOriginal(source)
    }

    @Test fun `コピーの同サイズ破損を検出し原本を残す`() {
        val source = original()
        val ops = object : ExportFileOperations() {
            override fun copyAndSync(source: File, target: File, checkCancelled: () -> Unit) {
                val bytes = source.readBytes()
                bytes[4] = (bytes[4].toInt() xor 1).toByte()
                target.writeBytes(bytes)
            }
        }
        prepared().use {
            assertEquals(ImageExportResult.Failed(ExportFailure.INVALID_OUTPUT), LocalImageExport(ops).deliver(it, source, FileFingerprint.read(source), ImageFormat.JPEG, true))
        }
        assertOriginal(source)
    }

    @Test fun `原子的置換が使えないとき直接上書きへフォールバックしない`() {
        val source = original()
        val ops = object : ExportFileOperations() {
            override fun replaceAtomically(source: File, target: File) {
                throw AtomicMoveNotSupportedException(source.path, target.path, "unsupported")
            }
        }
        prepared().use {
            assertEquals(ImageExportResult.Failed(ExportFailure.ATOMIC_REPLACE_UNAVAILABLE), LocalImageExport(ops).deliver(it, source, FileFingerprint.read(source), ImageFormat.JPEG, true))
        }
        assertOriginal(source)
    }

    @Test fun `配置直前の例外でも原本を残す`() {
        val source = original()
        val ops = object : ExportFileOperations() {
            override fun replaceAtomically(source: File, target: File) { throw IOException("rename failed") }
        }
        prepared().use { LocalImageExport(ops).deliver(it, source, FileFingerprint.read(source), ImageFormat.JPEG, true) }
        assertOriginal(source)
    }

    @Test fun `編集中に同サイズで原本が変わったら置換しない`() {
        val source = original()
        val before = FileFingerprint.read(source)
        source.writeText("modified-image")
        prepared().use {
            assertEquals(ImageExportResult.Failed(ExportFailure.SOURCE_CHANGED), LocalImageExport().deliver(it, source, before, ImageFormat.JPEG, true))
        }
        assertEquals("modified-image", source.readText())
    }

    @Test fun `一時コピー中の原本変更も配置前に検出する`() {
        val source = original()
        val before = FileFingerprint.read(source)
        val ops = object : ExportFileOperations() {
            override fun copyAndSync(input: File, target: File, checkCancelled: () -> Unit) {
                super.copyAndSync(input, target, checkCancelled)
                source.writeText("another-writer")
            }
        }
        prepared().use {
            assertEquals(ImageExportResult.Failed(ExportFailure.SOURCE_CHANGED), LocalImageExport(ops).deliver(it, source, before, ImageFormat.JPEG, true))
        }
        assertEquals("another-writer", source.readText())
    }

    @Test fun `別名が既存なら連番で保存し既存内容を保持する`() {
        val source = original()
        val existing = folder.newFile("photo_edited.jpg").apply { writeText("existing") }
        prepared().use {
            val result = LocalImageExport().deliver(it, source, FileFingerprint.read(source), ImageFormat.JPEG, false)
            assertEquals(ImageExportResult.Saved(File(folder.root, "photo_edited2.jpg").path), result)
            assertEquals(it.fingerprint, FileFingerprint.read(File(folder.root, "photo_edited2.jpg")))
        }
        assertEquals("existing", existing.readText())
        assertOriginal(source)
    }

    @Test fun `新規作成時の競合で既存ファイルを消さない`() {
        val source = original()
        val ops = object : ExportFileOperations() {
            override fun createNew(input: File, target: File, checkCancelled: () -> Unit) {
                if (target.name == "photo_edited.jpg") target.writeText("racing-writer")
                super.createNew(input, target, checkCancelled)
            }
        }
        prepared().use { assertTrue(LocalImageExport(ops).deliver(it, source, FileFingerprint.read(source), ImageFormat.JPEG, false) is ImageExportResult.Saved) }
        assertEquals("racing-writer", File(folder.root, "photo_edited.jpg").readText())
        assertOriginal(source)
    }

    @Test fun `大文字JPEG拡張子は元の名前を維持して上書きする`() {
        val source = original("photo.JPEG")
        prepared().use {
            assertEquals(ImageExportResult.Saved(source.path), LocalImageExport().deliver(it, source, FileFingerprint.read(source), ImageFormat.JPEG, true))
        }
        assertFalse(File(folder.root, "photo.jpg").exists())
    }

    @Test fun `形式変更で別の既存ファイルを上書きしない`() {
        val source = original("photo.png")
        val other = folder.newFile("photo.jpg").apply { writeText("other-image") }
        prepared().use {
            assertEquals(ImageExportResult.Failed(ExportFailure.DESTINATION_EXISTS), LocalImageExport().deliver(it, source, FileFingerprint.read(source), ImageFormat.JPEG, true))
        }
        assertEquals("other-image", other.readText())
        assertOriginal(source)
    }

    @Test fun `未知の拡張子へJPEGを上書きしない`() {
        val source = original("photo.bmp")
        prepared().use {
            assertEquals(ImageExportResult.Failed(ExportFailure.DESTINATION_EXISTS), LocalImageExport().deliver(it, source, FileFingerprint.read(source), ImageFormat.JPEG, true))
        }
        assertOriginal(source)
    }

    @Test fun `シンボリックリンクの参照先を上書きしない`() {
        val source = original()
        val link = File(folder.root, "linked.jpg")
        Files.createSymbolicLink(link.toPath(), source.toPath())
        prepared().use {
            assertEquals(ImageExportResult.Failed(ExportFailure.SOURCE_CHANGED), LocalImageExport().deliver(it, link, FileFingerprint.read(source), ImageFormat.JPEG, true))
        }
        assertOriginal(source)
    }

    @Test fun `キャンセル例外を失敗へ潰さず一時ファイルを片付ける`() {
        val source = original()
        val ops = object : ExportFileOperations() {
            override fun copyAndSync(source: File, target: File, checkCancelled: () -> Unit) { throw CancellationException() }
        }
        prepared().use {
            try {
                LocalImageExport(ops).deliver(it, source, FileFingerprint.read(source), ImageFormat.JPEG, true)
                fail("cancellation must propagate")
            } catch (_: CancellationException) { }
        }
        assertOriginal(source)
    }

    @Test fun `別名保存の途中取消は自分の新規ファイルだけを消す`() {
        val source = original()
        val ops = object : ExportFileOperations() {
            override fun createNew(source: File, target: File, checkCancelled: () -> Unit) {
                super.createNew(source, target) { throw CancellationException() }
            }
        }
        prepared().use {
            try {
                LocalImageExport(ops).deliver(it, source, FileFingerprint.read(source), ImageFormat.JPEG, false)
                fail("cancellation must propagate")
            } catch (_: CancellationException) { }
        }
        assertFalse(File(folder.root, "photo_edited.jpg").exists())
        assertOriginal(source)
    }

    @Test fun `書き込み中に別プロセスが置き換えた同名ファイルを削除しない`() {
        val source = original()
        val ops = object : ExportFileOperations() {
            override fun createNew(source: File, target: File, checkCancelled: () -> Unit) {
                super.createNew(source, target) {
                    target.delete()
                    target.writeText("another-process")
                    throw CancellationException()
                }
            }
        }
        prepared().use {
            try {
                LocalImageExport(ops).deliver(it, source, FileFingerprint.read(source), ImageFormat.JPEG, false)
                fail("cancellation must propagate")
            } catch (_: CancellationException) { }
        }
        assertEquals("another-process", File(folder.root, "photo_edited.jpg").readText())
        assertOriginal(source)
    }
}
