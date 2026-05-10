package com.zerotoship.foldex.server.sftp

import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.core.data.repo.ServerConfigRepository
import com.zerotoship.foldex.core.model.ServerAuthMode
import com.zerotoship.foldex.core.model.ServerConfig
import com.zerotoship.foldex.core.model.ServerType
import com.zerotoship.foldex.core.model.StorageError
import com.zerotoship.foldex.server.security.Argon2idHasher
import com.zerotoship.foldex.server.security.HostKeyManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.net.InetAddress
import java.nio.file.Path
import java.nio.file.Paths
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 設定毎に Apache MINA SSHD の [SshServer] を起動・停止するマネージャ。
 *
 * 本コミットでは「ローカルファイルシステム上のパス」をルートとした SFTP
 * サーバーをサポートする (SAF / リモート URI を rootUri に持つ場合は
 * UnsupportedRoot エラーを返す)。StorageProvider との橋渡しは後続コミットで
 * SftpFileSystem として組み込む。
 */
@Singleton
class SftpServerManager @Inject constructor(
    private val repository: ServerConfigRepository,
    private val hostKeyManager: HostKeyManager,
    private val hasher: Argon2idHasher,
) {
    private val mutex = Mutex()
    private val running: MutableMap<String, SshServer> = mutableMapOf()

    /** 起動中の設定 ID 集合 (UI 表示用)。 */
    fun runningIds(): Set<String> = synchronized(running) { running.keys.toSet() }

    fun isRunning(configId: String): Boolean =
        synchronized(running) { running.containsKey(configId) }

    suspend fun start(configId: String): Result<ServerConfig, StorageError> = mutex.withLock {
        if (running.containsKey(configId)) {
            val cfg = repository.findById(configId)
                ?: return@withLock Result.Failure(StorageError.IoError("Server config not found"))
            return@withLock Result.Success(cfg)
        }
        val config = repository.findById(configId)
            ?: return@withLock Result.Failure(StorageError.IoError("Server config not found"))
        if (config.type != ServerType.SFTP) {
            return@withLock Result.Failure(
                StorageError.IoError("Not an SFTP config: ${config.type}"),
            )
        }
        val rootPath = resolveLocalRoot(config.rootUri)
            ?: return@withLock Result.Failure(
                StorageError.IoError("rootUri must be a local path for now: ${config.rootUri}"),
            )
        val server = buildServer(config, rootPath)
        try {
            server.start()
        } catch (t: Throwable) {
            runCatching { server.stop(true) }
            return@withLock Result.Failure(
                StorageError.IoError("Failed to start SFTP server: ${t.message}", t),
            )
        }
        running[configId] = server
        repository.touchLastStarted(configId)
        Result.Success(config)
    }

    suspend fun stop(configId: String) = mutex.withLock {
        running.remove(configId)?.let { server ->
            runCatching { server.stop(true) }
        }
    }

    suspend fun stopAll() = mutex.withLock {
        running.values.forEach { runCatching { it.stop(true) } }
        running.clear()
    }

    private fun buildServer(config: ServerConfig, rootPath: Path): SshServer {
        val server = SshServer.setUpDefaultServer()
        server.port = config.port
        server.host = resolveHost(config.bindAddress)

        server.keyPairProvider = SftpKeyPairProvider(config.id, hostKeyManager)
        server.subsystemFactories = listOf(SftpSubsystemFactory())
        server.fileSystemFactory = VirtualFileSystemFactory(rootPath)

        val authMode = config.authMode
        if (authMode != ServerAuthMode.PUBLIC_KEY) {
            server.passwordAuthenticator =
                SftpPasswordAuthenticator(config.id, repository, hasher)
        }
        if (authMode != ServerAuthMode.PASSWORD) {
            server.publickeyAuthenticator =
                SftpPublickeyAuthenticator(config.id, repository)
        }
        return server
    }

    /**
     * `bindAddress` の値からバインド先 IP を決める。
     * - "wifi_only" — 後続コミットで Wi-Fi IP を解決する。本コミットでは loopback 扱い。
     * - "0.0.0.0" — 全インターフェース。
     * - 特定 IP — そのまま。
     */
    private fun resolveHost(bindAddress: String): String = when (bindAddress) {
        ServerConfig.BIND_WIFI_ONLY -> "127.0.0.1"
        ServerConfig.BIND_ALL_INTERFACES -> "0.0.0.0"
        else -> runCatching { InetAddress.getByName(bindAddress).hostAddress }
            .getOrDefault("0.0.0.0") ?: "0.0.0.0"
    }

    private fun resolveLocalRoot(rootUri: String): Path? {
        val prefix = "local://"
        if (!rootUri.startsWith(prefix)) return null
        val absolutePath = rootUri.removePrefix(prefix)
        return runCatching { Paths.get(absolutePath) }.getOrNull()
    }
}
