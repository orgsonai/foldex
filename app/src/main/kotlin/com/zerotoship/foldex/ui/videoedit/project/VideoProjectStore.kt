// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.project

import com.zerotoship.foldex.core.common.Result
import kotlinx.coroutines.CancellationException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.BasicFileAttributes
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * 動画編集の下書きの保存 (設計書 §11)。規則は画像編集の ImageDraftStore と同じ。
 *
 * - 世代ごとのファイルに SHA-256 を付けて sync し、読み戻して一致を確かめてから原子的な改名で公開する。
 * - 正常な世代を新しい 2 つだけ残す。壊れた最新の世代は 1 つ前へ戻す。
 * - 新しい版の形式は、古い世代で隠さず上書きもしない。
 * - 同じプロセスの別の画面が使っている下書きは、開かず消さない。
 *
 * 素材はコピーしないので、記録は編集内容と書き出し設定だけ (数十 KB 程度)。ファイル操作を伴うので IO スレッドから呼ぶこと。
 */
internal class VideoProjectStore(root: File, private val beforePublish: () -> Unit = {}) {
    private val root = root.canonicalFile
    private var closed = false
    private val owner = UUID.randomUUID().toString()
    private val shared = synchronized(roots) { roots.getOrPut(this.root.path) { Shared() } }

    /** 新しい下書きを最初の世代として保存し、このストアの使用中にする。 */
    fun create(draft: VideoDraft): Result<VideoDraft, VideoDraftFailure> = guarded {
        synchronized(shared) {
            if (closed) abort(VideoDraftFailure.BUSY)
            val dir = directory(draft.id)
            if (dir.exists() || shared.owners.containsKey(draft.id)) abort(VideoDraftFailure.CONFLICT)
            if (!dir.mkdirs()) abort(VideoDraftFailure.IO)
            var committed = false
            try {
                publish(draft)
                committed = true
                shared.owners[draft.id] = owner
                draft
            } finally {
                // 公開した直後の中断では、下書きを消さない。
                if (!committed && generations(dir).isEmpty()) deleteTree(dir)
            }
        }
    }

    fun open(id: String): Result<OpenedVideoDraft, VideoDraftFailure> = guarded {
        synchronized(shared) {
            if (closed) abort(VideoDraftFailure.BUSY)
            if (id in shared.owners) abort(VideoDraftFailure.BUSY)
            val opened = readLatest(id)
            shared.owners[id] = owner
            cleanTemps(directory(id))
            opened
        }
    }

    /** 保存した revision を返す。古い revision は無視し、最新の revision を返す。 */
    fun save(draft: VideoDraft): Result<Long, VideoDraftFailure> = guarded {
        synchronized(shared) {
            if (closed) abort(VideoDraftFailure.BUSY)
            if (shared.owners[draft.id] != owner) abort(VideoDraftFailure.BUSY)
            val latest = readLatest(draft.id).draft
            if (draft.revision < latest.revision) return@synchronized latest.revision
            if (draft.revision == latest.revision) {
                if (draft != latest) abort(VideoDraftFailure.CONFLICT)
                return@synchronized latest.revision
            }
            publish(draft)
            draft.revision
        }
    }

    fun list(): Result<List<VideoDraftEntry>, VideoDraftFailure> = guarded {
        synchronized(shared) {
            if (closed) abort(VideoDraftFailure.BUSY)
            if (!root.exists()) return@synchronized emptyList()
            val dirs = root.listFiles() ?: abort(VideoDraftFailure.IO)
            dirs.filter { VideoProjectCodec.ID.matches(it.name) && Files.isDirectory(it.toPath(), NOFOLLOW_LINKS) }
                .map { dir ->
                    if (dir.name !in shared.owners) cleanTemps(dir)
                    val read = guarded { readLatest(dir.name).draft }
                    val draft = read.getOrNull()
                    VideoDraftEntry(
                        id = dir.name,
                        name = draft?.name ?: dir.name,
                        updatedAt = draft?.updatedAt ?: dir.lastModified(),
                        bytes = diskBytes(dir),
                        clipCount = draft?.project?.clipCount ?: 0,
                        durationUs = draft?.project?.durationUs ?: 0L,
                        problem = read.errorOrNull(),
                        inUse = dir.name in shared.owners,
                    )
                }
                .sortedByDescending { it.updatedAt }
        }
    }

