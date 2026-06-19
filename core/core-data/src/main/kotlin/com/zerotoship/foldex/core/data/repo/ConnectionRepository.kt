// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.data.repo

import com.zerotoship.foldex.core.data.db.ConnectionDao
import com.zerotoship.foldex.core.data.db.EncryptedCredentialDao
import com.zerotoship.foldex.core.data.db.EncryptedCredentialEntity
import com.zerotoship.foldex.core.data.db.toEntity
import com.zerotoship.foldex.core.data.db.toModel
import com.zerotoship.foldex.core.data.security.CredentialCipher
import com.zerotoship.foldex.core.model.Connection
import com.zerotoship.foldex.core.model.Credential
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionRepository @Inject constructor(
    private val connectionDao: ConnectionDao,
    private val credentialDao: EncryptedCredentialDao,
    private val cipher: CredentialCipher,
) {

    fun observeAll(): Flow<List<Connection>> =
        connectionDao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun findById(id: String): Connection? =
        connectionDao.findById(id)?.toModel()

    /**
     * 接続を保存。[credential] が non-null なら新しい暗号化レコードを作って差し替える。
     * null なら既存の credentialRef を維持する (編集時にパスワードを変更しないケース)。
     */
    suspend fun save(connection: Connection, credential: Credential?): Connection {
        val now = System.currentTimeMillis()
        val existing = connectionDao.findById(connection.id)
        val credentialRef = if (credential != null) {
            existing?.credentialRef?.let { credentialDao.deleteById(it) }
            writeCredential(credential, now)
        } else {
            existing?.credentialRef
        }
        val entity = connection.toEntity(
            credentialRef = credentialRef,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            lastConnectedAt = existing?.lastConnectedAt,
            sortOrder = existing?.sortOrder ?: 0,
        )
        connectionDao.upsert(entity)
        return connection
    }

    suspend fun delete(id: String) {
        connectionDao.findById(id)?.credentialRef?.let { credentialDao.deleteById(it) }
        connectionDao.deleteById(id)
    }

    suspend fun loadCredential(connectionId: String): Credential? {
        val entity = connectionDao.findById(connectionId) ?: return null
        val ref = entity.credentialRef ?: return Credential.Anonymous
        val record = credentialDao.findById(ref) ?: return null
        val plaintext = cipher.decrypt(
            CredentialCipher.EncryptedBytes(
                ciphertext = record.ciphertext,
                iv = record.iv,
                keyAlias = record.keyAlias,
            ),
        )
        return when (record.credentialType) {
            CRED_TYPE_PASSWORD -> Credential.Password(plaintext)
            CRED_TYPE_SSH_KEY -> Credential.SshPrivateKey(plaintext, passphrase = null)
            else -> error("Unknown credential type: ${record.credentialType}")
        }
    }

    suspend fun touchLastConnected(connectionId: String) {
        connectionDao.updateLastConnectedAt(connectionId, System.currentTimeMillis())
    }

    /** ドラッグ並び替えの確定保存。[orderedIds] の並び順を sortOrder として書き込む。 */
    suspend fun reorder(orderedIds: List<String>) {
        orderedIds.forEachIndexed { index, id ->
            connectionDao.updateSortOrder(id, index)
        }
    }

    private suspend fun writeCredential(credential: Credential, now: Long): String? {
        return when (credential) {
            is Credential.Anonymous -> null
            is Credential.Password -> {
                val encrypted = cipher.encrypt(credential.secret)
                val ref = UUID.randomUUID().toString()
                credentialDao.upsert(
                    EncryptedCredentialEntity(
                        id = ref,
                        credentialType = CRED_TYPE_PASSWORD,
                        ciphertext = encrypted.ciphertext,
                        iv = encrypted.iv,
                        keyAlias = encrypted.keyAlias,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                ref
            }
            is Credential.SshPrivateKey -> {
                val encrypted = cipher.encrypt(credential.keyData)
                val ref = UUID.randomUUID().toString()
                credentialDao.upsert(
                    EncryptedCredentialEntity(
                        id = ref,
                        credentialType = CRED_TYPE_SSH_KEY,
                        ciphertext = encrypted.ciphertext,
                        iv = encrypted.iv,
                        keyAlias = encrypted.keyAlias,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                ref
            }
        }
    }

    companion object {
        private const val CRED_TYPE_PASSWORD = "password"
        private const val CRED_TYPE_SSH_KEY = "ssh_private_key"
    }
}
