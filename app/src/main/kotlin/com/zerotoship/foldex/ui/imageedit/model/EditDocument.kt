// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.model

import kotlin.math.roundToInt

/**
 * 編集セッション全体を表す値オブジェクト。**画素データを一切持たない。**
 *
 * 画素を持たないので、コピーが数 KB で済む → そのまま Undo 履歴に積める
 * ([EditHistory])。表示も保存も「このドキュメントを指定解像度で描画する」という
 * 同じ処理を通す (engine/DocumentRenderer)。
 *
 * 編集操作 = このデータクラスを新しい値に置き換えること。元画像には決して書き込まない。
 */
data class EditDocument(
    /** レイヤーを配置する論理座標系のサイズ。切り抜きで変わる。 */
    val canvas: CanvasSpec,
    /** 保存時の出力指定 (論理サイズに対する倍率と形式)。 */
    val output: OutputSpec,
    /** 下 (index 0) から上へ描画する。 */
    val layers: List<Layer>,
    val activeLayerId: String,
) {
    val activeLayer: Layer? get() = layers.firstOrNull { it.id == activeLayerId }

    /** 保存したときの画素サイズ。 */
    val outputSize: EditSize
        get() = EditSize(
            width = (canvas.width * output.scale).roundToInt().coerceAtLeast(1),
            height = (canvas.height * output.scale).roundToInt().coerceAtLeast(1),
        )

    fun updateLayer(id: String, block: (Layer) -> Layer): EditDocument =
        copy(layers = layers.map { if (it.id == id) block(it) else it })

    /** アクティブなレイヤーが [Layer.Image] のときだけ書き換える。 */
    fun updateActiveImage(block: (Layer.Image) -> Layer.Image): EditDocument {
        val target = activeLayer as? Layer.Image ?: return this
        return updateLayer(target.id) { block(it as Layer.Image) }
    }

    /**
     * 論理キャンバスを [size] に合わせ直す。切り抜き・回転でレイヤーの形が変わった後に呼ぶ。
     * 出力倍率 ([OutputSpec.scale]) は維持されるので、「先にリサイズ → 後で切り抜き」でも
     * 指定した縮小率が失われない。
     */
    fun withCanvasFitting(size: EditSize): EditDocument =
        copy(canvas = canvas.copy(width = size.width, height = size.height))

    /** 出力の長辺を [longEdge] px にする倍率を設定する。 */
    fun withOutputLongEdge(longEdge: Int): EditDocument {
        val current = maxOf(canvas.width, canvas.height)
        return copy(output = output.copy(scale = (longEdge.toFloat() / current).coerceAtLeast(0.01f)))
    }

    companion object {
        /** 画像 1 枚を開いた直後の状態を作る。 */
        fun ofSingleImage(
            layerId: String,
            name: String,
            sourceKey: String,
            sourceSize: EditSize,
            format: ImageFormat,
            quality: Int = ImageFormat.DEFAULT_QUALITY,
        ): EditDocument {
            val layer = Layer.Image(
                id = layerId,
                name = name,
                sourceKey = sourceKey,
                sourceSize = sourceSize,
            )
            return EditDocument(
                canvas = CanvasSpec(sourceSize.width, sourceSize.height, Background.Transparent),
                output = OutputSpec(scale = 1f, format = format, quality = quality),
                layers = listOf(layer),
                activeLayerId = layerId,
            )
        }
    }
}

/** 論理キャンバス。 */
data class CanvasSpec(
    val width: Int,
    val height: Int,
    val background: Background,
)

/** キャンバスの下地。透明を持てるのは PNG / WebP で保存したときだけ (JPEG は白く潰れる)。 */
sealed interface Background {
    data object Transparent : Background
    data class Solid(val color: Int) : Background
}

/**
 * 保存時の出力指定。
 *
 * [scale] は論理キャンバスに対する倍率で持つ。px を直接持たないのは、切り抜きで
 * キャンバスの形が変わっても「50% に縮める」という意図が保たれるようにするため。
 */
data class OutputSpec(
    val scale: Float,
    val format: ImageFormat,
    val quality: Int,
)

/** 出力形式。透明を保持できるかどうかがここでの本質。 */
enum class ImageFormat(val displayName: String, val extension: String, val supportsAlpha: Boolean, val hasQuality: Boolean) {
    JPEG("JPEG", "jpg", supportsAlpha = false, hasQuality = true),
    PNG("PNG", "png", supportsAlpha = true, hasQuality = false),
    WEBP("WebP", "webp", supportsAlpha = true, hasQuality = true),
    ;

    companion object {
        const val DEFAULT_QUALITY: Int = 90

        /** 拡張子から推測する。分からなければ JPEG (写真が最も多いため)。 */
        fun fromFileName(name: String): ImageFormat =
            when (name.substringAfterLast('.', "").lowercase()) {
                "png" -> PNG
                "webp" -> WEBP
                else -> JPEG
            }
    }
}
