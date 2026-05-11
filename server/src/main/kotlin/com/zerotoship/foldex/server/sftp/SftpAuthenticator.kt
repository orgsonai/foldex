package com.zerotoship.foldex.server.sftp

import com.zerotoship.foldex.core.data.repo.ServerConfigRepository
import com.zerotoship.foldex.core.model.ServerAuthMode
import com.zerotoship.foldex.core.model.ServerLogEvent
import com.zerotoship.foldex.server.log.ServerLogger
import com.zerotoship.foldex.server.security.Argon2idHasher
import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.session.ServerSession
import java.io.StringReader
import java.security.PublicKey

/**
 * 設定 [configId] の認証ポリシーに従ってパスワード認証を検証し、
 * 成功/失敗を [ServerLogger] に記録する。
 *
 * MINA SSHD の Authenticator は synchronous なので、Repository / Logger
 * の suspend 呼び出しは [runBlocking] でブリッジする。SQLite 書き込みは
 * 端末ローカルでミリ秒単位なので、認証応答ラグへの影響は小さい。
 */
internal class SftpPasswordAuthenticator(
    private val configId: String,
    private val repository: ServerConfigRepository,
    private val hasher: Argon2idHasher,
    private val logger: ServerLogger,
) : PasswordAuthenticator {

    override fun authenticate(username: String, password: String, session: ServerSession): Boolean {
        val clientAddress = session.clientAddressString()
        return runBlocking {
            val config = repository.findById(configId)
            if (config == null) {
                logger.record(
                    configId = configId,
                    event = ServerLogEvent.AUTH_FAILED,
                    clientAddress = clientAddress,
                    username = username,
                    details = "config_not_found",
                )
                return@runBlocking false
            }
            val authorized = when (config.authMode) {
                ServerAuthMode.ANONYMOUS -> true
                ServerAuthMode.PASSWORD, ServerAuthMode.PASSWORD_OR_PUBLIC_KEY -> {
                    if (config.username != username) {
                        false
                    } else {
                        val hashBytes = repository.loadCredentialBytes(config.passwordHashRef)
                        if (hashBytes == null) {
                            false
                        } else {
                            hasher.verify(
                                password.toByteArray(Charsets.UTF_8),
                                String(hashBytes, Charsets.UTF_8),
                            )
                        }
                    }
                }
                ServerAuthMode.PUBLIC_KEY -> false
            }
            logger.record(
                configId = configId,
                event = if (authorized) ServerLogEvent.AUTH_SUCCESS else ServerLogEvent.AUTH_FAILED,
                clientAddress = clientAddress,
                username = username,
                details = "method=password,mode=${config.authMode.name}",
            )
            authorized
        }
    }
}

/**
 * authorized_keys 互換形式の公開鍵リストとクライアント提示鍵を突き合わせる。
 *
 * パースは MINA SSHD 標準の [AuthorizedKeyEntry] に委譲し、公開鍵比較は
 * [KeyUtils.compareKeys] で型に依存しない正しい比較を行う。
 */
internal class SftpPublickeyAuthenticator(
    private val configId: String,
    private val repository: ServerConfigRepository,
    private val logger: ServerLogger,
) : PublickeyAuthenticator {

    override fun authenticate(username: String, key: PublicKey, session: ServerSession): Boolean {
        val clientAddress = session.clientAddressString()
        return runBlocking {
            val config = repository.findById(configId)
            if (config == null) {
                logger.record(
                    configId = configId,
                    event = ServerLogEvent.AUTH_FAILED,
                    clientAddress = clientAddress,
                    username = username,
                    details = "config_not_found",
                )
                return@runBlocking false
            }
            val authorized = when (config.authMode) {
                ServerAuthMode.PUBLIC_KEY, ServerAuthMode.PASSWORD_OR_PUBLIC_KEY -> {
                    if (config.username != username) {
                        false
                    } else {
                        val keysBytes = repository.loadCredentialBytes(config.authorizedKeysRef)
                        if (keysBytes == null) {
                            false
                        } else {
                            val authorizedKeys = parseAuthorizedKeys(String(keysBytes, Charsets.UTF_8))
                            authorizedKeys.any { KeyUtils.compareKeys(it, key) }
                        }
                    }
                }
                else -> false
            }
            logger.record(
                configId = configId,
                event = if (authorized) ServerLogEvent.AUTH_SUCCESS else ServerLogEvent.AUTH_FAILED,
                clientAddress = clientAddress,
                username = username,
                details = "method=publickey,mode=${config.authMode.name}",
            )
            authorized
        }
    }

    private fun parseAuthorizedKeys(text: String): List<PublicKey> {
        val entries: List<AuthorizedKeyEntry> = runCatching {
            AuthorizedKeyEntry.readAuthorizedKeys(StringReader(text), true)
        }.getOrElse { return emptyList() }
        return entries.mapNotNull { entry ->
            runCatching {
                entry.resolvePublicKey(null, emptyMap(), PublicKeyEntryResolver.IGNORING)
            }.getOrNull()
        }
    }
}

private fun ServerSession.clientAddressString(): String =
    runCatching { clientAddress?.toString() ?: "unknown" }.getOrDefault("unknown")
