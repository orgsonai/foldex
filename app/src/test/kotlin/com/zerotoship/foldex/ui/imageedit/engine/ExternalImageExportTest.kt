// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.core.model.*
import com.zerotoship.foldex.ui.imageedit.model.ImageFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class ExternalImageExportTest {
    @get:Rule val folder = TemporaryFolder()
    private val source = FileUri.Remote(Protocol.SFTP, "test", "/photo.jpg")
    private val target = source.copy(path = "/photo_edited-unique.jpg")
    private fun prepared(): PreparedImage {
        val file = folder.newFile().apply { writeText("encoded-output") }
        return PreparedImage(file, FileFingerprint.read(file), false)
    }

    @Test fun `新規コピーを読み戻して一致した場合だけ成功する`() = runBlocking {
        val storage = FakeStorage().apply { data[source] = "original".toByteArray() }
        prepared().use {
            assertEquals(ImageExportResult.Saved(target.displayName()), ExternalImageExport(storage).deliver(it, target, source))
            assertEquals(WriteMode.CREATE_NEW, storage.mode)
            assertArrayEquals("original".toByteArray(), storage.data[source])
            assertArrayEquals(it.file.readBytes(), storage.data[target])
            assertEquals(1, storage.reads)
        }
    }

    @Test fun `同サイズでも内容が異なれば成功にしない`() = runBlocking {
        val storage = FakeStorage().apply { corrupt = true }
        prepared().use {
            assertEquals(ImageExportResult.Failed(ExportFailure.VERIFICATION_FAILED, target.displayName()), ExternalImageExport(storage).deliver(it, target, source))
        }
    }

    @Test fun `読み戻し不能は未確認とし再確認で出力を再送しない`() = runBlocking {
        val storage = FakeStorage().apply { readFails = true }
        val exporter = ExternalImageExport(storage)
        prepared().use {
            val result = exporter.deliver(it, target, source) as ImageExportResult.Unconfirmed
            storage.readFails = false
            assertEquals(ImageExportResult.Saved(target.displayName()), exporter.verify(result.receipt))
            assertEquals(1, storage.writes)
        }
    }

    @Test fun `closeの応答を失っても保存済みの内容を確認できる`() = runBlocking {
        val storage = FakeStorage().apply { closeThrows = true }
        prepared().use {
            assertEquals(ImageExportResult.Saved(target.displayName()), ExternalImageExport(storage).deliver(it, target, source))
        }
    }

    @Test fun `送信失敗と未作成を保存成功にしない`() = runBlocking {
        val storage = FakeStorage().apply { discardOutput = true; closeThrows = true }
        prepared().use {
            assertTrue(ExternalImageExport(storage).deliver(it, target, source) is ImageExportResult.Unconfirmed)
        }
    }

    @Test fun `元URIを渡しても原本を開いて上書きしない`() = runBlocking {
        val storage = FakeStorage()
        prepared().use {
            assertEquals(ImageExportResult.Failed(ExportFailure.DESTINATION_EXISTS), ExternalImageExport(storage).deliver(it, source, source))
            assertEquals(0, storage.writes)
        }
    }

    @Test fun `既存名への排他的作成が失敗しても既存内容を変更しない`() = runBlocking {
        val storage = FakeStorage().apply { data[target] = "existing".toByteArray() }
        prepared().use {
            assertEquals(ImageExportResult.Failed(ExportFailure.DESTINATION_EXISTS), ExternalImageExport(storage).deliver(it, target, source))
            assertArrayEquals("existing".toByteArray(), storage.data[target])
        }
    }

    @Test fun `SAFはpickerで新規作成した文書だけに書く`() = runBlocking {
        val original = FileUri.Saf("content://test/original")
        val destination = FileUri.Saf("content://test/new")
        val storage = FakeStorage().apply { data[original] = "original".toByteArray() }
        prepared().use {
            assertTrue(ExternalImageExport(storage).deliver(it, destination, original) is ImageExportResult.Saved)
            assertEquals(WriteMode.OVERWRITE, storage.mode)
            assertArrayEquals("original".toByteArray(), storage.data[original])
        }
    }

    @Test fun `キャンセルは握りつぶさず原本を触らない`() = runBlocking {
        val storage = FakeStorage()
        prepared().use {
            try {
                ExternalImageExport(storage).deliver(it, target, source) { throw CancellationException() }
                fail("cancellation must propagate")
            } catch (_: CancellationException) { }
            assertEquals(0, storage.writes)
        }
    }

    @Test fun `新規の兄弟名は接続と親と拡張子を保持し重複しない`() {
        for (path in listOf("/photo.jpg", "folder/photo.jpg", "photo.jpg", "/folder/photo.jpg")) {
            val original = source.copy(path = path)
            val first = ExternalImageExport.newSibling(original, "商品".repeat(100) + ".jpg", ImageFormat.PNG)
            val second = ExternalImageExport.newSibling(original, "商品".repeat(100) + ".jpg", ImageFormat.PNG)
            assertEquals(original.connectionId, first.connectionId)
            assertEquals(original.protocol, first.protocol)
            assertEquals(path.substringBeforeLast('/', ""), first.path.substringBeforeLast('/', ""))
            assertEquals(path.startsWith('/'), first.path.startsWith('/'))
            assertTrue(first.path.endsWith(".png"))
            assertTrue(first.displayName().toByteArray(Charsets.UTF_8).size <= 255)
            assertNotEquals(first, second)
        }
    }

    private class FakeStorage : StorageProvider {
        val data = mutableMapOf<FileUri, ByteArray>()
        var writes = 0
        var reads = 0
        var mode: WriteMode? = null
        var corrupt = false
        var closeThrows = false
        var readFails = false
        var discardOutput = false
        override fun canHandle(uri: FileUri) = true
        override suspend fun connect() = Result.Success(Unit)
        override suspend fun disconnect() = Unit
        override suspend fun stat(uri: FileUri): Result<FileNode, StorageError> = Result.Failure(StorageError.NotFound(uri))
        override fun list(uri: FileUri, options: ListOptions): Flow<FileNode> = emptyFlow()
        override suspend fun openInput(uri: FileUri): Result<InputStream, StorageError> {
            reads++
            if (readFails) return Result.Failure(StorageError.IoError("offline"))
            return data[uri]?.let { Result.Success(ByteArrayInputStream(it)) } ?: Result.Failure(StorageError.NotFound(uri))
        }
        override suspend fun openOutput(uri: FileUri, mode: WriteMode): Result<OutputStream, StorageError> {
            writes++
            this.mode = mode
            if (mode == WriteMode.CREATE_NEW && uri in data) return Result.Failure(StorageError.AlreadyExists(uri))
            return Result.Success(object : ByteArrayOutputStream() {
                override fun close() {
                    val bytes = toByteArray()
                    if (corrupt && bytes.isNotEmpty()) bytes[0] = (bytes[0].toInt() xor 1).toByte()
                    if (!discardOutput) data[uri] = bytes
                    if (closeThrows) throw IOException("lost response")
                }
            })
        }
        override suspend fun mkdir(uri: FileUri, recursive: Boolean): Result<Unit, StorageError> = error("must not mkdir")
        override suspend fun delete(uri: FileUri, recursive: Boolean): Result<Unit, StorageError> = error("must not delete")
        override suspend fun rename(from: FileUri, to: FileUri): Result<Unit, StorageError> = error("must not rename")
        override suspend fun copyWithin(from: FileUri, to: FileUri, observer: ProgressObserver?): Result<Unit, StorageError> = error("must not copy")
        override suspend fun moveWithin(from: FileUri, to: FileUri, observer: ProgressObserver?): Result<Unit, StorageError> = error("must not move")
    }
}
