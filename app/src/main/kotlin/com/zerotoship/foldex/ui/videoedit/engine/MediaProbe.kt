// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.engine

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.zerotoship.foldex.core.model.filetype.Category
import com.zerotoship.foldex.core.model.filetype.FileTypeRegistry
import com.zerotoship.foldex.ui.videoedit.model.AssetKind
import com.zerotoship.foldex.ui.videoedit.model.MediaAsset
import java.io.File

/**
 * 素材の長さ・寸法・音声の有無を調べる。ファイルの読み取りを伴うので IO スレッドから呼ぶこと。
 *
 * 試作版では MediaMetadataRetriever の値をそのまま使う。実際のフレーム時刻やキーフレーム位置の
 * 索引 (設計書 §6-4) はまだ作らない。
 */
internal object MediaProbe {

    data class Result(val name: String, val asset: MediaAsset?)

    fun probe(context: Context, source: String, knownName: String?, id: String): Result {
        val uri = if (source.startsWith("content://")) Uri.parse(source) else null
        val name = knownName
            ?: uri?.let { queryName(context, it) ?: it.lastPathSegment.orEmpty() }
            ?: File(source).name
        val mime = uri?.let { runCatching { context.contentResolver.getType(it) }.getOrNull() }
            ?: FileTypeRegistry.mimeTypeFor(name)
        val kind = kindOf(mime, name) ?: return Result(name, null)
        val asset = runCatching {
            if (kind == AssetKind.IMAGE) probeImage(context, source, uri, name, id) else probeMedia(context, source, uri, name, id, kind)
        }.getOrNull()
        // 書き出しの直前に比べ、差し替えられた素材で黙って書き出さないために記録しておく。
        return Result(name, asset?.copy(stamp = SourceStamps.read(context, source)))
    }

    private fun kindOf(mime: String?, name: String): AssetKind? = when {
        mime?.startsWith("video/") == true -> AssetKind.VIDEO
        mime?.startsWith("image/") == true -> AssetKind.IMAGE
        mime?.startsWith("audio/") == true -> AssetKind.AUDIO
        else -> when (FileTypeRegistry.categorize(name)) {
            Category.VIDEO -> AssetKind.VIDEO
            Category.IMAGE -> AssetKind.IMAGE
            Category.AUDIO -> AssetKind.AUDIO
            else -> null
        }
    }

    private fun probeMedia(context: Context, source: String, uri: Uri?, name: String, id: String, kind: AssetKind): MediaAsset? {
        val retriever = MediaMetadataRetriever()
        try {
            if (uri != null) retriever.setDataSource(context, uri) else retriever.setDataSource(source)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (durationMs <= 0L) return null
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            // 縦に撮った動画は横長の画素に回転情報が付いているので、表示の向きに入れ替える。
            if (rotation % 180 != 0) {
                val swap = width
                width = height
                height = swap
            }
            return MediaAsset(
                id = id,
                // 拡張子が動画でも映像トラックが無ければ音声として扱う。
                kind = if (kind == AssetKind.VIDEO && !hasVideo) AssetKind.AUDIO else kind,
                source = source,
                name = name,
                durationUs = durationMs * 1_000L,
                widthPx = width,
                heightPx = height,
                hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun probeImage(context: Context, source: String, uri: Uri?, name: String, id: String): MediaAsset? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val input = (if (uri != null) context.contentResolver.openInputStream(uri) else File(source).inputStream()) ?: return null
        input.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return MediaAsset(id, AssetKind.IMAGE, source, name, durationUs = 0L, widthPx = options.outWidth, heightPx = options.outHeight)
    }

    private fun queryName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
}
