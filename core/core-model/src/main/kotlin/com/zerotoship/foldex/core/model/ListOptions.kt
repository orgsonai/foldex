// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.model

enum class SortBy { NAME, SIZE, DATE, TYPE }

data class ListOptions(
    val showHidden: Boolean = false,
    val followSymlinks: Boolean = false,
    val sortBy: SortBy = SortBy.NAME,
    val sortAscending: Boolean = true,
)
