// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.common

import java.util.concurrent.atomic.AtomicLong

/**
 * 別の画面 (画像編集・動画の書き出し) でファイルを保存したことを、ファイル一覧へ伝える。
 *
 * 一覧は戻ってきただけでは読み直さないので、別名で保存したファイルが出てこなかった。
 * 保存のたびに番号を進め、一覧は前面に戻ったときに番号が変わっていれば今のフォルダを読み直す。
 * 保存先のフォルダは問わない (NAS・SAF の保存先も同じ扱いにするため)。
 */
object SavedFileChanges {
    private val counter = AtomicLong()

    val version: Long get() = counter.get()

    fun notifySaved() {
        counter.incrementAndGet()
    }
}
