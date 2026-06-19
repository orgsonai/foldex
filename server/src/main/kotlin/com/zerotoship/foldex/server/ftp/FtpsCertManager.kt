// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.server.ftp

import android.content.Context
import com.zerotoship.foldex.core.data.repo.ServerConfigRepository
import com.zerotoship.foldex.server.internal.BouncyCastleSetup
import dagger.hilt.android.qualifiers.ApplicationContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Explicit FTPS 用の自己署名証明書 (PKCS12) を管理する — 仕様書 §9-I「FTPS (Explicit TLS)」。
 *
 * - 初回起動時に RSA 2048bit の鍵ペアと自己署名 X.509 証明書を生成。
 * - PKCS12 バイト列は [ServerConfigRepository.saveTlsCert] で AES-GCM 暗号化して DB に保存。
 * - Apache FtpServer の `SslConfigurationFactory` はキーストア「ファイル」を要求するため、
 *   復号したバイト列をアプリ専用キャッシュ (`cacheDir/foldex-ftps/<id>.p12`) に書き出して渡す。
 * - [regenerate] で鍵漏洩時に作り直せる。
 *
 * X.509 証明書を組み立てるのに JCA だけでは API が足りないため BouncyCastle (bcpkix) を使う。
 * 生成したキーストア自体はプラットフォーム標準の PKCS12 なので、読み込み側 (FtpServer) は
 * 通常の `KeyStore` で扱える。
 */
@Singleton
class FtpsCertManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ServerConfigRepository,
) {

    data class Keystore(val file: File, val password: String, val type: String, val keyAlias: String)

    /** 既存の証明書を取得 (無効/未生成なら生成して保存)。 */
    suspend fun loadOrGenerate(configId: String): Keystore {
        val config = repository.findById(configId) ?: error("Server config not found: $configId")
        val bytes = repository.loadCredentialBytes(config.ftpsTlsCertRef)
            ?.takeIf { runCatching { isUsablePkcs12(it) }.getOrDefault(false) }
            ?: run {
                val generated = generatePkcs12()
                repository.saveTlsCert(configId, generated)
                generated
            }
        return materialize(configId, bytes)
    }

    /** 証明書を強制的に作り直して保存する。 */
    suspend fun regenerate(configId: String): Keystore {
        val generated = generatePkcs12()
        repository.saveTlsCert(configId, generated)
        return materialize(configId, generated)
    }

    private fun materialize(configId: String, pkcs12: ByteArray): Keystore {
        val file = keystoreFileFor(configId).apply {
            parentFile?.mkdirs()
            writeBytes(pkcs12)
        }
        return Keystore(file = file, password = KEYSTORE_PASSWORD, type = KEYSTORE_TYPE, keyAlias = KEY_ALIAS)
    }

    private fun keystoreFileFor(configId: String): File =
        File(File(context.cacheDir, "foldex-ftps"), "$configId.p12")

    private fun isUsablePkcs12(bytes: ByteArray): Boolean {
        val ks = KeyStore.getInstance(KEYSTORE_TYPE)
        ByteArrayInputStream(bytes).use { ks.load(it, KEYSTORE_PASSWORD.toCharArray()) }
        return ks.isKeyEntry(KEY_ALIAS)
    }

    private fun generatePkcs12(): ByteArray {
        BouncyCastleSetup.ensureInstalled()
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048)
        val keyPair = keyGen.generateKeyPair()

        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 60 * 60 * 1000)               // 1 日前 (端末の時計ずれ対策)
        val notAfter = Date(now + 20L * 365 * 24 * 60 * 60 * 1000)     // 約 20 年
        val name = X500Name("CN=Foldex FTPS")
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certHolder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            notBefore,
            notAfter,
            name,
            keyPair.public,
        ).build(signer)
        val cert = JcaX509CertificateConverter().getCertificate(certHolder)

        val ks = KeyStore.getInstance(KEYSTORE_TYPE)
        ks.load(null, null)
        ks.setKeyEntry(KEY_ALIAS, keyPair.private, KEYSTORE_PASSWORD.toCharArray(), arrayOf(cert))
        return ByteArrayOutputStream().use { out ->
            ks.store(out, KEYSTORE_PASSWORD.toCharArray())
            out.toByteArray()
        }
    }

    private companion object {
        const val KEYSTORE_TYPE = "PKCS12"
        const val KEY_ALIAS = "foldex"

        // ローカル端末上の自己署名証明書専用パスワード。実体は DB に AES-GCM 暗号化して保存し、
        // キャッシュファイルはアプリ専用ストレージにあるため、固定文字列で実害は無い。
        const val KEYSTORE_PASSWORD = "foldex-ftps"
    }
}
