// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.model

import kotlin.time.Instant

data class FileNode(
    val uri: FileUri,
    val name: String,
    val type: NodeType,
    val size: Long,
    val lastModified: Instant?,
    val permissions: Permissions,
    val mimeType: String? = null,
    val isHidden: Boolean = false,
) {
    val isDirectory: Boolean get() = type == NodeType.DIRECTORY
    val isFile: Boolean get() = type == NodeType.FILE
    val extension: String get() = name.substringAfterLast('.', missingDelimiterValue = "")
}
