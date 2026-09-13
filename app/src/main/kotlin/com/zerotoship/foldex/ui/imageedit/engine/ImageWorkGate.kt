// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 全画面共通の一枠。圧縮・推論の終了と所有画像の解放まで枠を保持する。 */
internal class ImageWorkGate {
    private val mutex = Mutex()

    suspend fun <T> run(block: () -> T): T = mutex.withLock { block() }
}
