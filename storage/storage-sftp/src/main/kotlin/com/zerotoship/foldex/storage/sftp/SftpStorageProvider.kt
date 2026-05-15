package com.zerotoship.foldex.storage.sftp

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
import com.zerotoship.foldex.storage.sftp.internal.SftpPath
import com.zerotoship.foldex.storage.sftp.internal.SftpSessionPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.sftp.Response
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

@Singleton
class SftpStorageProvider @Inject internal constructor(
    private val pool: SftpSessionPool,
) : StorageProvider {

    override fun canHandle(uri: FileUri): Boolean =
        uri is FileUri.Remote && uri.protocol == Protocol.SFTP

    override suspend fun connect(): Result<Unit, StorageError> = Result.Success(Unit)

    override suspend fun disconnect() {
        pool.closeAll()
    }

    override suspend fun stat(uri: FileUri): Result<FileNode, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(uri) {
                val remote = uri.asRemote()
                    ?: return@runCatching Result.Failure(StorageError.IoError("Not an SFTP URI"))
                val sftp = pool.acquire(remote.connectionId)
                val attrs = sftp.stat(SftpPath.normalize(remote.path))
                Result.Success(toFileNode(uri, SftpPath.basename(remote.path), attrs))
            }
        }

    override fun list(uri: FileUri, options: ListOptions): Flow<FileNode> = flow {
        val remote = uri.asRemote() ?: throw IOException("Not an SFTP URI")
        val sftp = pool.acquire(remote.connectionId)
        val dirPath = SftpPath.normalize(remote.path)
        val children = sftp.ls(dirPath)
            .asSequence()
            .filter { it.name != "." && it.name != ".." }
            .filter { entry ->
                if (options.showHidden) true else !entry.name.startsWith('.')
            }
            .sortedWith(sftpComparator(options))
            .toList()
        for (child in children) {
            val childPath = SftpPath.join(dirPath, child.name)
            val childUri = FileUri.Remote(Protocol.SFTP, remote.connectionId, childPath)
            emit(toFileNode(childUri, child.name, child.attributes))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun openInput(uri: FileUri): Result<InputStream, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(uri) {
                val remote = uri.asRemote()
                    ?: return@runCatching Result.Failure(StorageError.IoError("Not an SFTP URI"))
                val sftp = pool.acquire(remote.connectionId)
                val file = sftp.open(SftpPath.normalize(remote.path), EnumSet.of(OpenMode.READ))
                Result.Success(SftpInputStream(file) as InputStream)
            }
        }

    override suspend fun openInputRange(uri: FileUri, offset: Long): Result<InputStream, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(uri) {
                val remote = uri.asRemote()
                    ?: return@runCatching Result.Failure(StorageError.IoError("Not an SFTP URI"))
                val sftp = pool.acquire(remote.connectionId)
                val file = sftp.open(SftpPath.normalize(remote.path), EnumSet.of(OpenMode.READ))
                // sshj は RemoteFileInputStream(offset) で位置指定読み取りに対応。
                Result.Success(SftpInputStream(file, fileOffset = offset) as InputStream)
            }
        }

    override suspend fun openOutput(uri: FileUri, mode: WriteMode): Result<OutputStream, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(uri) {
                val remote = uri.asRemote()
                    ?: return@runCatching Result.Failure(StorageError.IoError("Not an SFTP URI"))
                val sftp = pool.acquire(remote.connectionId)
                val openModes = when (mode) {
                    WriteMode.CREATE_NEW -> EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)
                    WriteMode.OVERWRITE -> EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
                    WriteMode.APPEND -> EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.APPEND)
                }
                val file = sftp.open(SftpPath.normalize(remote.path), openModes)
                Result.Success(SftpOutputStream(file) as OutputStream)
            }
        }

    override suspend fun mkdir(uri: FileUri, recursive: Boolean): Result<Unit, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(uri) {
                val remote = uri.asRemote()
                    ?: return@runCatching Result.Failure(StorageError.IoError("Not an SFTP URI"))
                val sftp = pool.acquire(remote.connectionId)
                val target = SftpPath.normalize(remote.path)
                if (recursive) sftp.mkdirs(target) else sftp.mkdir(target)
                Result.Success(Unit)
            }
        }

    override suspend fun delete(uri: FileUri, recursive: Boolean): Result<Unit, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(uri) {
                val remote = uri.asRemote()
                    ?: return@runCatching Result.Failure(StorageError.IoError("Not an SFTP URI"))
                val sftp = pool.acquire(remote.connectionId)
                val target = SftpPath.normalize(remote.path)
                val attrs = sftp.statExistence(target)
                    ?: return@runCatching Result.Failure(StorageError.NotFound(uri))
                when {
                    attrs.type == FileMode.Type.DIRECTORY -> {
                        if (recursive) deleteFolderRecursive(sftp, target)
                        else sftp.rmdir(target)
                    }
                    else -> sftp.rm(target)
                }
                Result.Success(Unit)
            }
        }

    override suspend fun rename(from: FileUri, to: FileUri): Result<Unit, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(from) {
                val src = from.asRemote()
                    ?: return@runCatching Result.Failure(StorageError.IoError("Not an SFTP URI"))
                val dst = to.asRemote()
                    ?: return@runCatching Result.Failure(StorageError.IoError("Cross-protocol rename not supported"))
                if (src.connectionId != dst.connectionId) {
                    return@runCatching Result.Failure(
                        StorageError.IoError("Cross-connection rename not supported"),
                    )
                }
                val sftp = pool.acquire(src.connectionId)
                sftp.rename(SftpPath.normalize(src.path), SftpPath.normalize(dst.path))
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
                ?: return@runCatching Result.Failure(StorageError.IoError("Not an SFTP URI"))
            val dst = to.asRemote()
                ?: return@runCatching Result.Failure(StorageError.IoError("Cross-protocol copy not supported"))
            if (src.connectionId != dst.connectionId) {
                return@runCatching Result.Failure(
                    StorageError.IoError("Cross-connection copy not supported"),
                )
            }
            val sftp = pool.acquire(src.connectionId)
            copyRecursive(
                sftp,
                SftpPath.normalize(src.path),
                SftpPath.normalize(dst.path),
                observer,
            )
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
        val copied = copyWithin(from, to, observer)
        if (copied is Result.Failure) return copied
        return delete(from, recursive = true)
    }

    // --- helpers ---

    private fun deleteFolderRecursive(sftp: SFTPClient, path: String) {
        for (entry in sftp.ls(path)) {
            if (entry.name == "." || entry.name == "..") continue
            val child = SftpPath.join(path, entry.name)
            if (entry.attributes.type == FileMode.Type.DIRECTORY) {
                deleteFolderRecursive(sftp, child)
            } else {
                sftp.rm(child)
            }
        }
        sftp.rmdir(path)
    }

    private fun copyRecursive(
        sftp: SFTPClient,
        src: String,
        dst: String,
        observer: ProgressObserver?,
    ) {
        val attrs = sftp.statExistence(src) ?: throw IOException("Source not found: $src")
        if (attrs.type == FileMode.Type.DIRECTORY) {
            if (sftp.statExistence(dst) == null) sftp.mkdir(dst)
            for (entry in sftp.ls(src)) {
                if (entry.name == "." || entry.name == "..") continue
                copyRecursive(
                    sftp,
                    SftpPath.join(src, entry.name),
                    SftpPath.join(dst, entry.name),
                    observer,
                )
            }
        } else {
            val total = attrs.size
            val srcFile = sftp.open(src, EnumSet.of(OpenMode.READ))
            val dstFile = sftp.open(
                dst,
                EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC),
            )
            try {
                SftpInputStream(srcFile).use { input ->
                    SftpOutputStream(dstFile).use { output ->
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
            } catch (t: Throwable) {
                runCatching { srcFile.close() }
                runCatching { dstFile.close() }
                throw t
            }
        }
    }

    private inline fun <T> runCatching(uri: FileUri, block: () -> Result<T, StorageError>): Result<T, StorageError> {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SFTPException) {
            Result.Failure(translateSftp(uri, e))
        } catch (e: net.schmizz.sshj.userauth.UserAuthException) {
            Result.Failure(StorageError.AuthenticationFailed("SFTP authentication failed", e))
        } catch (e: net.schmizz.sshj.transport.TransportException) {
            Result.Failure(
                StorageError.ProtocolError(Protocol.SFTP, e.message ?: "Transport error", e),
            )
        } catch (e: java.net.UnknownHostException) {
            Result.Failure(StorageError.HostUnreachable(uri.hostOrDescription(), e))
        } catch (e: Exception) {
            Result.Failure(StorageError.IoError(e.message ?: "Unknown error", e))
        }
    }

    private fun translateSftp(uri: FileUri, e: SFTPException): StorageError = when (e.statusCode) {
        Response.StatusCode.NO_SUCH_FILE,
        Response.StatusCode.NO_SUCH_PATH ->
            StorageError.NotFound(uri)
        Response.StatusCode.PERMISSION_DENIED ->
            StorageError.PermissionDenied(uri)
        Response.StatusCode.FILE_ALREADY_EXISTS ->
            StorageError.AlreadyExists(uri)
        else ->
            StorageError.ProtocolError(Protocol.SFTP, "SFTP error ${e.statusCode}: ${e.message}", e)
    }

    private fun FileUri.asRemote(): FileUri.Remote? = this as? FileUri.Remote

    private fun FileUri.hostOrDescription(): String = when (this) {
        is FileUri.Remote -> connectionId
        else -> toStorageString()
    }

    private fun toFileNode(uri: FileUri, name: String, attrs: FileAttributes): FileNode {
        val isDir = attrs.type == FileMode.Type.DIRECTORY
        val mtime = attrs.mtime
        val readable = (attrs.mode.permissionsMask and PERM_READ_OWNER) != 0
        val writable = (attrs.mode.permissionsMask and PERM_WRITE_OWNER) != 0
        return FileNode(
            uri = uri,
            name = name,
            type = if (isDir) NodeType.DIRECTORY else NodeType.FILE,
            size = if (isDir) -1L else attrs.size,
            lastModified = if (mtime > 0L) Instant.fromEpochSeconds(mtime) else null,
            permissions = Permissions(readable = readable, writable = writable),
            isHidden = name.startsWith('.'),
        )
    }

    private fun sftpComparator(options: ListOptions): Comparator<RemoteResourceInfo> {
        val nameOrder = Comparator<RemoteResourceInfo> { a, b ->
            String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name)
        }
        val base: Comparator<RemoteResourceInfo> = when (options.sortBy) {
            SortBy.NAME -> nameOrder
            SortBy.SIZE -> compareBy<RemoteResourceInfo> { it.attributes.size }.then(nameOrder)
            SortBy.DATE -> compareBy<RemoteResourceInfo> { it.attributes.mtime }.then(nameOrder)
            SortBy.TYPE -> compareBy<RemoteResourceInfo> {
                it.name.substringAfterLast('.', "").lowercase()
            }.then(nameOrder)
        }
        val ordered = if (options.sortAscending) base else base.reversed()
        return Comparator { a, b ->
            val aDir = a.attributes.type == FileMode.Type.DIRECTORY
            val bDir = b.attributes.type == FileMode.Type.DIRECTORY
            if (aDir != bDir) if (aDir) -1 else 1 else ordered.compare(a, b)
        }
    }

    companion object {
        private const val PERM_READ_OWNER = 0x100
        private const val PERM_WRITE_OWNER = 0x080
    }
}
