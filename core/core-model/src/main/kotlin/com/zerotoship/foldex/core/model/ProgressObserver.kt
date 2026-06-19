// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.model

fun interface ProgressObserver {
    fun onProgress(bytesTransferred: Long, totalBytes: Long)
}
