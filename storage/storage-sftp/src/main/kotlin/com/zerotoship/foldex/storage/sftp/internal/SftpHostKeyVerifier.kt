// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.storage.sftp.internal

import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.PublicKey

/**
 * sshj の [HostKeyVerifier] 実装。
 *
 * - [expectedFingerprint] が non-null: 接続時のホスト鍵 SHA-256 フィンガープリントが
 *   完全一致したときだけ受理する (TOFU 後の通常運用)。
 * - [expectedFingerprint] が null + [onCapture] non-null: 任意の鍵を受理し、
 *   実フィンガープリントを [onCapture] に渡す (UI 初回接続時の確認用)。
 * - [expectedFingerprint] が null + [onCapture] null: 常に拒否する (安全側)。
 *
 * 比較は大文字小文字無視、`SHA256:` プレフィックスの有無も吸収する。
 */
internal class SftpHostKeyVerifier(
    private val expectedFingerprint: String?,
    private val onCapture: ((String) -> Unit)? = null,
) : HostKeyVerifier {

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val actual = SecurityUtils.getFingerprint(key)
        if (expectedFingerprint == null) {
            val capture = onCapture ?: return false
            capture(actual)
            return true
        }
        return matches(expectedFingerprint, actual)
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()

    private fun matches(expected: String, actual: String): Boolean {
        val a = expected.removePrefix("SHA256:").removePrefix("sha256:")
        val b = actual.removePrefix("SHA256:").removePrefix("sha256:")
        return a.equals(b, ignoreCase = true)
    }
}
