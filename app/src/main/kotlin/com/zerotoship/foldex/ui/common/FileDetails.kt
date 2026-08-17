// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.common

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
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
 */
object FileDetails {

    /** ダイアログを開いた瞬間に出せる情報。 */
    fun basicSections(node: FileNode): List<DetailSection> {
        val rows = buildList {
            add(DetailRow("名前", node.name))
            add(DetailRow("種類", typeLabel(node)))
            add(DetailRow("場所", locationOf(node), mono = true))
            if (node.type == NodeType.FILE) {
                add(DetailRow("サイズ", "${formatBytes(node.size)}  (${"%,d".format(node.size)} B)"))
                val ext = node.extension
                if (ext.isNotEmpty() && ext != node.name) {
                    add(DetailRow("拡張子", ".${ext.lowercase()}"))
                }
                mimeOf(node)?.let { add(DetailRow("MIME タイプ", it, mono = true)) }
            }
            node.lastModified?.toEpochMilliseconds()?.let {
                add(DetailRow("更新日時", TIMESTAMP.format(Date(it))))
            }
            add(DetailRow("権限", permissionText(node)))
            if (node.isHidden) add(DetailRow("属性", "隠しファイル"))
        }
        return listOf(DetailSection(null, rows))
    }

    /**
     * 実体を読まないと分からない情報。ローカル以外や、読めなかった場合は空を返す。
     * 呼び出し側はこれを基本情報の後ろに足すだけでよい。
     */
    suspend fun loadExtraSections(node: FileNode): List<DetailSection> = withContext(Dispatchers.IO) {
        val path = (node.uri as? FileUri.Local)?.absolutePath ?: return@withContext emptyList()
        val file = File(path)
        if (!file.exists()) return@withContext emptyList()

        try {
            when {
                node.type == NodeType.DIRECTORY -> listOfNotNull(folderSection(file))
                else -> when (FileTypeRegistry.categorize(node.name)) {
                    Category.IMAGE -> listOfNotNull(imageSection(path), exifSection(path))
                    Category.VIDEO, Category.AUDIO -> listOfNotNull(mediaSection(path))
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
    suspend fun computeHashes(node: FileNode): List<DetailRow> = withContext(Dispatchers.IO) {
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
                DetailRow("MD5", md5.digest().toHex(), mono = true),
                DetailRow("SHA-256", sha.digest().toHex(), mono = true),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            listOf(DetailRow("ハッシュ", "計算できませんでした (${e.javaClass.simpleName})"))
        }
    }

    // --- 個別のセクション ---

    private fun folderSection(dir: File): DetailSection? {
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
        return DetailSection(
            "中身",
            listOf(
                DetailRow("ファイル数", "%,d 個".format(files)),
                DetailRow("フォルダ数", "%,d 個".format(dirs)),
                DetailRow("合計サイズ", "${formatBytes(bytes)}  (${"%,d".format(bytes)} B)"),
            ),
        )
    }

    private fun imageSection(path: String): DetailSection? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) return null
        val rows = buildList {
            add(DetailRow("解像度", "$w × $h"))
            add(DetailRow("画素数", megaPixels(w, h)))
            aspectRatio(w, h)?.let { add(DetailRow("縦横比", it)) }
            opts.outMimeType?.let { add(DetailRow("形式", it, mono = true)) }
        }
        return DetailSection("画像", rows)
    }

    private fun exifSection(path: String): DetailSection? {
        val exif = runCatching { ExifInterface(path) }.getOrNull() ?: return null
        val rows = buildList {
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?.let { add(DetailRow("撮影日時", it)) }
            val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
            val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
            listOfNotNull(make, model).filter { it.isNotEmpty() }
                .takeIf { it.isNotEmpty() }
                ?.let { add(DetailRow("カメラ", it.joinToString(" "))) }
            exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.takeIf { it.isNotBlank() }
                ?.let { add(DetailRow("レンズ", it)) }
            exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.takeIf { it.isNotBlank() }
                ?.let { add(DetailRow("F 値", "f/$it")) }
            exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()
                ?.let { add(DetailRow("シャッター速度", shutterText(it))) }
            exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.takeIf { it.isNotBlank() }
                ?.let { add(DetailRow("ISO 感度", it)) }
            exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { raw ->
                fractionToDouble(raw)?.let { add(DetailRow("焦点距離", "%.0f mm".format(it))) }
            }
            orientationText(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED))
                ?.let { add(DetailRow("向き", it)) }
            exif.latLong?.let { (lat, lon) ->
                add(DetailRow("撮影場所", "%.6f, %.6f".format(lat, lon), mono = true))
            }
        }
        return if (rows.isEmpty()) null else DetailSection("撮影情報 (EXIF)", rows)
    }

