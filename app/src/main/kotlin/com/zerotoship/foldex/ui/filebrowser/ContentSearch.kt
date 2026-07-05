// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.filebrowser

import com.zerotoship.foldex.core.model.FileNode
import com.zerotoship.foldex.core.model.filetype.Category
import com.zerotoship.foldex.core.model.filetype.FileTypeRegistry
import com.zerotoship.foldex.ui.viewer.TextDecoding
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * 中身検索 (grep) の 1 ヒット。[node] にヒットしたファイル、[snippet] にマッチ箇所周辺の抜粋。
 */
data class ContentSearchHit(
    val node: FileNode,
    val snippet: String,
)

/**
 * ファイルの「中身」からテキストを取り出して検索するためのヘルパ。
 *
 * 対応:
 * - テキスト系 (TEXT / MARKDOWN / HTML): [TextDecoding] で文字コード判定してデコード
 * - Office 新形式 (docx/xlsx/pptx) と OpenDocument (odt/ods/odp): zip 内の本文 XML からタグを外して抽出
 *
 * 非対応 (v1): PDF・旧バイナリ形式 (doc/xls/ppt)・rtf。いずれも追加ライブラリが要るため除外。
 */
internal object ContentSearch {

    // OOXML / ODF は zip コンテナ。中の XML から本文を取り出せる。
    private val zipTextExt = setOf("docx", "xlsx", "pptx", "odt", "ods", "odp")

    /** 1 ファイルあたりの読み込み上限。これを超えるファイルは中身検索の対象外 (メモリ保護)。 */
    const val MAX_FILE_BYTES = 16L * 1024 * 1024

    /** [name] のファイルを中身検索の対象にできるか (拡張子 / カテゴリで判定)。 */
    fun isSearchable(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in zipTextExt) return true
        return isTextLike(name)
    }

    /** [bytes] から検索用のテキストを取り出す。対象外 / 抽出失敗なら null。 */
    fun extractText(name: String, bytes: ByteArray): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext in zipTextExt -> extractFromZip(bytes)
            isTextLike(name) -> runCatching { String(bytes, TextDecoding.detect(bytes)) }.getOrNull()
            else -> null
        }
    }

    private fun isTextLike(name: String): Boolean = when (FileTypeRegistry.categorize(name)) {
        Category.TEXT, Category.MARKDOWN, Category.HTML -> true
        else -> false
    }

    /**
     * OOXML / ODF (zip) から本文テキストを抽出する。本文を含む XML エントリだけを結合し、
     * タグを外して可読テキストにする。抽出できなければ null。
     */
    private fun extractFromZip(bytes: ByteArray): String? = runCatching {
        val sb = StringBuilder()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (isBodyXml(entry.name)) {
                    val xml = zip.readBytes().toString(Charsets.UTF_8)
                    sb.append(stripXmlTags(xml)).append('\n')
                }
                zip.closeEntry()
            }
        }
        sb.toString().takeIf { it.isNotBlank() }
    }.getOrNull()

    /** zip 内で本文を含む XML エントリか (画像・スタイル・メタデータ等は無視)。 */
    private fun isBodyXml(name: String): Boolean =
        name == "word/document.xml" || // docx
            name == "content.xml" || // odt / ods / odp
            (name.startsWith("ppt/slides/slide") && name.endsWith(".xml")) || // pptx
            name == "xl/sharedStrings.xml" || // xlsx のセル文字列本体
            (name.startsWith("xl/worksheets/") && name.endsWith(".xml")) // xlsx のインライン文字列

    /** XML からタグを外して本文だけにする。タグ境界に空白を入れて語の結合を防ぐ。 */
    private fun stripXmlTags(xml: String): String {
        val out = StringBuilder(xml.length)
        var inside = false
        for (c in xml) {
            when (c) {
                '<' -> { inside = true; out.append(' ') }
                '>' -> inside = false
                else -> if (!inside) out.append(c)
            }
        }
        // 最低限の XML エスケープ復元。
        return out.toString()
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    /**
     * [text] 内で [query] に最初にマッチした箇所の抜粋を返す。マッチしなければ null。
     * テキストはマッチ行、Office はマッチ周辺の文字ウィンドウになる (長い1行は切り詰め)。
     */
    fun snippetFor(text: String, query: String): String? {
        if (query.isEmpty()) return null
        val idx = text.indexOf(query, ignoreCase = true)
        if (idx < 0) return null
        val lineStart = text.lastIndexOf('\n', idx) + 1
        val lineEnd = text.indexOf('\n', idx).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, lineEnd).trim()
        val snippet = if (line.length <= MAX_SNIPPET_CHARS) {
            line
        } else {
            val from = (idx - 40).coerceAtLeast(lineStart)
            val to = (idx + query.length + 80).coerceAtMost(lineEnd)
            val prefix = if (from > lineStart) "…" else ""
            val suffix = if (to < lineEnd) "…" else ""
            prefix + text.substring(from, to).trim() + suffix
        }
        return snippet.ifBlank { null }
    }

    private const val MAX_SNIPPET_CHARS = 200
}
