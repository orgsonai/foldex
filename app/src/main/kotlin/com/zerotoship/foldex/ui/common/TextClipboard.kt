// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.common

import android.content.ClipData
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.toClipEntry

/**
 * 端末のクリップボードへプレーンテキストを入れる。
 *
 * Compose では同期的な `LocalClipboardManager.setText()` が非推奨になり、
 * 後継の `LocalClipboard.setClipEntry()` は suspend 関数になった (コルーチンから呼ぶ)。
 * 呼び出し側 3 箇所で ClipData の組み立てを繰り返さないよう、ここに 1 つだけ用意する。
 *
 * [label] は Android がクリップボードの中身を説明するときに使うラベル。
 *
 * ファイルの「コピー / 切り取り」を持ち回る [SharedClipboard] とは別物である
 * (あちらはアプリ内だけで完結する状態、こちらは端末共通のクリップボード)。
 */
suspend fun Clipboard.setPlainText(label: String, text: String) {
    setClipEntry(ClipData.newPlainText(label, text).toClipEntry())
}
