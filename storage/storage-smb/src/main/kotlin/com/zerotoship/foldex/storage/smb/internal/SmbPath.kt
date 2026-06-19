// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.storage.smb.internal

/**
 * SMB (smbj) のパス表記は Windows 形式 (`\` 区切り、share ルートからの相対)。
 * 内部の [com.zerotoship.foldex.core.model.FileUri.Remote.path] は POSIX 形式 (`/` 区切り、先頭スラッシュあり)
 * で扱うので、変換ヘルパを集約する。
 */
internal object SmbPath {
    /** "/foo/bar.txt" → "foo\\bar.txt"、ルートは空文字。 */
    fun toSmb(posix: String): String {
        val trimmed = posix.trimStart('/')
        return trimmed.replace('/', '\\')
    }

    /** "foo\\bar.txt" → "/foo/bar.txt" (先頭にスラッシュ付与)。 */
    fun toPosix(smb: String): String {
        val replaced = smb.replace('\\', '/')
        return if (replaced.startsWith("/")) replaced else "/$replaced"
    }

    /** posix path の最後のセグメント。"/" や "" の場合は空文字。 */
    fun basename(posix: String): String =
        posix.trimEnd('/').substringAfterLast('/', missingDelimiterValue = "")

    /** posix path の親ディレクトリ。"/foo/bar" → "/foo"、"/foo" → "/"。 */
    fun parent(posix: String): String {
        val trimmed = posix.trimEnd('/')
        if (!trimmed.contains('/')) return "/"
        val parent = trimmed.substringBeforeLast('/')
        return if (parent.isEmpty()) "/" else parent
    }

    /** 子要素を結合。末尾スラッシュは正規化される。 */
    fun join(parent: String, child: String): String {
        val p = parent.trimEnd('/')
        val c = child.trimStart('/')
        return if (p.isEmpty()) "/$c" else "$p/$c"
    }
}
