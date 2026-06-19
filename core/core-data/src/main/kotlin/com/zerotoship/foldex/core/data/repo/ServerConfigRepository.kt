// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.data.repo

import com.zerotoship.foldex.core.data.db.EncryptedCredentialDao
import com.zerotoship.foldex.core.data.db.EncryptedCredentialEntity
import com.zerotoship.foldex.core.data.db.ServerConfigDao
import com.zerotoship.foldex.core.data.db.toEntity
import com.zerotoship.foldex.core.data.db.toModel
import com.zerotoship.foldex.core.data.security.CredentialCipher
import com.zerotoship.foldex.core.model.ServerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自機サーバー設定の CRUD と、関連する暗号化シークレット (パスワードハッシュ /
 * 公開鍵リスト / ホスト鍵 / FTPS 証明書) の保存・読み出しを集約する。
 *
 * `ServerConfig` 自体は公開情報のみで、機密データは [EncryptedCredentialEntity]
 * に AES-GCM 暗号化して別管理する。
 */
@Singleton
class ServerConfigRepository @Inject constructor(
    private val configDao: ServerConfigDao,
    private val credentialDao: EncryptedCredentialDao,
    private val cipher: CredentialCipher,
) {

    fun observeAll(): Flow<List<ServerConfig>> =
        configDao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun findById(id: String): ServerConfig? =
        configDao.findById(id)?.toModel()

    suspend fun upsert(config: ServerConfig) {
        val now = System.currentTimeMillis()
        val existing = configDao.findById(config.id)
        val merged = config.copy(
            // 機密参照は UI 側のドメインオブジェクトには乗っていないため、上書き保存時は既存値を維持する
            // (これを忘れると、パスワード未変更で編集しただけでハッシュ参照が消える等の事故になる)。
            passwordHashRef = config.passwordHashRef ?: existing?.passwordHashRef,
            authorizedKeysRef = config.authorizedKeysRef ?: existing?.authorizedKeysRef,
            hostKeyRef = config.hostKeyRef ?: existing?.hostKeyRef,
            ftpsTlsCertRef = config.ftpsTlsCertRef ?: existing?.ftpsTlsCertRef,
            createdAt = existing?.createdAt ?: config.createdAt.takeIf { it > 0 } ?: now,
            updatedAt = now,
        )
        configDao.upsert(merged.toEntity())
    }

    suspend fun delete(id: String) {
        configDao.findById(id)?.let { entity ->
            entity.passwordHashRef?.let { credentialDao.deleteById(it) }
            entity.authorizedKeysRef?.let { credentialDao.deleteById(it) }
            entity.hostKeyRef?.let { credentialDao.deleteById(it) }
            entity.ftpsTlsCertRef?.let { credentialDao.deleteById(it) }
        }
        configDao.deleteById(id)
    }

    suspend fun touchLastStarted(id: String) {
        configDao.updateLastStartedAt(id, System.currentTimeMillis())
    }

    /** Argon2id ハッシュ値を保存し、新しい `passwordHashRef` を返す。 */
    suspend fun savePasswordHash(configId: String, hashBytes: ByteArray): String {
        val ref = writeCredential(configId, CRED_TYPE_PASSWORD_HASH, hashBytes)
        val current = configDao.findById(configId)
            ?: error("Server config not found: $configId")
        current.passwordHashRef?.let { credentialDao.deleteById(it) }
        configDao.upsert(current.copy(passwordHashRef = ref, updatedAt = System.currentTimeMillis()))
        return ref
    }

    /** authorized_keys 互換の公開鍵リスト (UTF-8 文字列の改行区切り) を保存。 */
    suspend fun saveAuthorizedKeys(configId: String, keysBytes: ByteArray): String {
        val ref = writeCredential(configId, CRED_TYPE_AUTHORIZED_KEYS, keysBytes)
        val current = configDao.findById(configId)
            ?: error("Server config not found: $configId")
        current.authorizedKeysRef?.let { credentialDao.deleteById(it) }
        configDao.upsert(current.copy(authorizedKeysRef = ref, updatedAt = System.currentTimeMillis()))
        return ref
    }

    /** Ed25519 サーバーホスト秘密鍵 (OpenSSH 形式の PEM バイト) を保存。 */
    suspend fun saveHostKey(configId: String, hostKeyBytes: ByteArray): String {
        val ref = writeCredential(configId, CRED_TYPE_HOST_KEY, hostKeyBytes)
        val current = configDao.findById(configId)
            ?: error("Server config not found: $configId")
        current.hostKeyRef?.let { credentialDao.deleteById(it) }
        configDao.upsert(current.copy(hostKeyRef = ref, updatedAt = System.currentTimeMillis()))
        return ref
    }

    /** FTPS 用の自己署名証明書 (PKCS12) を保存。 */
    suspend fun saveTlsCert(configId: String, pkcs12Bytes: ByteArray): String {
        val ref = writeCredential(configId, CRED_TYPE_TLS_CERT, pkcs12Bytes)
        val current = configDao.findById(configId)
            ?: error("Server config not found: $configId")
        current.ftpsTlsCertRef?.let { credentialDao.deleteById(it) }
        configDao.upsert(current.copy(ftpsTlsCertRef = ref, updatedAt = System.currentTimeMillis()))
        return ref
    }

    suspend fun loadCredentialBytes(ref: String?): ByteArray? {
        if (ref == null) return null
        val record = credentialDao.findById(ref) ?: return null
        return cipher.decrypt(
            CredentialCipher.EncryptedBytes(
                ciphertext = record.ciphertext,
                iv = record.iv,
                keyAlias = record.keyAlias,
            ),
        )
    }

    private suspend fun writeCredential(
        configId: String,
        type: String,
        bytes: ByteArray,
    ): String {
        val now = System.currentTimeMillis()
        val encrypted = cipher.encrypt(bytes)
        val ref = UUID.randomUUID().toString()
        credentialDao.upsert(
            EncryptedCredentialEntity(
                id = ref,
                credentialType = "${type}:${configId}",
                ciphertext = encrypted.ciphertext,
                iv = encrypted.iv,
                keyAlias = encrypted.keyAlias,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return ref
    }

    companion object {
        private const val CRED_TYPE_PASSWORD_HASH = "server_password_hash"
        private const val CRED_TYPE_AUTHORIZED_KEYS = "server_authorized_keys"
        private const val CRED_TYPE_HOST_KEY = "server_host_key"
        private const val CRED_TYPE_TLS_CERT = "server_tls_cert"
    }
}
