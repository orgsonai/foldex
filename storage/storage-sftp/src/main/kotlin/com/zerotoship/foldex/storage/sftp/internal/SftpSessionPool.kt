package com.zerotoship.foldex.storage.sftp.internal

import com.zerotoship.foldex.core.data.repo.ConnectionRepository
import com.zerotoship.foldex.core.model.Connection
import com.zerotoship.foldex.core.model.Credential
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 接続ごとに sshj の [SSHClient] / [SFTPClient] を遅延生成 + キャッシュする。
 *
 * ホスト鍵フィンガープリントが Connection に未設定の場合は、初回接続時に検出した
 * 値を [ConnectionRepository] 経由で保存し、TOFU で次回以降は固定検証する。
 */
@Singleton
internal class SftpSessionPool @Inject constructor(
    private val repository: ConnectionRepository,
) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, Holder>()

    suspend fun acquire(connectionId: String): SFTPClient {
        mutex.withLock {
            val connection = repository.findById(connectionId)
                ?: error("Connection not found: $connectionId")
            require(connection is Connection.Sftp) {
                "SftpSessionPool only handles Connection.Sftp (got ${connection::class.simpleName})"
            }
            cache[connectionId]?.let { holder ->
                // 接続が生きていて、かつキャッシュ時の host/port/username/authMethod/fingerprint が
                // 現在と一致するなら再利用。それ以外は張り直し (編集後即時反映)。
                if (holder.client.isConnected && holder.matches(connection)) return holder.sftp
                close(holder)
                cache.remove(connectionId)
            }
            val credential = repository.loadCredential(connectionId) ?: Credential.Anonymous
            val holder = open(connection, credential)
            cache[connectionId] = holder
            return holder.sftp
        }
    }

    suspend fun release(connectionId: String) {
        mutex.withLock {
            cache.remove(connectionId)?.let { close(it) }
        }
    }

    suspend fun closeAll() {
        mutex.withLock {
            cache.values.forEach { close(it) }
            cache.clear()
        }
    }

    private fun open(connection: Connection.Sftp, credential: Credential): Holder {
        val client = SSHClient(DefaultConfig())
        var capturedFingerprint: String? = null
        val verifier = SftpHostKeyVerifier(
            expectedFingerprint = connection.hostKeyFingerprint,
            onCapture = { fp -> capturedFingerprint = fp },
        )
        client.addHostKeyVerifier(verifier)
        try {
            client.connect(connection.host, connection.port)
            authenticate(client, connection, credential)
            val sftp = client.newSFTPClient()
            return Holder(
                spec = connection,
                client = client,
                sftp = sftp,
                capturedFingerprint = capturedFingerprint,
            )
        } catch (t: Throwable) {
            runCatching { client.disconnect() }
            throw t
        }
    }

    private fun authenticate(
        client: SSHClient,
        connection: Connection.Sftp,
        credential: Credential,
    ) {
        val username = connection.username
            ?: error("SFTP requires username")
        when (credential) {
            is Credential.Password -> {
                val chars = String(credential.secret, Charsets.UTF_8).toCharArray()
                client.authPassword(username, chars)
            }
            is Credential.SshPrivateKey -> {
                val keyText = String(credential.keyData, Charsets.UTF_8)
                val passphrase = credential.passphrase
                val provider = if (passphrase != null) {
                    val passChars = String(passphrase, Charsets.UTF_8).toCharArray()
                    client.loadKeys(keyText, null, OneShotPasswordFinder(passChars))
                } else {
                    client.loadKeys(keyText, null, null)
                }
                client.authPublickey(username, provider)
            }
            is Credential.Anonymous ->
                error("Anonymous auth is not supported for SFTP")
        }
    }

    /**
     * ホスト鍵検証で取得したフィンガープリントを呼び出し側に返す。
     * Connection.Sftp.hostKeyFingerprint が null の状態で接続が成功した直後に
     * UI 側で受け取り、ユーザー確認後に保存することを想定。
     */
    suspend fun capturedFingerprint(connectionId: String): String? = mutex.withLock {
        cache[connectionId]?.capturedFingerprint
    }

    private fun close(holder: Holder) {
        runCatching { holder.sftp.close() }
        runCatching { holder.client.disconnect() }
    }

    private data class Holder(
        /** プール時点の接続設定。再利用判定に使う。 */
        val spec: Connection.Sftp,
        val client: SSHClient,
        val sftp: SFTPClient,
        val capturedFingerprint: String?,
    ) {
        fun matches(current: Connection.Sftp): Boolean =
            spec.host == current.host &&
                spec.port == current.port &&
                spec.username == current.username &&
                spec.authMethod == current.authMethod &&
                spec.hostKeyFingerprint == current.hostKeyFingerprint
    }

    private class OneShotPasswordFinder(private val chars: CharArray) : PasswordFinder {
        override fun reqPassword(resource: Resource<*>?): CharArray = chars.copyOf()
        override fun shouldRetry(resource: Resource<*>?): Boolean = false
    }
}
