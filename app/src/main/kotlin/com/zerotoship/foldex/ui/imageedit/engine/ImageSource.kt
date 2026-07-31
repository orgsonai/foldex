// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.zerotoship.foldex.ui.imageedit.model.EditSize
import java.io.File
import kotlin.math.max

/**
 * 元画像を「必要な解像度だけ」デコードして貸し出す。
 *
 * 原寸を丸ごとメモリに載せない ([BitmapFactory.Options.inSampleSize] で間引く) のが要点。
 * 4000x3000 の写真は原寸 48MB だが、プレビュー用の 2048px なら 12MB で済む。
 * 保存時だけ原寸を要求し、終わったら解放する。
 *
 * 同じキーで同じ (以上の) 解像度が既にあれば使い回す。
 */
class ImageSource(private val file: File) {

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
    fun load(maxLongEdge: Int?): Bitmap? {
        val want = maxLongEdge ?: size.longEdge
        cached?.let { if (cachedLongEdge >= want && !it.isRecycled) return it }
        val decoded = decode(want) ?: return null
        cached?.takeIf { it !== decoded && !it.isRecycled }?.recycle()
        cached = decoded
        cachedLongEdge = max(decoded.width, decoded.height)
        return decoded
    }

    /** キャッシュを解放する。原寸を読んだ後など、メモリを戻したいときに呼ぶ。 */
    fun release() {
        cached?.takeIf { !it.isRecycled }?.recycle()
        cached = null
        cachedLongEdge = 0
    }

    private fun decode(maxLongEdge: Int): Bitmap? {
        val bounds = readRawBounds() ?: return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(max(bounds.width, bounds.height), maxLongEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }.getOrNull()
            ?: return null
        // EXIF の向きを画素へ適用しておく。以降の処理は「見たままの向き」だけを考えればよくなる。
        val rotated = applyExifOrientation(raw)
        // inSampleSize は 2 のべき乗でしか間引けないので、まだ大きいことがある。
        // ここで目標の長辺へきっちり合わせる (拡大はしない)。
        val longEdge = max(rotated.width, rotated.height)
        if (longEdge <= maxLongEdge) return rotated
        val target = EditSize(rotated.width, rotated.height).fitLongEdge(maxLongEdge, allowUpscale = false)
        val scaled = BitmapOps.scale(rotated, target)
        if (scaled !== rotated) rotated.recycle()
        return scaled
    }

    private fun readRawBounds(): EditSize? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        return EditSize(opts.outWidth, opts.outHeight)
    }

    private fun readSizeWithOrientation(): EditSize {
        val raw = readRawBounds() ?: EditSize(1, 1)
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
        val out = runCatching {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        }.getOrNull() ?: return src
        if (out !== src) src.recycle()
        return out
    }

    companion object {
        /**
         * 長辺 [sourceLongEdge] の画像を [targetLongEdge] 以下にするための間引き率。
         * BitmapFactory は 2 のべき乗しか受け付けないので、目標を下回らない最大の 2^n を返す。
         */
        fun sampleSizeFor(sourceLongEdge: Int, targetLongEdge: Int): Int {
            if (targetLongEdge <= 0 || sourceLongEdge <= targetLongEdge) return 1
            var sample = 1
            while (sourceLongEdge / (sample * 2) >= targetLongEdge) sample *= 2
            return sample
        }
    }
}
