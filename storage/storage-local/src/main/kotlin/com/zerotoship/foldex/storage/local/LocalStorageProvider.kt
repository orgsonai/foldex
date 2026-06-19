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
                // SAF: tree URI でも tree-document URI でも fromTreeUri で扱える。
                // child.uri は `tree/<root>/document/<child>` の tree-document URI で返るので、
                // 再 listing も同じく fromTreeUri 経由で 0 から N 階層辿れる。
                val androidUri = Uri.parse(uri.documentUri)
                val doc = DocumentFile.fromTreeUri(context, androidUri)
                    ?: throw IOException("Cannot open SAF URI: ${uri.documentUri}")
                if (!doc.isDirectory) throw IOException("Not a directory: ${uri.documentUri}")
                val children = doc.listFiles()
                    .filter { options.showHidden || !it.name.orEmpty().startsWith(".") }
                    .sortedWith(safComparator(options))
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
                        // CREATE_NEW のときは「親 (uri.documentUri) の配下に
                        // pendingChildName という名前のファイルを新規作成」する。
                        // 既存 (OVERWRITE/APPEND) のときはそのまま openOutputStream。
                        if (mode == WriteMode.CREATE_NEW) {
                            val name = uri.pendingChildName
                                ?: return@withContext Result.Failure(
                                    StorageError.IoError("SAF CREATE_NEW には親 + 子名が必要です"),
                                )
                            val parent = DocumentFile.fromTreeUri(context, Uri.parse(uri.documentUri))
                                ?: return@withContext Result.Failure(StorageError.NotFound(uri))
                            if (parent.findFile(name) != null) {
                                return@withContext Result.Failure(StorageError.AlreadyExists(uri))
                            }
                            val mime = guessMime(name)
                            val newDoc = parent.createFile(mime, name)
                                ?: return@withContext Result.Failure(StorageError.IoError("SAF createFile failed: $name"))
                            val stream = context.contentResolver.openOutputStream(newDoc.uri, "w")
                                ?: return@withContext Result.Failure(StorageError.NotFound(uri))
                            Result.Success(stream)
                        } else {
                            val modeStr = if (mode == WriteMode.APPEND) "wa" else "wt"
                            val stream = context.contentResolver.openOutputStream(Uri.parse(uri.documentUri), modeStr)
                                ?: return@withContext Result.Failure(StorageError.NotFound(uri))
                            Result.Success(stream)
                        }
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
                    is FileUri.Saf -> {
                        // SAF: 親 URI の配下に pendingChildName でディレクトリを作成。
                        val name = uri.pendingChildName
                            ?: return@withContext Result.Failure(
                                StorageError.IoError("SAF mkdir には親 + 子名が必要です"),
                            )
                        val parent = DocumentFile.fromTreeUri(context, Uri.parse(uri.documentUri))
                            ?: return@withContext Result.Failure(StorageError.NotFound(uri))
                        // 既に同名ディレクトリがあれば成功扱い (recursive と同じ感覚)。
                        val existing = parent.findFile(name)
                        if (existing != null && existing.isDirectory) return@withContext Result.Success(Unit)
                        if (existing != null) {
                            return@withContext Result.Failure(StorageError.AlreadyExists(uri))
                        }
                        val newDir = parent.createDirectory(name)
                            ?: return@withContext Result.Failure(StorageError.IoError("SAF createDirectory failed: $name"))
                        // newDir.exists() ぐらいの確認はしておく。
                        if (newDir.exists()) Result.Success(Unit)
                        else Result.Failure(StorageError.IoError("SAF mkdir succeeded but new dir not visible"))
                    }
                    is FileUri.Remote -> Result.Failure(StorageError.IoError("Remote not supported"))
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
                        // pendingChildName 付きは「親 URI + 子の名前」を指す擬似 URI。
                        // この場合 documentUri は親なので、findFile(name) で子を解決してから消す。
                        // (これをしないと親ディレクトリごと消す致命的な誤動作になる)。
                        val pending = uri.pendingChildName
                        val doc = if (pending != null) {
                            DocumentFile.fromTreeUri(context, Uri.parse(uri.documentUri))?.findFile(pending)
                                ?: return@withContext Result.Failure(StorageError.NotFound(uri))
                        } else {
                            // tree-document URI でも fromTreeUri で開けば delete できる。
                            // (fromSingleUri は plain content URI 想定で、tree-document URI で
                            //  delete が効かないケースが Termux 等で見られるため)。
                            DocumentFile.fromTreeUri(context, Uri.parse(uri.documentUri))
                                ?: DocumentFile.fromSingleUri(context, Uri.parse(uri.documentUri))
                                ?: return@withContext Result.Failure(StorageError.NotFound(uri))
                        }
                        if (doc.delete()) Result.Success(Unit)
                        else Result.Failure(StorageError.IoError("SAF delete failed: ${uri.documentUri}"))
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
                    // SAF が絡む組み合わせ (SAF↔SAF / SAF↔Local) はストリーム経由で汎用コピー。
                    else -> genericCopy(from, to, observer)
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
                    // SAF が絡む移動 = 汎用コピー後に元を削除 (SAF にアトミック move は無い)。
                    else -> when (val copied = genericCopy(from, to, observer)) {
                        is Result.Success -> delete(from, recursive = true)
                        is Result.Failure -> copied
                    }
                }
            }
        }

    // --- helpers ---

    /**
     * Local / SAF を問わずプロバイダのプリミティブ ([stat]/[list]/[openInput]/[openOutput]/[mkdir])
     * だけでコピーする汎用コピー。SAF↔SAF / SAF↔Local の双方向に対応する。
     *
     * ディレクトリは [ensureDirResolved] で作成後に「実体の URI」を取り直してから再帰する。
     * SAF の宛先 URI は「親 + pendingChildName」の擬似 URI なので、解決せずに子へ潜ると
     * 全部が同じ親直下に作られてしまうため、ここで一段ずつ実 URI に解決するのが要点。
     */
    private suspend fun genericCopy(
        from: FileUri,
        to: FileUri,
        observer: ProgressObserver?,
    ): Result<Unit, StorageError> {
        val node = when (val s = stat(from)) {
            is Result.Success -> s.value
            is Result.Failure -> return s
        }
        return if (node.type == NodeType.DIRECTORY) {
            val destDir = when (val d = ensureDirResolved(to)) {
                is Result.Success -> d.value
                is Result.Failure -> return d
            }
            var failure: StorageError? = null
            // showHidden=true: コピーでドットファイルを取りこぼさない。
            list(from, ListOptions(showHidden = true)).collect { child ->
                if (failure != null) return@collect
                when (val r = genericCopy(child.uri, resolvedChildUri(destDir, child.name), observer)) {
                    is Result.Success -> Unit
                    is Result.Failure -> failure = r.error
                }
            }
            failure?.let { Result.Failure(it) } ?: Result.Success(Unit)
        } else {
            genericCopyFile(from, to, node.size, observer)
        }
    }

    /**
     * 跨プロバイダコピー ([StorageProviderRouter]) から呼ぶための公開ラッパ。
     * SAF の「親 + pendingChildName」擬似 URI を、実際に作成したディレクトリの
     * tree-document URI に解決して返す (子へ再帰する前に必須)。Local はそのまま返す。
     */
    suspend fun resolveDestDirectory(to: FileUri): Result<FileUri, StorageError> =
        withContext(Dispatchers.IO) { ensureDirResolved(to) }

    /** [to] のディレクトリを (無ければ) 作り、子へ再帰できる「実体の URI」を返す。 */
    private fun ensureDirResolved(to: FileUri): Result<FileUri, StorageError> = when (to) {
        is FileUri.Local -> {
            File(to.absolutePath).mkdirs()
            Result.Success(to)
        }
        is FileUri.Saf -> {
            val pending = to.pendingChildName
            if (pending == null) {
                // 既に実体ディレクトリの URI。そのまま使う。
                Result.Success(to)
            } else {
                val parent = DocumentFile.fromTreeUri(context, Uri.parse(to.documentUri))
                    ?: return Result.Failure(StorageError.NotFound(to))
                val existing = parent.findFile(pending)
                val dir = when {
                    existing != null && existing.isDirectory -> existing
                    existing != null -> return Result.Failure(StorageError.AlreadyExists(to))
                    else -> parent.createDirectory(pending)
                        ?: return Result.Failure(StorageError.IoError("SAF createDirectory failed: $pending"))
                }
                Result.Success(FileUri.Saf(dir.uri.toString()))
            }
        }
        is FileUri.Remote -> Result.Failure(StorageError.IoError("Remote not supported"))
    }

    /** 実体ディレクトリ URI [parentDir] の配下に [name] を持つ子 URI を組み立てる。 */
    private fun resolvedChildUri(parentDir: FileUri, name: String): FileUri = when (parentDir) {
        is FileUri.Local -> FileUri.Local("${parentDir.absolutePath.trimEnd('/')}/$name")
        is FileUri.Saf -> FileUri.Saf(parentDir.documentUri, pendingChildName = name)
        is FileUri.Remote -> parentDir // 到達しない
    }

    private suspend fun genericCopyFile(
        from: FileUri,
        to: FileUri,
        size: Long,
        observer: ProgressObserver?,
    ): Result<Unit, StorageError> {
        val input = when (val i = openInput(from)) {
            is Result.Success -> i.value
            is Result.Failure -> return i
        }
        val output = when (val o = openOutput(to, WriteMode.CREATE_NEW)) {
            is Result.Success -> o.value
            is Result.Failure -> { runCatching { input.close() }; return o }
        }
        return runCatching {
            input.use { ins ->
                output.use { outs ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var transferred = 0L
                    var read: Int
                    while (ins.read(buffer).also { read = it } >= 0) {
                        outs.write(buffer, 0, read)
                        transferred += read
                        observer?.onProgress(transferred, size)
                    }
                    outs.flush()
                }
            }
            Result.Success(Unit)
        }.getOrElse { t ->
            if (t is CancellationException) throw t
            Result.Failure(StorageError.IoError("コピーに失敗しました: ${t.message}", t))
        }
    }

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
        val pending = uri.pendingChildName
        // pendingChildName 付きは「親 URI + これから作る子の名前」。
        // 親側で findFile(name) して存在を判定する。`fromSingleUri` は何も検証せず
        // wrapper を返してしまうので、ここを通すと「常に存在する」誤判定になる。
        if (pending != null) {
            val parent = DocumentFile.fromTreeUri(context, androidUri)
                ?: return Result.Failure(StorageError.NotFound(uri))
            val child = parent.findFile(pending)
                ?: return Result.Failure(StorageError.NotFound(uri))
            return Result.Success(child.toFileNode(uri))
        }
        val doc = DocumentFile.fromTreeUri(context, androidUri)
            ?: DocumentFile.fromSingleUri(context, androidUri)
            ?: return Result.Failure(StorageError.NotFound(uri))
        if (!doc.exists()) return Result.Failure(StorageError.NotFound(uri))
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

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    private fun safComparator(options: ListOptions): Comparator<DocumentFile> {
        val nameOrder: Comparator<DocumentFile> = Comparator { a, b ->
            String.CASE_INSENSITIVE_ORDER.compare(a.name.orEmpty(), b.name.orEmpty())
        }
        val base: Comparator<DocumentFile> = when (options.sortBy) {
            SortBy.NAME -> nameOrder
            SortBy.SIZE -> compareBy<DocumentFile> { it.length() }.then(nameOrder)
            SortBy.DATE -> compareBy<DocumentFile> { it.lastModified() }.then(nameOrder)
            SortBy.TYPE -> compareBy<DocumentFile> { it.name.orEmpty().substringAfterLast('.').lowercase() }.then(nameOrder)
        }
        val ordered = if (options.sortAscending) base else base.reversed()
        return compareByDescending<DocumentFile> { it.isDirectory }.then(ordered)
    }

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
