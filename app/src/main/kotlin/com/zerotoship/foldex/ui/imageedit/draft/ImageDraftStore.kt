// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.draft

import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.ui.imageedit.engine.FileFingerprint
import com.zerotoship.foldex.ui.imageedit.model.EditDocument
import com.zerotoship.foldex.ui.imageedit.model.Layer
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.UUID

/**
 * アプリ内の下書き。素材 → sync 済みの世代 → 原子的な公開の順で確定する。
 * 同一プロセスの複数 Activity からも排他し、利用中の文書は一覧から削除しない。
 * 公開前に中断した世代は復元対象にせず、正常な過去 2 世代を保持する。
 */
internal class ImageDraftStore(root: File, private val beforePublish: () -> Unit = {}) {
    private val root = root.canonicalFile
    private var closed = false
    private val owner = UUID.randomUUID().toString()
    private val shared = synchronized(roots) { roots.getOrPut(this.root.path) { Shared() } }

    fun create(
        id: String, source: File, name: String, uri: String?, document: EditDocument,
        checkCancelled: () -> Unit = {},
    ): Result<ImageDraft, DraftFailure> = guarded {
        synchronized(shared) {
            if (closed) abort(DraftFailure.BUSY)
            val dir = directory(id)
            if (dir.exists() || shared.owners.containsKey(id)) abort(DraftFailure.CONFLICT)
            if (!dir.mkdirs()) abort(DraftFailure.IO)
            val assets = File(dir, "assets").also { if (!it.mkdir()) abort(DraftFailure.IO) }
            val temp = File(assets, "copy.tmp")
            var committed = false
            try {
                val original = FileFingerprint.read(source, checkCancelled)
                FileOutputStream(temp).use { out ->
                    source.inputStream().use { input -> copy(input, out, checkCancelled) }
                    out.fd.sync()
                }
                val fingerprint = FileFingerprint.read(temp, checkCancelled)
                if (fingerprint != original || FileFingerprint.read(source, checkCancelled) != original) {
                    abort(DraftFailure.MISSING_SOURCE)
                }
                checkCancelled()
                Files.move(temp.toPath(), File(assets, fingerprint.sha256).toPath(), ATOMIC_MOVE)
                val draft = ImageDraft(id, 0, System.currentTimeMillis(), name,
                    DraftOrigin((uri?.let { com.zerotoship.foldex.core.model.FileUri.fromStorageStringOrNull(it) }
                        as? com.zerotoship.foldex.core.model.FileUri.Local)?.absolutePath ?: source.absolutePath, uri, original), mapOf(fingerprint.sha256 to fingerprint),
                    document.copy(layers = document.layers.map {
                        if (it is Layer.Image) it.copy(sourceKey = fingerprint.sha256) else it
                    }), false)
                publish(draft, checkCancelled)
                committed = true
                shared.owners[id] = owner
                draft
            } finally {
                temp.delete()
                // 公開が成功した直後の中断では、下書きを消さない。
                if (!committed && generations(dir).isEmpty()) deleteTree(dir)
            }
        }
    }

    fun open(id: String): Result<OpenedImageDraft, DraftFailure> = guarded {
        synchronized(shared) {
            if (closed) abort(DraftFailure.BUSY)
            if (id in shared.owners) abort(DraftFailure.BUSY)
            val opened = readLatest(id, verifyAssets = true)
            shared.owners[id] = owner
            cleanTemps(directory(id))
            opened
        }
    }

    fun save(draft: ImageDraft): Result<Long, DraftFailure> = guarded {
        synchronized(shared) {
            if (closed) abort(DraftFailure.BUSY)
            if (shared.owners[draft.id] != owner) abort(DraftFailure.BUSY)
            val latest = readLatest(draft.id, verifyAssets = false).draft
            if (draft.revision < latest.revision) return@synchronized latest.revision
            if (draft.revision == latest.revision) {
                if (draft != latest) abort(DraftFailure.CONFLICT)
                return@synchronized latest.revision
            }
            if (draft.assets != latest.assets) abort(DraftFailure.CONFLICT)
            draft.assets.forEach { (key, fp) ->
                val asset = assetFile(draft.id, key)
                if (!Files.isRegularFile(asset.toPath(), NOFOLLOW_LINKS) || asset.length() != fp.size) {
                    abort(DraftFailure.MISSING_SOURCE)
                }
            }
            publish(draft)
            draft.revision
        }
    }

