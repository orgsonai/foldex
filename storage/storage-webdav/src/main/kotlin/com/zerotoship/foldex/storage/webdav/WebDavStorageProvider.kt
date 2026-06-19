// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.storage.webdav

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
import com.zerotoship.foldex.storage.webdav.internal.WebDavMultiStatusParser
import com.zerotoship.foldex.storage.webdav.internal.WebDavPath
import com.zerotoship.foldex.storage.webdav.internal.WebDavSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

@Singleton
class WebDavStorageProvider @Inject internal constructor(
    private val sessions: WebDavSessionStore,
) : StorageProvider {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun canHandle(uri: FileUri): Boolean =
        uri is FileUri.Remote && uri.protocol == Protocol.WEBDAV

    override suspend fun connect(): Result<Unit, StorageError> = Result.Success(Unit)

    override suspend fun disconnect() {
        sessions.invalidateAll()
    }

    override suspend fun stat(uri: FileUri): Result<FileNode, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(uri) {
                val remote = uri.asRemote()
                    ?: return@runCatching Result.Failure(StorageError.IoError("Not a WebDAV URI"))
                val session = sessions.resolve(remote.connectionId)
                val target = WebDavPath.normalize(remote.path)
                val resourceUrl = session.resolve(target)
                val entry = propfind(session, resourceUrl, depth = "0")
                    .firstOrNull()
                    ?: return@runCatching Result.Failure(StorageError.NotFound(uri))
                val name = WebDavPath.basename(target).ifEmpty {
                    entry.displayName ?: WebDavPath.basename(entry.decodedPath)
                }
                Result.Success(toFileNode(uri, name, entry))
            }
        }

    override fun list(uri: FileUri, options: ListOptions): Flow<FileNode> = flow {
        val remote = uri.asRemote() ?: throw IOException("Not a WebDAV URI")
        val session = sessions.resolve(remote.connectionId)
        val dirPath = WebDavPath.normalize(remote.path)
        val resourceUrl = session.resolve(dirPath)
        val entries = propfind(session, resourceUrl, depth = "1")
        val parentDecoded = decodedPathOfBaseUrl(session.resolve(dirPath))
        val children = entries
            .asSequence()
            .filter { isProperChild(it.decodedPath, parentDecoded) }
            .filter { entry ->
                val name = WebDavPath.basename(entry.decodedPath)
                if (options.showHidden) true else !name.startsWith('.')
            }
            .sortedWith(webDavComparator(options))
            .toList()
        for (child in children) {
            val childName = child.displayName ?: WebDavPath.basename(child.decodedPath)
            val childPath = WebDavPath.join(dirPath, childName)
            val childUri = FileUri.Remote(Protocol.WEBDAV, remote.connectionId, childPath)
            emit(toFileNode(childUri, childName, child))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun openInput(uri: FileUri): Result<InputStream, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(uri) {
                val remote = uri.asRemote()
                    ?: return@runCatching Result.Failure(StorageError.IoError("Not a WebDAV URI"))
                val session = sessions.resolve(remote.connectionId)
                val request = baseRequest(session, session.resolve(remote.path)).get().build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    val err = translateResponse(uri, response)
                    response.close()
                    return@runCatching Result.Failure(err)
                }
                Result.Success(WebDavInputStream(response) as InputStream)
            }
        }

    override suspend fun openInputRange(uri: FileUri, offset: Long): Result<InputStream, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(uri) {
                val remote = uri.asRemote()
                    ?: return@runCatching Result.Failure(StorageError.IoError("Not a WebDAV URI"))
                if (offset <= 0L) return@runCatching openInput(uri)
                val session = sessions.resolve(remote.connectionId)
                // HTTP Range ヘッダで位置指定読み取り。サーバが Range を実装していない場合は 200 OK で全体を
                // 返してくるので、その場合は skip-based のフォールバック (default 実装) に切替える。
                val request = baseRequest(session, session.resolve(remote.path))
                    .addHeader("Range", "bytes=$offset-")
                    .get()
                    .build()
                val response = client.newCall(request).execute()
                if (response.code == 206) {
                    // Partial Content。期待通り offset から先のバイトが流れる。
                    Result.Success(WebDavInputStream(response) as InputStream)
                } else if (response.isSuccessful) {
                    // Range 非対応サーバ: 200 OK で全体が返るので、デフォルト挙動 (skip) で代用。
                    response.close()
                    super.openInputRange(uri, offset)
                } else {
                    val err = translateResponse(uri, response)
                    response.close()
                    Result.Failure(err)
                }
            }
        }

    override suspend fun openOutput(uri: FileUri, mode: WriteMode): Result<OutputStream, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(uri) {
                val remote = uri.asRemote()
                    ?: return@runCatching Result.Failure(StorageError.IoError("Not a WebDAV URI"))
                if (mode == WriteMode.APPEND) {
                    return@runCatching Result.Failure(
                        StorageError.IoError("APPEND mode not supported for WebDAV"),
                    )
                }
                val session = sessions.resolve(remote.connectionId)
                if (mode == WriteMode.CREATE_NEW) {
                    val statResult = stat(uri)
                    if (statResult is Result.Success) {
                        return@runCatching Result.Failure(StorageError.AlreadyExists(uri))
                    }
                }
                val tempFile = File.createTempFile("foldex-webdav-", ".put")
                val builder = baseRequest(session, session.resolve(remote.path))
                Result.Success(WebDavOutputStream(client, builder, tempFile) as OutputStream)
            }
        }

    override suspend fun mkdir(uri: FileUri, recursive: Boolean): Result<Unit, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(uri) {
                val remote = uri.asRemote()
                    ?: return@runCatching Result.Failure(StorageError.IoError("Not a WebDAV URI"))
                val session = sessions.resolve(remote.connectionId)
                val target = WebDavPath.normalize(remote.path)
                val parts = target.trim('/').split('/').filter { it.isNotEmpty() }
                if (parts.isEmpty()) return@runCatching Result.Success(Unit)
                if (recursive) {
                    var current = ""
                    for (part in parts) {
                        current = "$current/$part"
                        if (!ensureCollection(session, current)) {
                            return@runCatching Result.Failure(
                                StorageError.IoError("MKCOL failed at $current"),
                            )
                        }
                    }
                } else {
                    val request = baseRequest(session, session.resolve(target))
                        .method("MKCOL", null)
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            return@runCatching Result.Failure(translateResponse(uri, response))
                        }
                    }
                }
                Result.Success(Unit)
            }
        }

    override suspend fun delete(uri: FileUri, recursive: Boolean): Result<Unit, StorageError> =
        withContext(Dispatchers.IO) {
            runCatching(uri) {
                val remote = uri.asRemote()
                    ?: return@runCatching Result.Failure(StorageError.IoError("Not a WebDAV URI"))
                val session = sessions.resolve(remote.connectionId)
                val request = baseRequest(session, session.resolve(remote.path))
                    .delete()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@runCatching Result.Failure(translateResponse(uri, response))
                    }
                }
                Result.Success(Unit)
            }
        }

    override suspend fun rename(from: FileUri, to: FileUri): Result<Unit, StorageError> =
        withContext(Dispatchers.IO) { move(from, to) }

    override suspend fun copyWithin(
        from: FileUri,
        to: FileUri,
        observer: ProgressObserver?,
    ): Result<Unit, StorageError> = withContext(Dispatchers.IO) {
        runCatching(from) {
            val src = from.asRemote()
                ?: return@runCatching Result.Failure(StorageError.IoError("Not a WebDAV URI"))
            val dst = to.asRemote()
                ?: return@runCatching Result.Failure(StorageError.IoError("Cross-protocol copy not supported"))
            if (src.connectionId != dst.connectionId) {
                return@runCatching Result.Failure(
                    StorageError.IoError("Cross-connection copy not supported"),
                )
            }
            val session = sessions.resolve(src.connectionId)
            val request = baseRequest(session, session.resolve(src.path))
                .header("Destination", session.resolve(dst.path).toString())
                .header("Overwrite", "T")
                .method("COPY", null)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@runCatching Result.Failure(translateResponse(from, response))
                }
            }
            Result.Success(Unit)
        }
    }

    override suspend fun moveWithin(
        from: FileUri,
        to: FileUri,
        observer: ProgressObserver?,
    ): Result<Unit, StorageError> = withContext(Dispatchers.IO) { move(from, to) }

    // --- helpers ---

    private suspend fun move(from: FileUri, to: FileUri): Result<Unit, StorageError> =
        runCatching(from) {
            val src = from.asRemote()
                ?: return@runCatching Result.Failure(StorageError.IoError("Not a WebDAV URI"))
            val dst = to.asRemote()
                ?: return@runCatching Result.Failure(StorageError.IoError("Cross-protocol move not supported"))
            if (src.connectionId != dst.connectionId) {
                return@runCatching Result.Failure(
                    StorageError.IoError("Cross-connection move not supported"),
                )
            }
            val session = sessions.resolve(src.connectionId)
            val request = baseRequest(session, session.resolve(src.path))
                .header("Destination", session.resolve(dst.path).toString())
                .header("Overwrite", "T")
                .method("MOVE", null)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@runCatching Result.Failure(translateResponse(from, response))
                }
            }
            Result.Success(Unit)
        }

    private fun ensureCollection(session: WebDavSessionStore.Session, path: String): Boolean {
        val request = baseRequest(session, session.resolve(path))
            .method("MKCOL", null)
            .build()
        client.newCall(request).execute().use { response ->
            // 201 Created or 405 Method Not Allowed (already exists) を OK 扱い
            return response.isSuccessful || response.code == 405
        }
    }

    private fun propfind(
        session: WebDavSessionStore.Session,
        url: HttpUrl,
        depth: String,
    ): List<WebDavMultiStatusParser.Entry> {
        val request = baseRequest(session, url)
            .header("Depth", depth)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(MEDIA_TYPE_XML))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("PROPFIND failed: HTTP ${response.code} ${response.message}")
            }
            val body = response.body ?: throw IOException("PROPFIND returned empty body")
            return body.byteStream().use { stream ->
                WebDavMultiStatusParser.parse(stream)
            }
        }
    }

    private fun baseRequest(session: WebDavSessionStore.Session, url: HttpUrl): Request.Builder {
        val builder = Request.Builder().url(url)
        session.authHeader?.let { builder.header("Authorization", it) }
        return builder
    }

    private fun decodedPathOfBaseUrl(url: HttpUrl): String {
        val raw = url.encodedPath
        val decoded = java.net.URLDecoder.decode(raw, Charsets.UTF_8)
        return decoded.trimEnd('/')
    }

    private fun isProperChild(decodedHrefPath: String, parentDecoded: String): Boolean {
        val a = decodedHrefPath.trimEnd('/')
        val b = parentDecoded.trimEnd('/')
        if (a == b) return false
        val prefix = if (b.endsWith('/')) b else "$b/"
        if (!a.startsWith(prefix)) return false
        val rest = a.substring(prefix.length)
        return !rest.contains('/')
    }

    private inline fun <T> runCatching(uri: FileUri, block: () -> Result<T, StorageError>): Result<T, StorageError> {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.net.UnknownHostException) {
            Result.Failure(StorageError.HostUnreachable(uri.hostOrDescription(), e))
        } catch (e: javax.net.ssl.SSLException) {
            Result.Failure(StorageError.ProtocolError(Protocol.WEBDAV, "TLS error: ${e.message}", e))
        } catch (e: IOException) {
            Result.Failure(StorageError.IoError(e.message ?: "I/O error", e))
        } catch (e: Exception) {
            Result.Failure(StorageError.Unknown(e.message ?: "Unknown error", e))
        }
    }

    private fun translateResponse(uri: FileUri, response: Response): StorageError = when (response.code) {
        401, 403 -> StorageError.AuthenticationFailed("HTTP ${response.code} ${response.message}")
        404, 410 -> StorageError.NotFound(uri)
        409 -> StorageError.IoError("WebDAV conflict: HTTP ${response.code}")
        412 -> StorageError.AlreadyExists(uri)
        in 400..599 -> StorageError.ProtocolError(
            Protocol.WEBDAV,
            "HTTP ${response.code} ${response.message}",
        )
        else -> StorageError.IoError("HTTP ${response.code} ${response.message}")
    }

    private fun FileUri.asRemote(): FileUri.Remote? = this as? FileUri.Remote

    private fun FileUri.hostOrDescription(): String = when (this) {
        is FileUri.Remote -> connectionId
        else -> toStorageString()
    }

    private fun toFileNode(
        uri: FileUri,
        name: String,
        entry: WebDavMultiStatusParser.Entry,
    ): FileNode {
        return FileNode(
            uri = uri,
            name = name,
            type = if (entry.isCollection) NodeType.DIRECTORY else NodeType.FILE,
            size = entry.contentLength,
            lastModified = entry.lastModifiedEpochMillis?.let { Instant.fromEpochMilliseconds(it) },
            permissions = Permissions(readable = true, writable = true),
            isHidden = name.startsWith('.'),
        )
    }

    private fun webDavComparator(options: ListOptions): Comparator<WebDavMultiStatusParser.Entry> {
        val nameOrder = Comparator<WebDavMultiStatusParser.Entry> { a, b ->
            val an = a.displayName ?: WebDavPath.basename(a.decodedPath)
            val bn = b.displayName ?: WebDavPath.basename(b.decodedPath)
            String.CASE_INSENSITIVE_ORDER.compare(an, bn)
        }
        val base: Comparator<WebDavMultiStatusParser.Entry> = when (options.sortBy) {
            SortBy.NAME -> nameOrder
            SortBy.SIZE -> compareBy<WebDavMultiStatusParser.Entry> { it.contentLength }.then(nameOrder)
            SortBy.DATE -> compareBy<WebDavMultiStatusParser.Entry> {
                it.lastModifiedEpochMillis ?: 0L
            }.then(nameOrder)
            SortBy.TYPE -> compareBy<WebDavMultiStatusParser.Entry> {
                val n = it.displayName ?: WebDavPath.basename(it.decodedPath)
                n.substringAfterLast('.', "").lowercase()
            }.then(nameOrder)
        }
        val ordered = if (options.sortAscending) base else base.reversed()
        return Comparator { a, b ->
            if (a.isCollection != b.isCollection) if (a.isCollection) -1 else 1
            else ordered.compare(a, b)
        }
    }

    companion object {
        private val MEDIA_TYPE_XML = "application/xml; charset=utf-8".toMediaType()
        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:resourcetype/>
    <d:getcontentlength/>
    <d:getlastmodified/>
    <d:displayname/>
  </d:prop>
</d:propfind>
"""
    }
}
