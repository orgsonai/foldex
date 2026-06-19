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
 * SFTP サーバー用ホスト鍵の生成・永続化・フィンガープリント計算。
 *
 * **アルゴリズム**: RSA 3072 を既定とする。理由: Apache MINA SSHD の
 * `SecurityUtils.isEDDSACurveSupported()` は環境によって false にキャッシュされ、
 * `signatureFactories` から ed25519 が落ちることがある (host key と一致する署名アルゴリズムが
 * 無くなり SSH_MSG_DISCONNECT "no resolved signatures available")。RSA は Android 標準 JCE で
 * 安定に動くため切替えた。
 *
 * **永続化フォーマット v2**: `[magic:int][algo:UTF][privateLen:int][priv PKCS8 DER]
 * [publicLen:int][pub X.509 SPKI DER]`。magic で旧フォーマット (algo 無し / Ed25519 固定) を識別する。
 * 旧フォーマットを読んだ場合は再生成にフォールバックする (アルゴリズム切替のため fingerprint も変わる)。
 */
@Singleton
class HostKeyManager @Inject constructor(
    private val repository: ServerConfigRepository,
) {

    /** 新しい RSA 3072 鍵ペアを生成。 */
    fun generate(): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(3072)
        return gen.generateKeyPair()
    }

    /** 鍵ペアを単一バイト列にシリアライズ (永続化用、v2 フォーマット)。 */
    fun serialize(keyPair: KeyPair): ByteArray {
        val priv = keyPair.private.encoded
        val pub = keyPair.public.encoded
        val algo = keyPair.private.algorithm
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeInt(MAGIC_V2)
            d.writeUTF(algo)
            d.writeInt(priv.size)
            d.write(priv)
            d.writeInt(pub.size)
            d.write(pub)
        }
        return out.toByteArray()
    }

    /** [serialize] の逆操作。旧 v1 フォーマット (Ed25519 固定) は明示的に拒否する → 呼び出し側で再生成。 */
    fun deserialize(bytes: ByteArray): KeyPair {
        DataInputStream(ByteArrayInputStream(bytes)).use { d ->
            val magic = d.readInt()
            require(magic == MAGIC_V2) { "Unsupported host key format: 0x${magic.toString(16)}" }
            val algo = d.readUTF()
            val privLen = d.readInt()
            val priv = ByteArray(privLen).also { d.readFully(it) }
            val pubLen = d.readInt()
            val pub = ByteArray(pubLen).also { d.readFully(it) }
            // Ed25519 だけは BC 経由 (Android の JCA は持っていない)。RSA など他は標準 JCA。
            val kf = if (algo.equals("Ed25519", ignoreCase = true)) {
                BouncyCastleSetup.ensureInstalled()
                KeyFactory.getInstance(algo, BouncyCastleProvider.PROVIDER_NAME)
            } else {
                KeyFactory.getInstance(algo)
            }
            val privateKey = kf.generatePrivate(PKCS8EncodedKeySpec(priv))
            val publicKey = kf.generatePublic(X509EncodedKeySpec(pub))
            return KeyPair(publicKey, privateKey)
        }
    }

    private companion object {
        // "Fol2" — host key blob v2 (algo 名を含む新フォーマット)。
        const val MAGIC_V2: Int = 0x466F6C32
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