    fun list(): Result<List<ImageDraftEntry>, DraftFailure> = guarded {
        synchronized(shared) {
            if (closed) abort(DraftFailure.BUSY)
            if (!root.exists()) return@synchronized emptyList()
            val dirs = root.listFiles() ?: abort(DraftFailure.IO)
            dirs.filter { ImageDraftCodec.ID.matches(it.name) && Files.isDirectory(it.toPath(), NOFOLLOW_LINKS) }
                .map { dir ->
                    if (dir.name !in shared.owners) {
                        cleanTemps(dir)
                        val assets = File(dir, "assets")
                        if (assets.canonicalFile == assets) File(assets, "copy.tmp").delete()
                    }
                    val draft = guarded { readLatest(dir.name, verifyAssets = false).draft }
                    ImageDraftEntry(dir.name, draft.getOrNull()?.name ?: dir.name,
                        draft.getOrNull()?.updatedAt ?: dir.lastModified(), diskBytes(dir),
                        draft.errorOrNull(), dir.name in shared.owners)
                }.sortedByDescending { it.updatedAt }
        }
    }

    fun delete(id: String): Result<Unit, DraftFailure> = guarded {
        synchronized(shared) {
            if (closed) abort(DraftFailure.BUSY)
            if (id in shared.owners) abort(DraftFailure.BUSY)
            val dir = directory(id)
            if (dir.exists() && !deleteTree(dir)) abort(DraftFailure.IO)
        }
    }

    fun discard(id: String): Result<Unit, DraftFailure> = guarded {
        synchronized(shared) {
            if (closed) abort(DraftFailure.BUSY)
            if (shared.owners[id] != owner) abort(DraftFailure.BUSY)
            if (!deleteTree(directory(id))) abort(DraftFailure.IO)
            shared.owners.remove(id)
            Unit
        }
    }

    fun close() = synchronized(shared) {
        closed = true
        shared.owners.entries.removeAll { it.value == owner }
    }

    fun release(id: String) = synchronized(shared) {
        if (shared.owners[id] == owner) shared.owners.remove(id)
    }

    fun assetFile(id: String, key: String): File {
        require(ImageDraftCodec.HASH.matches(key))
        val file = File(directory(id), "assets/$key")
        require(file.canonicalFile == file)
        return file
    }

    private fun readLatest(id: String, verifyAssets: Boolean): OpenedImageDraft {
        val files = generations(directory(id))
        var lastError = DraftFailure.CORRUPT
        for ((index, file) in files.withIndex()) {
            try {
                val draft = readGeneration(file)
                require(draft.id == id && file.name == generationName(draft.revision))
                if (verifyAssets) {
                    draft.assets.forEach { (key, fingerprint) ->
                        val asset = assetFile(id, key)
                        if (!Files.isRegularFile(asset.toPath(), NOFOLLOW_LINKS) ||
                            FileFingerprint.read(asset) != fingerprint) abort(DraftFailure.MISSING_SOURCE)
                    }
                }
                return OpenedImageDraft(draft, index > 0)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: DraftException) {
                // 新しいアプリの記録を旧版で上書きしない。素材不一致も旧世代で隠さない。
                if (e.reason == DraftFailure.UNSUPPORTED || e.reason == DraftFailure.MISSING_SOURCE) throw e
                lastError = e.reason
            } catch (_: OutOfMemoryError) {
                abort(DraftFailure.TOO_LARGE)
            } catch (_: Exception) {
                lastError = DraftFailure.CORRUPT
            }
        }
        abort(lastError)
    }

