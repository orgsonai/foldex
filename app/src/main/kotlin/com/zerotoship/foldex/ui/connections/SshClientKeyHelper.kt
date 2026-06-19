package com.zerotoship.foldex.ui.connections

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

/**
 * 接続側 (SFTP クライアント) のパスワードレス認証用に、RSA 3072 鍵ペアを生成して
 * 「秘密鍵 PEM (PKCS#8)」と「公開鍵 OpenSSH 形式 (ssh-rsa AAAA...)」を取り出す簡易ヘルパ。
 *
 * 出力:
 * - 秘密鍵 PEM は SSHJ の `client.loadKeys(text, null, null)` がそのまま受け付ける形。
 * - 公開鍵は authorized_keys に貼り付ける形 (`ssh-rsa <base64> <comment>`)。
 *
 * BouncyCastle に依存せず Android 標準 JCA のみで完結する (RSA は最も互換性が高い)。
 */
internal object SshClientKeyHelper {

    fun generate(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()

    fun toPkcs8Pem(keyPair: KeyPair): String {
        val der = keyPair.private.encoded
        val b64 = Base64.encodeToString(der, Base64.NO_WRAP)
        return buildString {
            append("-----BEGIN PRIVATE KEY-----\n")
            for (line in b64.chunked(64)) { append(line); append('\n') }
            append("-----END PRIVATE KEY-----\n")
        }
    }

    fun toOpenSshPublic(keyPair: KeyPair, comment: String = "foldex"): String {
        val rsa = keyPair.public as? RSAPublicKey
            ?: error("Only RSA keys are supported by this helper")
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            writeString(d, "ssh-rsa")
            writeMpint(d, rsa.publicExponent)
            writeMpint(d, rsa.modulus)
        }
        return "ssh-rsa " + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP) + " " + comment
    }

    private fun writeString(d: DataOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.US_ASCII)
        d.writeInt(bytes.size)
        d.write(bytes)
    }

    /** SSH の mpint (big-endian 2's complement、必要なら先頭 0x00 を付けて正の数を表す) を書き出す。 */
    private fun writeMpint(d: DataOutputStream, n: BigInteger) {
        val raw = n.toByteArray() // 既に必要に応じて 0x00 が付与される
        d.writeInt(raw.size)
        d.write(raw)
    }
}
