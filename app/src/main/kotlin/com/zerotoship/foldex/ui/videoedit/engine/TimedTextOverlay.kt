// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings
import com.zerotoship.foldex.ui.videoedit.plan.PlannedText
import kotlin.math.ceil

/**
 * 表示区間の間だけ見える文字。
 *
 * Bitmap は作ってから変えないので、GPU への転送は最初の 1 回だけ (BitmapOverlay は同じ Bitmap なら転送しない)。
 * 重ねは画素の寸法のまま描かれるので、文字の大きさの Bitmap を作り、中心の位置だけを指定する。
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class TimedTextOverlay(
    private val text: PlannedText,
    canvasWidth: Int,
    canvasHeight: Int,
) : BitmapOverlay() {

    private val bitmap: Bitmap = TextBitmap.render(text, canvasWidth, canvasHeight)

    // 位置は -1〜1 の座標で、上が +1。文字の中心をキャンバス上の中心の位置に合わせる。
    private val anchorX = (text.centerX * 2f - 1f).coerceIn(-1f, 1f)
    private val anchorY = (1f - text.centerY * 2f).coerceIn(-1f, 1f)
    private val shown: OverlaySettings = StaticOverlaySettings.Builder().setBackgroundFrameAnchor(anchorX, anchorY).build()
    private val hidden: OverlaySettings =
        StaticOverlaySettings.Builder().setBackgroundFrameAnchor(anchorX, anchorY).setAlphaScale(0f).build()

    override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap

    /** 合成全体の効果に届く時刻は、出力の先頭からの時刻 (シーケンスの各素材に、前の素材の長さの合計を足した値)。 */
    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
        if (presentationTimeUs >= text.startUs && presentationTimeUs < text.endUs) shown else hidden
}

/** プレビューの文字と同じ見た目の Bitmap。太字、大きさはキャンバスの高さ × sizeFraction、縁取りは文字の大きさの 12%。 */
internal object TextBitmap {
    private const val OUTLINE_RATIO = 0.12f

    fun render(text: PlannedText, canvasWidth: Int, canvasHeight: Int): Bitmap {
        val fontPx = (text.sizeFraction * canvasHeight).coerceAtLeast(1f)
        val stroke = if (text.outlined) fontPx * OUTLINE_RATIO else 0f
        val padding = ceil(stroke).toInt() + 2
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontPx
            typeface = Typeface.DEFAULT_BOLD
            color = text.color
        }
        // プレビューと同じく、キャンバスの幅を超える行は折り返す。
        val maxWidth = (canvasWidth - 2 * padding).coerceAtLeast(1)
        val width = ceil(Layout.getDesiredWidth(text.text, paint)).toInt().coerceIn(1, maxWidth)
        val layout = StaticLayout.Builder.obtain(text.text, 0, text.text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        val bitmap = Bitmap.createBitmap(width + 2 * padding, layout.height + 2 * padding, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(padding.toFloat(), padding.toFloat())
        if (text.outlined) {
            // StaticLayout は渡した paint で描くので、縁取り → 塗りの順に paint を切り替えて 2 回描く。
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = Color.BLACK
            layout.draw(canvas)
            paint.style = Paint.Style.FILL
            paint.color = text.color
        }
        layout.draw(canvas)
        return bitmap
    }
}
