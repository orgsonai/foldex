// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.zerotoship.foldex.ui.imageedit.model.Background
import com.zerotoship.foldex.ui.imageedit.model.EditDocument
import com.zerotoship.foldex.ui.imageedit.model.EditRect
import com.zerotoship.foldex.ui.imageedit.model.EditSize
import com.zerotoship.foldex.ui.imageedit.model.Layer
import com.zerotoship.foldex.ui.imageedit.model.Stroke
import com.zerotoship.foldex.ui.imageedit.model.StrokeMode
import com.zerotoship.foldex.ui.imageedit.model.TextFont
import com.zerotoship.foldex.ui.imageedit.model.TextOutline
import kotlin.math.roundToInt

/**
 * [EditDocument] を指定解像度の Bitmap に描く。**画面表示も保存もこの 1 本を通す。**
 *
 * ここを 2 本に分けると「プレビューでは合っていたのに保存したら違う」が必ず起きる。
 * 違いは渡す解像度だけにする:
 *  - プレビュー: `targetLongEdge = 画面に合わせた長辺` (既定 2048px)
 *  - 保存:       `targetLongEdge = null` → ドキュメントの出力サイズ (原寸 × 出力倍率)
 */
object DocumentRenderer {

    /**
     * @param sources レイヤーの `sourceKey` から画素を引くための解決関数。
     * @param targetLongEdge null なら [EditDocument.outputSize]、指定するとその長辺に収める。
     * @return 描画結果。メモリを確保できなければ null。
     */
    fun render(
        doc: EditDocument,
        sources: (String) -> ImageSource?,
        targetLongEdge: Int? = null,
    ): Bitmap? {
        val outSize = outputSizeFor(doc, targetLongEdge)
        val out = BitmapOps.createCanvasBitmap(outSize) ?: return null
        val canvas = Canvas(out)

        // 論理キャンバス座標 → 出力画素へのスケール。
        val s = outSize.width.toFloat() / doc.canvas.width

        when (val bg = doc.canvas.background) {
            is Background.Transparent -> Unit // Bitmap は透明で初期化済み
            is Background.Solid -> canvas.drawColor(bg.color)
        }

        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
        }