    fun delete(id: String): Result<Unit, VideoDraftFailure> = guarded {
        synchronized(shared) {
            if (closed) abort(VideoDraftFailure.BUSY)
            if (id in shared.owners) abort(VideoDraftFailure.BUSY)
            val dir = directory(id)
            if (dir.exists() && !deleteTree(dir)) abort(VideoDraftFailure.IO)
        }
    }

    /** 自分が使用中の下書きを削除する (閉じるときに「下書きを削除」を選んだとき)。 */
    fun discard(id: String): Result<Unit, VideoDraftFailure> = guarded {
        synchronized(shared) {
            if (closed) abort(VideoDraftFailure.BUSY)
            if (shared.owners[id] != owner) abort(VideoDraftFailure.BUSY)
            if (!deleteTree(directory(id))) abort(VideoDraftFailure.IO)
            shared.owners.remove(id)
            Unit
        }
    }

    fun close() {
        synchronized(shared) {
            closed = true
            shared.owners.entries.removeAll { it.value == owner }
        }
    }

    /** 使用中の印だけを外す。下書きは残す。 */
    fun release(id: String) {
        synchronized(shared) {
            if (shared.owners[id] == owner) shared.owners.remove(id)
        }
    }

    /** 次に使える revision。壊れて読めない世代の番号も飛ばし、同じ名前のファイルを作らない。 */
    fun nextRevision(id: String, current: Long): Long = synchronized(shared) {
        val largest = generations(directory(id)).firstOrNull()?.name
            ?.removePrefix(GENERATION_PREFIX)?.removeSuffix(GENERATION_SUFFIX)?.toLongOrNull() ?: -1L
        Math.addExact(maxOf(largest, current), 1L)
    }

    private fun readLatest(id: String): OpenedVideoDraft {
        val files = generations(directory(id))
        var lastError = VideoDraftFailure.CORRUPT
        for ((index, file) in files.withIndex()) {
            try {
                val draft = readGeneration(file)
                require(draft.id == id && file.name == generationName(draft.revision))
                return OpenedVideoDraft(draft, index > 0)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: VideoDraftException) {
                // 新しい版の記録を、古い世代で黙って置き換えない。
                if (e.reason == VideoDraftFailure.UNSUPPORTED) throw e
                lastError = e.reason
            } catch (_: OutOfMemoryError) {
                abort(VideoDraftFailure.TOO_LARGE)
            } catch (_: Exception) {
                lastError = VideoDraftFailure.CORRUPT
            }
        }
        abort(lastError)
    }

    private fun publish(draft: VideoDraft) {
        val dir = directory(draft.id)
        val temp = File.createTempFile(PENDING_PREFIX, PENDING_SUFFIX, dir)
        try {
            FileOutputStream(temp).use { file ->
                val digest = MessageDigest.getInstance("SHA-256")
                val bounded = object : FilterOutputStream(DigestOutputStream(file, digest)) {
                    private var count = 0L

                    override fun write(b: Int) {
                        if (++count > VideoProjectCodec.MAX_BYTES) abort(VideoDraftFailure.TOO_LARGE)
                        out.write(b)
                    }

                    override fun write(b: ByteArray, off: Int, len: Int) {
                        count += len
                        if (count > VideoProjectCodec.MAX_BYTES) abort(VideoDraftFailure.TOO_LARGE)
                        out.write(b, off, len)
                    }
                }
                val buffered = BufferedOutputStream(bounded, BUFFER_BYTES)
                VideoProjectCodec.write(draft, buffered)
                buffered.flush()
                file.write(digest.digest())
                file.fd.sync()
            }
            // 保存できない値を「保存済み」にしない。実際に読み戻して全項目を比べる。
            if (readGeneration(temp) != draft) abort(VideoDraftFailure.CORRUPT)
            beforePublish()
            val destination = File(dir, generationName(draft.revision))
            // 壊れた最新世代から戻した後も、そのファイルは置き換えず新しい番号を使う。
            if (destination.exists()) abort(VideoDraftFailure.CONFLICT)
            Files.move(temp.toPath(), destination.toPath(), ATOMIC_MOVE)
            // ここから先の後片付けの失敗で、公開済みの記録を失敗扱いにしない。
            runCatching {
                val valid = generations(dir).filter { runCatching { readGeneration(it) }.isSuccess }
                val keep = valid.take(KEEP_GENERATIONS).toSet()
                generations(dir).filter { it !in keep }.forEach { it.delete() }
                cleanTemps(dir)
            }
        } finally {
            temp.delete()
        }
    }

