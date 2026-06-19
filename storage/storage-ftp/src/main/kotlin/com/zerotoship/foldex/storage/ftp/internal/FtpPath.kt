// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.storage.ftp.internal

/**
 * FTP のパスは Unix 風 ("/" 区切り、絶対パス) を採用する。
 * core-model 側の [com.zerotoship.foldex.core.model.FileUri.Remote.path] と同じ表現。
 */
internal object FtpPath {

    fun normalize(path: String): String {
        if (path.isBlank()) return "/"
        val collapsed = path.replace(Regex("/+"), "/")
        val trimmed = if (collapsed.length > 1) collapsed.trimEnd('/') else collapsed
        return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    }

    fun basename(path: String): String =
        normalize(path).trimEnd('/').substringAfterLast('/', missingDelimiterValue = "")

    fun parent(path: String): String {
        val normalized = normalize(path)
        if (normalized == "/") return "/"
        val trimmed = normalized.trimEnd('/')
        val parent = trimmed.substringBeforeLast('/')
        return if (parent.isEmpty()) "/" else parent
    }

    fun join(parent: String, child: String): String {
        val p = normalize(parent).trimEnd('/')
        val c = child.trimStart('/')
        return if (p.isEmpty()) "/$c" else "$p/$c"
    }
}
