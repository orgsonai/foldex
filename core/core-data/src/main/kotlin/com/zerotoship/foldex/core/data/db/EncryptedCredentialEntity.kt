package com.zerotoship.foldex.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "encrypted_credentials")
data class EncryptedCredentialEntity(
    @PrimaryKey val id: String,
    val credentialType: String,
    val ciphertext: ByteArray,
    val iv: ByteArray,
    val keyAlias: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedCredentialEntity) return false
        if (id != other.id) return false
        if (credentialType != other.credentialType) return false
        if (!ciphertext.contentEquals(other.ciphertext)) return false
        if (!iv.contentEquals(other.iv)) return false
        if (keyAlias != other.keyAlias) return false
        if (createdAt != other.createdAt) return false
        if (updatedAt != other.updatedAt) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + credentialType.hashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + keyAlias.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}
