// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.model

/**
 * 自機サーバーへの接続イベント 1件分。
 * 永続化レコードは `core-data` の `ServerLogEntity` に対応する。
 *
 * 不正アクセス検知やトラブルシュートのため UI から閲覧する想定で、
 * ホットパスにいる認証ハンドラから書き込まれるので軽量に保つ。
 */
data class ServerLog(
    val id: Long,
    val configId: String,
    val event: ServerLogEvent,
    val clientAddress: String,
    val username: String?,
    val timestamp: Long,
    val details: String? = null,
)
