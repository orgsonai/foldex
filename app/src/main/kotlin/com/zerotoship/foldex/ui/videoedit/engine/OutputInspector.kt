// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.engine

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import com.zerotoship.foldex.ui.videoedit.plan.OutputFacts
import java.io.File

/** 書き出したファイルを読み戻して確かめる。ファイルの読み取りを伴うので IO スレッドから呼ぶこと。 */
internal object OutputInspector {

    private const val PROBE_EDGE_PX = 160
    private const val END_MARGIN_US = 500_000L

    /** トラックの形式・長さ・表示の向きの寸法を読む。読めないファイルは例外。 */
    fun inspect(file: File): OutputFacts {
        var videoMime: String? = null
        var audioMime: String? = null
        var videoDurationUs = -1L
        var audioDurationUs = -1L
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L
                if (mime.startsWith("video/") && videoMime == null) {
                    videoMime = mime
                    videoDurationUs = duration
                } else if (mime.startsWith("audio/") && audioMime == null) {
                    audioMime = mime
                    audioDurationUs = duration
                }
            }
        } finally {
            extractor.release()
        }

        var width = 0
        var height = 0
        var containerDurationUs = 0L
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            containerDurationUs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1_000L
            if (videoMime != null) {
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                // 縦長の出力は互換性のため横長の画素と回転情報で保存される。表示の向きで比べる。
                if (rotation % 180 != 0) {
                    val swap = width
                    width = height
                    height = swap
                }
            }
        } finally {
            runCatching { retriever.release() }
        }

        val durationUs = when {
            videoDurationUs > 0L -> videoDurationUs
            audioDurationUs > 0L -> audioDurationUs
            else -> containerDurationUs
        }
        return OutputFacts(width, height, videoMime, audioMime, durationUs, file.length())
    }

    /** 先頭・中間・末尾近くのコマを実際に復号できるか。ヘッダーだけ正しい壊れた出力を保存しないため。 */
    fun framesDecodable(file: File, durationUs: Long): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val times = listOf(0L, durationUs / 2, (durationUs - END_MARGIN_US).coerceAtLeast(0L))
            times.all { timeUs ->
                val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, PROBE_EDGE_PX, PROBE_EDGE_PX)
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
                frame?.recycle()
                frame != null
            }
        } catch (_: RuntimeException) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }
}
