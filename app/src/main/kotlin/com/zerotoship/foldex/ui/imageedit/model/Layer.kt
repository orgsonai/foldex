// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.model

/**
 * キャンバス上に積むレイヤー。下から順に描画される。
 *
 * v1 で使うのは [Image] だけだが、最初から `List<Layer>` の器で持っておく。
 * 単一 Bitmap を前提に書いてしまうと、複数画像の合成 (v5) を足すときに全滅するため。
 * ブラシ (Drawing) とテキスト (Text) は v2 でこの sealed interface に足す。
 */
sealed interface Layer {
    val id: String
    val name: String
    val visible: Boolean

    /** 0f..1f。1f で不透明。 */
    val alpha: Float

    /** 論理キャンバス座標系への配置。 */
    val transform: LayerTransform

    /**
     * 読み込んだ画像。**画素を持たない** — 実体は [sourceKey] で engine 側のキャッシュから引く。
     * これによりレイヤー (とドキュメント) のコピーが軽くなり、Undo 履歴に積める。
     *
     * 切り抜き・回転・反転はここのフィールドを書き換えるだけで、画素には触らない (非破壊)。
     * 何度やり直しても再サンプリングによる劣化が起きない。
     */
    data class Image(
        override val id: String,
        override val name: String,
        override val visible: Boolean = true,
        override val alpha: Float = 1f,
        override val transform: LayerTransform = LayerTransform(),
        /** 画素を引くためのキー (通常は元ファイルの絶対パス)。 */
        val sourceKey: String,
        /** 元画像の画素サイズ (EXIF の向きを適用した後の値)。 */
        val sourceSize: EditSize,
        /** 元画像座標での切り抜き範囲。null なら全体。 */
        val crop: EditRect? = null,
        /** 90° 単位の回転 (0..3)。切り抜き後の内容に適用される。 */
        val quarterTurns: Int = 0,
        val flipH: Boolean = false,
        val flipV: Boolean = false,
    ) : Layer {

        /** 切り抜き後の範囲 (未指定なら元画像全体)。 */
        val cropRect: EditRect get() = crop ?: EditRect.ofSize(sourceSize.width, sourceSize.height)

        /**
         * 切り抜き + 回転を適用した後の論理サイズ。
         * 90° / 270° では幅と高さが入れ替わる。
         */
        val logicalSize: EditSize
            get() {
                val w = cropRect.width.toInt().coerceAtLeast(1)
                val h = cropRect.height.toInt().coerceAtLeast(1)
                return if (quarterTurns % 2 == 0) EditSize(w, h) else EditSize(h, w)
            }

        /** 右に 90° 回す。4 回で元に戻る。 */
        fun rotatedRight(): Image = copy(quarterTurns = (quarterTurns + 1) % 4)

        /** 左に 90° 回す。 */
        fun rotatedLeft(): Image = copy(quarterTurns = (quarterTurns + 3) % 4)

        /**
         * 元画像座標での切り抜きを設定する。範囲外は元画像の内側へ丸める。
         * 既に切り抜かれている場合も **元画像座標での指定**として扱う (入れ子にしない)。
         */
        fun withCrop(rect: EditRect): Image {
            val bounds = EditRect.ofSize(sourceSize.width, sourceSize.height)
            val clipped = rect.intersect(bounds) ?: return this
            return copy(crop = clipped)
        }

        fun withoutCrop(): Image = copy(crop = null)
    }
}

/** レイヤーを論理キャンバスへ配置する変換。v1 では常に既定値 (原点・等倍・無回転)。 */
data class LayerTransform(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scale: Float = 1f,
    val rotationDeg: Float = 0f,
)
