// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.server.sftp

import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.core.data.repo.ServerConfigRepository
import com.zerotoship.foldex.core.model.ServerAuthMode
import com.zerotoship.foldex.core.model.ServerConfig
import com.zerotoship.foldex.core.model.ServerLogEvent
import com.zerotoship.foldex.core.model.ServerType
import com.zerotoship.foldex.core.model.StorageError
import com.zerotoship.foldex.server.NetworkBindingResolver
import com.zerotoship.foldex.server.log.ServerLogger
import com.zerotoship.foldex.server.security.Argon2idHasher
import com.zerotoship.foldex.server.security.HostKeyManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.signature.BuiltinSignatures
import org.apache.sshd.server.ServerBuilder
import org.apache.sshd.server.SshServer
import org.apache.sshd.sftp.server.SftpSubsystemFactory
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
    private val networkResolver: NetworkBindingResolver,
    private val logger: ServerLogger,
    private val appLogger: com.zerotoship.foldex.core.data.log.AppLogger,
) {
    private val mutex = Mutex()
    private val running: MutableMap<String, SshServer> = mutableMapOf()
    private val _runningIds = MutableStateFlow<Set<String>>(emptySet())

    /** 起動中の設定 ID を UI から observe するための StateFlow。 */
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
                StorageError.IoError("SFTP サーバーの起動中に予期しないエラー: ${t.message}", t),
            )
        }
        if (result is Result.Failure) {
            val msg = "SFTP サーバーを起動できませんでした: ${result.error.message}"
            _startErrors.tryEmit(msg)
            // snackbar は表示が短い・画面遷移で消えるので、ログとしても残す
            // (サーバー画面 → ログから後追いできる)。
            runCatching {
                logger.record(
                    configId = configId,
                    event = ServerLogEvent.SERVER_START_FAILED,
                    clientAddress = "self",
                    details = "type=SFTP,reason=${result.error.message}",
                )
            }
            appLogger.error("Server/SFTP", msg)
        }
        result
    }

    // mutex.withLock の内側で実行する起動本体。失敗は Result.Failure で返し、
    // 例外も呼び出し側 (start) で握りつぶして Result 化する (Service のクラッシュを防ぐため)。
    private suspend fun startLocked(configId: String): Result<ServerConfig, StorageError> {
        if (running.containsKey(configId)) {
            val cfg = repository.findById(configId)
                ?: return Result.Failure(StorageError.IoError("Server config not found"))
            return Result.Success(cfg)
        }
        val config = repository.findById(configId)
            ?: return Result.Failure(StorageError.IoError("Server config not found"))
        if (config.type != ServerType.SFTP) {
            return Result.Failure(
                StorageError.IoError("Not an SFTP config: ${config.type}"),
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
        val server = buildServer(config, rootPath, resolvedHost)
        try {
            server.start()
        } catch (t: Throwable) {
            runCatching { server.stop(true) }
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
            details = "type=SFTP,authMode=${config.authMode.name}",
        )
        return Result.Success(config)
    }

    suspend fun stop(configId: String) {
        // 「一覧から外して状態を落とす」までを mutex の内側、実際の close は外側で行う。
        // close はクライアント接続が残っていると待たされることがあり、ロックを握ったまま
        // 待つと以後の起動/停止が全部そこで詰まる (= 何度押しても止まらない) ため。
        val server = mutex.withLock { running.remove(configId) } ?: return
        _runningIds.update { it - configId }
        val closed = closeServer(server)
        logger.record(
            configId = configId,
            event = ServerLogEvent.SERVER_STOPPED,
            clientAddress = "self",
            details = if (closed) "type=SFTP" else "type=SFTP,warn=close_timeout",
        )
    }

    suspend fun stopAll() {
        val servers = mutex.withLock {
            val snapshot = running.toMap()
            running.clear()
            snapshot
        }
        if (servers.isEmpty()) return
        _runningIds.value = emptySet()
        servers.forEach { (configId, server) ->
            val closed = closeServer(server)
            logger.record(
                configId = configId,
                event = ServerLogEvent.SERVER_STOPPED,
                clientAddress = "self",
                details = if (closed) "type=SFTP,reason=stopAll" else "type=SFTP,reason=stopAll,warn=close_timeout",
            )
        }
    }

    /**
     * サーバーの待ち受けを閉じる。完了したら true、[CLOSE_TIMEOUT_MS] 以内に
     * 閉じきらなければ false (呼び出し元は待たずに先へ進む)。
     *
     * `SshServer.stop(true)` は内部で close の完了を待つ (既定 10 秒、条件次第では
     * さらに長い) ため、ここでは待たない `close(true)` を使い、完了確認だけ短い
     * タイムアウトで行う。呼び出し元 (ForegroundService) を絶対に固めないことを優先し、
     * 閉じきれなかった場合もサーバーは「停止扱い」にする。残った接続は
     * サービス終了後に OS 側で回収される。
     */
    private suspend fun closeServer(server: SshServer): Boolean =
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { server.close(true).await(CLOSE_TIMEOUT_MS) }
                .onFailure { t ->
                    appLogger.warn("Server/SFTP", "サーバーの停止処理で例外: ${t.message}", t)
                }
                .getOrDefault(false)
        }

    private fun buildServer(config: ServerConfig, rootPath: Path, host: String): SshServer {
        val server = SshServer.setUpDefaultServer()
        server.port = config.port
        server.host = host

        // Android では setUpDefaultServer の defaults が空になることがある (BC が未登録で
        // DH 系が unsupported になるなど)。FoldexApplication で BC を登録した上で、
        // 念のため supported なものだけで再構築する。ignoreUnsupported=true で空エントリを除外。
        if (server.keyExchangeFactories.isNullOrEmpty()) {
            server.keyExchangeFactories = ServerBuilder.setUpDefaultKeyExchanges(true)
        }
        if (server.signatureFactories.isNullOrEmpty()) {
            server.signatureFactories = ServerBuilder.setUpDefaultSignatureFactories(true)
        }
        // ホスト鍵は RSA 3072 を使うため、RSA 系の署名アルゴリズムが必ず含まれることを保証する。
        // defaults が何らかの理由で RSA を落とすケース (環境依存の SecurityUtils 判定など) の保険。
        run {
            val sigs = (server.signatureFactories ?: emptyList()).toMutableList()
            val have = sigs.map { it.name }.toMutableSet()
            listOf(BuiltinSignatures.rsaSHA512, BuiltinSignatures.rsaSHA256, BuiltinSignatures.rsa)
                .forEach { f ->
                    if (f.isSupported && f.name !in have) {
                        sigs.add(0, f); have.add(f.name)
                    }
                }
            server.signatureFactories = sigs
        }
        if (server.cipherFactories.isNullOrEmpty()) {
            server.cipherFactories = ServerBuilder.setUpDefaultCiphers(true)
        }
        if (server.macFactories.isNullOrEmpty()) {
            server.macFactories = ServerBuilder.setUpDefaultMacs(true)
        }

        server.keyPairProvider = SftpKeyPairProvider(config.id, hostKeyManager)
        server.subsystemFactories = listOf(SftpSubsystemFactory())
        server.fileSystemFactory = VirtualFileSystemFactory(rootPath)

        val authMode = config.authMode
        if (authMode != ServerAuthMode.PUBLIC_KEY) {
            server.passwordAuthenticator =
                SftpPasswordAuthenticator(config.id, repository, hasher, logger)
        }
        if (authMode != ServerAuthMode.PASSWORD) {
            server.publickeyAuthenticator =
                SftpPublickeyAuthenticator(config.id, repository, logger)
        }
        return server
    }

    private fun resolveLocalRoot(rootUri: String): Path? {
        val prefix = "local://"
        if (!rootUri.startsWith(prefix)) return null
        val absolutePath = rootUri.removePrefix(prefix)
        return runCatching { Paths.get(absolutePath) }.getOrNull()
    }

    private companion object {
        /** 停止時に close の完了を待つ上限 (ミリ秒)。超えたら待たずに停止扱いにする。 */
        const val CLOSE_TIMEOUT_MS = 5_000L
    }
}