    private fun mediaSection(path: String): DetailSection? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(path)
            fun meta(key: Int): String? = mmr.extractMetadata(key)?.takeIf { it.isNotBlank() }
            val rows = buildList {
                meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?.let { add(DetailRow("再生時間", formatDuration(it))) }
                val w = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val h = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                if (w != null && h != null) add(DetailRow("解像度", "$w × $h"))
                meta(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
                    ?.let { add(DetailRow("フレームレート", "%.2f fps".format(it))) }
                meta(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
                    ?.let { add(DetailRow("ビットレート", "%,d kbps".format(it / 1000))) }
                meta(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                    ?.let { add(DetailRow("形式", it, mono = true)) }
                meta(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { add(DetailRow("タイトル", it)) }
                meta(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { add(DetailRow("アーティスト", it)) }
                meta(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { add(DetailRow("アルバム", it)) }
            }
            if (rows.isEmpty()) null else DetailSection("メディア情報", rows)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    // --- 表示用の細かい変換 ---

    private fun typeLabel(node: FileNode): String {
        if (node.type == NodeType.DIRECTORY) return "フォルダ"
        return when (FileTypeRegistry.categorize(node.name)) {
            Category.IMAGE -> "画像"
            Category.VIDEO -> "動画"
            Category.AUDIO -> "音声"
            Category.TEXT -> "テキスト"
            Category.MARKDOWN -> "Markdown"
            Category.HTML -> "HTML"
            Category.PDF -> "PDF"
            Category.ARCHIVE -> "書庫"
            Category.OFFICE -> "Office 文書"
            Category.APK -> "Android アプリ"
            Category.ISO -> "ディスクイメージ"
            Category.BINARY -> "バイナリ"
            Category.UNKNOWN -> "ファイル"
        }
    }

    private fun locationOf(node: FileNode): String = when (val u = node.uri) {
        is FileUri.Local -> u.absolutePath
        is FileUri.Saf -> u.toStorageString()
        is FileUri.Remote -> "${u.protocol.name.lowercase()}://${u.connectionId}${u.path}"
    }

    private fun mimeOf(node: FileNode): String? =
        node.mimeType?.takeIf { it.isNotBlank() } ?: FileTypeRegistry.mimeTypeFor(node.name)

    /** `rwx` 表記。読めるだけなら `r--`。 */
    private fun permissionText(node: FileNode): String {
        val p = node.permissions
        val s = buildString {
            append(if (p.readable) 'r' else '-')
            append(if (p.writable) 'w' else '-')
            append(if (p.executable) 'x' else '-')
        }
        val words = buildList {
            if (p.readable) add("読み取り")
            if (p.writable) add("書き込み")
            if (p.executable) add("実行")
        }
        return if (words.isEmpty()) "$s (権限なし)" else "$s (${words.joinToString("・")})"
    }

    internal fun megaPixels(w: Int, h: Int): String {
        val mp = w.toLong() * h / 1_000_000.0
        return if (mp >= 1) "約 %.1f メガピクセル".format(mp) else "%,d ピクセル".format(w.toLong() * h)
    }

    /** よくある比 (16:9 等) に丸めて出す。当てはまらなければ約分した比。 */
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

    /** 1 秒未満は 1/125 のような分数で出す (カメラの表記に合わせる)。 */
    internal fun shutterText(seconds: Double): String = when {
        seconds <= 0 -> "-"
        seconds >= 1 -> "%.1f 秒".format(seconds)
        else -> "1/${Math.round(1 / seconds)} 秒"
    }

    /** EXIF は "24/1" のような分数で入っていることがある。 */
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

    private fun orientationText(value: Int): String? = when (value) {
        ExifInterface.ORIENTATION_NORMAL -> "そのまま"
        ExifInterface.ORIENTATION_ROTATE_90 -> "右に 90° 回転"
        ExifInterface.ORIENTATION_ROTATE_180 -> "180° 回転"
        ExifInterface.ORIENTATION_ROTATE_270 -> "左に 90° 回転"
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> "左右反転"
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> "上下反転"
        else -> null
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun formatDuration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun formatBytes(b: Long): String {
        if (b <= 0) return "0 B"
        val u = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = 0
        while (v >= 1024 && i < u.lastIndex) { v /= 1024; i++ }
        return String.format(Locale.US, if (i == 0) "%.0f %s" else "%.1f %s", v, u[i])
    }

    private val TIMESTAMP: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
}
