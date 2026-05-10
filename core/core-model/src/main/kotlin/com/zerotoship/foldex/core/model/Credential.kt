package com.zerotoship.foldex.core.model

/**
 * 接続認証情報。機密データは [String] ではなく [ByteArray] で持つ。
 * JVM の文字列内部キャッシュやヒープダンプにそのまま乗らないようにするため。
 */
sealed class Credential {
    data class Password(val secret: ByteArray) : Credential() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Password) return false
            return secret.contentEquals(other.secret)
        }

        override fun hashCode(): Int = secret.contentHashCode()
    }

    data class SshPrivateKey(
        val keyData: ByteArray,
        val passphrase: ByteArray?,
    ) : Credential() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SshPrivateKey) return false
            if (!keyData.contentEquals(other.keyData)) return false
            return when {
                passphrase == null && other.passphrase == null -> true
                passphrase == null || other.passphrase == null -> false
                else -> passphrase.contentEquals(other.passphrase)
            }
        }

        override fun hashCode(): Int {
            var result = keyData.contentHashCode()
            result = 31 * result + (passphrase?.contentHashCode() ?: 0)
            return result
        }
    }

    object Anonymous : Credential()
}
