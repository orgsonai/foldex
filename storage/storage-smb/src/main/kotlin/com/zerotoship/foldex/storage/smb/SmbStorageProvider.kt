// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.storage.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileAllInformation
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.DiskShare
import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.core.model.FileNode
import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.core.model.ListOptions
import com.zerotoship.foldex.core.model.NodeType
import com.zerotoship.foldex.core.model.Permissions
import com.zerotoship.foldex.core.model.ProgressObserver
import com.zerotoship.foldex.core.model.Protocol
import com.zerotoship.foldex.core.model.SortBy
import com.zerotoship.foldex.core.model.StorageError
import com.zerotoship.foldex.core.model.StorageProvider
import com.zerotoship.foldex.core.model.WriteMode
import com.zerotoship.foldex.storage.smb.internal.SmbPath
import com.zerotoship.foldex.storage.smb.internal.SmbSessionPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

@Singleton
class SmbStorageProvider @Inject internal constructor(
    private val pool: SmbSessionPool,
) : StorageProvider {

    override fun canHandle(uri: FileUri): Boolean =
        uri is FileUri.Remote && uri.protocol == Protocol.SMB

    override suspend fun connect(): Result<Unit, StorageError> = Result.Success(Unit)

    override suspend fun disconnect() {
        pool.closeAll()
    }

    override suspend fun stat(uri: FileUri): Result<FileNode, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(uri) {
                val remote = uri.asRemote()
                    ?: return@runCatching Result.Failure(StorageError.IoError("Not an SMB URI"))
                val share = pool.acquire(remote.connectionId)
                val info = share.getFileInformation(SmbPath.toSmb(remote.path))
                Result.Success(info.toFileNode(uri))
            }
        }

    override fun list(uri: FileUri, options: ListOptions): Flow<FileNode> = flow {
        val remote = uri.asRemote() ?: throw IOException("Not an SMB URI")
        val children = try {
            val share = pool.acquire(remote.connectionId)
            val smbPath = SmbPath.toSmb(remote.path)
            share.list(smbPath)
                .asSequence()
                .filter { it.fileName != "." && it.fileName != ".." }
                .filter { entry ->
                    if (options.showHidden) true
                    else !FileAttributes.FILE_ATTRIBUTE_HIDDEN.isSet(entry.fileAttributes)
                }
                .sortedWith(smbComparator(options))
                .toList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: com.hierynomus.mssmb2.SMBApiException) {
            if (isSessionLost(e.status)) invalidate(uri)
            throw e
        } catch (e: Exception) {
            // 列挙中の切断は接続を捨てて次回 acquire で張り直させる (再起動不要にする)。
            invalidate(uri)
            throw e
        }
        for (child in children) {
            val childPath = SmbPath.join(remote.path, child.fileName)
            val childUri = FileUri.Remote(Protocol.SMB, remote.connectionId, childPath)
            emit(child.toFileNode(childUri))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun openInput(uri: FileUri): Result<InputStream, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(uri) {
                val remote = uri.asRemote()
                    ?: return@runCatching Result.Failure(StorageError.IoError("Not an SMB URI"))
                val share = pool.acquire(remote.connectionId)
                val file = share.openFile(
                    SmbPath.toSmb(remote.path),
                    EnumSet.of(AccessMask.GENERIC_READ),
                    EnumSet.noneOf(FileAttributes::class.java),
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.noneOf(SMB2CreateOptions::class.java),
                )
                Result.Success(SmbInputStream(file) as InputStream)
            }
        }

    override suspend fun openInputRange(uri: FileUri, offset: Long): Result<InputStream, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(uri) {
                val remote = uri.asRemote()
                    ?: return@runCatching Result.Failure(StorageError.IoError("Not an SMB URI"))
                val share = pool.acquire(remote.connectionId)
                val file = share.openFile(
                    SmbPath.toSmb(remote.path),
                    EnumSet.of(AccessMask.GENERIC_READ),
                    EnumSet.noneOf(FileAttributes::class.java),
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.noneOf(SMB2CreateOptions::class.java),
                )
                // SMB2 READ は fileOffset 指定可能なので位置指定 InputStream で返す。
                Result.Success(SmbRangeInputStream(file, startOffset = offset) as InputStream)
            }
        }

    override suspend fun openOutput(uri: FileUri, mode: WriteMode): Result<OutputStream, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(uri) {
                val remote = uri.asRemote()
                    ?: return@runCatching Result.Failure(StorageError.IoError("Not an SMB URI"))
                if (mode == WriteMode.APPEND) {
                    return@runCatching Result.Failure(
                        StorageError.IoError("APPEND mode not supported for SMB"),
                    )
                }
                val share = pool.acquire(remote.connectionId)
                val disposition = when (mode) {
                    WriteMode.CREATE_NEW -> SMB2CreateDisposition.FILE_CREATE
                    WriteMode.OVERWRITE -> SMB2CreateDisposition.FILE_OVERWRITE_IF
                    WriteMode.APPEND -> error("unreachable")
                }
                val file = share.openFile(
                    SmbPath.toSmb(remote.path),
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    EnumSet.noneOf(FileAttributes::class.java),
                    SMB2ShareAccess.ALL,
                    disposition,
                    EnumSet.noneOf(SMB2CreateOptions::class.java),
                )
                Result.Success(SmbOutputStream(file) as OutputStream)
            }
        }

    override suspend fun mkdir(uri: FileUri, recursive: Boolean): Result<Unit, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(uri) {
                val remote = uri.asRemote()
                    ?: return@runCatching Result.Failure(StorageError.IoError("Not an SMB URI"))
                val share = pool.acquire(remote.connectionId)
                if (recursive) {
                    val parts = remote.path.trim('/').split('/').filter { it.isNotEmpty() }
                    var current = ""
                    for (part in parts) {
                        current = if (current.isEmpty()) part else "$current/$part"
                        val smbPart = SmbPath.toSmb("/$current")
                        if (!share.folderExists(smbPart)) {
                            share.mkdir(smbPart)
                        }
                    }
                } else {
                    share.mkdir(SmbPath.toSmb(remote.path))
                }
                Result.Success(Unit)
            }
        }

    override suspend fun delete(uri: FileUri, recursive: Boolean): Result<Unit, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(uri) {
                val remote = uri.asRemote()
                    ?: return@runCatching Result.Failure(StorageError.IoError("Not an SMB URI"))
                val share = pool.acquire(remote.connectionId)
                val smbPath = SmbPath.toSmb(remote.path)
                when {
                    share.folderExists(smbPath) -> {
                        if (recursive) deleteFolderRecursive(share, smbPath)
                        else share.rmdir(smbPath, false)
                    }
                    share.fileExists(smbPath) -> share.rm(smbPath)
                    else -> return@runCatching Result.Failure(StorageError.NotFound(uri))
                }
                Result.Success(Unit)
            }
        }

    override suspend fun rename(from: FileUri, to: FileUri): Result<Unit, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(from) {
                val src = from.asRemote()
                    ?: return@runCatching Result.Failure(StorageError.IoError("Not an SMB URI"))
                val dst = to.asRemote()
                    ?: return@runCatching Result.Failure(StorageError.IoError("Cross-protocol rename not supported"))
                if (src.connectionId != dst.connectionId) {
                    return@runCatching Result.Failure(
                        StorageError.IoError("Cross-connection rename not supported"),
                    )
                }
                val share = pool.acquire(src.connectionId)
                val smbSrc = SmbPath.toSmb(src.path)
                val smbDst = SmbPath.toSmb(dst.path)
                if (share.folderExists(smbSrc)) {
                    share.openDirectory(
                        smbSrc,
                        EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_WRITE),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        EnumSet.noneOf(SMB2CreateOptions::class.java),
                    ).use { it.rename(smbDst) }
                } else if (share.fileExists(smbSrc)) {
                    share.openFile(
                        smbSrc,
                        EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_WRITE),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        EnumSet.noneOf(SMB2CreateOptions::class.java),
                    ).use { it.rename(smbDst) }
                } else {
                    return@runCatching Result.Failure(StorageError.NotFound(from))
                }
                Result.Success(Unit)
            }
        }

    override suspend fun copyWithin(
        from: FileUri,
        to: FileUri,
        observer: ProgressObserver?,
    ): Result<Unit, StorageError> = withContext(Dispatchers.IO) {
        runCatching(from) {
            val src = from.asRemote()
                ?: return@runCatching Result.Failure(StorageError.IoError("Not an SMB URI"))
            val dst = to.asRemote()
                ?: return@runCatching Result.Failure(StorageError.IoError("Cross-protocol copy not supported"))
            if (src.connectionId != dst.connectionId) {
                return@runCatching Result.Failure(
                    StorageError.IoError("Cross-connection copy not supported"),
                )
            }
            val share = pool.acquire(src.connectionId)
            copyRecursive(share, SmbPath.toSmb(src.path), SmbPath.toSmb(dst.path), observer)
            Result.Success(Unit)
        }
    }

    override suspend fun moveWithin(
        from: FileUri,
        to: FileUri,
        observer: ProgressObserver?,
    ): Result<Unit, StorageError> {
        val renamed = rename(from, to)
        if (renamed is Result.Success) return renamed
        // Fallback: copy + delete (for cross-directory moves where rename may fail)
        val copied = copyWithin(from, to, observer)
        if (copied is Result.Failure) return copied
        return delete(from, recursive = true)
    }

    // --- helpers ---

    private fun deleteFolderRecursive(share: DiskShare, smbPath: String) {
        for (entry in share.list(smbPath)) {
            if (entry.fileName == "." || entry.fileName == "..") continue
            val childSmb = if (smbPath.isEmpty()) entry.fileName else "$smbPath\\${entry.fileName}"
            if (FileAttributes.FILE_ATTRIBUTE_DIRECTORY.isSet(entry.fileAttributes)) {
                deleteFolderRecursive(share, childSmb)
            } else {
                share.rm(childSmb)
            }
        }
        share.rmdir(smbPath, false)
    }

    private fun copyRecursive(
        share: DiskShare,
        srcSmb: String,
        dstSmb: String,
        observer: ProgressObserver?,
    ) {
        if (share.folderExists(srcSmb)) {
            if (!share.folderExists(dstSmb)) share.mkdir(dstSmb)
            for (entry in share.list(srcSmb)) {
                if (entry.fileName == "." || entry.fileName == "..") continue
                val childSrc = if (srcSmb.isEmpty()) entry.fileName else "$srcSmb\\${entry.fileName}"
                val childDst = if (dstSmb.isEmpty()) entry.fileName else "$dstSmb\\${entry.fileName}"
                copyRecursive(share, childSrc, childDst, observer)
            }
        } else if (share.fileExists(srcSmb)) {
            val srcFile = share.openFile(
                srcSmb,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.noneOf(SMB2CreateOptions::class.java),
            )
            val dstFile = share.openFile(
                dstSmb,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                EnumSet.noneOf(SMB2CreateOptions::class.java),
            )
            try {
                val total = srcFile.fileInformation.standardInformation.endOfFile
                srcFile.inputStream.use { input ->
                    dstFile.outputStream.use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var transferred = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } >= 0) {
                            output.write(buffer, 0, read)
                            transferred += read
                            observer?.onProgress(transferred, total)
                        }
                    }
                }
            } finally {
                runCatching { srcFile.close() }
                runCatching { dstFile.close() }
            }
        } else {
            throw IOException("Source not found: $srcSmb")
        }
    }

    private suspend inline fun <T> runCatching(uri: FileUri, block: () -> Result<T, StorageError>): Result<T, StorageError> {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: com.hierynomus.mssmb2.SMBApiException) {
            // セッション/接続が落ちた系の NT ステータスはプールの死んだホルダーを捨てて張り直す。
            // (これをしないと一度切れたサーバへは isConnected の取りこぼしで再起動まで繋がらない。)
            if (isSessionLost(e.status)) invalidate(uri)
            Result.Failure(translateSmb(uri, e))
        } catch (e: java.net.UnknownHostException) {
            invalidate(uri)
            Result.Failure(StorageError.HostUnreachable(uri.hostOrDescription(), e))
        } catch (e: Exception) {
            // トランスポート切断・ソケットリセット・EOF 等。接続を疑って破棄し、次回 acquire で再接続する。
            invalidate(uri)
            Result.Failure(StorageError.IoError(e.message ?: "Unknown error", e))
        }
    }

    /** uri の接続をプールから破棄する (次回 acquire で TCP/セッションを張り直させる)。 */
    private suspend fun invalidate(uri: FileUri) {
        (uri as? FileUri.Remote)?.let { pool.release(it.connectionId) }
    }

    /** セッション/接続が消失したことを示す NT ステータスか (= 張り直しが必要)。 */
    private fun isSessionLost(status: com.hierynomus.mserref.NtStatus): Boolean = status in CONNECTION_LOST_STATUSES

    private fun translateSmb(uri: FileUri, e: com.hierynomus.mssmb2.SMBApiException): StorageError {
        val name = e.statusCode.toString(16)
        return when (e.status) {
            com.hierynomus.mserref.NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
            com.hierynomus.mserref.NtStatus.STATUS_OBJECT_PATH_NOT_FOUND ->
                StorageError.NotFound(uri)
            com.hierynomus.mserref.NtStatus.STATUS_OBJECT_NAME_COLLISION ->
                StorageError.AlreadyExists(uri)
            com.hierynomus.mserref.NtStatus.STATUS_ACCESS_DENIED ->
                StorageError.PermissionDenied(uri)
            com.hierynomus.mserref.NtStatus.STATUS_LOGON_FAILURE,
            com.hierynomus.mserref.NtStatus.STATUS_PASSWORD_EXPIRED,
            com.hierynomus.mserref.NtStatus.STATUS_ACCOUNT_DISABLED,
            com.hierynomus.mserref.NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED ->
                StorageError.AuthenticationFailed("SMB authentication failed (0x$name)", e)
            else ->
                StorageError.ProtocolError(Protocol.SMB, "SMB error ${e.status}: ${e.message}", e)
        }
    }

    private companion object {
        /** これらの NT ステータスはセッション/接続が消失した印。プールから捨てて張り直す。 */
        val CONNECTION_LOST_STATUSES = setOf(
            com.hierynomus.mserref.NtStatus.STATUS_USER_SESSION_DELETED,
            com.hierynomus.mserref.NtStatus.STATUS_NETWORK_SESSION_EXPIRED,
            com.hierynomus.mserref.NtStatus.STATUS_NETWORK_NAME_DELETED,
            com.hierynomus.mserref.NtStatus.STATUS_CONNECTION_DISCONNECTED,
            com.hierynomus.mserref.NtStatus.STATUS_CONNECTION_RESET,
        )
    }

    private fun FileUri.asRemote(): FileUri.Remote? = this as? FileUri.Remote

    private fun FileUri.hostOrDescription(): String = when (this) {
        is FileUri.Remote -> connectionId
        else -> toStorageString()
    }

    private fun FileIdBothDirectoryInformation.toFileNode(uri: FileUri): FileNode {
        val isDir = FileAttributes.FILE_ATTRIBUTE_DIRECTORY.isSet(fileAttributes)
        val isHidden = FileAttributes.FILE_ATTRIBUTE_HIDDEN.isSet(fileAttributes)
        return FileNode(
            uri = uri,
            name = fileName,
            type = if (isDir) NodeType.DIRECTORY else NodeType.FILE,
            size = if (isDir) -1L else endOfFile,
            lastModified = lastWriteTime?.toEpochMillis()?.let { Instant.fromEpochMilliseconds(it) },
            permissions = Permissions(readable = true, writable = true),
            isHidden = isHidden,
        )
    }

    private fun FileAllInformation.toFileNode(uri: FileUri): FileNode {
        val attrs = standardInformation.isDirectory
        val info = basicInformation
        val isHidden = FileAttributes.FILE_ATTRIBUTE_HIDDEN.isSet(info.fileAttributes.toLong())
        return FileNode(
            uri = uri,
            name = SmbPath.basename((uri as? FileUri.Remote)?.path ?: uri.toStorageString()),
            type = if (attrs) NodeType.DIRECTORY else NodeType.FILE,
            size = if (attrs) -1L else standardInformation.endOfFile,
            lastModified = info.lastWriteTime?.toEpochMillis()?.let { Instant.fromEpochMilliseconds(it) },
            permissions = Permissions(readable = true, writable = true),
            isHidden = isHidden,
        )
    }

    private fun FileAttributes.isSet(value: Long): Boolean = (value and this.value) != 0L

    private fun smbComparator(options: ListOptions): Comparator<FileIdBothDirectoryInformation> {
        val nameOrder = Comparator<FileIdBothDirectoryInformation> { a, b ->
            String.CASE_INSENSITIVE_ORDER.compare(a.fileName, b.fileName)
        }
        val base: Comparator<FileIdBothDirectoryInformation> = when (options.sortBy) {
            SortBy.NAME -> nameOrder
            SortBy.SIZE -> compareBy<FileIdBothDirectoryInformation> { it.endOfFile }.then(nameOrder)
            SortBy.DATE -> compareBy<FileIdBothDirectoryInformation> {
                it.lastWriteTime?.toEpochMillis() ?: 0L
            }.then(nameOrder)
            SortBy.TYPE -> compareBy<FileIdBothDirectoryInformation> {
                it.fileName.substringAfterLast('.', "").lowercase()
            }.then(nameOrder)
        }
        val ordered = if (options.sortAscending) base else base.reversed()
        return Comparator { a, b ->
            val aDir = FileAttributes.FILE_ATTRIBUTE_DIRECTORY.isSet(a.fileAttributes)
            val bDir = FileAttributes.FILE_ATTRIBUTE_DIRECTORY.isSet(b.fileAttributes)
            if (aDir != bDir) if (aDir) -1 else 1 else ordered.compare(a, b)
        }
    }
}
