package com.zerotoship.foldex.server.sftp

import com.zerotoship.foldex.core.data.repo.ServerConfigRepository
import com.zerotoship.foldex.core.model.ServerAuthMode
import com.zerotoship.foldex.server.security.Argon2idHasher
import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.session.ServerSession
import java.security.PublicKey

/**
 * 設定 [configId] の認証ポリシーに従ってパスワード/公開鍵を検証する。
 *
 * MINA SSHD の Authenticator は synchronous なので Repository アクセス時のみ
 * runBlocking する。ログ書き込みは log/ServerLogger に集約する想定 (本コミット
 * では未配線、後続コミットで足す)。
 */
internal class SftpPasswordAuthenticator(
    private val configId: String,
    private val repository: ServerConfigRepository,
    private val hasher: Argon2idHasher,
) : PasswordAuthenticator {

    override fun authenticate(username: String, password: String, session: ServerSession): Boolean {
        return runBlocking {
            val config = repository.findById(configId) ?: return@runBlocking false
            when (config.authMode) {
                ServerAuthMode.ANONYMOUS -> true
                ServerAuthMode.PASSWORD, ServerAuthMode.PASSWORD_OR_PUBLIC_KEY -> {
                    if (config.username != username) return@runBlocking false
                    val hashBytes = repository.loadCredentialBytes(config.passwordHashRef)
                        ?: return@runBlocking false
                    val encoded = String(hashBytes, Charsets.UTF_8)
                    hasher.verify(password.toByteArray(Charsets.UTF_8), encoded)
                }
                ServerAuthMode.PUBLIC_KEY -> false
            }
        }
    }
}

/**
 * authorized_keys 互換形式の公開鍵リストと突き合わせる。本コミットでは
 * loadAuthorizedKeys() のロード部分のみ用意し、解析は後続コミットで詰める。
 */
internal class SftpPublickeyAuthenticator(
    private val configId: String,
    private val repository: ServerConfigRepository,
) : PublickeyAuthenticator {

    override fun authenticate(username: String, key: PublicKey, session: ServerSession): Boolean {
        return runBlocking {
            val config = repository.findById(configId) ?: return@runBlocking false
            when (config.authMode) {
                ServerAuthMode.PUBLIC_KEY, ServerAuthMode.PASSWORD_OR_PUBLIC_KEY -> {
                    if (config.username != username) return@runBlocking false
                    val keysBytes = repository.loadCredentialBytes(config.authorizedKeysRef)
                        ?: return@runBlocking false
                    val authorizedKeys = parseAuthorizedKeys(String(keysBytes, Charsets.UTF_8))
                    authorizedKeys.any { it.publicKeyEquals(key) }
                }
                else -> false
            }
        }
    }

    private fun parseAuthorizedKeys(text: String): List<AuthorizedKey> {
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split(' ').filter { it.isNotEmpty() }
                if (parts.size < 2) return@mapNotNull null
                val type = parts[0]
                val base64 = parts[1]
                runCatching {
                    val raw = java.util.Base64.getDecoder().decode(base64)
                    AuthorizedKey(type = type, rawBlob = raw)
                }.getOrNull()
            }
            .toList()
    }

    private data class AuthorizedKey(val type: String, val rawBlob: ByteArray) {
        fun publicKeyEquals(key: PublicKey): Boolean {
            // SSHD 側で同じ raw blob にエンコードして比較する経路は本コミットでは未実装。
            // 後続で OpenSshPublicKeyDecoder 経由の比較に置き換える。
            return false
        }
    }
}
