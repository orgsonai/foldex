// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.archive

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import java.io.File

/**
 * ZIP を「展開せずに」中身を閲覧するためのヘルパ (FOLDEX-HANDOFF.md §10 中身プレビュー)。
 *
 * - [listEntries] : 全エントリをヘッダ読みのみで取得 (展開しない / パスワード不要)。
 * - [childrenOf]  : あるディレクトリ直下の子 (フォルダ/ファイル) を算出。通常フォルダのように潜れる。
 * - [extractEntry]: 単一エントリだけをキャッシュへ展開して File を返す (暗号化時は password が要る)。
 *
 * 独立した StorageProvider にはしない方針 (仕様 §10-実装方針)。zip4j のヘッダ API を薄く包むだけ。
 */
object ArchiveExplorer {

    data class Entry(
        /** zip 内のフルパス (末尾スラッシュ無し、区切りは '/')。 */
        val path: String,
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val encrypted: Boolean,
    )

    /** ツリー表示用ノード。中間フォルダは entry を持たない (明示的なフォルダエントリが無くても合成する)。 */
    sealed interface Node {
        val name: String
        data class Folder(override val name: String, val path: String) : Node
        data class FileItem(override val name: String, val entry: Entry) : Node
    }

    /** zip の全エントリを読む。展開はしない。読めなければ null (壊れた zip / zip ではない)。 */
    fun listEntries(zip: File): List<Entry>? = runCatching {
        ZipFile(zip).use { zf ->
            zf.fileHeaders.map { h ->
                val clean = h.fileName.replace('\\', '/').trimEnd('/')
                Entry(
                    path = clean,
                    name = clean.substringAfterLast('/'),
                    isDirectory = h.isDirectory,
                    size = h.uncompressedSize,
                    encrypted = h.isEncrypted,
                )
            }
        }
    }.getOrNull()

    /** いずれかのエントリが暗号化されているか (= 展開時にパスワードが要る zip か)。 */
    fun isEncrypted(entries: List<Entry>): Boolean = entries.any { it.encrypted }

    /**
     * [dirPath] (空文字 = ルート) 直下の子ノードを返す。フォルダ → ファイルの順、各々名前順。
     * 明示的なフォルダエントリが無い zip でも、ファイルパスの途中ディレクトリから合成する。
     */
    fun childrenOf(entries: List<Entry>, dirPath: String): List<Node> {
        val prefix = if (dirPath.isEmpty()) "" else "${dirPath.trimEnd('/')}/"
        val folders = sortedSetOf(String.CASE_INSENSITIVE_ORDER)
        val files = mutableListOf<Entry>()
        for (e in entries) {
            if (e.path.isEmpty()) continue
            if (prefix.isNotEmpty() && !e.path.startsWith(prefix)) continue
            val rest = e.path.removePrefix(prefix)
            if (rest.isEmpty()) continue
            val slash = rest.indexOf('/')
            if (slash < 0) {
                if (e.isDirectory) folders += rest else files += e
            } else {
                folders += rest.substring(0, slash)
            }
        }
        val folderNodes = folders.map { Node.Folder(it, if (prefix.isEmpty()) it else "$prefix$it") }
        val fileNodes = files
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .map { Node.FileItem(it.name, it) }
        return folderNodes + fileNodes
    }

    /**
     * 単一エントリだけを [destDir] 配下に展開し、その File を返す (zip 内パスを保ったまま展開)。
     * 暗号化エントリで password が誤り/未指定なら [WrongPassword] を投げる。
     */
    fun extractEntry(zip: File, entry: Entry, destDir: File, password: String?): File {
        if (!destDir.exists()) destDir.mkdirs()
        val zf = if (password.isNullOrEmpty()) ZipFile(zip) else ZipFile(zip, password.toCharArray())
        try {
            zf.use { it.extractFile(entry.path, destDir.absolutePath) }
        } catch (e: ZipException) {
            val msg = e.message ?: ""
            if (msg.contains("password", ignoreCase = true)) throw WrongPassword(msg)
            throw e
        }
        return File(destDir, entry.path)
    }

    /** zip4j のパスワード系例外を UI 側で扱いやすくしたラッパ。 */
    class WrongPassword(message: String) : Exception(message)
}
