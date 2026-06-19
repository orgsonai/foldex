// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.model.filetype

/**
 * ファイルの大分類。タップ時の挙動 (内蔵ビューア / 外部アプリ / インストール 等) や
 * 一覧アイコン・サムネ生成可否の判断に使う。判定は基本的に拡張子ベース ([FileTypeRegistry])。
 */
enum class Category {
    IMAGE,
    VIDEO,
    AUDIO,
    TEXT,
    MARKDOWN,
    HTML,
    PDF,
    ARCHIVE,
    OFFICE,
    APK,
    ISO,
    BINARY,
    UNKNOWN,
    ;

    /** アプリ内に簡易ビューアを持つカテゴリか (タップで内蔵画面を開く対象)。 */
    val hasBuiltInViewer: Boolean
        get() = this == IMAGE || this == TEXT || this == MARKDOWN || this == HTML ||
            this == AUDIO || this == VIDEO || this == PDF

    /** サムネイル生成を試みる価値があるカテゴリか。 */
    val supportsThumbnail: Boolean
        get() = this == IMAGE || this == VIDEO || this == AUDIO || this == PDF
}
