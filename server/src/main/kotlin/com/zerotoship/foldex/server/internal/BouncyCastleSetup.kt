// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.server.internal

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Android 既定の Conscrypt / AndroidOpenSSL に加え、Ed25519 などサーバー側で必要な
 * アルゴリズムを使えるよう BouncyCastle を JCA に登録する。位置はリストの末尾
 * (低優先) なので、既に解決可能なアルゴリズム要求は AndroidOpenSSL に任せ、
 * 不足分のみ BC が応える形になる。
 *
 * 多重登録を避けるため、最初の呼び出しでのみ実際に provider を追加する。
 */
internal object BouncyCastleSetup {
    @Volatile private var installed: Boolean = false

    fun ensureInstalled() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
            installed = true
        }
    }
}
