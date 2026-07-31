// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import android.graphics.Bitmap
import com.zerotoship.foldex.ui.imageedit.model.EditSize
import kotlin.math.max

/** Bitmap に対する素朴な操作。UI からは独立させ、engine 内で完結させる。 */
object BitmapOps {

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
        var createdIntermediate = false
        // 目標の 2 倍より大きい間は半分ずつ落とす。
        while (current.width / 2 >= target.width && current.height / 2 >= target.height &&
            current.width / 2 >= 1 && current.height / 2 >= 1
        ) {
            val half = runCatching {
                Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
            }.getOrNull() ?: break
            if (createdIntermediate && current !== src) current.recycle()
            current = half
            createdIntermediate = true
        }
        val out = runCatching {
            Bitmap.createScaledBitmap(current, target.width, target.height, true)
        }.getOrNull() ?: return current
        if (createdIntermediate && current !== src && current !== out) current.recycle()
        return out
    }

    /** ARGB_8888 の空キャンバスを作る。確保できなければ null (呼び出し側で握る)。 */
    fun createCanvasBitmap(size: EditSize): Bitmap? = runCatching {
        Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
    }.getOrNull()

    /** [bitmap] が占めるおおよそのバイト数。 */
    fun byteSize(bitmap: Bitmap): Int = bitmap.allocationByteCount

    /** 長辺。 */
    fun longEdge(bitmap: Bitmap): Int = max(bitmap.width, bitmap.height)
}
