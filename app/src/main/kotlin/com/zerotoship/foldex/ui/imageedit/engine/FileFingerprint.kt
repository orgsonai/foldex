// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** サイズ一致だけで、保存成功や原本の未変更を判定しない。 */
data class FileFingerprint(val size: Long, val sha256: String) {
    companion object {
        fun read(file: File, checkCancelled: () -> Unit = {}): FileFingerprint =
            file.inputStream().use { read(it, checkCancelled) }

        fun read(input: InputStream, checkCancelled: () -> Unit = {}): FileFingerprint {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var size = 0L
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                size += count
            }
            return FileFingerprint(size, digest.digest().joinToString("") { "%02x".format(it) })
        }
    }
}
