// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.engine

import java.io.File

/**
 * 書き出し中の一時ファイルの場所を 1 行だけ記録する。
 * プロセスが途中で終了すると finally が走らないため、次に編集画面を開いたときにこの記録から片付ける (設計書 §8-7)。
 */
internal class VideoExportJournal(private val file: File) {

    fun begin(temp: File) {
        file.parentFile?.mkdirs()
        file.writeText(temp.absolutePath)
    }

    fun clear() {
        file.delete()
    }

    /** 前回の書き出しが途中で終わっていたら、残った一時ファイルを消して true を返す。 */
    fun recover(): Boolean {
        if (!file.isFile) return false
        val path = runCatching { file.readText().trim() }.getOrNull()
        // 記録が壊れていても、Foldex の一時ファイルの名前でなければ消さない。
        if (!path.isNullOrEmpty()) {
            val temp = File(path)
            if (temp.name.startsWith(VideoExportCoordinator.TEMP_PREFIX)) temp.delete()
        }
        file.delete()
        return true
    }
}
