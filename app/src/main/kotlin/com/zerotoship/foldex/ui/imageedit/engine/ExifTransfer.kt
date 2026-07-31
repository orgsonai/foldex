// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * 元画像の EXIF を編集後のファイルへ引き継ぐ。
 *
 * Bitmap を経由すると EXIF は消えるので、保存後に書き戻す。方針:
 *  - 撮影情報 (日時・機種・レンズ・露出) は**引き継ぐ** — ユーザーのデータを黙って捨てない
 *  - GPS も既定で引き継ぐ。落としたいときは [keepLocation] = false (保存ダイアログのチェック)
 *  - Orientation は **1 に正規化**する。回転はすでに画素へ適用済みなので、
 *    元の向き情報を残すと二重に回ってしまう
 */
object ExifTransfer {

    /** 引き継ぐ撮影情報。GPS は [LOCATION_TAGS] で別に扱う。 */
    private val CAPTURE_TAGS = arrayOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_SCENE_CAPTURE_TYPE,
    )

    private val LOCATION_TAGS = arrayOf(
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
    )

    /**
     * [from] の EXIF を [to] へ書き戻す。失敗しても保存自体は成功扱いにしたいので
     * 例外は握りつぶす (PNG など書き込めない形式もあるため)。
     *
     * @return 書き戻せたら true
     */
    fun copy(from: File, to: File, keepLocation: Boolean): Boolean = runCatching {
        if (!from.exists() || !to.exists()) return false
        val src = ExifInterface(from.absolutePath)
        val dst = ExifInterface(to.absolutePath)

        CAPTURE_TAGS.forEach { tag ->
            src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
        }
        if (keepLocation) {
            LOCATION_TAGS.forEach { tag ->
                src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
            }
        }
        // 回転は画素に適用済み。ここを引き継ぐと二重に回る。
        dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        dst.saveAttributes()
        true
    }.getOrDefault(false)

    /** 元画像が位置情報を持っているか (保存ダイアログでチェックを出すかの判定)。 */
    fun hasLocation(file: File): Boolean = runCatching {
        if (!file.exists()) return false
        val exif = ExifInterface(file.absolutePath)
        LOCATION_TAGS.any { exif.getAttribute(it) != null }
    }.getOrDefault(false)
}
