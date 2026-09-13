// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.ColorSpace
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.zerotoship.foldex.ui.imageedit.model.EditSize
import com.zerotoship.foldex.ui.imageedit.model.Transparency
import java.io.File
import kotlin.math.max

/**
 * 元画像を「必要な解像度だけ」デコードして貸し出す。
 *
 * 原寸を丸ごとメモリに載せない ([BitmapFactory.Options.inSampleSize] で間引く) のが要点。
 * inSampleSize 後の画素と EXIF/縮小の中間画像も RenderBudget に含める。
 * 貸出中だけ保持し、描画が終われば原寸・縮小版とも解放する。
 */
class ImageSource(private val file: File) {

    val transparency: Transparency by lazy {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outMimeType == "image/jpeg") Transparency.OPAQUE else Transparency.UNKNOWN
    }

    private val rawSize: EditSize? by lazy { readRawBounds() }

    /** EXIF の向きを適用した後の画素サイズ。 */
    val size: EditSize by lazy { readSizeWithOrientation() }

    private var cached: Bitmap? = null
    private var cachedLongEdge: Int = 0

    /**
     * 長辺が [maxLongEdge] 以下になる解像度でデコードして返す。
     * `null` を渡すと原寸。失敗したら null。
     *
     * 返した Bitmap は内部でキャッシュされる。呼び出し側で recycle しないこと。
     */
    @Synchronized
    private fun load(maxLongEdge: Int?): Bitmap? {
        val want = (maxLongEdge ?: size.longEdge).coerceIn(1, size.longEdge)
        cached?.let { if (cachedLongEdge >= want && !it.isRecycled) return it }
        release()
        val decoded = decode(want) ?: return null
        cached = decoded
        cachedLongEdge = max(decoded.width, decoded.height)
        return decoded
    }

    /** キャッシュを解放する。原寸を読んだ後など、メモリを戻したいときに呼ぶ。 */
    @Synchronized
    fun release() {
        cached?.takeIf { !it.isRecycled }?.recycle()
        cached = null
        cachedLongEdge = 0
    }

    /** 描画が終わるまで、別の解像度要求によるキャッシュの recycle を防ぐ。 */
    @Synchronized
    fun withBitmap(maxLongEdge: Int, draw: (Bitmap) -> Unit): Boolean {
        try {
            val bitmap = load(maxLongEdge) ?: return false
            draw(bitmap)
            return true
        } finally {
            release()
        }
    }

    private fun decode(maxLongEdge: Int): Bitmap? {
        val bounds = readRawBounds() ?: return null
        // 予算の計算後に別サイズのファイルへ差し替わった場合は再確保しない。
        if (bounds != rawSize) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(max(bounds.width, bounds.height), maxLongEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val raw = runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }.getOrNull()
            ?: return null
        // EXIF の向きを画素へ適用しておく。以降の処理は「見たままの向き」だけを考えればよくなる。
        // HDR gainmap を次の描画へ暗黙に持ち込まず、標準画質のベース画像を使う。
        if (Build.VERSION.SDK_INT >= 34 && raw.hasGainmap()) raw.setGainmap(null)
        val normalized = BitmapOps.toSrgb(raw)
        val rotated = applyExifOrientation(normalized)
        // inSampleSize は 2 のべき乗でしか間引けないので、まだ大きいことがある。
        // ここで目標の長辺へきっちり合わせる (拡大はしない)。
        val longEdge = max(rotated.width, rotated.height)
        if (longEdge <= maxLongEdge) return rotated
        val target = EditSize(rotated.width, rotated.height).fitLongEdge(maxLongEdge, allowUpscale = false)
        return try {
            BitmapOps.scale(rotated, target)
        } finally {
            rotated.recycle()
        }
    }

    private fun readRawBounds(): EditSize? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        return EditSize(opts.outWidth, opts.outHeight)
    }

    private fun readSizeWithOrientation(): EditSize {
        val raw = rawSize ?: EditSize(1, 1)
        return if (exifQuarterTurns() % 2 == 1) EditSize(raw.height, raw.width) else raw
    }

    /** EXIF の Orientation を 90° 単位の回数へ落とす (反転のみの向きは 0 扱い)。 */
    private fun exifQuarterTurns(): Int = runCatching {
        when (ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 1
            ExifInterface.ORIENTATION_ROTATE_180 -> 2
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 3
            else -> 0
        }
    }.getOrDefault(0)

    private fun applyExifOrientation(src: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        if (orientation == ExifInterface.ORIENTATION_NORMAL ||
            orientation == ExifInterface.ORIENTATION_UNDEFINED
        ) {
            return src
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(270f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(270f); m.postScale(-1f, 1f) }
            else -> return src
        }
        // 向きの変換失敗時に未変換の画像を成功として返すと、選択の位置も保存結果も変わる。
        val out = try {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        } catch (error: Throwable) {
            src.recycle()
            throw error
        }
        if (out !== src) src.recycle()
        return out
    }

    companion object {
        /**
         * 長辺 [sourceLongEdge] の画像を [targetLongEdge] 以下にするための間引き率。
         * BitmapFactory は 2 のべき乗しか受け付けないので、目標を下回らない最大の 2^n を返す。
         */
        fun sampleSizeFor(sourceLongEdge: Int, targetLongEdge: Int): Int =
            RenderBudget.sampleSizeFor(sourceLongEdge, targetLongEdge)
    }
}
