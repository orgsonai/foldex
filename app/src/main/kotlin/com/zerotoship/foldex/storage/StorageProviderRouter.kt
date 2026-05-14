package com.zerotoship.foldex.storage

import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.core.model.FileNode
import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.core.model.ListOptions
import com.zerotoship.foldex.core.model.NodeType
import com.zerotoship.foldex.core.model.ProgressObserver
import com.zerotoship.foldex.core.model.StorageError
import com.zerotoship.foldex.core.model.StorageProvider
import com.zerotoship.foldex.core.model.WriteMode
import com.zerotoship.foldex.storage.ftp.FtpStorageProvider
import com.zerotoship.foldex.storage.local.LocalStorageProvider
import com.zerotoship.foldex.storage.sftp.SftpStorageProvider
import com.zerotoship.foldex.storage.smb.SmbStorageProvider
import com.zerotoship.foldex.storage.webdav.WebDavStorageProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [FileUri] の種別に応じて適切な [StorageProvider] を選んで委譲するルーター。
 * UI/ViewModel 層は本クラスのみを保持すれば良く、ストレージ実装の追加は本クラスへ反映する。
 *
 * リモートプロバイダ (SMB/SFTP/FTP/WebDAV) は `dagger.Lazy<T>` 経由で受け取り、
 * 最初に使われるまで生成しない。これにより起動経路 (Hilt graph 構築) から
 * jcifs-ng / MINA-SSHD / Apache Commons NET / OkHttp のクラス初期化を外せる。
 */
