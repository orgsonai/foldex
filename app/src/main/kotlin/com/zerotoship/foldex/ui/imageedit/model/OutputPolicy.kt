// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.model

enum class MetadataPolicy { KEEP_CAPTURE, REMOVE_LOCATION, SHARE }

enum class Transparency { OPAQUE, HAS_ALPHA, UNKNOWN }

/** 縮小プレビューで透明画素を見逃しても、不透明だと断定しない。 */
fun EditDocument.transparency(): Transparency {
    var result = when (val bg = canvas.background) {
        Background.Transparent -> Transparency.HAS_ALPHA
        is Background.Solid -> if (bg.color ushr 24 == 255) Transparency.OPAQUE else Transparency.HAS_ALPHA
    }
    for (layer in layers) {
        if (!layer.visible || layer.alpha <= 0f) continue
        when (layer) {
            is Layer.Image -> {
                val t = layer.transform
                val covers = t.rotationDeg == 0f && t.scale.isFinite() && t.scale > 0f &&
                    t.offsetX <= 0f && t.offsetY <= 0f &&
                    t.offsetX + layer.logicalSize.width * t.scale >= canvas.width &&
                    t.offsetY + layer.logicalSize.height * t.scale >= canvas.height
                if (layer.sourceTransparency == Transparency.OPAQUE && layer.alpha >= 1f && covers) {
                    result = Transparency.OPAQUE
                } else if (result != Transparency.OPAQUE) result = Transparency.UNKNOWN
            }
            is Layer.Fill -> {
                if (layer.area.isEmpty) continue
                if (layer.mode == FillMode.CLEAR) result = Transparency.UNKNOWN
                else if (result != Transparency.OPAQUE) result = Transparency.UNKNOWN
            }
            is Layer.Mosaic -> if (result != Transparency.OPAQUE) result = Transparency.UNKNOWN
            is Layer.Drawing, is Layer.Text -> if (result != Transparency.OPAQUE) result = Transparency.UNKNOWN
        }
    }
    return result
}
