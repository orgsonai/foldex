// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.common

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import com.zerotoship.foldex.R
import com.zerotoship.foldex.core.model.FileNode
import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.core.model.NodeType
import com.zerotoship.foldex.core.model.filetype.Category
import com.zerotoship.foldex.core.model.filetype.FileTypeRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** プロパティ 1 行分。 */
data class DetailRow(
    val label: String,
    val value: String,
    /** パスやハッシュのように等幅で見たいもの。 */
    val mono: Boolean = false,
)

/** 見出し付きのまとまり。[title] が null なら見出しを出さない。 */
data class DetailSection(val title: String?, val rows: List<DetailRow>)

/**
 * プロパティに出す情報を集める。
 *
 * 基本情報 ([basicSections]) は [FileNode] だけで作れるので即座に出す。
 * 解像度や EXIF、フォルダの合計サイズは実体を読む必要があって時間がかかるので、
 * [loadExtraSections] を別途 IO で回して後から足す。
 *
 * 実体を読む処理はローカルファイルにしか効かない。リモート (SMB/SFTP 等) や SAF は
 * ダウンロードが要るので、ここでは基本情報だけにしている。
 *
 * 見出しやラベルは `strings.xml` から引くので [Context] を受け取る。数値そのものの
 * 変換 ([megaPixels] や [shutterText] 等) は言語に依存しないので Context を取らない。
 */
object FileDetails {

    /** ダイアログを開いた瞬間に出せる情報。 */
    fun basicSections(context: Context, node: FileNode): List<DetailSection> {
        val rows = buildList {
            add(DetailRow(context.getString(R.string.prop_name), node.name))
            add(DetailRow(context.getString(R.string.prop_kind), typeLabel(context, node)))
            add(DetailRow(context.getString(R.string.prop_location), locationOf(node), mono = true))
            if (node.type == NodeType.FILE) {
                add(
                    DetailRow(
                        context.getString(R.string.prop_size),
                        context.getString(
                            R.string.prop_size_value,
                            formatBytes(node.size),
                            "%,d".format(node.size),
                        ),
                    ),
                )
                val ext = node.extension
                if (ext.isNotEmpty() && ext != node.name) {
                    add(
                        DetailRow(
                            context.getString(R.string.prop_extension),
                            context.getString(R.string.prop_extension_value, ext.lowercase()),
                        ),
                    )
                }
                mimeOf(node)?.let {
                    add(DetailRow(context.getString(R.string.prop_mime_type), it, mono = true))
                }
            }
            node.lastModified?.toEpochMilliseconds()?.let {
                add(DetailRow(context.getString(R.string.prop_modified), timestamp().format(Date(it))))
            }
            add(DetailRow(context.getString(R.string.prop_permissions), permissionText(context, node)))
            if (node.isHidden) {
                add(
                    DetailRow(
                        context.getString(R.string.prop_attributes),
                        context.getString(R.string.prop_hidden),
                    ),
                )
            }
        }
        return listOf(DetailSection(null, rows))
    }

    /**
     * 実体を読まないと分からない情報。ローカル以外や、読めなかった場合は空を返す。
     * 呼び出し側はこれを基本情報の後ろに足すだけでよい。
     */
    suspend fun loadExtraSections(context: Context, node: FileNode): List<DetailSection> =
        withContext(Dispatchers.IO) {
            val path = (node.uri as? FileUri.Local)?.absolutePath ?: return@withContext emptyList()
            val file = File(path)
            if (!file.exists()) return@withContext emptyList()

            try {
                when {
                    node.type == NodeType.DIRECTORY -> listOfNotNull(folderSection(context, file))
                    else -> when (FileTypeRegistry.categorize(node.name)) {
                        Category.IMAGE -> listOfNotNull(
                            imageSection(context, path),
                            exifSection(context, path),
                        )
                        Category.VIDEO, Category.AUDIO -> listOfNotNull(mediaSection(context, path))
                        else -> emptyList()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }

    /** ハッシュは大きいファイルだと重いので、ユーザーが押したときだけ計算する。 */
    suspend fun computeHashes(context: Context, node: FileNode): List<DetailRow> =
        withContext(Dispatchers.IO) {
            val path = (node.uri as? FileUri.Local)?.absolutePath ?: return@withContext emptyList()
            val file = File(path)
            if (!file.isFile) return@withContext emptyList()
            try {
                val md5 = MessageDigest.getInstance("MD5")
                val sha = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        md5.update(buf, 0, n)
                        sha.update(buf, 0, n)
                    }
                }
                listOf(
                    DetailRow(context.getString(R.string.prop_md5), md5.digest().toHex(), mono = true),
                    DetailRow(context.getString(R.string.prop_sha256), sha.digest().toHex(), mono = true),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                listOf(
                    DetailRow(
                        context.getString(R.string.prop_hash_failed_label),
                        context.getString(R.string.prop_hash_failed, e.javaClass.simpleName),
                    ),
                )
            }
        }

    // --- 個別のセクション ---

    private fun folderSection(context: Context, dir: File): DetailSection {
        var files = 0
        var dirs = 0
        var bytes = 0L
        dir.walkTopDown().forEach {
            when {
                it == dir -> Unit
                it.isDirectory -> dirs++
                else -> { files++; bytes += it.length() }
            }
        }
        fun count(n: Int) = context.resources.getQuantityString(R.plurals.prop_item_count_value, n, n)
        return DetailSection(
            context.getString(R.string.prop_folder_section),
            listOf(
                DetailRow(context.getString(R.string.prop_file_count), count(files)),
                DetailRow(context.getString(R.string.prop_folder_count), count(dirs)),
                DetailRow(
                    context.getString(R.string.prop_total_size),
                    context.getString(R.string.prop_size_value, formatBytes(bytes), "%,d".format(bytes)),
                ),
            ),
        )
    }

    private fun imageSection(context: Context, path: String): DetailSection? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) return null
        val rows = buildList {
            add(
                DetailRow(
                    context.getString(R.string.prop_resolution),
                    context.getString(R.string.prop_resolution_value, w, h),
                ),
            )
            add(DetailRow(context.getString(R.string.prop_pixels), megaPixels(context, w, h)))
            aspectRatio(w, h)?.let {
                add(DetailRow(context.getString(R.string.prop_aspect_ratio), it))
            }
            opts.outMimeType?.let {
                add(DetailRow(context.getString(R.string.prop_format), it, mono = true))
            }
        }
        return DetailSection(context.getString(R.string.prop_image_section), rows)
    }

