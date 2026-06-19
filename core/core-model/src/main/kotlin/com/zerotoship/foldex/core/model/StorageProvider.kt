package com.zerotoship.foldex.core.model

import com.zerotoship.foldex.core.common.Result
import kotlinx.coroutines.flow.Flow
import java.io.InputStream
import java.io.OutputStream

interface StorageProvider {
    fun canHandle(uri: FileUri): Boolean
    suspend fun connect(): Result<Unit, StorageError>
    suspend fun disconnect()
    suspend fun stat(uri: FileUri): Result<FileNode, StorageError>
    fun list(uri: FileUri, options: ListOptions = ListOptions()): Flow<FileNode>
    suspend fun openInput(uri: FileUri): Result<InputStream, StorageError>

    /**
     * 指定オフセットから始まる読み取りストリームを開く (seek 対応プロトコル向け)。
     * 既定実装は [openInput] を呼んで [offset] バイト skip する単純実装。
     * 各実装が範囲リクエスト (SMB SMB2_READ at offset, SFTP RemoteFile.read(offset,...),
     * FTP REST, WebDAV Range ヘッダ) を持つ場合は override してネイティブ範囲読み取りに置換すべき。
     */
    suspend fun openInputRange(uri: FileUri, offset: Long): Result<InputStream, StorageError> {
        return when (val r = openInput(uri)) {
            is Result.Success -> {
                val stream = r.value
                runCatching {
                    var remaining = offset
                    while (remaining > 0) {
                        val skipped = stream.skip(remaining)
                        if (skipped <= 0) break
                        remaining -= skipped
                    }
                }.onFailure { runCatching { stream.close() } }
                Result.Success(stream)
            }
            is Result.Failure -> r
        }
    }

    suspend fun openOutput(uri: FileUri, mode: WriteMode): Result<OutputStream, StorageError>
    suspend fun mkdir(uri: FileUri, recursive: Boolean = false): Result<Unit, StorageError>
    suspend fun delete(uri: FileUri, recursive: Boolean = false): Result<Unit, StorageError>
    suspend fun rename(from: FileUri, to: FileUri): Result<Unit, StorageError>
    suspend fun copyWithin(from: FileUri, to: FileUri, observer: ProgressObserver? = null): Result<Unit, StorageError>
    suspend fun moveWithin(from: FileUri, to: FileUri, observer: ProgressObserver? = null): Result<Unit, StorageError>
}
