// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

sealed interface RenderFailure {
    data class MissingSource(val key: String) : RenderFailure
    data class UnreadableSource(val key: String) : RenderFailure
    data object InvalidGeometry : RenderFailure
    data object NotEnoughMemory : RenderFailure
    data object DrawingFailed : RenderFailure
}
