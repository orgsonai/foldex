package com.zerotoship.foldex.core.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AndroidKeyStore に保管した AES-256 鍵で AES/GCM/NoPadding 暗号化を行う。
 * IV はレコード毎にランダム生成 (96bit)。鍵は端末から出ない。
 */
class CredentialCipher(
    private val defaultKeyAlias: String = DEFAULT_KEY_ALIAS,
) {
    fun encrypt(plaintext: ByteArray, keyAlias: String = defaultKeyAlias): EncryptedBytes {
        val key = getOrCreateKey(keyAlias)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv.copyOf()
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedBytes(ciphertext = ciphertext, iv = iv, keyAlias = keyAlias)
    }

    fun decrypt(payload: EncryptedBytes): ByteArray {
        val key = getKey(payload.keyAlias)
            ?: error("Key not found in AndroidKeyStore: ${payload.keyAlias}")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, payload.iv))
        return cipher.doFinal(payload.ciphertext)
    }

    private fun getKey(alias: String): SecretKey? {
        val keystore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return (keystore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun getOrCreateKey(alias: String): SecretKey {
        getKey(alias)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    data class EncryptedBytes(
        val ciphertext: ByteArray,
        val iv: ByteArray,
        val keyAlias: String,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EncryptedBytes) return false
            if (!ciphertext.contentEquals(other.ciphertext)) return false
            if (!iv.contentEquals(other.iv)) return false
            return keyAlias == other.keyAlias
        }

        override fun hashCode(): Int {
            var result = ciphertext.contentHashCode()
            result = 31 * result + iv.contentHashCode()
            result = 31 * result + keyAlias.hashCode()
            return result
        }
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "foldex_master_key_v1"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_TAG_BITS = 128
    }
}
