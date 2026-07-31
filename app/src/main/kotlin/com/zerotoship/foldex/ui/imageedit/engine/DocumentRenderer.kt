// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.zerotoship.foldex.ui.imageedit.model.Background
import com.zerotoship.foldex.ui.imageedit.model.EditDocument
import com.zerotoship.foldex.ui.imageedit.model.EditSize
import com.zerotoship.foldex.ui.imageedit.model.Layer
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
}
