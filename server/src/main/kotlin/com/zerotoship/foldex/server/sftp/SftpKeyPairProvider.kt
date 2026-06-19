// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.server.sftp

import com.zerotoship.foldex.server.security.HostKeyManager
import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.session.SessionContext
import java.security.KeyPair

/**
 * 設定 [configId] のホスト鍵を [HostKeyManager.loadOrGenerate] で取り出して
 * SSHD の [KeyPairProvider] 契約に渡す。初回呼び出しで秘密鍵を生成 + 永続化、
 * 2 回目以降は復号して同じ鍵を返す。
 */
internal class SftpKeyPairProvider(
    private val configId: String,
    private val hostKeyManager: HostKeyManager,
) : KeyPairProvider {

    override fun loadKeys(session: SessionContext?): Iterable<KeyPair> = runBlocking {
        listOf(hostKeyManager.loadOrGenerate(configId))
    }
}