    private fun exifSection(context: Context, path: String): DetailSection? {
        val exif = runCatching { ExifInterface(path) }.getOrNull() ?: return null
        val rows = buildList {
            fun row(labelRes: Int, value: String, mono: Boolean = false) =
                add(DetailRow(context.getString(labelRes), value, mono))

            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?.let { row(R.string.prop_date_taken, it) }
            val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
            val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
            listOfNotNull(make, model).filter { it.isNotEmpty() }
                .takeIf { it.isNotEmpty() }
                ?.let { row(R.string.prop_camera, it.joinToString(" ")) }
            exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.takeIf { it.isNotBlank() }
                ?.let { row(R.string.prop_lens, it) }
            exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.takeIf { it.isNotBlank() }
                ?.let { row(R.string.prop_aperture, context.getString(R.string.prop_aperture_value, it)) }
            exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()
                ?.let { row(R.string.prop_shutter, shutterText(context, it)) }
            exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.takeIf { it.isNotBlank() }
                ?.let { row(R.string.prop_iso, it) }
            exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { raw ->
                fractionToDouble(raw)?.let {
                    row(R.string.prop_focal_length, context.getString(R.string.prop_focal_length_value, it))
                }
            }
            orientationRes(
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED),
            )?.let { row(R.string.prop_orientation, context.getString(it)) }
            exif.latLong?.let { (lat, lon) ->
                row(R.string.prop_location_taken, "%.6f, %.6f".format(lat, lon), mono = true)
            }
        }
        return if (rows.isEmpty()) null else DetailSection(context.getString(R.string.prop_exif_section), rows)
    }

    private fun mediaSection(context: Context, path: String): DetailSection? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(path)
            fun meta(key: Int): String? = mmr.extractMetadata(key)?.takeIf { it.isNotBlank() }
            val rows = buildList {
                fun row(labelRes: Int, value: String, mono: Boolean = false) =
                    add(DetailRow(context.getString(labelRes), value, mono))

                meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?.let { row(R.string.prop_duration, formatDuration(it)) }
                val w = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                val h = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                if (w != null && h != null) {
                    row(R.string.prop_resolution, context.getString(R.string.prop_resolution_value, w, h))
                }
                meta(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
                    ?.let { row(R.string.prop_frame_rate, context.getString(R.string.prop_frame_rate_value, it)) }
                meta(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
                    ?.let {
                        row(
                            R.string.prop_bitrate,
                            context.getString(R.string.prop_bitrate_value, "%,d".format(it / 1000)),
                        )
                    }
                meta(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                    ?.let { row(R.string.prop_format, it, mono = true) }
                meta(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { row(R.string.prop_track_title, it) }
                meta(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { row(R.string.prop_artist, it) }
                meta(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { row(R.string.prop_album, it) }
            }
            if (rows.isEmpty()) null else DetailSection(context.getString(R.string.prop_media_section), rows)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    // --- 表示用の細かい変換 ---

    private fun typeLabel(context: Context, node: FileNode): String {
        if (node.type == NodeType.DIRECTORY) return context.getString(R.string.kind_folder)
        val res = when (FileTypeRegistry.categorize(node.name)) {
            Category.IMAGE -> R.string.kind_image
            Category.VIDEO -> R.string.kind_video
            Category.AUDIO -> R.string.kind_audio
            Category.TEXT -> R.string.kind_text
            Category.MARKDOWN -> R.string.kind_markdown
            Category.HTML -> R.string.kind_html
            Category.PDF -> R.string.kind_pdf
            Category.ARCHIVE -> R.string.kind_archive
            Category.OFFICE -> R.string.kind_office
            Category.APK -> R.string.kind_apk
            Category.ISO -> R.string.kind_iso
            Category.BINARY -> R.string.kind_binary
            Category.UNKNOWN -> R.string.kind_file
        }
        return context.getString(res)
    }

    private fun locationOf(node: FileNode): String = when (val u = node.uri) {
        is FileUri.Local -> u.absolutePath
        is FileUri.Saf -> u.toStorageString()
        is FileUri.Remote -> "${u.protocol.name.lowercase()}://${u.connectionId}${u.path}"
    }

    private fun mimeOf(node: FileNode): String? =
        node.mimeType?.takeIf { it.isNotBlank() } ?: FileTypeRegistry.mimeTypeFor(node.name)

    /** `rwx` 表記。読めるだけなら `r--`。 */
    private fun permissionText(context: Context, node: FileNode): String {
        val p = node.permissions
        val flags = buildString {
            append(if (p.readable) 'r' else '-')
            append(if (p.writable) 'w' else '-')
            append(if (p.executable) 'x' else '-')
        }
        val words = buildList {
            if (p.readable) add(context.getString(R.string.perm_read))
            if (p.writable) add(context.getString(R.string.perm_write))
            if (p.executable) add(context.getString(R.string.perm_execute))
        }
        val detail = if (words.isEmpty()) {
            context.getString(R.string.perm_none)
        } else {
            words.joinToString(context.getString(R.string.perm_separator))
        }
        return context.getString(R.string.perm_value, flags, detail)
    }

    /** 総画素数。言語に依存しない。 */
    internal fun pixelCount(w: Int, h: Int): Long = w.toLong() * h

    /** メガピクセル値。1.0 以上ならメガピクセル表記にする。 */
    internal fun megaPixelValue(w: Int, h: Int): Double = pixelCount(w, h) / 1_000_000.0

    private fun megaPixels(context: Context, w: Int, h: Int): String {
        val mp = megaPixelValue(w, h)
        return if (mp >= 1) {
            context.getString(R.string.prop_megapixels, mp)
        } else {
            context.getString(R.string.prop_pixels_value, "%,d".format(pixelCount(w, h)))
        }
    }

    /** よくある比 (16:9 等) に丸めて出す。当てはまらなければ約分した比。言語に依存しない。 */
    internal fun aspectRatio(w: Int, h: Int): String? {
        if (w <= 0 || h <= 0) return null
        val ratio = w.toDouble() / h
        val known = listOf(
            "16:9" to 16.0 / 9, "9:16" to 9.0 / 16, "4:3" to 4.0 / 3, "3:4" to 3.0 / 4,
            "3:2" to 3.0 / 2, "2:3" to 2.0 / 3, "1:1" to 1.0, "21:9" to 21.0 / 9,
        )
        known.firstOrNull { abs(it.second - ratio) < 0.01 }?.let { return it.first }
        val g = gcd(w, h)
        return "${w / g}:${h / g}"
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    /**
     * 1 秒未満のシャッター速度を `1/N` の N に直す (カメラの表記に合わせる)。
     * 1 秒以上や 0 以下は分数にしないので null。言語に依存しない。
     */
    internal fun shutterDenominator(seconds: Double): Int? =
        if (seconds <= 0 || seconds >= 1) null else Math.round(1 / seconds).toInt()

    private fun shutterText(context: Context, seconds: Double): String {
        shutterDenominator(seconds)?.let {
            return context.getString(R.string.prop_shutter_fraction, it)
        }
        return if (seconds <= 0) {
            context.getString(R.string.prop_unavailable)
        } else {
            context.getString(R.string.prop_shutter_seconds, seconds)
        }
    }

    /** EXIF は "24/1" のような分数で入っていることがある。言語に依存しない。 */
    internal fun fractionToDouble(raw: String): Double? {
        val parts = raw.split('/')
        return when (parts.size) {
            1 -> parts[0].toDoubleOrNull()
            2 -> {
                val n = parts[0].toDoubleOrNull() ?: return null
                val d = parts[1].toDoubleOrNull() ?: return null
                if (d == 0.0) null else n / d
            }
            else -> null
        }
    }

    private fun orientationRes(value: Int): Int? = when (value) {
        ExifInterface.ORIENTATION_NORMAL -> R.string.orientation_normal
        ExifInterface.ORIENTATION_ROTATE_90 -> R.string.orientation_rotate_90
        ExifInterface.ORIENTATION_ROTATE_180 -> R.string.orientation_rotate_180
        ExifInterface.ORIENTATION_ROTATE_270 -> R.string.orientation_rotate_270
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> R.string.orientation_flip_horizontal
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> R.string.orientation_flip_vertical
        else -> null
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /** `1:23:45` 形式。区切りは言語を問わず同じなのでリソース化しない。 */
    fun formatDuration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** `1.5 GB` 形式。単位記号は各国共通なのでリソース化しない。 */
    fun formatBytes(b: Long): String {
        if (b <= 0) return "0 B"
        val u = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = 0
        while (v >= 1024 && i < u.lastIndex) { v /= 1024; i++ }
        return String.format(Locale.US, if (i == 0) "%.0f %s" else "%.1f %s", v, u[i])
    }

    private fun timestamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
}