    private fun publish(draft: ImageDraft, checkCancelled: () -> Unit = {}) {
        val dir = directory(draft.id)
        val temp = File.createTempFile("pending-", ".tmp", dir)
        try {
            FileOutputStream(temp).use { file ->
                val digest = MessageDigest.getInstance("SHA-256")
                val bounded = object : FilterOutputStream(java.security.DigestOutputStream(file, digest)) {
                    var count = 0L
                    override fun write(b: Int) {
                        if (++count > ImageDraftCodec.MAX_BYTES) abort(DraftFailure.TOO_LARGE)
                        out.write(b)
                    }
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        count += len
                        if (count > ImageDraftCodec.MAX_BYTES) abort(DraftFailure.TOO_LARGE)
                        out.write(b, off, len)
                    }
                }
                ImageDraftCodec.write(draft, java.io.BufferedOutputStream(bounded, 64 * 1024))
                file.write(digest.digest())
                file.fd.sync()
            }
            // 保存不能な値を「保存済み」にしない。実際の読み戻しで全 DTO を検証する。
            if (readGeneration(temp) != draft) abort(DraftFailure.CORRUPT)
            checkCancelled()
            beforePublish()
            val destination = File(dir, generationName(draft.revision))
            // 壊れた最新世代からの復元でもそのファイルは置換せず、新しい番号を使う。
            if (destination.exists()) abort(DraftFailure.CONFLICT)
            Files.move(temp.toPath(), destination.toPath(), ATOMIC_MOVE)
            // この先の後片付けの失敗で、公開済みの記録を失敗扱いにしない。
            runCatching {
                val valid = generations(dir).filter { runCatching { readGeneration(it) }.isSuccess }
                val keep = valid.take(2).toSet()
                generations(dir).filter { it !in keep }.forEach { it.delete() }
                cleanTemps(dir)
            }
        } finally {
            temp.delete()
        }
    }

    private fun readGeneration(file: File): ImageDraft {
        val length = file.length()
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && length in 40..(ImageDraftCodec.MAX_BYTES + 32))
        // 長さ・SHA-256 を先に調べ、不正な内容でオブジェクトを構築しない。
        file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var remaining = length - 32
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                require(n > 0)
                digest.update(buffer, 0, n); remaining -= n
            }
            val expected = ByteArray(32)
            java.io.DataInputStream(input).readFully(expected)
            require(MessageDigest.isEqual(digest.digest(), expected) && input.read() == -1)
        }
        return file.inputStream().use { input ->
            ImageDraftCodec.read(java.io.BufferedInputStream(object : InputStream() {
                var remaining = length - 32
                override fun read(): Int = if (remaining <= 0) -1 else input.read().also { remaining-- }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (remaining <= 0) return -1
                    return input.read(b, off, minOf(remaining, len.toLong()).toInt()).also { if (it > 0) remaining -= it }
                }
            }, 64 * 1024))
        }
    }

    fun nextRevision(id: String, current: Long): Long = synchronized(shared) {
        val largest = generations(directory(id)).firstOrNull()?.name?.removePrefix("manifest-")?.removeSuffix(".bin")?.toLongOrNull() ?: -1
        Math.addExact(maxOf(largest, current), 1)
    }

    private fun directory(id: String): File {
        require(ImageDraftCodec.ID.matches(id))
        val dir = File(root, id)
        require(dir.canonicalFile == dir)
        return dir
    }
    private fun generationName(revision: Long) = "manifest-${revision.toString().padStart(20, '0')}.bin"
    private fun generations(dir: File): List<File> = dir.listFiles().orEmpty()
        .filter { Regex("manifest-[0-9]{20}\\.bin").matches(it.name) }.sortedByDescending { it.name }
    private fun cleanTemps(dir: File) {
        dir.listFiles().orEmpty().filter { it.name.startsWith("pending-") && it.name.endsWith(".tmp") }.forEach { it.delete() }
    }
    private fun deleteTree(dir: File): Boolean = try {
        Files.walkFileTree(dir.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                Files.delete(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(path: java.nio.file.Path, error: IOException?): java.nio.file.FileVisitResult {
                if (error != null) throw error
                Files.delete(path)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
        true
    } catch (_: Exception) { false }

    private fun diskBytes(dir: File): Long = dir.walkTopDown().onEnter {
        !Files.isSymbolicLink(it.toPath())
    }.filter { Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) }.sumOf { it.length() }

    private fun copy(input: InputStream, output: OutputStream, checkCancelled: () -> Unit) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
    }
    private inline fun <T> guarded(block: () -> T): Result<T, DraftFailure> = try {
        Result.Success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: DraftException) {
        Result.Failure(e.reason)
    } catch (_: OutOfMemoryError) {
        Result.Failure(DraftFailure.TOO_LARGE)
    } catch (_: IllegalArgumentException) {
        Result.Failure(DraftFailure.CORRUPT)
    } catch (_: Exception) {
        Result.Failure(DraftFailure.IO)
    }
    private fun abort(failure: DraftFailure): Nothing = throw DraftException(failure)
    private class Shared { val owners = mutableMapOf<String, String>() }
    private companion object { val roots = mutableMapOf<String, Shared>() }
}
