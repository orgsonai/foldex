// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import androidx.exifinterface.media.ExifInterface
import com.zerotoship.foldex.ui.imageedit.model.EditSize
import com.zerotoship.foldex.ui.imageedit.model.MetadataPolicy
import java.io.File

/** 新しく符号化した画像に、選んだ撮影情報だけを書き戻す。 */
internal object ExifTransfer {
    /** 引き継ぐ撮影情報。GPS は [LOCATION_TAGS] で別に扱う。 */
    val CAPTURE_TAGS = arrayOf(
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

    val LOCATION_TAGS = arrayOf(
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_VERSION_ID,
        ExifInterface.TAG_GPS_SATELLITES,
        ExifInterface.TAG_GPS_STATUS,
        ExifInterface.TAG_GPS_MEASURE_MODE,
        ExifInterface.TAG_GPS_DOP,
        ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_TRACK_REF,
        ExifInterface.TAG_GPS_TRACK,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LATITUDE,
        ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LONGITUDE,
        ExifInterface.TAG_GPS_DEST_BEARING_REF,
        ExifInterface.TAG_GPS_DEST_BEARING,
        ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
        ExifInterface.TAG_GPS_DEST_DISTANCE,
        ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_DIFFERENTIAL,
        ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
    )


    /** ICC・XMP・MakerNote・サムネイル・古い寸法等を元ファイルからコピーしない。 */
    fun selectAttributes(policy: MetadataPolicy, read: (String) -> String?): Map<String, String> {
        if (policy == MetadataPolicy.SHARE) return emptyMap()
        val tags = if (policy == MetadataPolicy.KEEP_CAPTURE) CAPTURE_TAGS + LOCATION_TAGS else CAPTURE_TAGS
        return tags.mapNotNull { tag -> read(tag)?.let { tag to it } }.toMap()
    }

    data class Capture(val attributes: Map<String, String>, val readable: Boolean)

    fun read(from: File, policy: MetadataPolicy): Capture {
        if (policy == MetadataPolicy.SHARE) return Capture(emptyMap(), true)
        return try {
            val source = ExifInterface(from.absolutePath)
            Capture(selectAttributes(policy, source::getAttribute), true)
        } catch (_: Exception) {
            Capture(emptyMap(), false)
        }
    }

    /** 引数 to は必ず Bitmap から新たに符号化した一時出力。元ファイルへは適用しない。 */
    fun apply(capture: Capture, to: File, policy: MetadataPolicy, size: EditSize): Boolean = try {
        val destination = ExifInterface(to.absolutePath)
        val selected = selectAttributes(policy, capture.attributes::get)
        selected.forEach { (tag, value) -> destination.setAttribute(tag, value) }
        destination.setAttribute(ExifInterface.TAG_ORIENTATION, "1")
        destination.setAttribute(ExifInterface.TAG_COLOR_SPACE, "1") // sRGB で描画済み。
        destination.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, size.width.toString())
        destination.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, size.height.toString())
        destination.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, size.width.toString())
        destination.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, size.height.toString())
        destination.saveAttributes()
        val verified = ExifInterface(to.absolutePath)
        capture.readable && selected.all { (tag, value) -> verified.getAttribute(tag) == value } &&
            verified.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0) == 1 &&
            (policy == MetadataPolicy.KEEP_CAPTURE || LOCATION_TAGS.none { verified.getAttribute(it) != null }) &&
            (policy != MetadataPolicy.SHARE || CAPTURE_TAGS.none { verified.getAttribute(it) != null })
    } catch (_: Exception) {
        false
    }

    fun hasLocation(file: File): Boolean = try {
        val exif = ExifInterface(file.absolutePath)
        LOCATION_TAGS.any { exif.getAttribute(it) != null }
    } catch (_: Exception) {
        false
    }
}
