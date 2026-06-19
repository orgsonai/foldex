// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.ftpserver.DataConnectionConfigurationFactory
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.ssl.SslConfiguration
import org.apache.ftpserver.ssl.SslConfigurationFactory
import java.nio.file.Path
import java.nio.file.Paths
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 設定毎に Apache FtpServer の [FtpServer] を起動・停止するマネージャ。
 *
 * SFTP 側 (`SftpServerManager`) と同じ責務を FTP プロトコルで提供する。
 * 平文 FTP は仕様書 §9-I に従い ── 削除はしないが UI 側で警告を出す ── 実装は残す。
 * `config.ftpsEnabled` のときは [FtpsCertManager] から自己署名 PKCS12 を取得し、
 * Explicit FTPS (AUTH TLS、制御・データチャネルとも) を有効にする。
 */
@Singleton
class FtpServerManager @Inject constructor(
    private val repository: ServerConfigRepository,
    private val hasher: Argon2idHasher,
    private val networkResolver: NetworkBindingResolver,
    private val logger: ServerLogger,
    private val ftpsCertManager: FtpsCertManager,
    private val appLogger: com.zerotoship.foldex.core.data.log.AppLogger,
) {
    private val mutex = Mutex()
    private val running: MutableMap<String, FtpServer> = mutableMapOf()
    private val _runningIds = MutableStateFlow<Set<String>>(emptySet())

    val runningIds: StateFlow<Set<String>> = _runningIds.asStateFlow()

    private val _startErrors = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 起動に失敗したときのエラーメッセージ (UI で snackbar 表示する用)。 */
    val startErrors: SharedFlow<String> = _startErrors.asSharedFlow()

    fun isRunning(configId: String): Boolean = configId in _runningIds.value

    suspend fun start(configId: String): Result<ServerConfig, StorageError> = mutex.withLock {
        val result: Result<ServerConfig, StorageError> = try {
            startLocked(configId)
        } catch (t: Throwable) {
            Result.Failure(
                StorageError.IoError("FTP サーバーの起動中に予期しないエラー: ${t.message}", t),
            )
        }
        if (result is Result.Failure) {
            val msg = "FTP サーバーを起動できませんでした: ${result.error.message}"
            _startErrors.tryEmit(msg)
            runCatching {
                logger.record(
                    configId = configId,
                    event = ServerLogEvent.SERVER_START_FAILED,
                    clientAddress = "self",
                    details = "type=FTP,reason=${result.error.message}",
                )
            }
            appLogger.error("Server/FTP", msg)
        }
        result
    }

    private suspend fun startLocked(configId: String): Result<ServerConfig, StorageError> {
        if (running.containsKey(configId)) {
            val cfg = repository.findById(configId)
                ?: return Result.Failure(StorageError.IoError("Server config not found"))
            return Result.Success(cfg)
        }
        val config = repository.findById(configId)
            ?: return Result.Failure(StorageError.IoError("Server config not found"))
        if (config.type != ServerType.FTP) {
            return Result.Failure(
                StorageError.IoError("Not an FTP config: ${config.type}"),
            )
        }
        val rootPath = resolveLocalRoot(config.rootUri)
            ?: return Result.Failure(
                StorageError.IoError("ルートパスはローカルパスである必要があります: ${config.rootUri}"),
            )
        val resolvedHost = networkResolver.resolve(config.bindAddress)
            ?: return Result.Failure(
                StorageError.IoError(
                    when (config.bindAddress) {
                        ServerConfig.BIND_WIFI_ONLY -> "Wi-Fi に接続されていないため起動できません"
                        else -> "バインドアドレスを解決できませんでした: ${config.bindAddress}"
                    },
                ),
            )
        val sslConfig = if (config.ftpsEnabled) {
            try {
                buildSslConfiguration(ftpsCertManager.loadOrGenerate(config.id))
            } catch (t: Throwable) {
                return Result.Failure(
                    StorageError.IoError("FTPS 証明書の準備に失敗しました: ${t.message}", t),
                )
            }
        } else {
            null
        }
        val server = buildServer(config, rootPath, resolvedHost, sslConfig)
        try {
            server.start()
        } catch (t: Throwable) {
            runCatching { server.stop() }
            return Result.Failure(
                StorageError.IoError("ポート ${config.port} で待ち受けを開始できませんでした: ${t.message}", t),
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
        return Result.Success(config)
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

    private fun buildServer(
        config: ServerConfig,
        rootPath: Path,
        host: String,
        sslConfig: SslConfiguration?,
    ): FtpServer {
        val serverFactory = FtpServerFactory()
        val listenerFactory = ListenerFactory().apply {
            port = config.port
            serverAddress = host
            // PASV で 227 が「サーバーの正しい LAN IP」を返さないと、
            // クライアントは戻ってきたアドレスに接続できずアップロードが
            // 「接続失敗」相当で落ちる。listener と同じ host を明示しておく。
            val dataConf = DataConnectionConfigurationFactory().apply {
                passiveAddress = host
                passiveExternalAddress = host
                // PASV のポート範囲を固定 (デフォルトは ephemeral = 0 で OS 任せ)。
                // Android 11+ では一部 OS バージョンで ephemeral ポートの listen 開始が
                // 遅延する事例があり、データ転送開始 (STOR) で client がタイムアウトする
                // ことがある。固定範囲にしておくとデバッグもしやすい。
                passivePorts = "30000-30100"
                if (sslConfig != null) {
                    setImplicitSsl(false)
                    setSslConfiguration(sslConfig)
                }
            }
            setDataConnectionConfiguration(dataConf.createDataConnectionConfiguration())
            if (sslConfig != null) {
                setSslConfiguration(sslConfig)
                // Explicit FTPS: 平文で接続して AUTH TLS でアップグレードする。
                setImplicitSsl(false)
            }
        }
        serverFactory.addListener("default", listenerFactory.createListener())
        serverFactory.userManager = FoldexFtpUserManager(
            configId = config.id,
            rootPath = rootPath.toString(),
            repository = repository,
            hasher = hasher,
            logger = logger,
        )
        // NIO ([java.nio.file.Files]) ベースの独自 FileSystemFactory に差し替える。
        // 動機: 実機で FTP の書き込みが通らない件 (java.io.File + RandomAccessFile が
        // Android scoped storage で不安定だった可能性) と、SFTP (NIO) は動くという
        // 切り分けから、FTP も NIO に揃える。詳細は NioFileSystemFactory のコメント。
        serverFactory.fileSystem = NioFileSystemFactory(rootPath)
        // 書き込み系コマンドの 5xx を Foldex のサーバーログに流す診断 Ftplet。
        serverFactory.ftplets = mapOf(
            "foldex-diag" to FoldexFtpDiagnosticFtplet(config.id, logger),
        )
        return serverFactory.createServer()
    }

    private fun buildSslConfiguration(keystore: FtpsCertManager.Keystore): SslConfiguration =
        SslConfigurationFactory().apply {
            setKeystoreFile(keystore.file)
            setKeystorePassword(keystore.password)
            setKeyPassword(keystore.password)
            setKeystoreType(keystore.type)
            setKeyAlias(keystore.keyAlias)
            setSslProtocol("TLS")
        }.createSslConfiguration()

    private fun resolveLocalRoot(rootUri: String): Path? {
        val prefix = "local://"
        if (!rootUri.startsWith(prefix)) return null
        val absolutePath = rootUri.removePrefix(prefix)
        return runCatching { Paths.get(absolutePath) }.getOrNull()
    }
}
