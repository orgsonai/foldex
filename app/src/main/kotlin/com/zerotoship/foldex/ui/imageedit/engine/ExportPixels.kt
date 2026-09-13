// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import com.zerotoship.foldex.ui.imageedit.model.ImageFormat
import com.zerotoship.foldex.ui.imageedit.model.OutputSpec

internal object ExportPixels {
    /** CLEAR の処理後に背景を敷く。先に背景を塗ると透明化で背景も消えてしまう。 */
    fun apply(bitmap: Bitmap, output: OutputSpec) {
        if (output.format == ImageFormat.JPEG) {
            Canvas(bitmap).drawColor(output.jpegBackground or (0xff shl 24), PorterDuff.Mode.DST_OVER)
            bitmap.setHasAlpha(false)
        }
    }
}
