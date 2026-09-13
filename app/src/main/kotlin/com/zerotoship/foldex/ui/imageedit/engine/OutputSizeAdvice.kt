// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import com.zerotoship.foldex.ui.imageedit.model.EditSize
import kotlin.math.sqrt

/** 実測容量から再試行用の候補を計算。達成可否は再符号化後の最終ファイルで判定する。 */
internal object OutputSizeAdvice {
    fun suggest(size: EditSize, actualBytes: Long, targetBytes: Int): Int? {
        if (size.longEdge <= 16 || actualBytes <= targetBytes || targetBytes <= 0) return null
        val factor = sqrt(targetBytes.toDouble() / actualBytes * 0.8)
        return (size.longEdge * factor).toInt().coerceIn(16, size.longEdge - 1)
    }
}

internal data class ExportPreparationFailure(
    val reason: ExportFailure,
    val actualBytes: Long? = null,
    val suggestedLongEdge: Int? = null,
)
