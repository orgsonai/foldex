// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.sync.engine

/**
 * 単一 glob パターンを相対パス (区切りは常に `/`、先頭スラッシュ無し) に対して
 * マッチングするコンパイル済みマッチャ。仕様書 §8-H に従い glob のみサポートし、
 * 正規表現は受け付けない。
 *
 * 採用しているセマンティクス (gitignore に近い):
 * - パターンに `/` を含まない場合は **ベース名マッチ** とみなし、任意の深さで一致する
 *   (例: `*.jpg` は `a.jpg` と `dir/a.jpg` の両方に一致)。
 * - パターンに `/` を含む場合は同期ルートからの相対パスとして **アンカー** される。
 * - `*` … `/` を除く 0 文字以上。
 * - `?` … `/` を除く 1 文字。
 * - `**` … パスセパレータを跨いだ 0 文字以上。`**` の直後にスラッシュが続く場合は
 *   「0 階層以上の任意ディレクトリ」を意味する。
 * - `[abc]` / `[a-z]` / `[!abc]` … 文字クラス。
 * - `\x` … 直後の 1 文字をリテラルとして扱うエスケープ。
 * - 末尾の `/` (ディレクトリ指定) は無視する (同期はファイル単位のため)。
 */
internal class GlobMatcher private constructor(private val regex: Regex) {

    fun matches(path: String): Boolean = regex.matches(path)

    companion object {
        /** 空・空白のみのパターンは無効として `null` を返す。 */
        fun compileOrNull(glob: String): GlobMatcher? {
            val normalized = glob.trim().trimEnd('/')
            if (normalized.isEmpty()) return null
            val effective = if (normalized.contains('/')) {
                normalized.removePrefix("/")
            } else {
                // ベース名マッチ: 任意の深さに置けるよう "**/" を前置する
                "**/$normalized"
            }
            return GlobMatcher(Regex(globToRegex(effective)))
        }

        private val REGEX_META = setOf('.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}')

        private fun globToRegex(glob: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < glob.length) {
                when (val c = glob[i]) {
                    '*' -> {
                        val doubleStar = i + 1 < glob.length && glob[i + 1] == '*'
                        if (doubleStar) {
                            i++ // 2 つ目の '*' を消費
                            val slashAfter = i + 1 < glob.length && glob[i + 1] == '/'
                            if (slashAfter) {
                                i++ // 直後の '/' を消費
                                sb.append("(?:.*/)?")
                            } else {
                                sb.append(".*")
                            }
                        } else {
                            sb.append("[^/]*")
                        }
                    }

                    '?' -> sb.append("[^/]")

                    '\\' -> {
                        if (i + 1 < glob.length) {
                            i++
                            sb.append(Regex.escape(glob[i].toString()))
                        } else {
                            sb.append("\\\\")
                        }
                    }

                    '[' -> {
                        val end = glob.indexOf(']', startIndex = i + 1)
                        if (end == -1) {
                            sb.append("\\[")
                        } else {
                            var cls = glob.substring(i + 1, end)
                            if (cls.startsWith("!")) cls = "^" + cls.substring(1)
                            sb.append('[').append(cls).append(']')
                            i = end
                        }
                    }

                    in REGEX_META -> sb.append('\\').append(c)

                    else -> sb.append(c)
                }
                i++
            }
            return sb.toString()
        }
    }
}