        for (layer in doc.layers) {
            if (!layer.visible || layer.alpha <= 0f) continue
            when (layer) {
                is Layer.Image -> drawImageLayer(canvas, paint, layer, sources, s)
                is Layer.Drawing -> drawStrokes(canvas, layer, s, outSize)
                is Layer.Text -> drawText(canvas, layer, s)
            }
        }
        return out
    }

    /** [render] が返すサイズ。ダイアログの「予想サイズ」表示でも使う。 */
    fun outputSizeFor(doc: EditDocument, targetLongEdge: Int?): EditSize =
        if (targetLongEdge == null) {
            doc.outputSize
        } else {
            EditSize(doc.canvas.width, doc.canvas.height)
                .fitLongEdge(targetLongEdge, allowUpscale = false)
        }

    private fun drawImageLayer(
        canvas: Canvas,
        paint: Paint,
        layer: Layer.Image,
        sources: (String) -> ImageSource?,
        s: Float,
    ) {
        val source = sources(layer.sourceKey) ?: return

        // 元画像 1px が出力上で何 px になるか。これが必要な解像度をそのまま決める。
        // (切り抜きや回転は倍率を変えないので、レイヤーの拡大率と出力スケールの積でよい)
        val pixelRatio = layer.transform.scale * s
        val neededLongEdge = (layer.sourceSize.longEdge * pixelRatio).roundToInt().coerceAtLeast(1)
        val bitmap = source.load(neededLongEdge) ?: return
        if (bitmap.isRecycled) return

        // 読み込んだ Bitmap は元画像の縮小版かもしれないので、切り抜き範囲を Bitmap 座標へ移す。
        val bmpScale = bitmap.width.toFloat() / layer.sourceSize.width
        val crop = layer.cropRect
        val src = Rect(
            (crop.left * bmpScale).roundToInt().coerceIn(0, bitmap.width),
            (crop.top * bmpScale).roundToInt().coerceIn(0, bitmap.height),
            (crop.right * bmpScale).roundToInt().coerceIn(0, bitmap.width),
            (crop.bottom * bmpScale).roundToInt().coerceIn(0, bitmap.height),
        )
        if (src.width() <= 0 || src.height() <= 0) return

        // 回転後の論理サイズ (90°/270° で幅と高さが入れ替わる) を出力座標へ。
        val logical = layer.logicalSize
        val dw = logical.width * layer.transform.scale * s
        val dh = logical.height * layer.transform.scale * s
        // 回転前 (= 切り抜いた素の向き) のサイズ。中心そろえで描くのに使う。
        val rw = crop.width * layer.transform.scale * s
        val rh = crop.height * layer.transform.scale * s

        val left = layer.transform.offsetX * s
        val top = layer.transform.offsetY * s

        paint.alpha = (layer.alpha.coerceIn(0f, 1f) * 255).roundToInt()

        canvas.save()
        // 配置先の中心へ移動してから、画面座標系で 反転 → 回転 の順に効かせる。
        // (反転を回転の外側に置くことで、90° 回した状態で「左右反転」を押しても
        //  画面上の左右が入れ替わる = 押した見た目どおりに動く)
        canvas.translate(left + dw / 2f, top + dh / 2f)
        canvas.scale(if (layer.flipH) -1f else 1f, if (layer.flipV) -1f else 1f)
        canvas.rotate(layer.quarterTurns * 90f + layer.transform.rotationDeg)
        canvas.drawBitmap(bitmap, src, RectF(-rw / 2f, -rh / 2f, rw / 2f, rh / 2f), paint)
        canvas.restore()

        paint.alpha = 255
    }

    /**
     * ブラシ・消しゴムの軌跡を描く。
     *
     * `saveLayer` で独立した層に描いてから合成するのが要点。消しゴム (PorterDuff.CLEAR) を
     * そのまま本体のキャンバスに当てると下の写真ごと穴が開いてしまうが、層を分けておけば
     * 「自分が描いた線だけを消す」になる。
     */
    private fun drawStrokes(canvas: Canvas, layer: Layer.Drawing, s: Float, outSize: EditSize) {
        if (layer.strokes.isEmpty()) return
        val paint = Paint().apply {
            isAntiAlias = true
            isDither = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val saved = canvas.saveLayerAlpha(
            0f,
            0f,
            outSize.width.toFloat(),
            outSize.height.toFloat(),
            (layer.alpha.coerceIn(0f, 1f) * 255).roundToInt(),
        )
        for (stroke in layer.strokes) {
            paint.color = stroke.color
            paint.strokeWidth = (stroke.widthPx * s).coerceAtLeast(1f)
            paint.xfermode = if (stroke.mode == StrokeMode.ERASE) ERASE_MODE else null
            paint.maskFilter = blurFor(stroke.hardness, paint.strokeWidth)
            canvas.drawPath(strokePath(stroke, s), paint)
        }
        canvas.restoreToCount(saved)
    }

    /** 硬さ 1.0 ならぼかしなし。小さいほど縁が柔らかくなる。 */
    private fun blurFor(hardness: Float, strokeWidthPx: Float): BlurMaskFilter? {
        if (hardness >= 0.99f) return null
        val radius = ((1f - hardness) * strokeWidthPx * 0.5f).coerceAtLeast(0.5f)
        return runCatching { BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) }.getOrNull()
    }

    /**
     * 点列を滑らかな曲線にする。隣り合う点の中点を結び、元の点を制御点に使う
     * (指の軌跡をそのまま直線で繋ぐとカクカクするため)。
     */
    private fun strokePath(stroke: Stroke, s: Float): Path {
        val path = Path()
        val pts = stroke.points
        if (pts.isEmpty()) return path
        if (pts.size == 1) {
            // 点を 1 つ打っただけ。strokeCap = ROUND なので極小の線分で丸い点になる。
            path.moveTo(pts[0].x * s, pts[0].y * s)
            path.lineTo(pts[0].x * s + 0.01f, pts[0].y * s)
            return path
        }
        path.moveTo(pts[0].x * s, pts[0].y * s)
        for (i in 1 until pts.size) {
            val prev = pts[i - 1]
            val cur = pts[i]
            path.quadTo(
                prev.x * s,
                prev.y * s,
                (prev.x + cur.x) / 2f * s,
                (prev.y + cur.y) / 2f * s,
            )
        }
        path.lineTo(pts.last().x * s, pts.last().y * s)
        return path
    }

    /**
     * テキストを描く。文字列のまま保持されているので、**この解像度でフォントを組み直す**。
     * 拡大保存しても文字がボケないのはこのため。
     */
    private fun drawText(canvas: Canvas, layer: Layer.Text, s: Float) {
        if (layer.text.isBlank()) return
        val style = layer.style
        val paint = Paint().apply {
            isAntiAlias = true
            textSize = (style.sizePx * s).coerceAtLeast(1f)
            typeface = typefaceFor(style.font, style.bold)
            alpha = (layer.alpha.coerceIn(0f, 1f) * 255).roundToInt()
        }
        val lines = layer.text.split("\n")
        val metrics = paint.fontMetrics
        val lineHeight = metrics.descent - metrics.ascent
        val blockWidth = lines.maxOf { paint.measureText(it) }
        val blockHeight = lineHeight * lines.size

        val left = layer.transform.offsetX * s
        val top = layer.transform.offsetY * s

        canvas.save()
        if (layer.transform.rotationDeg != 0f) {
            canvas.rotate(layer.transform.rotationDeg, left + blockWidth / 2f, top + blockHeight / 2f)
        }

        style.backgroundColor?.let { bg ->
            val pad = paint.textSize * BACKGROUND_PADDING_RATIO
            val bgPaint = Paint().apply {
                color = bg
                alpha = (layer.alpha.coerceIn(0f, 1f) * Color.alpha(bg)).roundToInt()
            }
            canvas.drawRect(
                left - pad,
                top - pad,
                left + blockWidth + pad,
                top + blockHeight + pad,
                bgPaint,
            )
        }

        val outlineColor = when (style.outline) {
            TextOutline.NONE -> null
            TextOutline.WHITE -> Color.WHITE
            TextOutline.BLACK -> Color.BLACK
        }
        lines.forEachIndexed { index, line ->
            val baseline = top - metrics.ascent + lineHeight * index
            if (outlineColor != null) {
                // 縁を先に太く描き、その上に本体を重ねる。
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = paint.textSize * OUTLINE_WIDTH_RATIO
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = outlineColor
                canvas.drawText(line, left, baseline, paint)
                paint.style = Paint.Style.FILL
            }
            paint.color = style.color
            canvas.drawText(line, left, baseline, paint)
        }
        canvas.restore()
    }

    private fun typefaceFor(font: TextFont, bold: Boolean): Typeface {
        val base = when (font) {
            TextFont.SANS -> Typeface.SANS_SERIF
            TextFont.SERIF -> Typeface.SERIF
            TextFont.MONOSPACE -> Typeface.MONOSPACE
        }
        return if (bold) Typeface.create(base, Typeface.BOLD) else base
    }

    /**
     * テキストが論理キャンバス上で占める大きさ。移動の当たり判定や、追加した直後に
     * 画面内へ収めるために使う (描画と同じ計算を通すのでズレない)。
     */
    fun measureText(layer: Layer.Text): EditRect {
        val paint = Paint().apply {
            isAntiAlias = true
            textSize = layer.style.sizePx.coerceAtLeast(1f)
            typeface = typefaceFor(layer.style.font, layer.style.bold)
        }
        val lines = layer.text.ifEmpty { " " }.split("\n")
        val metrics = paint.fontMetrics
        val lineHeight = metrics.descent - metrics.ascent
        val width = lines.maxOf { paint.measureText(it) }
        val height = lineHeight * lines.size
        val left = layer.transform.offsetX
        val top = layer.transform.offsetY
        return EditRect(left, top, left + width, top + height)
    }

    /** 透明部分を持つか (JPEG で保存してよいかの判定に使う)。大きい画像でも間引いて調べる。 */
    fun hasTransparency(bitmap: Bitmap): Boolean {
        if (!bitmap.hasAlpha()) return false
        val stepX = (bitmap.width / SAMPLE_GRID).coerceAtLeast(1)
        val stepY = (bitmap.height / SAMPLE_GRID).coerceAtLeast(1)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) < 255) return true
                x += stepX
            }
            y += stepY
        }
        return false
    }

    /** 透明の判定に使う 1 辺あたりのサンプル数 (64x64 = 4096 点)。 */
    private const val SAMPLE_GRID = 64

    /** 消しゴム。saveLayer の内側で使うので、下のレイヤーには影響しない。 */
    private val ERASE_MODE = PorterDuffXfermode(PorterDuff.Mode.CLEAR)

    /** 縁取りの太さ (フォントサイズに対する比)。 */
    private const val OUTLINE_WIDTH_RATIO = 0.14f

    /** 背景ボックスの余白 (フォントサイズに対する比)。 */
    private const val BACKGROUND_PADDING_RATIO = 0.2f
}
