// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.viewer

import android.media.MediaMetadataRetriever
import android.net.Uri
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.Buffer
import okio.FileSystem
import java.io.File

/**
 * 音声ファイルのモデル。これを Coil の `data` に渡すと [AudioArtFetcher] が埋め込みアルバムアートを返す。
 * 通常の画像/動画ファイルと区別するためのラッパー ([source] は [File] か [Uri])。
 */
data class AudioArt(val source: Any)

/** 音声ファイルの埋め込みアルバムアートを `MediaMetadataRetriever` で取り出す Coil フェッチャ。 */
class AudioArtFetcher(
    private val art: AudioArt,
    private val context: PlatformContext,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val retriever = MediaMetadataRetriever()
        return try {
            when (val s = art.source) {
                is File -> retriever.setDataSource(s.absolutePath)
                is Uri -> retriever.setDataSource(context, s)
                else -> return null
            }
            val bytes = retriever.embeddedPicture ?: return null
            SourceFetchResult(
                source = ImageSource(source = Buffer().apply { write(bytes) }, fileSystem = FileSystem.SYSTEM),
                mimeType = null,
                dataSource = DataSource.DISK,
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    class Factory : Fetcher.Factory<AudioArt> {
        override fun create(data: AudioArt, options: Options, imageLoader: ImageLoader): Fetcher =
            AudioArtFetcher(data, options.context)
    }
}
