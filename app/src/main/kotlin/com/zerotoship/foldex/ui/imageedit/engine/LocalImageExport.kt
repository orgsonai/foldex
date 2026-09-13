// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import com.zerotoship.foldex.ui.imageedit.model.ImageFormat
import com.zerotoship.foldex.ui.imageedit.model.SaveNaming
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** 原本を開いて切り詰めない。置換できないファイルシステムでは失敗として別名保存へ戻す。 */
internal class LocalImageExport(private val files: ExportFileOperations = ExportFileOperations()) {
    fun deliver(
        prepared: PreparedImage,
        original: File,
        expectedOriginal: FileFingerprint,
        format: ImageFormat,
        overwrite: Boolean,
        checkCancelled: () -> Unit = {},
    ): ImageExportResult {
        var staged: File? = null
        try {
            val parent = original.absoluteFile.parentFile
                ?: return ImageExportResult.Failed(ExportFailure.WRITE_FAILED)
            if (overwrite && (!original.isFile || Files.isSymbolicLink(original.toPath()) ||
                    FileFingerprint.read(original, checkCancelled) != expectedOriginal)
            ) return ImageExportResult.Failed(ExportFailure.SOURCE_CHANGED)
            // 形式変更を「上書き」として別の既存ファイルへ向けない。
            if (overwrite && !SaveNaming.canOverwrite(original.name, format)) {
                return ImageExportResult.Failed(ExportFailure.DESTINATION_EXISTS)
            }
            staged = File.createTempFile(".foldex-export-", ".tmp", parent)
            files.copyAndSync(prepared.file, staged, checkCancelled)
            if (FileFingerprint.read(staged, checkCancelled) != prepared.fingerprint) {
                return ImageExportResult.Failed(ExportFailure.INVALID_OUTPUT)
            }
            if (overwrite) {
                // 時間のかかる描画・書き込みの後、配置直前にも変更を確認する。
                if (Files.isSymbolicLink(original.toPath()) ||
                    FileFingerprint.read(original, checkCancelled) != expectedOriginal
                ) return ImageExportResult.Failed(ExportFailure.SOURCE_CHANGED)
                checkCancelled()
                files.replaceAtomically(staged, original)
                // 確定後はキャンセルを挟まず結果を返す。
                return ImageExportResult.Saved(original.absolutePath)
            }
            val desired = SaveNaming.editedName(original.name, format)
            for (number in 1..1000) {
                checkCancelled()
                val name = if (number == 1) desired else
                    "${desired.substringBeforeLast('.')}$number.${format.extension}"
                val destination = File(parent, name)
                try {
                    files.createNew(staged, destination, checkCancelled)
                    return ImageExportResult.Saved(destination.absolutePath)
                } catch (_: FileAlreadyExistsException) {
                    // 排他的作成が負けても、そのファイルを消したり上書きしたりしない。
                }
            }
            return ImageExportResult.Failed(ExportFailure.DESTINATION_EXISTS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AtomicMoveNotSupportedException) {
            return ImageExportResult.Failed(ExportFailure.ATOMIC_REPLACE_UNAVAILABLE)
        } catch (_: Exception) {
            return ImageExportResult.Failed(ExportFailure.WRITE_FAILED)
        } finally {
            staged?.delete()
        }
    }
}

/** ディスク満杯、close/sync、配置直前の失敗を実ファイルで検証するための境界。 */
internal open class ExportFileOperations {
    open fun copyAndSync(source: File, target: File, checkCancelled: () -> Unit) {
        FileOutputStream(target).use { output ->
            source.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
            output.flush()
            output.fd.sync()
        }
    }

    open fun replaceAtomically(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    open fun createNew(source: File, target: File, checkCancelled: () -> Unit) {
        // Atomic move の「上書きなし」は OS ごとに競合時の意味が異なるため、排他的作成を使う。
        val channel = FileChannel.open(
            target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        )
        var ownedKey: Any? = null
        fun ownsPath(): Boolean = ownedKey != null && try {
            Files.readAttributes(target.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey() == ownedKey
        } catch (_: Exception) { false }
        try {
            channel.use {
                ownedKey = Files.readAttributes(target.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
                source.inputStream().use { input ->
                    val output = Channels.newOutputStream(channel)
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                    channel.force(true)
                }
                channel.position(0)
                if (FileFingerprint.read(Channels.newInputStream(channel), checkCancelled) !=
                    FileFingerprint.read(source, checkCancelled)
                ) throw java.io.IOException("Export verification failed")
            }
            if (ownedKey != null && !ownsPath()) throw java.io.IOException("Export destination changed")
        } catch (error: Exception) {
            // 別プロセスが同名ファイルを差し替えた場合は、そのファイルを消さない。
            // 識別子を取れない Provider では不完全な新規ファイルを残す方を選ぶ。
            if (ownsPath()) target.delete()
            throw error
        }
    }
}
