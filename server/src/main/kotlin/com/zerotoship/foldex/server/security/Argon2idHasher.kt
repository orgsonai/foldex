package com.zerotoship.foldex.server.security

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BouncyCastle の lightweight API を使った Argon2id パスワードハッシュ。
 *
 * 仕様書 §9-C は argon2-jvm を指定しているが、それは JNA + Linux/Mac/Windows
 * ネイティブのみのため Android では動作しない。BouncyCastle の
 * [Argon2BytesGenerator] は純 Java で同じアルゴリズムを実装しており、
 * 互換性のある Argon2id ハッシュを生成・検証できる。
 *
 * 出力は PHC 形式の文字列 `$argon2id$v=19$m=65536,t=3,p=1$<salt>$<hash>` で、
 * 他の Argon2 実装と相互運用可能。
 */
@Singleton
class Argon2idHasher @Inject constructor() {

    /** デフォルトパラメータ: m=64MB / t=3 / p=1。モバイル端末で 0.5〜1 秒程度。 */
    fun hash(
        password: ByteArray,
        memoryKB: Int = DEFAULT_MEMORY_KB,
        iterations: Int = DEFAULT_ITERATIONS,
        parallelism: Int = DEFAULT_PARALLELISM,
        saltSize: Int = DEFAULT_SALT_SIZE,
    ): String {
        require(password.isNotEmpty()) { "Password must not be empty" }
        val salt = ByteArray(saltSize).also { SecureRandom().nextBytes(it) }
        val hash = derive(password, salt, memoryKB, iterations, parallelism, OUTPUT_BYTES)
        return encode(memoryKB, iterations, parallelism, salt, hash)
    }

    fun verify(password: ByteArray, encoded: String): Boolean {
        val parsed = decode(encoded) ?: return false
        val recomputed = derive(
            password,
            parsed.salt,
            parsed.memoryKB,
            parsed.iterations,
            parsed.parallelism,
            parsed.hash.size,
        )
        return constantTimeEquals(recomputed, parsed.hash)
    }

    private fun derive(
        password: ByteArray,
        salt: ByteArray,
        memoryKB: Int,
        iterations: Int,
        parallelism: Int,
        outputBytes: Int,
    ): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(memoryKB)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .build()
        val gen = Argon2BytesGenerator()
        gen.init(params)
        val out = ByteArray(outputBytes)
        gen.generateBytes(password, out)
        return out
    }

    private fun encode(
        memoryKB: Int,
        iterations: Int,
        parallelism: Int,
        salt: ByteArray,
        hash: ByteArray,
    ): String {
        val b64 = Base64.getEncoder().withoutPadding()
        val saltStr = b64.encodeToString(salt)
        val hashStr = b64.encodeToString(hash)
        return "\$argon2id\$v=19\$m=$memoryKB,t=$iterations,p=$parallelism\$$saltStr\$$hashStr"
    }

    private fun decode(encoded: String): Parsed? {
        val parts = encoded.split('$').filter { it.isNotEmpty() }
        if (parts.size != 5) return null
        if (parts[0] != "argon2id") return null
        if (parts[1] != "v=19") return null
        val params = parts[2].split(',').associate {
            val kv = it.split('=', limit = 2)
            kv[0] to (kv.getOrNull(1) ?: return null)
        }
        val memoryKB = params["m"]?.toIntOrNull() ?: return null
        val iterations = params["t"]?.toIntOrNull() ?: return null
        val parallelism = params["p"]?.toIntOrNull() ?: return null
        val b64 = Base64.getDecoder()
        val salt = runCatching { b64.decode(parts[3]) }.getOrNull() ?: return null
        val hash = runCatching { b64.decode(parts[4]) }.getOrNull() ?: return null
        return Parsed(memoryKB, iterations, parallelism, salt, hash)
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    private data class Parsed(
        val memoryKB: Int,
        val iterations: Int,
        val parallelism: Int,
        val salt: ByteArray,
        val hash: ByteArray,
    )

    companion object {
        const val DEFAULT_MEMORY_KB: Int = 64 * 1024
        const val DEFAULT_ITERATIONS: Int = 3
        const val DEFAULT_PARALLELISM: Int = 1
        const val DEFAULT_SALT_SIZE: Int = 16
        const val OUTPUT_BYTES: Int = 32
    }
}
