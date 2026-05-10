package com.zerotoship.foldex.storage.ftp

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
import com.zerotoship.foldex.storage.ftp.internal.FtpClientPool
import com.zerotoship.foldex.storage.ftp.internal.FtpPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

@Singleton
class FtpStorageProvider @Inject internal constructor(
    private val pool: FtpClientPool,
) : StorageProvider {

    override fun canHandle(uri: FileUri): Boolean =
        uri is FileUri.Remote && uri.protocol == Protocol.FTP

    override suspend fun connect(): Result<Unit, StorageError> = Result.Success(Unit)

    override suspend fun disconnect() {
        pool.closeAll()
    }

    override suspend fun stat(uri: FileUri): Result<FileNode, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(uri) {
                val remote = uri.asRemote()
                    ?: return@runCatching Result.Failure(StorageError.IoError("Not an FTP URI"))
                val client = pool.acquire(remote.connectionId)
                val target = FtpPath.normalize(remote.path)
                if (target == "/") {
                    return@runCatching Result.Success(rootNode(uri))
                }
                val parent = FtpPath.parent(target)
                val name = FtpPath.basename(target)
                val entry = client.listFiles(parent).firstOrNull { it.name == name }
                    ?: return@runCatching Result.Failure(StorageError.NotFound(uri))
                Result.Success(toFileNode(uri, entry))
            }
        }

    override fun list(uri: FileUri, options: ListOptions): Flow<FileNode> = flow {
        val remote = uri.asRemote() ?: throw IOException("Not an FTP URI")
        val client = pool.acquire(remote.connectionId)
        val dirPath = FtpPath.normalize(remote.path)
        val children = client.listFiles(dirPath)
            .asSequence()
            .filter { it.name != "." && it.name != ".." }
            .filter { entry ->
                if (options.showHidden) true else !entry.name.startsWith('.')
            }
            .sortedWith(ftpComparator(options))
            .toList()
        for (child in children) {
            val childPath = FtpPath.join(dirPath, child.name)
            val childUri = FileUri.Remote(Protocol.FTP, remote.connectionId, childPath)
            emit(toFileNode(childUri, child))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun openInput(uri: FileUri): Result<InputStream, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(uri) {
                val remote = uri.asRemote()
                    ?: return@runCatching Result.Failure(StorageError.IoError("Not an FTP URI"))
                val client = pool.acquire(remote.connectionId)
                val stream = client.retrieveFileStream(FtpPath.normalize(remote.path))
                if (stream == null) {
                    return@runCatching Result.Failure(translateReply(uri, client))
                }
                Result.Success(FtpInputStream(client, stream) as InputStream)
            }
        }

    override suspend fun openOutput(uri: FileUri, mode: WriteMode): Result<OutputStream, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(uri) {
                val remote = uri.asRemote()
                    ?: return@runCatching Result.Failure(StorageError.IoError("Not an FTP URI"))
                val client = pool.acquire(remote.connectionId)
                val target = FtpPath.normalize(remote.path)
                val stream = when (mode) {
                    WriteMode.CREATE_NEW -> {
                        val exists = client.listFiles(FtpPath.parent(target))
                            .any { it.name == FtpPath.basename(target) }
                        if (exists) return@runCatching Result.Failure(StorageError.AlreadyExists(uri))
                        client.storeFileStream(target)
                    }
                    WriteMode.OVERWRITE -> client.storeFileStream(target)
                    WriteMode.APPEND -> client.appendFileStream(target)
                }
                if (stream == null) {
                    return@runCatching Result.Failure(translateReply(uri, client))
                }
                Result.Success(FtpOutputStream(client, stream) as OutputStream)
            }
        }

    override suspend fun mkdir(uri: FileUri, recursive: Boolean): Result<Unit, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(uri) {
                val remote = uri.asRemote()
                    ?: return@runCatching Result.Failure(StorageError.IoError("Not an FTP URI"))
                val client = pool.acquire(remote.connectionId)
                val target = FtpPath.normalize(remote.path)
                if (recursive) {
                    val parts = target.trim('/').split('/').filter { it.isNotEmpty() }
                    var current = ""
                    for (part in parts) {
                        current = "$current/$part"
                        val parentPath = FtpPath.parent(current)
                        val exists = client.listFiles(parentPath).any { it.name == part }
                        if (!exists) {
                            if (!client.makeDirectory(current)) {
                                return@runCatching Result.Failure(translateReply(uri, client))
                            }
                        }
                    }
                } else {
                    if (!client.makeDirectory(target)) {
                        return@runCatching Result.Failure(translateReply(uri, client))
                    }
                }
                Result.Success(Unit)
            }
        }

    override suspend fun delete(uri: FileUri, recursive: Boolean): Result<Unit, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(uri) {
                val remote = uri.asRemote()
                    ?: return@runCatching Result.Failure(StorageError.IoError("Not an FTP URI"))
                val client = pool.acquire(remote.connectionId)
                val target = FtpPath.normalize(remote.path)
                val parent = FtpPath.parent(target)
                val name = FtpPath.basename(target)
                val entry = client.listFiles(parent).firstOrNull { it.name == name }
                    ?: return@runCatching Result.Failure(StorageError.NotFound(uri))
                when {
                    entry.isDirectory -> {
                        if (recursive) deleteFolderRecursive(client, target)
                        else if (!client.removeDirectory(target)) {
                            return@runCatching Result.Failure(translateReply(uri, client))
                        }
                    }
                    else -> {
                        if (!client.deleteFile(target)) {
                            return@runCatching Result.Failure(translateReply(uri, client))
                        }
                    }
                }
                Result.Success(Unit)
            }
        }

    override suspend fun rename(from: FileUri, to: FileUri): Result<Unit, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(from) {
                val src = from.asRemote()
                    ?: return@runCatching Result.Failure(StorageError.IoError("Not an FTP URI"))
                val dst = to.asRemote()
                    ?: return@runCatching Result.Failure(StorageError.IoError("Cross-protocol rename not supported"))
                if (src.connectionId != dst.connectionId) {
                    return@runCatching Result.Failure(
                        StorageError.IoError("Cross-connection rename not supported"),
                    )
                }
                val client = pool.acquire(src.connectionId)
                if (!client.rename(FtpPath.normalize(src.path), FtpPath.normalize(dst.path))) {
                    return@runCatching Result.Failure(translateReply(from, client))
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
                ?: return@runCatching Result.Failure(StorageError.IoError("Not an FTP URI"))
            val dst = to.asRemote()
                ?: return@runCatching Result.Failure(StorageError.IoError("Cross-protocol copy not supported"))
            if (src.connectionId != dst.connectionId) {
                return@runCatching Result.Failure(
                    StorageError.IoError("Cross-connection copy not supported"),
                )
            }
            val client = pool.acquire(src.connectionId)
            copyRecursive(client, FtpPath.normalize(src.path), FtpPath.normalize(dst.path), observer)
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

    private fun deleteFolderRecursive(client: FTPClient, path: String) {
        for (entry in client.listFiles(path)) {
            if (entry.name == "." || entry.name == "..") continue
            val child = FtpPath.join(path, entry.name)
            if (entry.isDirectory) {
                deleteFolderRecursive(client, child)
            } else {
                if (!client.deleteFile(child)) {
                    throw IOException("Failed to delete $child (${client.replyString.trim()})")
                }
            }
        }
        if (!client.removeDirectory(path)) {
            throw IOException("Failed to remove directory $path (${client.replyString.trim()})")
        }
    }

    private fun copyRecursive(
        client: FTPClient,
        src: String,
        dst: String,
        observer: ProgressObserver?,
    ) {
        val parent = FtpPath.parent(src)
        val name = FtpPath.basename(src)
        val entry = client.listFiles(parent).firstOrNull { it.name == name }
            ?: throw IOException("Source not found: $src")
        if (entry.isDirectory) {
            val dstParent = FtpPath.parent(dst)
            val dstName = FtpPath.basename(dst)
            val dstExists = client.listFiles(dstParent).any { it.name == dstName }
            if (!dstExists) {
                if (!client.makeDirectory(dst)) {
                    throw IOException("Failed to create directory $dst (${client.replyString.trim()})")
                }
            }
            for (child in client.listFiles(src)) {
                if (child.name == "." || child.name == "..") continue
                copyRecursive(
                    client,
                    FtpPath.join(src, child.name),
                    FtpPath.join(dst, child.name),
                    observer,
                )
            }
        } else {
            val total = entry.size
            val input = client.retrieveFileStream(src)
                ?: throw IOException("Failed to open source $src (${client.replyString.trim()})")
            val wrappedIn = FtpInputStream(client, input)
            wrappedIn.use { srcStream ->
                val output = client.storeFileStream(dst)
                    ?: throw IOException("Failed to open destination $dst (${client.replyString.trim()})")
                val wrappedOut = FtpOutputStream(client, output)
                wrappedOut.use { dstStream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var transferred = 0L
                    var read: Int
                    while (srcStream.read(buffer).also { read = it } >= 0) {
                        dstStream.write(buffer, 0, read)
                        transferred += read
                        observer?.onProgress(transferred, total)
                    }
                }
            }
        }
    }

    private inline fun <T> runCatching(uri: FileUri, block: () -> Result<T, StorageError>): Result<T, StorageError> {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.net.UnknownHostException) {
            Result.Failure(StorageError.HostUnreachable(uri.hostOrDescription(), e))
        } catch (e: org.apache.commons.net.ftp.FTPConnectionClosedException) {
            Result.Failure(StorageError.NotConnected("FTP connection closed: ${e.message}"))
        } catch (e: Exception) {
            Result.Failure(StorageError.IoError(e.message ?: "Unknown error", e))
        }
    }

    private fun translateReply(uri: FileUri, client: FTPClient): StorageError {
        val code = client.replyCode
        val msg = client.replyString?.trim().orEmpty()
        return when {
            FTPReply.isNegativePermanent(code) && code == 550 -> {
                if (msg.contains("exists", ignoreCase = true)) StorageError.AlreadyExists(uri)
                else StorageError.NotFound(uri)
            }
            code == 530 -> StorageError.AuthenticationFailed("FTP authentication failed: $msg")
            code in 400..599 -> StorageError.ProtocolError(Protocol.FTP, "FTP $code: $msg")
            else -> StorageError.IoError("FTP reply $code: $msg")
        }
    }

    private fun FileUri.asRemote(): FileUri.Remote? = this as? FileUri.Remote

    private fun FileUri.hostOrDescription(): String = when (this) {
        is FileUri.Remote -> connectionId
        else -> toStorageString()
    }

    private fun rootNode(uri: FileUri): FileNode = FileNode(
        uri = uri,
        name = "",
        type = NodeType.DIRECTORY,
        size = -1L,
        lastModified = null,
        permissions = Permissions(readable = true, writable = true),
        isHidden = false,
    )

    private fun toFileNode(uri: FileUri, entry: FTPFile): FileNode {
        val isDir = entry.isDirectory
        val mtime = entry.timestamp?.timeInMillis
        return FileNode(
            uri = uri,
            name = entry.name,
            type = if (isDir) NodeType.DIRECTORY else NodeType.FILE,
            size = if (isDir) -1L else entry.size,
            lastModified = mtime?.let { Instant.fromEpochMilliseconds(it) },
            permissions = Permissions(
                readable = entry.hasPermission(FTPFile.USER_ACCESS, FTPFile.READ_PERMISSION),
                writable = entry.hasPermission(FTPFile.USER_ACCESS, FTPFile.WRITE_PERMISSION),
            ),
            isHidden = entry.name.startsWith('.'),
        )
    }

    private fun ftpComparator(options: ListOptions): Comparator<FTPFile> {
        val nameOrder = Comparator<FTPFile> { a, b ->
            String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name)
        }
        val base: Comparator<FTPFile> = when (options.sortBy) {
            SortBy.NAME -> nameOrder
            SortBy.SIZE -> compareBy<FTPFile> { it.size }.then(nameOrder)
            SortBy.DATE -> compareBy<FTPFile> { it.timestamp?.timeInMillis ?: 0L }.then(nameOrder)
            SortBy.TYPE -> compareBy<FTPFile> {
                it.name.substringAfterLast('.', "").lowercase()
            }.then(nameOrder)
        }
        val ordered = if (options.sortAscending) base else base.reversed()
        return Comparator { a, b ->
            if (a.isDirectory != b.isDirectory) if (a.isDirectory) -1 else 1
            else ordered.compare(a, b)
        }
    }
}
