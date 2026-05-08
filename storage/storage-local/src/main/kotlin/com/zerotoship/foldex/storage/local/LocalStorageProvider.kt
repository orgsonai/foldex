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
import kotlin.time.Instant
import java.io.File
import java.io.FileOutputStream
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

    override suspend fun openOutput(uri: FileUri, mode: WriteMode): Result<OutputStream, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(uri) {
                when (uri) {
                    is FileUri.Local -> {
                        val file = File(uri.absolutePath)
                        if (mode == WriteMode.CREATE_NEW && file.exists())
                            return@withContext Result.Failure(StorageError.AlreadyExists(uri))
                        Result.Success(FileOutputStream(file, mode == WriteMode.APPEND))
                    }
                    is FileUri.Saf -> {
                        val modeStr = when (mode) {
                            WriteMode.CREATE_NEW, WriteMode.OVERWRITE -> "wt"
                            WriteMode.APPEND -> "wa"
                        }
                        val stream = context.contentResolver.openOutputStream(Uri.parse(uri.documentUri), modeStr)
                            ?: return@withContext Result.Failure(StorageError.NotFound(uri))
                        Result.Success(stream)
                    }
                    is FileUri.Remote -> Result.Failure(StorageError.IoError("Remote not supported"))
                }
            }
        }

    override suspend fun mkdir(uri: FileUri, recursive: Boolean): Result<Unit, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(uri) {
                when (uri) {
                    is FileUri.Local -> {
                        val file = File(uri.absolutePath)
                        val ok = if (recursive) file.mkdirs() else file.mkdir()
                        if (ok || file.isDirectory) Result.Success(Unit)
                        else Result.Failure(StorageError.IoError("mkdir failed: ${uri.absolutePath}"))
                    }
                    else -> Result.Failure(StorageError.IoError("SAF mkdir not supported"))
                }
            }
        }

    override suspend fun delete(uri: FileUri, recursive: Boolean): Result<Unit, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(uri) {
                when (uri) {
                    is FileUri.Local -> {
                        val file = File(uri.absolutePath)
                        if (!file.exists()) return@withContext Result.Failure(StorageError.NotFound(uri))
                        val ok = if (recursive) file.deleteRecursively() else file.delete()
                        if (ok) Result.Success(Unit)
                        else Result.Failure(StorageError.IoError("delete failed: ${uri.absolutePath}"))
                    }
                    is FileUri.Saf -> {
                        val doc = DocumentFile.fromSingleUri(context, Uri.parse(uri.documentUri))
                            ?: return@withContext Result.Failure(StorageError.NotFound(uri))
                        if (doc.delete()) Result.Success(Unit)
                        else Result.Failure(StorageError.IoError("SAF delete failed"))
                    }
                    is FileUri.Remote -> Result.Failure(StorageError.IoError("Remote not supported"))
                }
            }
        }

    override suspend fun rename(from: FileUri, to: FileUri): Result<Unit, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(from) {
                when {
                    from is FileUri.Local && to is FileUri.Local -> {
                        val src = File(from.absolutePath)
                        val dst = File(to.absolutePath)
                        if (!src.exists()) return@withContext Result.Failure(StorageError.NotFound(from))
                        if (dst.exists()) return@withContext Result.Failure(StorageError.AlreadyExists(to))
                        if (src.renameTo(dst)) Result.Success(Unit)
                        else Result.Failure(StorageError.IoError("rename failed"))
                    }
                    from is FileUri.Saf -> {
                        // `to.documentUri` carries the new display name by convention for SAF
                        val newName = (to as? FileUri.Saf)?.documentUri
                            ?: return@withContext Result.Failure(StorageError.IoError("Invalid SAF rename target"))
                        val doc = DocumentFile.fromSingleUri(context, Uri.parse(from.documentUri))
                            ?: return@withContext Result.Failure(StorageError.NotFound(from))
                        if (doc.renameTo(newName)) Result.Success(Unit)
                        else Result.Failure(StorageError.IoError("SAF rename failed"))
                    }
                    else -> Result.Failure(StorageError.IoError("Cross-type rename not supported"))
                }
            }
        }

    override suspend fun copyWithin(from: FileUri, to: FileUri, observer: ProgressObserver?): Result<Unit, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(from) {
                when {
                    from is FileUri.Local && to is FileUri.Local -> {
                        val src = File(from.absolutePath)
                        val dst = File(to.absolutePath)
                        if (!src.exists()) return@withContext Result.Failure(StorageError.NotFound(from))
                        if (dst.exists()) return@withContext Result.Failure(StorageError.AlreadyExists(to))
                        copyFileOrDir(src, dst, observer)
                        Result.Success(Unit)
                    }
                    else -> Result.Failure(StorageError.IoError("Cross-type copy not supported"))
                }
            }
        }

    override suspend fun moveWithin(from: FileUri, to: FileUri, observer: ProgressObserver?): Result<Unit, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(from) {
                when {
                    from is FileUri.Local && to is FileUri.Local -> {
                        val src = File(from.absolutePath)
                        val dst = File(to.absolutePath)
                        if (!src.exists()) return@withContext Result.Failure(StorageError.NotFound(from))
                        if (dst.exists()) return@withContext Result.Failure(StorageError.AlreadyExists(to))
                        // Try atomic rename first (same filesystem)
                        if (src.renameTo(dst)) return@withContext Result.Success(Unit)
                        // Fallback: copy then delete
                        copyFileOrDir(src, dst, observer)
                        src.deleteRecursively()
                        Result.Success(Unit)
                    }
                    else -> Result.Failure(StorageError.IoError("Cross-type move not supported"))
                }
            }
        }

    // --- helpers ---

    private fun copyFileOrDir(src: File, dst: File, observer: ProgressObserver?) {
        if (src.isDirectory) {
            dst.mkdirs()
            for (child in src.listFiles() ?: return) {
                copyFileOrDir(child, File(dst, child.name), observer)
            }
        } else {
            val total = src.length()
            src.inputStream().use { input ->
                dst.outputStream().use { output ->
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
        }
    }

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
        val nameOrder: Comparator<File> = Comparator { a, b -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name) }
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
