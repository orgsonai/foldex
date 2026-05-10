package com.zerotoship.foldex.storage.smb.internal

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection as SmbjConnection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.zerotoship.foldex.core.data.repo.ConnectionRepository
import com.zerotoship.foldex.core.model.Connection
import com.zerotoship.foldex.core.model.Credential
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 接続ごとに smbj の Connection / Session / DiskShare を遅延生成 + キャッシュする。
 * 切断は [release] か [closeAll] で行う。
 */
@Singleton
internal class SmbSessionPool @Inject constructor(
    private val repository: ConnectionRepository,
) {
    private val mutex = Mutex()
    private val client: SMBClient = SMBClient(SmbConfig.builder().build())
    private val cache = mutableMapOf<String, Holder>()

    /** Disk share を取得する。未接続なら新規接続。 */
    suspend fun acquire(connectionId: String): DiskShare {
        mutex.withLock {
            cache[connectionId]?.let { holder ->
                if (holder.share.isConnected) return holder.share
                close(holder)
                cache.remove(connectionId)
            }
            val connection = repository.findById(connectionId)
                ?: error("Connection not found: $connectionId")
            require(connection is Connection.Smb) {
                "SmbSessionPool only handles Connection.Smb (got ${connection::class.simpleName})"
            }
            val credential = repository.loadCredential(connectionId) ?: Credential.Anonymous
            val holder = open(connection, credential)
            cache[connectionId] = holder
            return holder.share
        }
    }

    /** 単一接続の切断 (タイムアウトや UI からの明示切断時)。 */
    suspend fun release(connectionId: String) {
        mutex.withLock {
            cache.remove(connectionId)?.let { close(it) }
        }
    }

    /** 全接続を切断 (Provider.disconnect() 用)。 */
    suspend fun closeAll() {
        mutex.withLock {
            cache.values.forEach { close(it) }
            cache.clear()
        }
    }

    private fun open(connection: Connection.Smb, credential: Credential): Holder {
        val authContext = when (credential) {
            is Credential.Password -> {
                val passwordChars = String(credential.secret, Charsets.UTF_8).toCharArray()
                AuthenticationContext(
                    connection.username.orEmpty(),
                    passwordChars,
                    connection.domain,
                )
            }
            is Credential.Anonymous -> AuthenticationContext.anonymous()
            is Credential.SshPrivateKey ->
                error("SSH private key credential is not valid for SMB")
        }
        val smbjConn: SmbjConnection = client.connect(connection.host, connection.port)
        val session: Session = try {
            smbjConn.authenticate(authContext)
        } catch (t: Throwable) {
            runCatching { smbjConn.close() }
            throw t
        }
        val share = try {
            session.connectShare(connection.share) as? DiskShare
                ?: error("Share is not a DiskShare: ${connection.share}")
        } catch (t: Throwable) {
            runCatching { session.close() }
            runCatching { smbjConn.close() }
            throw t
        }
        return Holder(connection = smbjConn, session = session, share = share)
    }

    private fun close(holder: Holder) {
        runCatching { holder.share.close() }
        runCatching { holder.session.close() }
        runCatching { holder.connection.close() }
    }

    private data class Holder(
        val connection: SmbjConnection,
        val session: Session,
        val share: DiskShare,
    )
}
