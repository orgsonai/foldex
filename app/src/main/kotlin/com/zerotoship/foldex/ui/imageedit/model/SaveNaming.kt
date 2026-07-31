// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.model

/**
 * 保存先のファイル名を決める。純粋関数にしてあるのでユニットテストで確かめられる。
 *
 * 既定は別名保存 (`photo.jpg` → `photo_edited.jpg`)。原本を壊さないことを優先する。
 */
object SaveNaming {

    const val EDITED_SUFFIX: String = "_edited"

    /** 編集後の既定ファイル名。拡張子は出力形式に合わせて付け替える。 */
    fun editedName(originalName: String, format: ImageFormat): String {
        val base = originalName.substringBeforeLast('.', originalName)
            .removeSuffix(EDITED_SUFFIX)
            .ifEmpty { "image" }
        return "$base$EDITED_SUFFIX.${format.extension}"
    }

    /** 元の名前のまま拡張子だけ出力形式に合わせる (上書き保存の可否判定に使う)。 */
    fun sameNameWithFormat(originalName: String, format: ImageFormat): String {
        val base = originalName.substringBeforeLast('.', originalName).ifEmpty { "image" }
        return "$base.${format.extension}"
    }

    /**
     * [desired] が既にあるなら `photo_edited2.jpg` `photo_edited3.jpg` … と番号を足す。
     * @param exists 同名が存在するかを判定する関数 (ローカルなら File.exists、リモートなら stat)
     */
    fun uniqueName(desired: String, exists: (String) -> Boolean): String {
        if (!exists(desired)) return desired
        val base = desired.substringBeforeLast('.', desired)
        val ext = desired.substringAfterLast('.', "")
        var n = 2
        while (n < MAX_ATTEMPTS) {
            val candidate = if (ext.isEmpty()) "$base$n" else "$base$n.$ext"
            if (!exists(candidate)) return candidate
            n++
        }
        return desired
    }

    private const val MAX_ATTEMPTS = 1000
}
