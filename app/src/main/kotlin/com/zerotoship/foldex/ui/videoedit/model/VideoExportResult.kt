// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.model

/** 書き出しが止まった理由。画面の案内は理由ごとに変える (設計書 §8-8)。 */
enum class VideoExportFailure {
    NOTHING_TO_EXPORT,
    NO_VIDEO,
    NO_AUDIO,
    TARGET_TOO_SMALL,
    UNSUPPORTED_PRESET,
    SOURCE_MISSING,
    SOURCE_CHANGED,
    NOT_ENOUGH_SPACE,
    UNSUPPORTED_FORMAT,
    DECODER_FAILED,
    ENCODER_UNSUPPORTED,
    ENCODER_FAILED,
    VIDEO_PROCESSING_FAILED,
    AUDIO_PROCESSING_FAILED,
    READ_FAILED,
    WRITE_FAILED,
    INVALID_OUTPUT,
    DESTINATION_EXISTS,
    TIME_LIMIT,
    /** 別の画面で書き出し中。 */
    BUSY,
    UNKNOWN,
    ;

    companion object {
        /**
         * Media3 の `ExportException.errorCode` を分類する。値は 1.11.1 の定数
         * (1000 番台 = その他、2000 = 読み込み、3000 = 復号、4000 = 符号化、5000 = 映像処理、6000 = 音声処理、7000 = 書き込み)。
         * Media3 に依存しない値で持ち、JVM テストで確かめる。
         */
        fun fromMedia3ErrorCode(code: Int): VideoExportFailure = when (code) {
            // ERROR_CODE_IO_FILE_NOT_FOUND / ERROR_CODE_IO_NO_PERMISSION
            2005, 2006 -> SOURCE_MISSING
            in 2000..2999 -> READ_FAILED
            // ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
            3003 -> UNSUPPORTED_FORMAT
            in 3000..3999 -> DECODER_FAILED
            // ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED
            4003 -> ENCODER_UNSUPPORTED
            in 4000..4999 -> ENCODER_FAILED
            in 5000..5999 -> VIDEO_PROCESSING_FAILED
            in 6000..6999 -> AUDIO_PROCESSING_FAILED
            in 7000..7999 -> WRITE_FAILED
            else -> UNKNOWN
        }
    }
}

/**
 * [detail] は案内の文に入れる値 (素材の名前・必要な空き容量)。
 * [code] は Media3 のエラー名で、問い合わせのときに見せる。
 */
data class VideoExportError(val reason: VideoExportFailure, val detail: String? = null, val code: String? = null)

/** 書き出した動画の既定の名前。画像編集と同じく `_edited`、重なれば `_edited2` `_edited3` … と番号を足す。 */
object VideoSaveNaming {
    const val EDITED_SUFFIX: String = "_edited"

    fun extension(audioOnly: Boolean): String = if (audioOnly) "m4a" else "mp4"

    fun mimeType(audioOnly: Boolean): String = if (audioOnly) "audio/mp4" else "video/mp4"

    /** 元の名前から拡張子と `_edited` を外した部分。空なら "video"。 */
    fun baseName(sourceName: String): String =
        sourceName.substringBeforeLast('.', sourceName).removeSuffix(EDITED_SUFFIX).ifBlank { "video" }

    fun candidate(baseName: String, extension: String, number: Int): String =
        if (number <= 1) "$baseName$EDITED_SUFFIX.$extension" else "$baseName$EDITED_SUFFIX$number.$extension"

    /** 書き出し名の元にする素材。最初のメインクリップ、無ければ最初の音声クリップ。 */
    fun sourceAsset(project: VideoProject): MediaAsset? =
        (project.mainTrack.firstOrNull()?.assetId ?: project.audioTrack.firstOrNull()?.assetId)?.let { project.assets[it] }

    /** 「場所を選んで書き出す」で最初に出す名前。 */
    fun suggestedName(project: VideoProject, audioOnly: Boolean): String =
        candidate(baseName(sourceAsset(project)?.name.orEmpty()), extension(audioOnly), 1)

    /**
     * 「このフォルダに書き出す」の保存先。メイン → 音声トラックの順で、最初の端末内の素材があるフォルダ。
     * content:// の素材しか無ければ null (場所を選んで保存する)。
     */
    fun localFolder(project: VideoProject): String? =
        (project.mainTrack.map { it.assetId } + project.audioTrack.map { it.assetId })
            .asSequence()
            .mapNotNull { project.assets[it]?.source }
            .firstOrNull { !it.startsWith("content://") }
            ?.substringBeforeLast('/', "")
            ?.ifEmpty { null }
}
