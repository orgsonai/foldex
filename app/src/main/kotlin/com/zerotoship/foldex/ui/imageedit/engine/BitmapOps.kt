// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Paint
import com.zerotoship.foldex.ui.imageedit.model.EditSize
import kotlin.math.max

/** Bitmap に対する素朴な操作。UI からは独立させ、engine 内で完結させる。 */
object BitmapOps {

    /** 入力の色空間をラベルだけ変更せず、sRGB の画素へ描き直す。src の所有権を引き取る。 */
    fun toSrgb(src: Bitmap): Bitmap {
        if (src.config == Bitmap.Config.ARGB_8888 && src.colorSpace?.isSrgb == true) return src
        var converted: Bitmap? = null
        try {
            val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888, true,
                ColorSpace.get(ColorSpace.Named.SRGB))
            converted = result
            Canvas(result).drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            return result
        } catch (error: Throwable) {
            converted?.recycle()
            throw error
        } finally {
            src.recycle()
        }
    }

    /**
     * [target] へ拡大縮小する。
     *
     * 1/2 以下に縮めるときは 2 段階 (半分ずつ) に分ける。
     * 一気に縮小するとサンプリングが飛んで、細かい模様がモアレになったりディテールが
     * ざらつく。半分ずつ落とすと平均化されて素直な絵になる。
     */
    fun scale(src: Bitmap, target: EditSize): Bitmap {
        if (src.width == target.width && src.height == target.height) return src
        var current = src
        try {
            while (current.width / 2 >= target.width && current.height / 2 >= target.height &&
                current.width / 2 >= 1 && current.height / 2 >= 1
            ) {
                val half = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
                if (current !== src && current !== half) current.recycle()
                current = half
            }
            val out = Bitmap.createScaledBitmap(current, target.width, target.height, true)
            if (current !== src && current !== out) current.recycle()
            return out
        } catch (error: Throwable) {
            if (current !== src) current.recycle()
            throw error
        }
    }

    /** ARGB_8888 の空キャンバスを作る。確保できなければ null (呼び出し側で握る)。 */
    fun createCanvasBitmap(size: EditSize): Bitmap? = runCatching {
        Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888, true,
            ColorSpace.get(ColorSpace.Named.SRGB))
    }.getOrNull()

    /** [bitmap] が占めるおおよそのバイト数。 */
    fun byteSize(bitmap: Bitmap): Int = bitmap.allocationByteCount

    /** 長辺。 */
    fun longEdge(bitmap: Bitmap): Int = max(bitmap.width, bitmap.height)
}
