package com.zerotoship.foldex.storage.ftp.internal

import com.zerotoship.foldex.core.data.repo.ConnectionRepository
import com.zerotoship.foldex.core.model.Connection
import com.zerotoship.foldex.core.model.Credential
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 接続ごとに [FTPClient] (平文 FTP) または [FTPSClient] (Explicit FTPS) を遅延生成 + キャッシュする。
 *
 * Apache Commons Net の FTPClient はスレッドセーフではないので、同一接続への並列操作は
 * ViewModel/UI 側で直列化される前提とする (P5 範囲では問題にならない)。
 */
@Singleton
internal class FtpClientPool @Inject constructor(
    private val repository: ConnectionRepository,
) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, Holder>()

    suspend fun acquire(connectionId: String): FTPClient {
        mutex.withLock {
            val connection = repository.findById(connectionId)
                ?: error("Connection not found: $connectionId")
            require(connection is Connection.Ftp) {
                "FtpClientPool only handles Connection.Ftp (got ${connection::class.simpleName})"
            }
            cache[connectionId]?.let { holder ->
                // 接続が生きていて、かつキャッシュ時の host/port/username/useTls/passive/charset が
                // 現在と一致するなら再利用。一致しなければ張り直す (編集後即時反映)。
                if (holder.client.isConnected && holder.client.sendNoOp() && holder.matches(connection)) {
                    return holder.client
                }
                close(holder.client)
                cache.remove(connectionId)
            }
            val credential = repository.loadCredential(connectionId) ?: Credential.Anonymous
            val client = open(connection, credential)
            cache[connectionId] = Holder(spec = connection, client = client)
            return client
        }
    }

    suspend fun release(connectionId: String) {
        mutex.withLock {
            cache.remove(connectionId)?.let { close(it.client) }
        }
    }

    suspend fun closeAll() {
        mutex.withLock {
            cache.values.forEach { close(it.client) }
            cache.clear()
        }
    }

    private data class Holder(
        /** プール時点の接続設定。再利用判定に使う。 */
        val spec: Connection.Ftp,
        val client: FTPClient,
    ) {
        fun matches(current: Connection.Ftp): Boolean =
            spec.host == current.host &&
                spec.port == current.port &&
                spec.username == current.username &&
                spec.useTls == current.useTls &&
                spec.passiveMode == current.passiveMode &&
                spec.charset == current.charset
    }

    private fun open(connection: Connection.Ftp, credential: Credential): FTPClient {
        val client: FTPClient = if (connection.useTls) FTPSClient("TLS", false) else FTPClient()
        client.controlEncoding = connection.charset
        client.connectTimeout = 15_000
        try {
            client.connect(connection.host, connection.port)
            val replyCode = client.replyCode
            if (replyCode !in 200..299 && replyCode !in 100..199) {
                client.disconnect()
                error("FTP connect failed: reply $replyCode")
            }
            val (user, password) = when (credential) {
                is Credential.Password -> {
                    (connection.username ?: "anonymous") to String(credential.secret, Charsets.UTF_8)
                }
                is Credential.Anonymous -> "anonymous" to "anonymous@"
                is Credential.SshPrivateKey -> error("SSH private key is not valid for FTP")
            }
            val loginOk = client.login(user, password)
            if (!loginOk) {
                client.disconnect()
                error("FTP authentication failed")
            }
            if (client is FTPSClient) {
                client.execPBSZ(0)
                client.execPROT("P")
            }
            client.setFileType(FTP.BINARY_FILE_TYPE)
            if (connection.passiveMode) {
                client.enterLocalPassiveMode()
            } else {
                client.enterLocalActiveMode()
            }
            return client
        } catch (t: Throwable) {
            runCatching { if (client.isConnected) client.disconnect() }
            throw t
        }
    }

    private fun close(client: FTPClient) {
        runCatching { if (client.isConnected) client.logout() }
        runCatching { if (client.isConnected) client.disconnect() }
    }
}