@Singleton
class StorageProviderRouter @Inject constructor(
    private val local: LocalStorageProvider,
    private val smbLazy: dagger.Lazy<SmbStorageProvider>,
    private val sftpLazy: dagger.Lazy<SftpStorageProvider>,
    private val ftpLazy: dagger.Lazy<FtpStorageProvider>,
    private val webdavLazy: dagger.Lazy<WebDavStorageProvider>,
) : StorageProvider {

    // 一度でも生成された Provider のみ disconnect で触れるよう追跡する。
    @Volatile private var smbCreated = false
    @Volatile private var sftpCreated = false
    @Volatile private var ftpCreated = false
    @Volatile private var webdavCreated = false

    private val smb: SmbStorageProvider get() = smbLazy.get().also { smbCreated = true }
    private val sftp: SftpStorageProvider get() = sftpLazy.get().also { sftpCreated = true }
    private val ftp: FtpStorageProvider get() = ftpLazy.get().also { ftpCreated = true }
    private val webdav: WebDavStorageProvider get() = webdavLazy.get().also { webdavCreated = true }

    private fun pick(uri: FileUri): StorageProvider = when (uri) {
        is FileUri.Local, is FileUri.Saf -> local
        is FileUri.Remote -> when (uri.protocol) {
            com.zerotoship.foldex.core.model.Protocol.SMB -> smb
            com.zerotoship.foldex.core.model.Protocol.SFTP -> sftp
            com.zerotoship.foldex.core.model.Protocol.FTP -> ftp
            com.zerotoship.foldex.core.model.Protocol.WEBDAV -> webdav
        }
    }

    override fun canHandle(uri: FileUri): Boolean = runCatching { pick(uri) }.isSuccess

    override suspend fun connect(): Result<Unit, StorageError> = Result.Success(Unit)

    override suspend fun disconnect() {
        local.disconnect()
        if (smbCreated) smb.disconnect()
        if (sftpCreated) sftp.disconnect()
        if (ftpCreated) ftp.disconnect()
        if (webdavCreated) webdav.disconnect()
    }

    override suspend fun stat(uri: FileUri): Result<FileNode, StorageError> = pick(uri).stat(uri)

    override fun list(uri: FileUri, options: ListOptions): Flow<FileNode> = flow {
        pick(uri).list(uri, options).collect { emit(it) }
    }

    override suspend fun openInput(uri: FileUri): Result<InputStream, StorageError> =
        pick(uri).openInput(uri)

    override suspend fun openOutput(uri: FileUri, mode: WriteMode): Result<OutputStream, StorageError> =
        pick(uri).openOutput(uri, mode)

    override suspend fun mkdir(uri: FileUri, recursive: Boolean): Result<Unit, StorageError> =
        pick(uri).mkdir(uri, recursive)

    override suspend fun delete(uri: FileUri, recursive: Boolean): Result<Unit, StorageError> =
        pick(uri).delete(uri, recursive)

    override suspend fun rename(from: FileUri, to: FileUri): Result<Unit, StorageError> =
        pick(from).rename(from, to)

    override suspend fun copyWithin(
        from: FileUri,
        to: FileUri,
        observer: ProgressObserver?,
    ): Result<Unit, StorageError> {
        // 同一プロバイダ内なら実装側の最適化に任せる。プロバイダを跨ぐ場合
        // (例: SMB 上のファイルを端末ローカルへ) はストリームでブリッジコピーする。
        val src = pick(from)
        return if (src === pick(to)) {
            src.copyWithin(from, to, observer)
        } else {
            crossCopy(from, to, observer)
        }
    }

    override suspend fun moveWithin(
        from: FileUri,
        to: FileUri,
        observer: ProgressObserver?,
    ): Result<Unit, StorageError> {
        val src = pick(from)
        if (src === pick(to)) return src.moveWithin(from, to, observer)
        // 跨プロバイダの移動 = コピーしてから元を削除。
        return when (val copied = crossCopy(from, to, observer)) {
            is Result.Success -> src.delete(from, recursive = true)
            is Result.Failure -> copied
        }
    }

    // --- 跨プロバイダ コピー ---

    private suspend fun crossCopy(
        from: FileUri,
        to: FileUri,
        observer: ProgressObserver?,
    ): Result<Unit, StorageError> {
        val node = when (val s = stat(from)) {
            is Result.Success -> s.value
            is Result.Failure -> return s
        }
        return if (node.type == NodeType.DIRECTORY) {
            copyDirectory(from, to, observer)
        } else {
            copyFile(from, to, node.size, observer)
        }
    }

    private suspend fun copyDirectory(
        from: FileUri,
        to: FileUri,
        observer: ProgressObserver?,
    ): Result<Unit, StorageError> {
        // 行き先を用意 (既に同名ディレクトリがあれば許容する)。
        when (val m = mkdir(to, recursive = true)) {
            is Result.Success -> Unit
            is Result.Failure -> {
                val existing = stat(to)
                if (!(existing is Result.Success && existing.value.type == NodeType.DIRECTORY)) {
                    return m
                }
            }
        }
        var failure: StorageError? = null
        list(from).collect { child ->
            if (failure != null) return@collect
            coroutineContext.ensureActive()
            val childDest = childUri(to, child.name) ?: run {
                failure = StorageError.IoError("コピー先のパスを解決できません: ${child.name}")
                return@collect
            }
            when (val r = crossCopy(child.uri, childDest, observer)) {
                is Result.Success -> Unit
                is Result.Failure -> failure = r.error
            }
        }
        return failure?.let { Result.Failure(it) } ?: Result.Success(Unit)
    }

    private suspend fun copyFile(
        from: FileUri,
        to: FileUri,
        size: Long,
        observer: ProgressObserver?,
    ): Result<Unit, StorageError> {
        val input = when (val i = openInput(from)) {
            is Result.Success -> i.value
            is Result.Failure -> return i
        }
        val output = when (val o = openOutput(to, WriteMode.OVERWRITE)) {
            is Result.Success -> o.value
            is Result.Failure -> { runCatching { input.close() }; return o }
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                input.use { ins ->
                    output.use { outs ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = ins.read(buf)
                            if (n < 0) break
                            outs.write(buf, 0, n)
                            total += n
                            observer?.onProgress(total, size)
                        }
                        outs.flush()
                    }
                }
                Result.Success(Unit)
            }.getOrElse { t ->
                if (t is kotlinx.coroutines.CancellationException) throw t
                Result.Failure(StorageError.IoError("コピーに失敗しました: ${t.message}", t))
            }
        }
    }

    /**
     * [parent] ディレクトリ配下に [name] という子要素を持つ URI を組み立てる。
     * SAF はまだ存在しない子の URI を解決できないので、`pendingChildName` を持つ
     * 「擬似 URI」を返す: mkdir/openOutput(CREATE_NEW) はこれを見て親に createDocument する。
     */
    private fun childUri(parent: FileUri, name: String): FileUri? = when (parent) {
        is FileUri.Local -> FileUri.Local("${parent.absolutePath.trimEnd('/')}/$name")
        is FileUri.Saf -> FileUri.Saf(parent.documentUri, pendingChildName = name)
        is FileUri.Remote -> FileUri.Remote(
            protocol = parent.protocol,
            connectionId = parent.connectionId,
            path = "${parent.path.trimEnd('/')}/$name",
        )
    }
}
