package com.zerotoship.foldex.storage

import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.core.model.FileNode
import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.core.model.ListOptions
import com.zerotoship.foldex.core.model.ProgressObserver
import com.zerotoship.foldex.core.model.StorageError
import com.zerotoship.foldex.core.model.StorageProvider
import com.zerotoship.foldex.core.model.WriteMode
import com.zerotoship.foldex.storage.local.LocalStorageProvider
import com.zerotoship.foldex.storage.smb.SmbStorageProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [FileUri] の種別に応じて適切な [StorageProvider] を選んで委譲するルーター。
 * UI/ViewModel 層は本クラスのみを保持すれば良く、ストレージ実装の追加は本クラスへ反映する。
 */
@Singleton
class StorageProviderRouter @Inject constructor(
    private val local: LocalStorageProvider,
    private val smb: SmbStorageProvider,
) : StorageProvider {

    private fun pick(uri: FileUri): StorageProvider = when (uri) {
        is FileUri.Local, is FileUri.Saf -> local
        is FileUri.Remote -> when (uri.protocol) {
            com.zerotoship.foldex.core.model.Protocol.SMB -> smb
            else -> error("No StorageProvider for ${uri.protocol} (P5+)")
        }
    }

    override fun canHandle(uri: FileUri): Boolean = runCatching { pick(uri) }.isSuccess

    override suspend fun connect(): Result<Unit, StorageError> = Result.Success(Unit)

    override suspend fun disconnect() {
        local.disconnect()
        smb.disconnect()
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
    ): Result<Unit, StorageError> = pick(from).copyWithin(from, to, observer)

    override suspend fun moveWithin(
        from: FileUri,
        to: FileUri,
        observer: ProgressObserver?,
    ): Result<Unit, StorageError> = pick(from).moveWithin(from, to, observer)
}
