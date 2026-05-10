package com.zerotoship.foldex.server.security

import com.zerotoship.foldex.core.data.repo.ServerConfigRepository
import com.zerotoship.foldex.server.internal.BouncyCastleSetup
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.digest.BuiltinDigests
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SFTP サーバー用 Ed25519 ホスト鍵の生成・永続化・フィンガープリント計算。
 *
 * 永続化フォーマットは `[privateLen:int][privateKey PKCS8 DER][publicLen:int]
 * [publicKey X.509 SPKI DER]` の単純なバイナリ。BouncyCastle は Android で
 * Ed25519 を扱うために必要 (minSdk 26 では JCA に Ed25519 が無いため)。
 */
@Singleton
class HostKeyManager @Inject constructor(
    private val repository: ServerConfigRepository,
) {

    /** 新しい Ed25519 鍵ペアを生成。 */
    fun generate(): KeyPair {
        BouncyCastleSetup.ensureInstalled()
        val gen = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
        return gen.generateKeyPair()
    }

    /** 鍵ペアを単一バイト列にシリアライズ (永続化用)。 */
    fun serialize(keyPair: KeyPair): ByteArray {
        val priv = keyPair.private.encoded
        val pub = keyPair.public.encoded
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeInt(priv.size)
            d.write(priv)
            d.writeInt(pub.size)
            d.write(pub)
        }
        return out.toByteArray()
    }

    /** [serialize] の逆操作。 */
    fun deserialize(bytes: ByteArray): KeyPair {
        BouncyCastleSetup.ensureInstalled()
        DataInputStream(ByteArrayInputStream(bytes)).use { d ->
            val privLen = d.readInt()
            val priv = ByteArray(privLen).also { d.readFully(it) }
            val pubLen = d.readInt()
            val pub = ByteArray(pubLen).also { d.readFully(it) }
            val kf = KeyFactory.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
            val privateKey = kf.generatePrivate(PKCS8EncodedKeySpec(priv))
            val publicKey = kf.generatePublic(X509EncodedKeySpec(pub))
            return KeyPair(publicKey, privateKey)
        }
    }

    /**
     * 該当 server config のホスト鍵を取得 (未生成なら生成 + 保存)。
     */
    suspend fun loadOrGenerate(configId: String): KeyPair {
        val config = repository.findById(configId)
            ?: error("Server config not found: $configId")
        val existing = repository.loadCredentialBytes(config.hostKeyRef)
        if (existing != null) {
            runCatching { return deserialize(existing) }
                .onFailure {
                    // データ破損時は再生成する。
                }
        }
        val keyPair = generate()
        repository.saveHostKey(configId, serialize(keyPair))
        return keyPair
    }

    /** OpenSSH 互換の `SHA256:xxxx` 形式フィンガープリント。 */
    fun fingerprintSha256(keyPair: KeyPair): String =
        KeyUtils.getFingerPrint(BuiltinDigests.sha256, keyPair.public)

    /** 鍵を強制再生成 (鍵漏洩時の対応)。 */
    suspend fun regenerate(configId: String): KeyPair {
        val keyPair = generate()
        repository.saveHostKey(configId, serialize(keyPair))
        return keyPair
    }
}
