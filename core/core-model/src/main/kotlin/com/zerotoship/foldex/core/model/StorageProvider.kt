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
    suspend fun openOutput(uri: FileUri, mode: WriteMode): Result<OutputStream, StorageError>
    suspend fun mkdir(uri: FileUri, recursive: Boolean = false): Result<Unit, StorageError>
    suspend fun delete(uri: FileUri, recursive: Boolean = false): Result<Unit, StorageError>
    suspend fun rename(from: FileUri, to: FileUri): Result<Unit, StorageError>
    suspend fun copyWithin(from: FileUri, to: FileUri, observer: ProgressObserver? = null): Result<Unit, StorageError>
    suspend fun moveWithin(from: FileUri, to: FileUri, observer: ProgressObserver? = null): Result<Unit, StorageError>
}
