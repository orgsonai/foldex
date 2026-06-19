// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.sync.engine

import com.zerotoship.foldex.core.model.SyncFilter

/**
 * [SyncFilter] (ユーザー定義の include / exclude glob と最大ファイルサイズ) を
 * 1 つの判定関数にまとめたもの。DiffEngine がローカル/リモートを列挙する際に
 * 各エントリを通すかどうかを決めるために使う。
 *
 * 判定順 (仕様書 §8-H):
 * 1. include が 1 つ以上あり、どれにも一致しなければ除外。
 * 2. exclude のいずれかに一致すれば除外。
 * 3. [SyncFilter.maxFileSize] が指定され、サイズがそれを超えていれば除外。
 * 4. それ以外は通す。
 *
 * パスは「同期ルートからの相対パス・区切り `/`・先頭スラッシュ無し」を期待する。
 * ディレクトリは常に走査対象とする (中のファイルを include したい場合に親を
 * exclude で落とすと到達できなくなるため、ディレクトリ自体の判定は呼び出し側に委ねる)。
 */
class Filter(filter: SyncFilter) {

    private val includes: List<GlobMatcher> = filter.includePatterns.mapNotNull(GlobMatcher::compileOrNull)
    private val excludes: List<GlobMatcher> = filter.excludePatterns.mapNotNull(GlobMatcher::compileOrNull)
    private val maxFileSize: Long? = filter.maxFileSize

    /** include / exclude のどちらも空で、サイズ上限も無い場合は素通しになる。 */
    val isEmpty: Boolean = includes.isEmpty() && excludes.isEmpty() && maxFileSize == null

    /**
     * @param relativePath 同期ルートからの相対パス。
     * @param size ファイルサイズ (bytes)。不明 (ディレクトリ等) なら `null` でサイズ判定をスキップ。
     */
    fun accepts(relativePath: String, size: Long? = null): Boolean {
        val path = relativePath.trim().removePrefix("/")
        if (path.isEmpty()) return false
        if (includes.isNotEmpty() && includes.none { it.matches(path) }) return false
        if (excludes.any { it.matches(path) }) return false
        if (size != null && maxFileSize != null && size > maxFileSize) return false
        return true
    }

    companion object {
        fun from(filter: SyncFilter): Filter = Filter(filter)
    }
}
