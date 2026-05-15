package com.zerotoship.foldex.storage.webdav.internal

import com.zerotoship.foldex.core.data.repo.ConnectionRepository
import com.zerotoship.foldex.core.model.Connection
import com.zerotoship.foldex.core.model.Credential
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebDAV 接続のメタ情報 (ベース URL + Basic 認証ヘッダ) を ConnectionRepository から
 * 解決し、簡易キャッシュする。OkHttpClient は単一インスタンスを再利用する想定なので、
 * クライアント本体ではなくセッション (= 接続毎の URL/Auth) のみここで管理する。
 */
@Singleton
internal class WebDavSessionStore @Inject constructor(
    private val repository: ConnectionRepository,
) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, Holder>()

    suspend fun resolve(connectionId: String): Session = mutex.withLock {
        val connection = repository.findById(connectionId)
            ?: error("Connection not found: $connectionId")
        require(connection is Connection.WebDav) {
            "WebDavSessionStore only handles Connection.WebDav (got ${connection::class.simpleName})"
        }
        cache[connectionId]?.let { holder ->
            // host/port/useHttps/basePath/username が変わっていなければ再利用。
            // 変わっていれば作り直し (= 編集後即時反映)。
            if (holder.matches(connection)) return@withLock holder.session
        }
        val credential = repository.loadCredential(connectionId) ?: Credential.Anonymous
        val session = buildSession(connection, credential)
        cache[connectionId] = Holder(spec = connection, session = session)
        session
    }

    suspend fun invalidate(connectionId: String) {
        mutex.withLock { cache.remove(connectionId) }
    }

    suspend fun invalidateAll() {
        mutex.withLock { cache.clear() }
    }

    private fun buildSession(connection: Connection.WebDav, credential: Credential): Session {
        val scheme = if (connection.useHttps) "https" else "http"
        val baseUrlString = buildString {
            append(scheme).append("://").append(connection.host)
            if ((connection.useHttps && connection.port != 443) ||
                (!connection.useHttps && connection.port != 80)
            ) {
                append(':').append(connection.port)
            }
            val basePath = if (connection.basePath.startsWith('/')) connection.basePath
            else "/${connection.basePath}"
            append(basePath.trimEnd('/'))
        }
        val baseUrl = baseUrlString.toHttpUrlOrNull()
            ?: error("Invalid WebDAV base URL: $baseUrlString")
        val authHeader = when (credential) {
            is Credential.Password -> {
                val user = connection.username.orEmpty()
                val pass = String(credential.secret, Charsets.UTF_8)
                okhttp3.Credentials.basic(user, pass)
            }
            is Credential.Anonymous -> null
            is Credential.SshPrivateKey -> error("SSH private key is not valid for WebDAV")
        }
        return Session(baseUrl = baseUrl, authHeader = authHeader)
    }

    private data class Holder(
        val spec: Connection.WebDav,
        val session: Session,
    ) {
        fun matches(current: Connection.WebDav): Boolean =
            spec.host == current.host &&
                spec.port == current.port &&
                spec.useHttps == current.useHttps &&
                spec.basePath == current.basePath &&
                spec.username == current.username
    }

    data class Session(val baseUrl: HttpUrl, val authHeader: String?) {
        /** 任意の POSIX パスをベース URL に結合 (リソース URL を生成)。 */
        fun resolve(path: String): HttpUrl {
            val builder = baseUrl.newBuilder()
            val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
            for (segment in segments) builder.addPathSegment(segment)
            return builder.build()
        }
    }
}
