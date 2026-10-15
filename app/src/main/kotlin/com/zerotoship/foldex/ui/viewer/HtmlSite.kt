// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.viewer

import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.core.model.FileNode
import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.core.model.ListOptions
import com.zerotoship.foldex.core.model.NodeType
import com.zerotoship.foldex.core.model.StorageProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * HTML ビューアが読むファイルの置き場。URL のパスを段ごとに分けたもの ([open] の segments) を、実際のファイルに対応させる。
 *
 * 端末内は [LocalHtmlSite]、SAF・NAS は [StorageHtmlSite]。どちらも、まず起点のフォルダ基準で探し、
 * 見つからなければ開いた HTML のフォルダから上へ順に当てはめる (`/css/a.css` のような `/` 始まりの参照のため)。
 * [open] は WebView の読み込みスレッドから呼ばれる。
 */
internal interface HtmlSite {
    /** 最初に開くページのパス。 */
    val startSegments: List<String>

    /** 見つからない・起点の外なら null。 */
    fun open(segments: List<String>): SiteFile?
}

internal class SiteFile(val name: String, val stream: () -> InputStream)

/**
 * 端末内のファイル。起点は共有ストレージならそのボリューム全体 (`/storage/emulated/0` や SD カード)、
 * キャッシュ実体 (他アプリから受け取ったファイル) は開いたファイルのフォルダだけ。
 */
internal class LocalHtmlSite(file: File) : HtmlSite {
    private val root = siteRoot(file)
    private val fallbacks = fallbackBases(root, file)

    override val startSegments: List<String> =
        file.canonicalFile.path.removePrefix(root.path).split('/').filter { it.isNotEmpty() }

    override fun open(segments: List<String>): SiteFile? {
        val relative = segments.joinToString("/")
        val target = (listOf(root) + fallbacks).firstNotNullOfOrNull { base -> resolveIn(base, relative) } ?: return null
        return SiteFile(target.name) { FileInputStream(target) }
    }

    /** [base] 基準で [relative] を解決する。フォルダなら中の index.html。[root] の外・存在しない・読めないものは null。 */
    private fun resolveIn(base: File, relative: String): File? {
        val requested = runCatching { File(base, relative).canonicalFile }.getOrNull() ?: return null
        val inside = requested.path == root.path || requested.path.startsWith(root.path + File.separator)
        if (!inside) return null
        val target = if (requested.isDirectory) File(requested, "index.html") else requested
        return target.takeIf { it.isFile && it.canRead() }
    }

    private companion object {
        fun siteRoot(file: File): File {
            val target = runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }
            val parts = target.path.split('/').filter { it.isNotEmpty() }
            val volume = when {
                parts.size > 3 && parts[0] == "storage" && parts[1] == "emulated" -> "/storage/emulated/${parts[2]}"
                parts.size > 2 && parts[0] == "storage" && parts[1] != "emulated" && parts[1] != "self" -> "/storage/${parts[1]}"
                parts.size > 2 && parts[0] == "sdcard" -> "/sdcard"
                else -> null
            }
            return File(volume ?: target.parentFile?.path ?: "/").canonicalFile
        }

        /** 開いた HTML のフォルダから [root] の手前まで、近い順。例: `site/sub/page.html` なら `site/sub` → `site`。 */
        fun fallbackBases(root: File, file: File): List<File> {
            val start = runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }.parentFile
            return generateSequence(start) { it.parentFile }
                .takeWhile { it.path.startsWith(root.path + File.separator) }
                .toList()
        }
    }
}

/**
 * SAF・NAS のファイル。ファイル一覧のパンくず ([folders]: 起点から HTML のあるフォルダまで) を道筋にして、
 * 子の一覧を名前で引いて相対パスをたどる。提供アプリが文書の親をたどる機能 (findDocumentPath) を持たなくても動く。
 * 起点より上は読まない。開いた HTML 自体は、編集中の内容を見せるためキャッシュの実体 [openedCopy] を返す。
 */
internal class StorageHtmlSite(
    private val storage: StorageProvider,
    private val folders: List<Pair<FileUri, String>>,
    fileName: String,
    private val openedCopy: File,
) : HtmlSite {
    init {
        require(folders.isNotEmpty())
    }

    override val startSegments: List<String> = folders.drop(1).map { it.second } + fileName

    /** フォルダ → 子の名前と中身。同じフォルダを何度も一覧しない。 */
    private val children = ConcurrentHashMap<String, Map<String, FileNode>>()

    override fun open(segments: List<String>): SiteFile? {
        if (segments == startSegments) return SiteFile(openedCopy.name) { FileInputStream(openedCopy) }
        // 起点 → 開いた HTML のフォルダ → その親 … の順。
        val bases = listOf(0) + (folders.lastIndex downTo 1)
        val node = bases.firstNotNullOfOrNull { resolve(it, segments) } ?: return null
        return SiteFile(node.name) { openStream(node.uri) }
    }

    private fun resolve(base: Int, segments: List<String>): FileNode? {
        var dir = folders[base].first
        var index = 0
        // パンくずで分かっている段は、一覧を取らずに進む。
        while (base + index < folders.lastIndex && index < segments.size && segments[index] == folders[base + index + 1].second) {
            dir = folders[base + index + 1].first
            index++
        }
        var node: FileNode? = null
        while (index < segments.size) {
            val child = child(dir, segments[index]) ?: return null
            index++
            if (index < segments.size) {
                if (child.type != NodeType.DIRECTORY) return null
                dir = child.uri
            } else {
                node = child
            }
        }
        // フォルダを指していれば中の index.html。
        return when {
            node == null -> child(dir, INDEX)
            node.type == NodeType.DIRECTORY -> child(node.uri, INDEX)
            else -> node
        }?.takeIf { it.type != NodeType.DIRECTORY }
    }

    private fun child(dir: FileUri, name: String): FileNode? =
        children.getOrPut(dir.toStorageString()) {
            runCatching { runBlocking { storage.list(dir, ListOptions(showHidden = true)).toList() } }
                .getOrDefault(emptyList())
                .associateBy { it.name }
        }[name]

    private fun openStream(uri: FileUri): InputStream =
        when (val result = runBlocking { storage.openInput(uri) }) {
            is Result.Success -> result.value
            is Result.Failure -> throw IOException(result.error.toString())
        }

    private companion object {
        const val INDEX = "index.html"
    }
}
