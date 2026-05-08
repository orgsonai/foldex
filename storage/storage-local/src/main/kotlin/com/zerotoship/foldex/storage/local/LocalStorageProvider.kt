package com.zerotoship.foldex.storage.local

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.core.model.FileNode
import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.core.model.ListOptions
import com.zerotoship.foldex.core.model.NodeType
import com.zerotoship.foldex.core.model.Permissions
import com.zerotoship.foldex.core.model.ProgressObserver
import com.zerotoship.foldex.core.model.SortBy
import com.zerotoship.foldex.core.model.StorageError
import com.zerotoship.foldex.core.model.StorageProvider
import com.zerotoship.foldex.core.model.WriteMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException

class LocalStorageProvider(private val context: Context) : StorageProvider {

    override fun canHandle(uri: FileUri): Boolean = uri is FileUri.Local || uri is FileUri.Saf

    override suspend fun connect(): Result<Unit, StorageError> = Result.Success(Unit)

    override suspend fun disconnect() = Unit

    override suspend fun stat(uri: FileUri): Result<FileNode, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(uri) {
                when (uri) {
                    is FileUri.Local -> statLocal(uri)
                    is FileUri.Saf -> statSaf(uri)
                    is FileUri.Remote -> Result.Failure(StorageError.IoError("Remote not supported"))
                }
            }
        }

    override fun list(uri: FileUri, options: ListOptions): Flow<FileNode> = flow {
        when (uri) {
            is FileUri.Local -> {
                val dir = File(uri.absolutePath)
                val children = dir.listFiles()
                    ?.filter { options.showHidden || !it.isHidden }
                    ?.sortedWith(localComparator(options))
                    ?: throw IOException("Cannot list: ${uri.absolutePath}")
                for (f in children) emit(f.toFileNode(FileUri.Local(f.absolutePath)))
            }
            is FileUri.Saf -> {
                val androidUri = Uri.parse(uri.documentUri)
                val doc = DocumentFile.fromTreeUri(context, androidUri)
                    ?: DocumentFile.fromSingleUri(context, androidUri)
                    ?: throw IOException("Cannot open SAF URI: ${uri.documentUri}")
                val children = doc.listFiles()
                    .filter { options.showHidden || !it.name.orEmpty().startsWith(".") }
                for (child in children) {
                    emit(child.toFileNode(FileUri.Saf(child.uri.toString())))
                }
            }
            is FileUri.Remote -> throw IOException("Remote not supported")
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun openInput(uri: FileUri): Result<InputStream, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(uri) {
                when (uri) {
                    is FileUri.Local -> {
                        val file = File(uri.absolutePath)
                        if (!file.exists()) return@withContext Result.Failure(StorageError.NotFound(uri))
                        Result.Success(file.inputStream())
                    }
                    is FileUri.Saf -> {
                        val stream = context.contentResolver.openInputStream(Uri.parse(uri.documentUri))
                            ?: return@withContext Result.Failure(StorageError.NotFound(uri))
                        Result.Success(stream)
                    }
                    is FileUri.Remote -> Result.Failure(StorageError.IoError("Remote not supported"))
                }
            }
        }

    // Write operations are implemented in P3
    override suspend fun openOutput(uri: FileUri, mode: WriteMode): Result<OutputStream, StorageError> =
        Result.Failure(StorageError.IoError("Write not supported in P2"))

    override suspend fun mkdir(uri: FileUri, recursive: Boolean): Result<Unit, StorageError> =
        Result.Failure(StorageError.IoError("mkdir not supported in P2"))

    override suspend fun delete(uri: FileUri, recursive: Boolean): Result<Unit, StorageError> =
        Result.Failure(StorageError.IoError("delete not supported in P2"))

    override suspend fun rename(from: FileUri, to: FileUri): Result<Unit, StorageError> =
        Result.Failure(StorageError.IoError("rename not supported in P2"))

    override suspend fun copyWithin(from: FileUri, to: FileUri, observer: ProgressObserver?): Result<Unit, StorageError> =
        Result.Failure(StorageError.IoError("copy not supported in P2"))

    override suspend fun moveWithin(from: FileUri, to: FileUri, observer: ProgressObserver?): Result<Unit, StorageError> =
        Result.Failure(StorageError.IoError("move not supported in P2"))

    // --- helpers ---

    private fun statLocal(uri: FileUri.Local): Result<FileNode, StorageError> {
        val file = File(uri.absolutePath)
        if (!file.exists()) return Result.Failure(StorageError.NotFound(uri))
        if (!file.canRead()) return Result.Failure(StorageError.PermissionDenied(uri))
        return Result.Success(file.toFileNode(uri))
    }

    private fun statSaf(uri: FileUri.Saf): Result<FileNode, StorageError> {
        val androidUri = Uri.parse(uri.documentUri)
        val doc = DocumentFile.fromSingleUri(context, androidUri)
            ?: return Result.Failure(StorageError.NotFound(uri))
        return Result.Success(doc.toFileNode(uri))
    }

    private inline fun <T> runCatching(uri: FileUri, block: () -> Result<T, StorageError>): Result<T, StorageError> {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Result.Failure(StorageError.PermissionDenied(uri))
        } catch (e: Exception) {
            Result.Failure(StorageError.IoError(e.message ?: "Unknown error", e))
        }
    }

    private fun File.toFileNode(uri: FileUri): FileNode = FileNode(
        uri = uri,
        name = name,
        type = when {
            isDirectory -> NodeType.DIRECTORY
            isFile -> NodeType.FILE
            else -> NodeType.UNKNOWN
        },
        size = if (isFile) length() else -1L,
        lastModified = Instant.fromEpochMilliseconds(lastModified()),
        permissions = Permissions(
            readable = canRead(),
            writable = canWrite(),
            executable = canExecute(),
        ),
        isHidden = isHidden,
    )

    private fun DocumentFile.toFileNode(uri: FileUri): FileNode = FileNode(
        uri = uri,
        name = name ?: uri.toStorageString().substringAfterLast("/"),
        type = when {
            isDirectory -> NodeType.DIRECTORY
            isFile -> NodeType.FILE
            else -> NodeType.UNKNOWN
        },
        size = length().let { if (it == 0L && isDirectory) -1L else it },
        lastModified = lastModified().takeIf { it > 0 }?.let { Instant.fromEpochMilliseconds(it) },
        permissions = Permissions(readable = canRead(), writable = canWrite()),
    )

    private fun localComparator(options: ListOptions): Comparator<File> {
        val nameOrder = compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        val base: Comparator<File> = when (options.sortBy) {
            SortBy.NAME -> nameOrder
            SortBy.SIZE -> compareBy<File> { it.length() }.then(nameOrder)
            SortBy.DATE -> compareBy<File> { it.lastModified() }.then(nameOrder)
            SortBy.TYPE -> compareBy<File> { it.extension.lowercase() }.then(nameOrder)
        }
        val ordered = if (options.sortAscending) base else base.reversed()
        // Directories always listed before files
        return compareByDescending<File> { it.isDirectory }.then(ordered)
    }
}
