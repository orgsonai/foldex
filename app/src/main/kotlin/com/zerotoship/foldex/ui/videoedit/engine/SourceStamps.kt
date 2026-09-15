// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.engine

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.zerotoship.foldex.ui.videoedit.model.AssetStamp
import java.io.File

/** 素材のサイズと更新時刻を読む。数 GB の動画でも一瞬で終わるよう、中身は読まない。IO スレッドから呼ぶこと。 */
internal object SourceStamps {

    /** 読めない (削除・移動・権限の失効) ときは null。 */
    fun read(context: Context, source: String): AssetStamp? =
        if (source.startsWith("content://")) readContent(context, Uri.parse(source)) else readFile(File(source))

    private fun readFile(file: File): AssetStamp? =
        if (file.isFile && file.canRead()) AssetStamp(file.length(), file.lastModified()) else null

    private fun readContent(context: Context, uri: Uri): AssetStamp? = try {
        // 開けるかどうかで、権限の失効や削除も見分ける。
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            AssetStamp(descriptor.statSize, modifiedMs(context, uri))
        }
    } catch (_: Exception) {
        null
    }

    private fun modifiedMs(context: Context, uri: Uri): Long = try {
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else -1L
        } ?: -1L
    } catch (_: Exception) {
        -1L
    }
}
