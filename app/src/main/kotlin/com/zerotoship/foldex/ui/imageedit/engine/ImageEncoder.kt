// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import android.graphics.Bitmap
import android.os.Build
import com.zerotoship.foldex.ui.imageedit.model.ImageFormat
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** 出力形式・品質へのエンコードと、「目標ファイルサイズ」からの品質決定。 */
object ImageEncoder {

    fun encode(bitmap: Bitmap, format: ImageFormat, quality: Int, out: OutputStream): Boolean =
        runCatching {
            bitmap.compress(compressFormat(format), quality.coerceIn(1, 100), out)
        }.getOrDefault(false)

    /** メモリ上でエンコードしてバイト数だけ測る (予想サイズ表示・品質探索用)。 */
    fun encodedSize(bitmap: Bitmap, format: ImageFormat, quality: Int): Int {
        val buffer = ByteArrayOutputStream(DEFAULT_BUFFER)
        return if (encode(bitmap, format, quality, buffer)) buffer.size() else -1
    }

    /**
     * 目標バイト数に収まる **最大の品質** を二分探索で決める。
     *
     * [measure] を差し替えられるようにしてあるのは、実際のエンコードを伴わずに
     * ユニットテストで探索の挙動を確かめられるようにするため。
     *
     * 品質を上げるほどサイズが増える (ほぼ単調) という前提を使う。1 回のエンコードが
     * 4000x3000 で 100〜200ms あるので、[maxSteps] で打ち切って全体 1 秒前後に収める。
     */
    fun findQualityForTarget(
        targetBytes: Int,
        minQuality: Int = MIN_QUALITY,
        maxQuality: Int = MAX_QUALITY,
        maxSteps: Int = DEFAULT_STEPS,
        measure: (quality: Int) -> Int,
    ): QualityResult {
        // まず最高品質で測る。目標が緩ければここで確定し、エンコードは 1 回で済む
        // (「とりあえず上限を決めておく」使い方が多いので、この一手が効く)。
        val topBytes = measure(maxQuality)
        if (topBytes in 0..targetBytes) return QualityResult(maxQuality, topBytes, withinTarget = true)

        var lo = minQuality
        var hi = maxQuality - 1
        var best: QualityResult? = null
        var steps = 0
        while (lo <= hi && steps < maxSteps) {
            steps++
            val mid = (lo + hi) / 2
            val bytes = measure(mid)
            if (bytes in 0..targetBytes) {
                if (best == null || mid > best.quality) {
                    best = QualityResult(mid, bytes, withinTarget = true)
                }
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        best?.let { return it }
        // 最低品質でも目標に収まらない場合。これ以上落とすと絵が壊れるので、
        // 最低品質で妥協し「収まらなかった」ことを呼び出し側へ伝える。
        val bytes = measure(minQuality)
        return QualityResult(minQuality, bytes, withinTarget = bytes in 0..targetBytes)
    }

    private fun compressFormat(format: ImageFormat): Bitmap.CompressFormat = when (format) {
        ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
        ImageFormat.PNG -> Bitmap.CompressFormat.PNG
        ImageFormat.WEBP ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
    }

    data class QualityResult(
        val quality: Int,
        val bytes: Int,
        /** 目標サイズに収まったか。false なら「これ以上小さくできなかった」。 */
        val withinTarget: Boolean,
    )

    const val MIN_QUALITY: Int = 30
    const val MAX_QUALITY: Int = 95
    const val DEFAULT_STEPS: Int = 6
    private const val DEFAULT_BUFFER = 256 * 1024
}
