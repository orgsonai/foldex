// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.model

/** 余白を除外し、ズーム・パンされた写真上のタップを論理座標に戻す。 */
internal object SelectionCoordinates {
    fun fromViewport(point: EditPoint, image: EditRect, canvas: EditSize): EditPoint? {
        if (image.isEmpty || !point.x.isFinite() || !point.y.isFinite() ||
            point.x < image.left || point.x >= image.right || point.y < image.top || point.y >= image.bottom
        ) return null
        return EditPoint(
            (point.x - image.left) / image.width * canvas.width,
            (point.y - image.top) / image.height * canvas.height,
        )
    }
}
