package com.zerotoship.foldex.server.ftp

import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.core.data.repo.ServerConfigRepository
import com.zerotoship.foldex.core.model.ServerConfig
import com.zerotoship.foldex.core.model.ServerLogEvent
import com.zerotoship.foldex.core.model.ServerType
import com.zerotoship.foldex.core.model.StorageError
import com.zerotoship.foldex.server.NetworkBindingResolver
import com.zerotoship.foldex.server.log.ServerLogger
import com.zerotoship.foldex.server.security.Argon2idHasher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import java.nio.file.Path
import java.nio.file.Paths
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 設定毎に Apache FtpServer の [FtpServer] を起動・停止するマネージャ。
 *
 * SFTP 側 (`SftpServerManager`) と同じ責務を FTP プロトコルで提供する。
 * 平文 FTP は仕様書 §9-I に従い ── 削除はしないが UI 側で警告を出す ── 実装は
 * 残す。FTPS (Explicit TLS) は P6 後半でこのクラスに自己署名 PKCS12 を渡す
 * 経路を追加する想定で、まずは平文での起動経路に集中する。
 */
@Singleton
class FtpServerManager @Inject constructor(
    private val repository: ServerConfigRepository,
    private val hasher: Argon2idHasher,
    private val networkResolver: NetworkBindingResolver,
    private val logger: ServerLogger,
) {
    private val mutex = Mutex()
    private val running: MutableMap<String, FtpServer> = mutableMapOf()
    private val _runningIds = MutableStateFlow<Set<String>>(emptySet())

    val runningIds: StateFlow<Set<String>> = _runningIds.asStateFlow()

    fun isRunning(configId: String): Boolean = configId in _runningIds.value

    suspend fun start(configId: String): Result<ServerConfig, StorageError> = mutex.withLock {
        if (running.containsKey(configId)) {
            val cfg = repository.findById(configId)
                ?: return@withLock Result.Failure(StorageError.IoError("Server config not found"))
            return@withLock Result.Success(cfg)
        }
        val config = repository.findById(configId)
            ?: return@withLock Result.Failure(StorageError.IoError("Server config not found"))
        if (config.type != ServerType.FTP) {
            return@withLock Result.Failure(
                StorageError.IoError("Not an FTP config: ${config.type}"),
            )
        }
        val rootPath = resolveLocalRoot(config.rootUri)
            ?: return@withLock Result.Failure(
                StorageError.IoError("rootUri must be a local path for now: ${config.rootUri}"),
            )
        val resolvedHost = networkResolver.resolve(config.bindAddress)
            ?: return@withLock Result.Failure(
                StorageError.IoError(
                    when (config.bindAddress) {
                        ServerConfig.BIND_WIFI_ONLY -> "Wi-Fi に接続されていないため起動できません"
                        else -> "bindAddress を解決できませんでした: ${config.bindAddress}"
                    },
                ),
            )
        val server = buildServer(config, rootPath, resolvedHost)
        try {
            server.start()
        } catch (t: Throwable) {
            runCatching { server.stop() }
            return@withLock Result.Failure(
                StorageError.IoError("Failed to start FTP server: ${t.message}", t),
            )
        }
        running[configId] = server
        _runningIds.update { it + configId }
        repository.touchLastStarted(configId)
        logger.record(
            configId = configId,
            event = ServerLogEvent.SERVER_STARTED,
            clientAddress = "$resolvedHost:${config.port}",
            details = "type=FTP,authMode=${config.authMode.name},ftps=${config.ftpsEnabled}",
        )
        Result.Success(config)
    }

    suspend fun stop(configId: String) = mutex.withLock {
        running.remove(configId)?.let { server ->
            runCatching { server.stop() }
            _runningIds.update { it - configId }
            logger.record(
                configId = configId,
                event = ServerLogEvent.SERVER_STOPPED,
                clientAddress = "self",
                details = "type=FTP",
            )
        }
    }

    suspend fun stopAll() = mutex.withLock {
        val ids = running.keys.toList()
        running.values.forEach { runCatching { it.stop() } }
        running.clear()
        _runningIds.value = emptySet()
        ids.forEach { configId ->
            logger.record(
                configId = configId,
                event = ServerLogEvent.SERVER_STOPPED,
                clientAddress = "self",
                details = "type=FTP,reason=stopAll",
            )
        }
    }

    private fun buildServer(config: ServerConfig, rootPath: Path, host: String): FtpServer {
        val serverFactory = FtpServerFactory()
        val listenerFactory = ListenerFactory().apply {
            port = config.port
            serverAddress = host
        }
        serverFactory.addListener("default", listenerFactory.createListener())
        serverFactory.userManager = FoldexFtpUserManager(
            configId = config.id,
            rootPath = rootPath.toString(),
            repository = repository,
            hasher = hasher,
            logger = logger,
        )
        return serverFactory.createServer()
    }

    private fun resolveLocalRoot(rootUri: String): Path? {
        val prefix = "local://"
        if (!rootUri.startsWith(prefix)) return null
        val absolutePath = rootUri.removePrefix(prefix)
        return runCatching { Paths.get(absolutePath) }.getOrNull()
    }
}