    private fun readGeneration(file: File): VideoDraft {
        val length = file.length()
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && length in 40..(VideoProjectCodec.MAX_BYTES + 32))
        // 長さと SHA-256 を先に確かめ、壊れた内容からオブジェクトを組み立てない。
        file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_BYTES)
            var remaining = length - 32
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                require(n > 0)
                digest.update(buffer, 0, n)
                remaining -= n
            }
            val expected = ByteArray(32)
            DataInputStream(input).readFully(expected)
            require(MessageDigest.isEqual(digest.digest(), expected) && input.read() == -1)
        }
        return file.inputStream().use { input ->
            VideoProjectCodec.read(
                BufferedInputStream(
                    object : InputStream() {
                        var remaining = length - 32

                        override fun read(): Int = if (remaining <= 0) -1 else input.read().also { remaining-- }

                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (remaining <= 0) return -1
                            return input.read(b, off, minOf(remaining, len.toLong()).toInt()).also { if (it > 0) remaining -= it }
                        }
                    },
                    BUFFER_BYTES,
                ),
            )
        }
    }

    private fun directory(id: String): File {
        require(VideoProjectCodec.ID.matches(id))
        val dir = File(root, id)
        require(dir.canonicalFile == dir)
        return dir
    }

    private fun generationName(revision: Long) = "$GENERATION_PREFIX${revision.toString().padStart(20, '0')}$GENERATION_SUFFIX"

    private fun generations(dir: File): List<File> = dir.listFiles().orEmpty()
        .filter { GENERATION_NAME.matches(it.name) }
        .sortedByDescending { it.name }

    private fun cleanTemps(dir: File) {
        dir.listFiles().orEmpty().filter { it.name.startsWith(PENDING_PREFIX) && it.name.endsWith(PENDING_SUFFIX) }.forEach { it.delete() }
    }

    private fun deleteTree(dir: File): Boolean = try {
        Files.walkFileTree(
            dir.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(path: Path, error: IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(path)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        true
    } catch (_: Exception) {
        false
    }

    private fun diskBytes(dir: File): Long = dir.walkTopDown()
        .onEnter { !Files.isSymbolicLink(it.toPath()) }
        .filter { Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) }
        .sumOf { it.length() }

    private inline fun <T> guarded(block: () -> T): Result<T, VideoDraftFailure> = try {
        Result.Success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: VideoDraftException) {
        Result.Failure(e.reason)
    } catch (_: OutOfMemoryError) {
        Result.Failure(VideoDraftFailure.TOO_LARGE)
    } catch (_: IllegalArgumentException) {
        Result.Failure(VideoDraftFailure.CORRUPT)
    } catch (_: Exception) {
        Result.Failure(VideoDraftFailure.IO)
    }

    private fun abort(failure: VideoDraftFailure): Nothing = throw VideoDraftException(failure)

    private class Shared {
        val owners = mutableMapOf<String, String>()
    }

    private companion object {
        const val GENERATION_PREFIX = "manifest-"
        const val GENERATION_SUFFIX = ".bin"
        const val PENDING_PREFIX = "pending-"
        const val PENDING_SUFFIX = ".tmp"
        const val KEEP_GENERATIONS = 2
        const val BUFFER_BYTES = 64 * 1024
        val GENERATION_NAME = Regex("manifest-[0-9]{20}\\.bin")
        val roots = mutableMapOf<String, Shared>()
    }
}
