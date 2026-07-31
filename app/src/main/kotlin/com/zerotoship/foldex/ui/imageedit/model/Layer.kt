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

    /**
     * ブラシ・消しゴムの軌跡。**点列のまま**持ち、描画するときにその解像度で引き直す。
     * 画素に焼かないので、拡大保存してもブラシの線がボケない。
     *
     * 消しゴム ([StrokeMode.ERASE]) はこのレイヤーの中だけを消す (下の画像には穴を開けない)。
     */
    data class Drawing(
        override val id: String,
        override val name: String,
        override val visible: Boolean = true,
        override val alpha: Float = 1f,
        override val transform: LayerTransform = LayerTransform(),
        val strokes: List<Stroke> = emptyList(),
    ) : Layer {
        fun plus(stroke: Stroke): Drawing = copy(strokes = strokes + stroke)
    }

    /**
     * テキスト。**確定しても文字列のまま持ち続ける**ので、保存するまで何度でも打ち直せる。
     * 画素へ焼き込まれるのは保存時の描画 1 回だけ。
     *
     * [transform] の offsetX/offsetY はテキストブロックの左上 (論理キャンバス座標)。
     */
    data class Text(
        override val id: String,
        override val name: String,
        override val visible: Boolean = true,
        override val alpha: Float = 1f,
        override val transform: LayerTransform = LayerTransform(),
        val text: String,
        val style: TextStyleSpec,
    ) : Layer {
        fun movedBy(dx: Float, dy: Float): Text = copy(
            transform = transform.copy(
                offsetX = transform.offsetX + dx,
                offsetY = transform.offsetY + dy,
            ),
        )
    }
}

/** レイヤーを論理キャンバスへ配置する変換。画像レイヤーは v1 では常に既定値。 */
data class LayerTransform(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scale: Float = 1f,
    val rotationDeg: Float = 0f,
)

/** テキストの見た目。サイズは論理キャンバス座標の px。 */
data class TextStyleSpec(
    val sizePx: Float,
    /** ARGB。 */
    val color: Int,
    val font: TextFont = TextFont.SANS,
    val bold: Boolean = false,
    val outline: TextOutline = TextOutline.NONE,
    /** 背景ボックスの色 (ARGB)。null なら背景なし。 */
    val backgroundColor: Int? = null,
)

enum class TextFont(val label: String) { SANS("ゴシック"), SERIF("明朝"), MONOSPACE("等幅") }

/** 縁取り。写真の上に載せた文字を読めるようにするための最小限の選択肢。 */
enum class TextOutline(val label: String) { NONE("なし"), WHITE("白フチ"), BLACK("黒フチ") }
